package ton.dariushkmetsyak.Web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ton.dariushkmetsyak.Charts.Chart;
import ton.dariushkmetsyak.Config.AppConfig;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.CoinsList;
import ton.dariushkmetsyak.Persistence.StateManager;
import ton.dariushkmetsyak.Persistence.TradingState;
import ton.dariushkmetsyak.Commission.CommissionCalculator;
import ton.dariushkmetsyak.Strategies.AtrEmaStrategy.AtrEmaBackTester;
import ton.dariushkmetsyak.Strategies.AtrEmaStrategy.AtrEmaTrader;
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointStrategyBackTester;
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointsStrategyTrader;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder;

import java.io.*;
import java.nio.file.*;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Per-user trading session manager.
 *
 * Each Telegram user gets their own isolated instance:
 *   TradingSessionManager.forUser(userId)
 *
 * Isolation guarantees:
 *  - Sessions are separate per user (one user's active session doesn't block another)
 *  - Binance keys come from UserProfile (not global AppConfig)
 *  - Files stored in trading_states/user_<userId>/sessions.json
 *  - Backtest results are per-user
 *  - autoResumeSessions() only touches sessions of that user
 */
public class TradingSessionManager {
    private static final Logger log = LoggerFactory.getLogger(TradingSessionManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    // ── Per-user registry ─────────────────────────────────────────────────────

    /** userId → manager. userId=0 is the legacy global manager. */
    private static final ConcurrentHashMap<Long, TradingSessionManager> userManagers =
            new ConcurrentHashMap<>();

    /**
     * Get or create the session manager for a specific user.
     * @param userId Telegram user ID. Use 0 for legacy/unauthenticated.
     */
    public static TradingSessionManager forUser(long userId) {
        return userManagers.computeIfAbsent(userId, TradingSessionManager::new);
    }

    /** Legacy compatibility — returns global (userId=0) manager */
    @Deprecated
    public static TradingSessionManager getInstance() {
        return forUser(0L);
    }

    // ── Thread → sessionId mapping (global, shared across all users) ──────────
    private static final ConcurrentHashMap<Long, String> threadToSession = new ConcurrentHashMap<>();

    public static void registerThread(String sessionId) {
        threadToSession.put(Thread.currentThread().getId(), sessionId);
    }
    public static void unregisterThread() {
        threadToSession.remove(Thread.currentThread().getId());
    }

    // ── Live state update (called from trader thread) ─────────────────────────

    public static void updateLiveState(double coinBalance, double usdtBalance,
                                       boolean isTrading, double currentPrice,
                                       Double maxPrice, Double buyTargetPrice, Double boughtAtPrice,
                                       Double sellProfitPrice, Double sellLossPrice) {
        updateLiveState(coinBalance, usdtBalance, isTrading, currentPrice, maxPrice, buyTargetPrice,
                boughtAtPrice, sellProfitPrice, sellLossPrice, 0, 0, 0.0, 0.0, 0.0, 0, false);
    }

    public static void updateLiveState(double coinBalance, double usdtBalance,
                                       boolean isTrading, double currentPrice,
                                       Double maxPrice, Double buyTargetPrice, Double boughtAtPrice,
                                       Double sellProfitPrice, Double sellLossPrice,
                                       int profitTradeCount, int lossTradeCount,
                                       double profitSum, double lossSum, double estimatedCommission,
                                       double startBalance, boolean isTesterAccount) {
        String sid = threadToSession.get(Thread.currentThread().getId());
        if (sid == null) return;
        for (TradingSessionManager mgr : userManagers.values()) {
            SessionInfo info = mgr.sessions.get(sid);
            if (info != null) {
                info.coinBalance     = coinBalance;
                info.usdtBalance     = usdtBalance;
                info.isTrading       = isTrading;
                info.currentPrice    = currentPrice;
                info.maxPrice        = maxPrice;
                info.buyTargetPrice  = buyTargetPrice;
                info.boughtAtPrice   = boughtAtPrice;
                info.sellProfitPrice = sellProfitPrice;
                info.sellLossPrice   = sellLossPrice;
                if (profitTradeCount > 0 || lossTradeCount > 0) {
                    info.profitTradeCount = profitTradeCount;
                    info.lossTradeCount   = lossTradeCount;
                    info.profitSum        = profitSum;
                    info.lossSum          = lossSum;
                    info.estimatedCommission = estimatedCommission;
                }
                info.winCount        = profitTradeCount;
                info.lossCount       = lossTradeCount;
                info.totalProfit     = profitSum;
                info.totalLoss       = lossSum;
                info.totalCommission = estimatedCommission;
                info.startBalance    = startBalance;
                info.isTesterAccount = isTesterAccount;
                mgr.saveSessions();
                return;
            }
        }
    }

    public static void logEventFromCurrentThread(String message) {
        logTypedEventFromCurrentThread(detectEventType(message), message);
    }

    public static void logTypedEventFromCurrentThread(String type, String message) {
        if (message == null) return;
        String sid = threadToSession.get(Thread.currentThread().getId());
        if (sid == null) return;
        for (TradingSessionManager mgr : userManagers.values()) {
            SessionInfo info = mgr.sessions.get(sid);
            if (info != null) {
                info.addEvent(type != null ? type : "INFO", message);
                mgr.saveSessions();
                return;
            }
        }
    }

    private static String detectEventType(String msg) {
        if (msg == null) return "INFO";
        String lower = msg.toLowerCase();
        if (lower.contains("ошибк") || lower.contains("❌ ошибка") || lower.contains("❌ error")
                || lower.contains("exception") || lower.contains("критическ") || lower.contains("⛔")) return "ERROR";
        if (lower.contains("остановл") || lower.contains("🛑") || lower.contains("stopped")) return "STOP";
        if (lower.contains("завершен") || lower.contains("done")) return "DONE";
        if (lower.contains("✅ покупка") || lower.contains("покупка совершена")) return "BUY";
        if (lower.contains("📈 продажа") || lower.contains("📉 продажа")) return "SELL";
        if (lower.contains("🆕 новая торговая") || lower.contains("состояние восстановлено")
                || lower.contains("сессия возобновлена")) return "START";
        return "INFO";
    }

    // ── Enums / Inner classes ─────────────────────────────────────────────────

    public enum SessionType { TESTER, BINANCE_TEST, BINANCE_REAL, RESEARCH, BACKTEST }

    public static class SessionEvent {
        public long timestamp;
        public String type;
        public String message;
        public SessionEvent() {}
        SessionEvent(String type, String message) {
            this.timestamp = System.currentTimeMillis();
            this.type = type; this.message = message;
        }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", timestamp); m.put("type", type);
            m.put("message", message != null ? message : "");
            return m;
        }
    }

    public static class SessionInfo {
        public String id;
        public SessionType type;
        public String coinName;
        public long ownerId = 0;
        public Map<String, Object> params;
        public volatile String status;
        public volatile String lastMessage;
        public volatile long startedAt;
        public volatile long endedAt = 0;
        public volatile double coinBalance = 0;
        public volatile double usdtBalance = 0;
        public volatile boolean isTrading = false;
        public volatile Double currentPrice = null;
        public volatile Double maxPrice = null;
        public volatile Double buyTargetPrice = null;
        public volatile Double boughtAtPrice = null;
        public volatile Double sellProfitPrice = null;
        public volatile Double sellLossPrice = null;
        public volatile boolean stoppedUnexpectedly = false;
        // Trade statistics
        public volatile int profitTradeCount = 0;
        public volatile int lossTradeCount = 0;
        public volatile double profitSum = 0.0;
        public volatile double lossSum = 0.0;
        public volatile double estimatedCommission = 0.0;
        public volatile double startUsdtBalance = 0.0;
        // Backtest progress
        public volatile int backtestProgressCurrent = 0;
        public volatile int backtestProgressTotal = 0;
        // Stored backtest result for later restoration (only for BACKTEST sessions)
        public transient Map<String, Object> backtestResultData = null;
        // Master fields (aliases / additional)
        public volatile int winCount = 0;
        public volatile int lossCount = 0;
        public volatile double totalProfit = 0;
        public volatile double totalLoss = 0;
        public volatile double totalCommission = 0;
        public volatile double startBalance = 0;
        public volatile boolean isTesterAccount = false;
        public transient Thread thread;
        public final List<SessionEvent> events = new CopyOnWriteArrayList<>();

        public SessionInfo() {}
        SessionInfo(String id, SessionType type, String coinName,
                    Map<String, Object> params, long ownerId) {
            this.id = id; this.type = type; this.coinName = coinName;
            this.params = new HashMap<>(params);
            this.status = "RUNNING";
            this.startedAt = System.currentTimeMillis();
            this.lastMessage = "Запущено";
            this.ownerId = ownerId;
        }

        public Map<String, Object> toMap() { return toMap(false); }
        public Map<String, Object> toMap(boolean includeEvents) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("type", type.name()); m.put("coinName", coinName);
            m.put("status", status); m.put("lastMessage", lastMessage);
            m.put("startedAt", startedAt); m.put("endedAt", endedAt);
            m.put("params", params); m.put("ownerId", ownerId);
            m.put("coinBalance", coinBalance); m.put("usdtBalance", usdtBalance);
            m.put("isTrading", isTrading); m.put("currentPrice", currentPrice);
            m.put("maxPrice", maxPrice); m.put("buyTargetPrice", buyTargetPrice);
            m.put("boughtAtPrice", boughtAtPrice); m.put("sellProfitPrice", sellProfitPrice);
            m.put("sellLossPrice", sellLossPrice); m.put("stoppedUnexpectedly", stoppedUnexpectedly);
            // Trade statistics
            m.put("profitTradeCount", profitTradeCount);
            m.put("lossTradeCount", lossTradeCount);
            m.put("profitSum", profitSum);
            m.put("lossSum", lossSum);
            m.put("estimatedCommission", estimatedCommission);
            m.put("startUsdtBalance", startUsdtBalance);
            m.put("backtestProgressCurrent", backtestProgressCurrent);
            m.put("backtestProgressTotal", backtestProgressTotal);
            m.put("winCount", winCount); m.put("lossCount", lossCount);
            m.put("totalProfit", totalProfit); m.put("totalLoss", totalLoss);
            m.put("totalCommission", totalCommission); m.put("startBalance", startBalance);
            m.put("isTesterAccount", isTesterAccount);
            m.put("netPnl", totalProfit - totalLoss);
            m.put("netPnlAfterCommission", totalProfit - totalLoss - totalCommission);
            if (includeEvents)
                m.put("events", events.stream().map(SessionEvent::toMap).collect(Collectors.toList()));
            return m;
        }

        public void addEvent(String type, String message) {
            events.add(new SessionEvent(type, message));
            while (events.size() > 1000) events.remove(0);
            lastMessage = message;
        }
    }

    // ── Instance state ────────────────────────────────────────────────────────

    private final long userId;
    private final String sessionStoreFile;
    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    // Per-strategy backtest/top10 results: keyed by strategy name ("reversal" or "reversal_recap")
    private final ConcurrentHashMap<String, Map<String, Object>> lastBacktestResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastBacktestChartPaths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, Object>> lastTop10Results = new ConcurrentHashMap<>();

    private TradingSessionManager(long userId) {
        this.userId = userId;
        String dir = userId == 0 ? "trading_states/global" : "trading_states/user_" + userId;
        this.sessionStoreFile = dir + "/sessions.json";
        try { Files.createDirectories(Paths.get(dir)); } catch (IOException ignored) {}
    }

    // ── Session guards ────────────────────────────────────────────────────────

    /** Check if any session is currently running (any type). */
    public boolean hasActiveSession() {
        return sessions.values().stream().anyMatch(s -> "RUNNING".equals(s.status));
    }

    /** Check if a session of the given type is already running.
     *  Allows one running session per type simultaneously. */
    public boolean hasActiveSessionOfType(SessionType type) {
        return sessions.values().stream()
                .anyMatch(s -> s.type == type && "RUNNING".equals(s.status));
    }

    private Map<String, Object> tooManySessionsError(SessionType type) {
        String typeName;
        switch (type) {
            case TESTER:       typeName = "Тестовая торговля"; break;
            case BINANCE_TEST: typeName = "Binance Testnet"; break;
            case BINANCE_REAL: typeName = "Binance Real"; break;
            case BACKTEST:     typeName = "Бэктест/Исследование"; break;
            default:           typeName = type.name(); break;
        }
        return Map.of("error", "Уже есть активная сессия типа «" + typeName + "». Остановите её перед запуском новой.");
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void saveSessions() {
        try {
            Files.createDirectories(Paths.get(new File(sessionStoreFile).getParent()));
            List<Map<String, Object>> list = sessions.values().stream()
                .map(s -> s.toMap(true)).collect(Collectors.toList());
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(sessionStoreFile), list);
        } catch (Exception e) {
            log.warn("Failed to save sessions for user {}: {}", userId, e.getMessage());
        }
    }

    private volatile boolean sessionsLoaded = false;

    @SuppressWarnings("unchecked")
    public void loadSessions() {
        // Only load from disk once — subsequent calls are no-ops.
        // This prevents overwriting live in-memory sessions on page refresh.
        if (sessionsLoaded) {
            log.debug("Sessions already loaded for user {}, skipping reload", userId);
            return;
        }
        File f = new File(sessionStoreFile);
        if (!f.exists()) { sessionsLoaded = true; return; }
        try {
            List<Map<String, Object>> list = mapper.readValue(f, List.class);
            for (Map<String, Object> m : list) {
                SessionInfo info = new SessionInfo();
                info.id = (String) m.get("id");
                info.type = SessionType.valueOf((String) m.get("type"));
                info.coinName = (String) m.get("coinName");
                info.params = (Map<String, Object>) m.getOrDefault("params", new HashMap<>());
                info.status = (String) m.getOrDefault("status", "STOPPED");
                info.lastMessage = (String) m.getOrDefault("lastMessage", "");
                info.startedAt = toLong(m.get("startedAt"));
                info.endedAt = toLong(m.get("endedAt"));
                info.ownerId = toLong(m.getOrDefault("ownerId", userId)); // default to this user
                info.stoppedUnexpectedly = (Boolean) m.getOrDefault("stoppedUnexpectedly", false);
                info.coinBalance = toDouble(m.get("coinBalance"));
                info.usdtBalance = toDouble(m.get("usdtBalance"));
                info.isTrading = (Boolean) m.getOrDefault("isTrading", false);
                info.profitTradeCount = (int) toLong(m.getOrDefault("profitTradeCount", 0));
                info.lossTradeCount = (int) toLong(m.getOrDefault("lossTradeCount", 0));
                info.profitSum = toDouble(m.getOrDefault("profitSum", 0.0));
                info.lossSum = toDouble(m.getOrDefault("lossSum", 0.0));
                info.estimatedCommission = toDouble(m.getOrDefault("estimatedCommission", 0.0));
                info.startUsdtBalance = toDouble(m.getOrDefault("startUsdtBalance", 0.0));
                info.currentPrice = m.get("currentPrice") != null ? toDouble(m.get("currentPrice")) : null;
                info.maxPrice = m.get("maxPrice") != null ? toDouble(m.get("maxPrice")) : null;
                info.buyTargetPrice = m.get("buyTargetPrice") != null ? toDouble(m.get("buyTargetPrice")) : null;
                info.boughtAtPrice = m.get("boughtAtPrice") != null ? toDouble(m.get("boughtAtPrice")) : null;
                info.sellProfitPrice = m.get("sellProfitPrice") != null ? toDouble(m.get("sellProfitPrice")) : null;
                info.sellLossPrice = m.get("sellLossPrice") != null ? toDouble(m.get("sellLossPrice")) : null;
                info.winCount = (int) toLong(m.get("winCount"));
                info.lossCount = (int) toLong(m.get("lossCount"));
                info.totalProfit = toDouble(m.get("totalProfit"));
                info.totalLoss = toDouble(m.get("totalLoss"));
                info.totalCommission = toDouble(m.get("totalCommission"));
                info.startBalance = toDouble(m.get("startBalance"));
                info.isTesterAccount = (Boolean) m.getOrDefault("isTesterAccount", false);
                List<Map<String, Object>> evts = (List<Map<String, Object>>) m.get("events");
                if (evts != null) for (Map<String, Object> ev : evts) {
                    SessionEvent e = new SessionEvent();
                    e.timestamp = toLong(ev.get("timestamp"));
                    e.type = (String) ev.get("type");
                    e.message = (String) ev.get("message");
                    info.events.add(e);
                }
                if ("RUNNING".equals(info.status)) {
                    info.status = "STOPPED";
                    info.stoppedUnexpectedly = true;
                    info.endedAt = System.currentTimeMillis();
                    info.addEvent("ERROR", "Сессия прервана (JVM завершён) — ожидает возобновления");
                }
                // Skip sessions already in memory (e.g. already running after resume)
                SessionInfo existing = sessions.get(info.id);
                if (existing != null) {
                    // Never overwrite a session that has a live thread
                    if ("RUNNING".equals(existing.status) ||
                            (existing.thread != null && existing.thread.isAlive())) {
                        log.info("Skipping reload of session {} — still active in memory", info.id);
                        continue;
                    }
                }
                sessions.put(info.id, info);
            }
            sessionsLoaded = true;
            log.info("Loaded {} sessions for user {}", sessions.size(), userId);
        } catch (Exception e) {
            log.warn("Failed to load sessions for user {}: {}", userId, e.getMessage());
        }
    }

    public void autoResumeSessions(long chatId) {
        sessions.values().stream()
            .filter(s -> s.stoppedUnexpectedly && s.type != SessionType.BACKTEST)
            .filter(s -> !"RUNNING".equals(s.status))  // never resume already-running
            .filter(s -> s.thread == null || !s.thread.isAlive())  // never resume if thread alive
            .forEach(s -> {
                // Extra guard: don't resume if another session of same type is already active
                if (hasActiveSessionOfType(s.type)) {
                    log.info("Skipping auto-resume of session {} — another {} session already active", s.id, s.type);
                    return;
                }

                log.info("Auto-resuming session {} for user {}", s.id, userId);

                // Find last error/stop event to report the cause
                String cause = "JVM остановлен";
                for (int i = s.events.size() - 1; i >= 0; i--) {
                    SessionEvent ev = s.events.get(i);
                    if ("ERROR".equals(ev.type) || "STOP".equals(ev.type)) {
                        cause = ev.message != null ? ev.message : cause;
                        break;
                    }
                }

                // Send Telegram notification about the crash
                try {
                    String notifyMsg = "⚠️ Сессия остановлена!\n" +
                        "Монета: " + s.coinName + "\n" +
                        "Причина: " + cause + "\n" +
                        "Остановлена: " + new java.util.Date(s.endedAt) + "\n\n" +
                        "🔄 Автоматическое возобновление...";
                    ImageAndMessageSender.sendTelegramMessage(notifyMsg, chatId);
                } catch (Exception e) {
                    log.warn("Failed to send auto-resume notification for session {}", s.id, e);
                }

                s.addEvent("INFO", "Авто-возобновление после сбоя (причина: " + cause + ")");
                resumeSession(s.id, chatId, null);
            });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private StateManager stateManager() {
        return new StateManager(userId);
    }

    private TradingState loadTradingState(String sessionId) {
        // Try user-scoped directory first
        StateManager sm = stateManager();
        if (sm.hasState(sessionId)) {
            TradingState state = sm.loadState(sessionId);
            if (state != null) {
                log.info("Loaded TradingState for session {} from user dir: isTrading={}", sessionId, state.isTrading());
                return state;
            }
        }
        // Fallback: check global/legacy directory (state saved before path fix)
        if (userId != 0) {
            StateManager globalSm = new StateManager(0L);
            if (globalSm.hasState(sessionId)) {
                TradingState state = globalSm.loadState(sessionId);
                if (state != null) {
                    log.info("Loaded TradingState for session {} from GLOBAL fallback: isTrading={}", sessionId, state.isTrading());
                    // Migrate: save to correct user dir and delete from global
                    sm.saveState(state);
                    globalSm.deleteState(sessionId);
                    log.info("Migrated state for session {} from global to user_{}", sessionId, userId);
                    return state;
                }
            }
        }
        log.warn("No TradingState found for session {} (checked user_{} and global)", sessionId, userId);
        return null;
    }

    /**
     * Get Binance API key for this user.
     * Reads from secure per-user key store (profiles/keys/<userId>/).
     * Falls back to global AppConfig if user has no keys configured.
     */
    /**
     * Resolve Binance API key ONLY from user's personal key store.
     * No fallback to global config.properties — each user must configure their own keys.
     */
    /** Reads ONLY from user's personal key store — no AppConfig fallback. */
    private char[] resolveApiKey(UserProfileManager.UserProfile profile, boolean testnet) {
        if (userId <= 0) return new char[0]; // anonymous users cannot trade on Binance
        String k = UserProfileManager.getInstance().getBinanceApiKey(userId, testnet);
        return (k != null && !k.isBlank()) ? k.toCharArray() : new char[0];
    }

    private char[] resolvePrivKeyPath(UserProfileManager.UserProfile profile, boolean testnet) {
        if (userId <= 0) return new char[0];
        String p = UserProfileManager.getInstance().getBinancePrivKeyPath(userId, testnet);
        if (p != null && !p.isBlank()) {
            String resolved = AppConfig.getInstance().resolvePrivateKeyPath(p);
            if (resolved != null && !resolved.isBlank()) return resolved.toCharArray();
        }
        return new char[0];
    }

    private static void setFinalStatus(SessionInfo info) {
        info.status = "STOPPED"; info.stoppedUnexpectedly = false;
        if (Thread.currentThread().isInterrupted()) {
            info.lastMessage = "Остановлено пользователем";
            info.addEvent("STOP", "Остановлено пользователем");
        } else {
            info.lastMessage = "Торговля завершена";
            info.addEvent("STOP", "Торговля завершена");
        }
        info.endedAt = System.currentTimeMillis();
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        return 0L;
    }
    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return 0.0;
    }
    private static Map<String, Object> buildParams(double tradingSum, double buyGap,
            double spg, double slg, int timeout, int chartRefresh) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tradingSum", tradingSum); p.put("buyGap", buyGap);
        p.put("sellWithProfitGap", spg); p.put("sellWithLossGap", slg);
        p.put("telegramUpdateSec", timeout); p.put("chartRefreshSec", chartRefresh);
        p.put("strategy", "reversal"); // default; overridden by ATR+EMA methods
        return p;
    }

    // ── Start Tester ──────────────────────────────────────────────────────────

    public Object startTesterTrading(String coinName, double startAssets, double tradingSum,
            double buyGap, double spg, double slg, int timeout, int chartRefresh, long chatId) {
        return startTesterTrading(coinName, startAssets, tradingSum, buyGap, spg, slg, timeout, chartRefresh, chatId, false);
    }

    public Object startTesterTrading(String coinName, double startAssets, double tradingSum,
            double buyGap, double spg, double slg, int timeout, int chartRefresh, long chatId, boolean recapitalize) {
        if (hasActiveSessionOfType(SessionType.TESTER)) return tooManySessionsError(SessionType.TESTER);
        String id = "tester_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, timeout, chartRefresh);
        params.put("startAssets", startAssets);
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.TESTER, coinName, params, userId);
        String stratLabel = recapitalize ? " [РЕКАП]" : "";
        info.addEvent("START", "Сессия запущена" + stratLabel + ": " + coinName);
        info.startUsdtBalance = startAssets;
        sessions.put(id, info);
        launchTesterThread(info, startAssets, tradingSum, buyGap, spg, slg, timeout, null, chatId, recapitalize);
        saveSessions();
        return info;
    }

    // ── Start Research (same logic as Tester, separate type for concurrent sessions) ──

    public Object startResearchTrading(String coinName, double startAssets, double tradingSum,
            double buyGap, double spg, double slg, int timeout, int chartRefresh, long chatId, boolean recapitalize) {
        if (hasActiveSessionOfType(SessionType.RESEARCH)) return tooManySessionsError(SessionType.RESEARCH);
        String id = "research_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, timeout, chartRefresh);
        params.put("startAssets", startAssets);
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.RESEARCH, coinName, params, userId);
        String stratLabel = recapitalize ? " [РЕКАП]" : "";
        info.addEvent("START", "Исследование запущено" + stratLabel + ": " + coinName);
        info.startUsdtBalance = startAssets;
        sessions.put(id, info);
        launchTesterThread(info, startAssets, tradingSum, buyGap, spg, slg, timeout, null, chatId, recapitalize);
        saveSessions();
        return info;
    }

    private void launchTesterThread(SessionInfo info, double startAssets, double tradingSum,
            double buyGap, double spg, double slg, int timeout,
            TradingState savedState, long chatId) {
        launchTesterThread(info, startAssets, tradingSum, buyGap, spg, slg, timeout, savedState, chatId, false);
    }

    private void launchTesterThread(SessionInfo info, double startAssets, double tradingSum,
            double buyGap, double spg, double slg, int timeout,
            TradingState savedState, long chatId, boolean recapitalize) {
        Thread t = new Thread(() -> {
            registerThread(info.id);
            try {
                Coin coin = CoinsList.getCoinByName(info.coinName);
                Map<Coin, Double> assets = new HashMap<>();
                if (savedState != null && savedState.getWalletAssets() != null
                        && !savedState.getWalletAssets().isEmpty()) {
                    double usdtAmt = savedState.getWalletAssets().getOrDefault("USDT", startAssets);
                    double coinAmt = savedState.getWalletAssets().getOrDefault(coin.getSymbol().toUpperCase(), 0.0);
                    if (usdtAmt == 0 && coinAmt == 0) { usdtAmt = info.usdtBalance > 0 ? info.usdtBalance : startAssets; coinAmt = info.coinBalance; }
                    assets.put(Account.USD_TOKENS.USDT.getCoin(), usdtAmt);
                    assets.put(coin, coinAmt);
                } else if (savedState == null && info.coinBalance == 0 && info.usdtBalance == 0) {
                    assets.put(Account.USD_TOKENS.USDT.getCoin(), startAssets);
                    assets.put(coin, 0.0);
                } else {
                    assets.put(Account.USD_TOKENS.USDT.getCoin(), info.usdtBalance > 0 ? info.usdtBalance : startAssets);
                    assets.put(coin, info.coinBalance);
                }
                Account account = AccountBuilder.createNewTester(assets);
                boolean resume = savedState != null || info.events.stream()
                    .anyMatch(e -> "START".equals(e.type) && e.message != null && e.message.contains("возобновлена"));
                new ReversalPointsStrategyTrader(account, coin, tradingSum, buyGap, spg, slg,
                        timeout, chatId, savedState, info.id, resume, userId, recapitalize).startTrading();
                setFinalStatus(info);
            } catch (Exception e) {
                info.status = "ERROR"; info.endedAt = System.currentTimeMillis();
                info.stoppedUnexpectedly = true;
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("Tester trading error for user {}", userId, e);
            } finally {
                unregisterThread();
                ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart.releaseForCurrentThread();
                ImageAndMessageSender.clearChatId();
                saveSessions();
            }
        });
        t.setDaemon(true); info.thread = t; info.status = "RUNNING"; t.start();
    }

    // ── Start Binance ─────────────────────────────────────────────────────────

    public Object startBinanceTrading(String coinName, double tradingSum, double buyGap,
            double spg, double slg, int timeout, int chartRefresh,
            long chatId, UserProfileManager.UserProfile profile) {
        return startBinanceTrading(coinName, tradingSum, buyGap, spg, slg, timeout, chartRefresh, chatId, profile, false);
    }

    public Object startBinanceTrading(String coinName, double tradingSum, double buyGap,
            double spg, double slg, int timeout, int chartRefresh,
            long chatId, UserProfileManager.UserProfile profile, boolean recapitalize) {
        if (hasActiveSessionOfType(SessionType.BINANCE_REAL)) return tooManySessionsError(SessionType.BINANCE_REAL);
        String id = "binance_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, timeout, chartRefresh);
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_REAL, coinName, params, userId);
        String stratLabel = recapitalize ? " [РЕКАП]" : "";
        info.addEvent("START", "Binance REAL сессия запущена" + stratLabel + ": " + coinName);
        sessions.put(id, info);
        launchBinanceThread(info, tradingSum, buyGap, spg, slg, timeout, false, null, chatId, profile, recapitalize);
        saveSessions();
        return info;
    }

    public Object startBinanceTestTrading(String coinName, double tradingSum, double buyGap,
            double spg, double slg, int timeout, int chartRefresh,
            long chatId, UserProfileManager.UserProfile profile) {
        return startBinanceTestTrading(coinName, tradingSum, buyGap, spg, slg, timeout, chartRefresh, chatId, profile, false);
    }

    public Object startBinanceTestTrading(String coinName, double tradingSum, double buyGap,
            double spg, double slg, int timeout, int chartRefresh,
            long chatId, UserProfileManager.UserProfile profile, boolean recapitalize) {
        if (hasActiveSessionOfType(SessionType.BINANCE_TEST)) return tooManySessionsError(SessionType.BINANCE_TEST);
        String id = "binance_test_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, timeout, chartRefresh);
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_TEST, coinName, params, userId);
        String stratLabel = recapitalize ? " [РЕКАП]" : "";
        info.addEvent("START", "Binance TEST сессия запущена" + stratLabel + ": " + coinName);
        sessions.put(id, info);
        launchBinanceThread(info, tradingSum, buyGap, spg, slg, timeout, true, null, chatId, profile, recapitalize);
        saveSessions();
        return info;
    }

    private void launchBinanceThread(SessionInfo info, double tradingSum,
            double buyGap, double spg, double slg, int timeout, boolean testnet,
            TradingState savedState, long chatId, UserProfileManager.UserProfile profile) {
        launchBinanceThread(info, tradingSum, buyGap, spg, slg, timeout, testnet, savedState, chatId, profile, false);
    }

    private void launchBinanceThread(SessionInfo info, double tradingSum,
            double buyGap, double spg, double slg, int timeout, boolean testnet,
            TradingState savedState, long chatId, UserProfileManager.UserProfile profile, boolean recapitalize) {
        Thread t = new Thread(() -> {
            registerThread(info.id);
            try {
                char[] apiKey   = resolveApiKey(profile, testnet);
                char[] privKey  = resolvePrivKeyPath(profile, testnet);
                if (apiKey.length == 0) throw new IllegalStateException(
                    "Binance API ключ не настроен. Перейдите в 👤 Кабинет → 🔑 Binance API ключи и добавьте " +
                    (testnet ? "Testnet" : "Mainnet") + " ключи.");
                if (privKey.length == 0) throw new IllegalStateException(
                    "Ed25519 .pem файл не загружен. Перейдите в 👤 Кабинет → 🔑 Binance API ключи и загрузите .pem файл.");
                Coin coin = CoinsList.getCoinByName(info.coinName);
                Account account = AccountBuilder.createNewBinance(apiKey, privKey,
                        testnet ? AccountBuilder.BINANCE_BASE_URL.TESTNET : AccountBuilder.BINANCE_BASE_URL.MAINNET);
                boolean resume = savedState != null || info.events.stream()
                    .anyMatch(e -> "START".equals(e.type) && e.message != null && e.message.contains("возобновлена"));
                new ReversalPointsStrategyTrader(account, coin, tradingSum, buyGap, spg, slg,
                        timeout, chatId, savedState, info.id, resume, userId, recapitalize).startTrading();
                setFinalStatus(info);
            } catch (Exception e) {
                info.status = "ERROR"; info.endedAt = System.currentTimeMillis();
                info.stoppedUnexpectedly = true;
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("Binance trading error (testnet={}) for user {}", testnet, userId, e);
            } finally {
                unregisterThread();
                ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart.releaseForCurrentThread();
                ImageAndMessageSender.clearChatId();
                saveSessions();
            }
        });
        t.setDaemon(true); info.thread = t; info.status = "RUNNING"; t.start();
    }

    // ── Resume ────────────────────────────────────────────────────────────────

    public Map<String, Object> resumeSession(String sessionId, long chatId,
                                              UserProfileManager.UserProfile profile) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return Map.of("error", "Сессия не найдена");
        if ("RUNNING".equals(info.status)) return Map.of("error", "Сессия уже запущена");
        if (hasActiveSessionOfType(info.type)) return Map.of("error", "Уже есть активная сессия этого типа");
        if (info.type == SessionType.BACKTEST) return Map.of("error", "Бэктест нельзя возобновить");

        // Kill old thread if still alive (e.g. stop+resume in quick succession)
        if (info.thread != null && info.thread.isAlive()) {
            log.warn("Old thread for session {} still alive — interrupting before resume", sessionId);
            info.thread.interrupt();
            try { info.thread.join(5000); } catch (InterruptedException ignored) {}
            if (info.thread.isAlive()) {
                log.error("Old thread for session {} did not stop within 5s", sessionId);
                return Map.of("error", "Предыдущий поток не удалось остановить. Попробуйте позже.");
            }
        }

        TradingState savedState = loadTradingState(sessionId);
        info.status = "RUNNING"; info.stoppedUnexpectedly = false; info.endedAt = 0;
        info.addEvent("START", savedState != null
            ? "Сессия возобновлена (isTrading=" + savedState.isTrading() + ")"
            : "Сессия возобновлена (без сохранённого состояния)");

        Map<String, Object> p = info.params;
        double tradingSum = toDouble(p.get("tradingSum"));
        double buyGap     = toDouble(p.get("buyGap"));
        double spg        = toDouble(p.get("sellWithProfitGap"));
        double slg        = toDouble(p.get("sellWithLossGap"));
        int timeout       = (int) toLong(p.get("telegramUpdateSec"));
        if (timeout == 0) timeout = 30;

        boolean recapitalize = Boolean.TRUE.equals(p.get("recapitalize"));

        switch (info.type) {
            case TESTER:
            case RESEARCH:
                launchTesterThread(info, toDouble(p.getOrDefault("startAssets", 150.0)),
                        tradingSum, buyGap, spg, slg, timeout, savedState, chatId, recapitalize);
                break;
            case BINANCE_REAL:
                launchBinanceThread(info, tradingSum, buyGap, spg, slg, timeout,
                        false, savedState, chatId, profile, recapitalize);
                break;
            case BINANCE_TEST:
                launchBinanceThread(info, tradingSum, buyGap, spg, slg, timeout,
                        true, savedState, chatId, profile, recapitalize);
                break;
            default:
                info.status = "STOPPED";
                return Map.of("error", "Тип сессии не поддерживает возобновление");
        }
        saveSessions();
        return Map.of("resumed", true, "id", sessionId,
                      "stateRestored", savedState != null,
                      "wasTrading", savedState != null && savedState.isTrading());
    }

    // ── Stop / Delete ─────────────────────────────────────────────────────────

    public boolean stopSession(String sessionId) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return false;
        boolean threadWasAlive = info.thread != null && info.thread.isAlive();
        if (threadWasAlive) {
            info.thread.interrupt();
            // Wait for the thread to actually die, so resume doesn't start a duplicate
            try { info.thread.join(10_000); } catch (InterruptedException ignored) {}
            if (info.thread.isAlive()) {
                log.warn("Thread for session {} still alive after 10s interrupt", sessionId);
            }
        }
        // setFinalStatus (called from the dying thread) already sets status/event.
        // Only add STOP event here if thread wasn't alive (i.e. setFinalStatus didn't run).
        if (!threadWasAlive || info.thread.isAlive()) {
            info.status = "STOPPED"; info.stoppedUnexpectedly = false;
            info.endedAt = System.currentTimeMillis();
            info.addEvent("STOP", "Остановлено пользователем");
        }
        saveSessions();
        return true;
    }

    public void stopAllSessions() { sessions.keySet().forEach(this::stopSession); }

    public boolean deleteSession(String sessionId) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return false;
        if ("RUNNING".equals(info.status)) return false;
        sessions.remove(sessionId);
        stateManager().deleteState(sessionId);
        saveSessions();
        return true;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<Map<String, Object>> getAllSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SessionInfo s : sessions.values()) {
            try { result.add(s.toMap(false)); }
            catch (Exception e) { log.warn("Failed to serialize session {}: {}", s.id, e.getMessage()); }
        }
        result.sort((a, b) -> {
            long t1 = a.get("startedAt") instanceof Number ? ((Number) a.get("startedAt")).longValue() : 0;
            long t2 = b.get("startedAt") instanceof Number ? ((Number) b.get("startedAt")).longValue() : 0;
            return Long.compare(t2, t1);
        });
        return result;
    }

    public Map<String, Object> getSessionDetail(String sessionId) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return Map.of("error", "Сессия не найдена");
        return info.toMap(true);
    }

    // ── Backtest (per-user result) ────────────────────────────────────────────

    public SessionInfo startBacktest(String coinName, double tradingSum, double buyGap,
                                     double spg, double slg, String chartType) {
        return startBacktest(coinName, tradingSum, buyGap, spg, slg, chartType, "Binance", 0.1);
    }

    public SessionInfo startBacktest(String coinName, double tradingSum, double buyGap,
                                     double spg, double slg, String chartType,
                                     String exchangeName) {
        return startBacktest(coinName, tradingSum, buyGap, spg, slg, chartType, exchangeName, 0.1);
    }

    public SessionInfo startBacktest(String coinName, double tradingSum, double buyGap,
                                     double spg, double slg, String chartType,
                                     String exchangeName, double commissionRate) {
        return startBacktest(coinName, tradingSum, buyGap, spg, slg, chartType, exchangeName, commissionRate, 0, 0, false);
    }

    public SessionInfo startBacktest(String coinName, double tradingSum, double buyGap,
                                     double spg, double slg, String chartType,
                                     String exchangeName, double commissionRate,
                                     long customFrom, long customTo) {
        return startBacktest(coinName, tradingSum, buyGap, spg, slg, chartType, exchangeName, commissionRate, customFrom, customTo, false);
    }

    public SessionInfo startBacktest(String coinName, double tradingSum, double buyGap,
                                     double spg, double slg, String chartType,
                                     String exchangeName, double commissionRate,
                                     long customFrom, long customTo, boolean recapitalize) {
        String id = "backtest_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, 30, 60);
        params.put("chartType", chartType);
        params.put("exchangeName", exchangeName);
        params.put("commissionRate", commissionRate);
        if (customFrom > 0) params.put("customFrom", customFrom);
        if (customTo > 0) params.put("customTo", customTo);
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.BACKTEST, coinName, params, userId);
        String strategyKey = recapitalize ? "reversal_recap" : "reversal";
        lastBacktestResults.remove(strategyKey);
        lastBacktestChartPaths.remove(strategyKey);
        String stratLabel = recapitalize ? " [РЕКАП]" : "";
        info.addEvent("START", "Бэктест запущен" + stratLabel + ": " + coinName);
        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                CommissionCalculator commCalc = new CommissionCalculator(CommissionCalculator.Exchange.BINANCE);
                Coin coin = CoinsList.getCoinByName(coinName);
                Chart chart;
                switch (chartType) {
                    case "5yr":
                        info.addEvent("INFO", "Загрузка 5-летних данных через Binance...");
                        chart = Chart.getBinanceChart(coin, 5);
                        break;
                    case "3yr":
                        info.addEvent("INFO", "Загрузка 3-летних данных через Binance...");
                        chart = Chart.getBinanceChart(coin, 3);
                        break;
                    case "yearly":
                        info.addEvent("INFO", "Загрузка годовых данных через Binance...");
                        chart = Chart.getBinanceChart(coin, 1);
                        break;
                    case "monthly":
                        java.time.YearMonth ym = java.time.YearMonth.now().minusMonths(1);
                        chart = Chart.getMonthlyChart_1hourInterval(coin, ym);
                        break;
                    case "custom":
                        long cfrom = customFrom > 0 ? customFrom : toLong(params.get("customFrom"));
                        long cto = customTo > 0 ? customTo : toLong(params.get("customTo"));
                        if (cfrom <= 0 || cto <= 0) {
                            cto = System.currentTimeMillis();
                            cfrom = cto - 365L * 24 * 3600 * 1000;
                        }
                        info.addEvent("INFO", "Загрузка данных за произвольный период через Binance...");
                        chart = Chart.getBinanceChart(coin, cfrom, cto);
                        break;
                    default:
                        chart = Chart.get1DayUntilNowChart_5MinuteInterval(coin);
                        break;
                }
                info.addEvent("INFO", "Данные загружены (" + chart.getPrices().size() + " точек), запуск бэктеста" + stratLabel + "...");
                ReversalPointStrategyBackTester tester = new ReversalPointStrategyBackTester(
                        coin, chart, tradingSum, buyGap, spg, slg,
                        new CommissionCalculator(CommissionCalculator.Exchange.BINANCE), recapitalize);
                info.backtestProgressTotal = chart.getPrices().size();

                // Start progress updater thread
                Thread progressUpdater = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted() && "RUNNING".equals(info.status)) {
                        info.backtestProgressCurrent = tester.getProgressCurrent();
                        info.backtestProgressTotal = tester.getProgressTotal();
                        try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                    }
                });
                progressUpdater.setDaemon(true);
                progressUpdater.start();

                ReversalPointStrategyBackTester.BackTestResult result = tester.startBackTest();
                progressUpdater.interrupt();

                // Generate chart image
                String chartPath = tester.generateChartImage();
                lastBacktestChartPaths.put(strategyKey, chartPath);

                if (result == null) {
                    info.status = "ERROR"; info.endedAt = System.currentTimeMillis();
                    info.addEvent("ERROR", "Бэктест завершился без результата (недостаточно данных)");
                    return;
                }

                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("coinName", coinName); rm.put("buyGap", result.getBuyGap());
                rm.put("sellWithProfitGap", result.getSellWithProfit());
                rm.put("sellWithLossGap", result.getSellWithLossGap());
                rm.put("profitUsd", result.getProfitInUsd());
                rm.put("profitPercent", result.getPercentageProfit());
                rm.put("profitUsdAfterCommission", result.getProfitInUsdAfterCommission());
                rm.put("profitPercentAfterCommission", result.getPercentageProfitAfterCommission());
                rm.put("tradingSum", tradingSum); rm.put("chartType", chartType);
                rm.put("profitTradeCount", result.getProfitTradeCount());
                rm.put("lossTradeCount", result.getLossTradeCount());
                rm.put("totalTradeCount", result.getTotalTradeCount());
                rm.put("totalProfit", result.getTotalProfit());
                rm.put("totalLoss", result.getTotalLoss());
                rm.put("totalCommission", result.getTotalCommission());
                rm.put("exchangeName", result.getExchangeName());
                rm.put("commissionRate", result.getCommissionRate());
                rm.put("profitAfterCommission", result.getProfitAfterCommission());
                rm.put("winCount", result.getWinCount());
                rm.put("lossCount", result.getLossCount());
                rm.put("totalTrades", result.getTotalTrades());
                rm.put("totalProfitAmount", result.getTotalProfitAmount());
                rm.put("totalLossAmount", result.getTotalLossAmount());
                rm.put("exchange", commCalc.getExchange().getDisplayName());
                rm.put("feePercent", commCalc.getFeePercent());
                rm.put("chartImageAvailable", chartPath != null);
                rm.put("recapitalize", recapitalize);
                rm.put("earlyTermination", result.isEarlyTermination());
                // Store trade events for chart
                List<Map<String, Object>> evList = new java.util.ArrayList<>();
                for (double[] ev : tester.getTradeEvents()) {
                    Map<String, Object> evm = new LinkedHashMap<>();
                    evm.put("timestamp", (long) ev[0]);
                    evm.put("price", ev[1]);
                    evm.put("eventType", (int) ev[2]); // 0=buy,1=sell_profit,2=sell_loss
                    evList.add(evm);
                }
                rm.put("tradeEvents", evList);
                // Equity curve for yield chart
                List<Map<String, Object>> eqList = new java.util.ArrayList<>();
                for (double[] eq : tester.getEquityCurve()) {
                    Map<String, Object> eqm = new LinkedHashMap<>();
                    eqm.put("timestamp", (long) eq[0]);
                    eqm.put("equity", eq[1]);
                    eqList.add(eqm);
                }
                rm.put("equityCurve", eqList);
                // Hold curve for comparison line
                List<Map<String, Object>> holdList = new java.util.ArrayList<>();
                for (double[] h : tester.getHoldCurve()) {
                    Map<String, Object> hm = new LinkedHashMap<>();
                    hm.put("timestamp", (long) h[0]);
                    hm.put("equity", h[1]);
                    holdList.add(hm);
                }
                rm.put("holdCurve", holdList);
                // Detailed trade report
                rm.put("tradeReport", tester.getTradeReport());
                lastBacktestResults.put(strategyKey, rm);
                info.backtestResultData = rm;
                info.status = "DONE"; info.endedAt = System.currentTimeMillis();
                info.backtestProgressCurrent = info.backtestProgressTotal;
                info.addEvent("DONE", String.format(
                    "Готово! Прибыль: %.2f USD (%.2f%%) | С комиссией: %.2f USD (%.2f%%) | Сделок: %d (+%d/-%d) | Комиссия: %.4f USD",
                    result.getProfitInUsd(), result.getPercentageProfit(),
                    result.getProfitInUsdAfterCommission(), result.getPercentageProfitAfterCommission(),
                    result.getTotalTradeCount(), result.getProfitTradeCount(), result.getLossTradeCount(),
                    result.getTotalCommission()));
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    info.status = "STOPPED"; info.addEvent("STOP", "Прервано");
                } else {
                    info.status = "ERROR"; info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                    log.error("Backtest error for user {}", userId, e);
                }
                info.endedAt = System.currentTimeMillis();
            } finally { unregisterThread(); saveSessions(); }
        });
        t.setDaemon(true); info.thread = t;
        sessions.put(id, info); t.start();
        return info;
    }

    // ── Top-10 strategy search ───────────────────────────────────────────────

    public SessionInfo startTop10Search(String coinName, double tradingSum, String chartType, String exchange) {
        return startTop10Search(coinName, tradingSum, chartType, exchange, false);
    }

    public SessionInfo startTop10Search(String coinName, double tradingSum, String chartType, String exchange, boolean recapitalize) {
        String id = "top10_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tradingSum", tradingSum);
        params.put("chartType", chartType);
        params.put("exchange", exchange);
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.BACKTEST, coinName, params, userId);
        String strategyKey = recapitalize ? "reversal_recap" : "reversal";
        lastTop10Results.remove(strategyKey);
        String stratLabel = recapitalize ? " [РЕКАП]" : "";
        info.addEvent("START", "Поиск ТОП-10 стратегий" + stratLabel + ": " + coinName);
        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                CommissionCalculator commCalc = new CommissionCalculator(CommissionCalculator.Exchange.BINANCE);
                Coin coin = CoinsList.getCoinByName(coinName);
                info.addEvent("INFO", "Загрузка данных графика...");
                Chart chart;
                switch (chartType) {
                    case "yearly": chart = Chart.getYearlyChart_1hourInterval(coin); break;
                    case "monthly": chart = Chart.getMonthlyChart_1hourInterval(coin, YearMonth.now().minusMonths(1)); break;
                    default: chart = Chart.get1DayUntilNowChart_5MinuteInterval(coin); break;
                }
                info.addEvent("INFO", "Данные загружены. Начинаем перебор стратегий...");

                TreeSet<ReversalPointStrategyBackTester.BackTestResult> results = new TreeSet<>();
                double step = 0.1;
                double startBuyGap = 0.1, maxBuyGap = 5;
                double startSPG = 1, maxSPG = 5;
                double startSLG = 1, maxSLG = 5;
                int totalIterations = (int) (((maxSLG - startSLG + step) / step)
                        * ((maxSPG - startSPG + step) / step)
                        * ((maxBuyGap - startBuyGap + step) / step));
                int completed = 0;
                int lastReportedPercent = 0;

                for (double bg = startBuyGap; bg <= maxBuyGap; bg += step) {
                    if (Thread.currentThread().isInterrupted()) break;
                    for (double spg = startSPG; spg <= maxSPG; spg += step) {
                        if (Thread.currentThread().isInterrupted()) break;
                        for (double slg = startSLG; slg <= maxSLG; slg += step) {
                            if (Thread.currentThread().isInterrupted()) break;
                            try {
                                ReversalPointStrategyBackTester tester = new ReversalPointStrategyBackTester(
                                        coin, chart, tradingSum, bg, spg, slg, commCalc, recapitalize);
                                ReversalPointStrategyBackTester.BackTestResult r = tester.startBackTest();
                                if (r != null) {
                                    results.add(r);
                                    if (results.size() > 10) results.pollLast();
                                }
                            } catch (Exception ignored) {}
                            completed++;
                            int pct = (int) ((double) completed / totalIterations * 100);
                            if (pct > lastReportedPercent && pct % 5 == 0) {
                                lastReportedPercent = pct;
                                info.addEvent("INFO", "Прогресс: " + pct + "% (" + completed + "/" + totalIterations + ")");
                                saveSessions();
                            }
                        }
                    }
                }

                List<Map<String, Object>> top10 = new ArrayList<>();
                int rank = 1;
                for (ReversalPointStrategyBackTester.BackTestResult r : results) {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("rank", rank++);
                    rm.put("buyGap", r.getBuyGap());
                    rm.put("sellWithProfitGap", r.getSellWithProfit());
                    rm.put("sellWithLossGap", r.getSellWithLossGap());
                    rm.put("profitUsd", r.getProfitInUsd());
                    rm.put("profitPercent", r.getPercentageProfit());
                    rm.put("totalCommission", r.getTotalCommission());
                    rm.put("profitAfterCommission", r.getProfitAfterCommission());
                    rm.put("winCount", r.getWinCount());
                    rm.put("lossCount", r.getLossCount());
                    rm.put("totalTrades", r.getTotalTrades());
                    top10.add(rm);
                }
                Map<String, Object> resultMap = new LinkedHashMap<>();
                resultMap.put("coinName", coinName);
                resultMap.put("chartType", chartType);
                resultMap.put("tradingSum", tradingSum);
                resultMap.put("exchange", commCalc.getExchange().getDisplayName());
                resultMap.put("feePercent", commCalc.getFeePercent());
                resultMap.put("totalIterations", totalIterations);
                resultMap.put("strategies", top10);
                lastTop10Results.put(strategyKey, resultMap);
                info.backtestResultData = resultMap;

                info.status = "DONE"; info.endedAt = System.currentTimeMillis();
                String best = top10.isEmpty() ? "нет результатов"
                    : String.format("Лучшая: $%.2f (с комиссией: $%.2f)",
                        top10.get(0).get("profitUsd"), top10.get(0).get("profitAfterCommission"));
                info.addEvent("DONE", "ТОП-10 готов! " + best);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    info.status = "STOPPED"; info.addEvent("STOP", "Прервано");
                } else {
                    info.status = "ERROR"; info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                    log.error("Top10 search error for user {}", userId, e);
                }
                info.endedAt = System.currentTimeMillis();
            } finally { unregisterThread(); saveSessions(); }
        });
        t.setDaemon(true); info.thread = t;
        sessions.put(id, info); t.start();
        return info;
    }

    /** Generate range array from min/max/step */
    private static double[] generateRange(double min, double max, double step) {
        if (step <= 0 || min > max) return new double[]{min};
        java.util.List<Double> vals = new java.util.ArrayList<>();
        for (double v = min; v <= max + step * 0.01; v += step) {
            vals.add(Math.round(v * 100.0) / 100.0);
        }
        return vals.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public List<Map<String, Object>> findTopStrategies(String coinName, double tradingSum,
                                                        String chartType, String exchangeName,
                                                        double commissionRate, int topN,
                                                        SessionInfo progressInfo,
                                                        double buyMin, double buyMax, double buyStep,
                                                        double profitMin, double profitMax, double profitStep,
                                                        double lossMin, double lossMax, double lossStep) {
        return findTopStrategies(coinName, tradingSum, chartType, exchangeName, commissionRate, topN, progressInfo,
                buyMin, buyMax, buyStep, profitMin, profitMax, profitStep, lossMin, lossMax, lossStep, false);
    }

    /** Find top N strategies by brute-force backtest over parameter grid */
    public List<Map<String, Object>> findTopStrategies(String coinName, double tradingSum,
                                                        String chartType, String exchangeName,
                                                        double commissionRate, int topN,
                                                        SessionInfo progressInfo,
                                                        double buyMin, double buyMax, double buyStep,
                                                        double profitMin, double profitMax, double profitStep,
                                                        double lossMin, double lossMax, double lossStep,
                                                        boolean recapitalize) {
        try {
            Coin coin = CoinsList.getCoinByName(coinName);
            Chart chart;
            switch (chartType) {
                case "5yr": chart = Chart.getBinanceChart(coin, 5); break;
                case "3yr": chart = Chart.getBinanceChart(coin, 3); break;
                case "yearly": chart = Chart.getBinanceChart(coin, 1); break;
                case "monthly":
                    java.time.YearMonth ym = java.time.YearMonth.now().minusMonths(1);
                    chart = Chart.getMonthlyChart_1hourInterval(coin, ym);
                    break;
                default: chart = Chart.get1DayUntilNowChart_5MinuteInterval(coin); break;
            }

            double[] buyGaps = generateRange(buyMin, buyMax, buyStep);
            double[] profitGaps = generateRange(profitMin, profitMax, profitStep);
            double[] lossGaps = generateRange(lossMin, lossMax, lossStep);

            int totalCombinations = buyGaps.length * profitGaps.length * lossGaps.length;
            if (progressInfo != null) {
                progressInfo.backtestProgressTotal = totalCombinations;
                progressInfo.backtestProgressCurrent = 0;
            }

            // Bounded min-heap: keep only topN best results to avoid OOM
            java.util.PriorityQueue<Object[]> topHeap = new java.util.PriorityQueue<>(topN + 1,
                    Comparator.comparingDouble(p -> ((ReversalPointStrategyBackTester.BackTestResult) p[0]).getProfitInUsdAfterCommission()));
            int done = 0;
            for (double bg : buyGaps) {
                for (double pg : profitGaps) {
                    for (double lg : lossGaps) {
                        if (pg >= lg) { done++; if (progressInfo != null) progressInfo.backtestProgressCurrent = done; continue; }
                        ReversalPointStrategyBackTester tester = new ReversalPointStrategyBackTester(
                                coin, chart, tradingSum, bg, pg, lg,
                                new CommissionCalculator(CommissionCalculator.Exchange.BINANCE), recapitalize);
                        ReversalPointStrategyBackTester.BackTestResult r = tester.startBackTest();
                        if (r != null) {
                            topHeap.add(new Object[]{r, tester});
                            if (topHeap.size() > topN) topHeap.poll(); // remove worst, keep topN
                        }
                        done++;
                        if (progressInfo != null) progressInfo.backtestProgressCurrent = done;
                    }
                }
            }

            // Sort descending by profit
            List<Object[]> resultPairs = new java.util.ArrayList<>(topHeap);
            resultPairs.sort(Comparator.comparingDouble(p -> -((ReversalPointStrategyBackTester.BackTestResult) p[0]).getProfitInUsdAfterCommission()));
            List<Map<String, Object>> top = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(topN, resultPairs.size()); i++) {
                ReversalPointStrategyBackTester.BackTestResult r = (ReversalPointStrategyBackTester.BackTestResult) resultPairs.get(i)[0];
                ReversalPointStrategyBackTester tester = (ReversalPointStrategyBackTester) resultPairs.get(i)[1];
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rank", i + 1);
                m.put("buyGap", r.getBuyGap());
                m.put("sellWithProfitGap", r.getSellWithProfit());
                m.put("sellWithLossGap", r.getSellWithLossGap());
                m.put("profitUsd", r.getProfitInUsd());
                m.put("profitPercent", r.getPercentageProfit());
                m.put("profitUsdAfterCommission", r.getProfitInUsdAfterCommission());
                m.put("profitPercentAfterCommission", r.getPercentageProfitAfterCommission());
                m.put("totalTradeCount", r.getTotalTradeCount());
                m.put("profitTradeCount", r.getProfitTradeCount());
                m.put("lossTradeCount", r.getLossTradeCount());
                m.put("totalCommission", r.getTotalCommission());
                m.put("exchangeName", r.getExchangeName());
                m.put("commissionRate", r.getCommissionRate());
                // Trade events for chart visualization
                List<Map<String, Object>> evList = new java.util.ArrayList<>();
                for (double[] ev : tester.getTradeEvents()) {
                    Map<String, Object> evm = new LinkedHashMap<>();
                    evm.put("timestamp", (long) ev[0]);
                    evm.put("price", ev[1]);
                    evm.put("eventType", (int) ev[2]);
                    evList.add(evm);
                }
                m.put("tradeEvents", evList);
                // Equity curve for yield chart
                List<Map<String, Object>> eqList = new java.util.ArrayList<>();
                for (double[] eq : tester.getEquityCurve()) {
                    Map<String, Object> eqm = new LinkedHashMap<>();
                    eqm.put("timestamp", (long) eq[0]);
                    eqm.put("equity", eq[1]);
                    eqList.add(eqm);
                }
                m.put("equityCurve", eqList);
                // Hold curve for comparison line
                List<Map<String, Object>> holdList = new java.util.ArrayList<>();
                for (double[] h : tester.getHoldCurve()) {
                    Map<String, Object> hm = new LinkedHashMap<>();
                    hm.put("timestamp", (long) h[0]);
                    hm.put("equity", h[1]);
                    holdList.add(hm);
                }
                m.put("holdCurve", holdList);
                m.put("tradeReport", tester.getTradeReport());
                m.put("recapitalize", tester.isRecapitalize());
                if (r.isEarlyTermination()) m.put("earlyTermination", true);
                top.add(m);
            }
            return top;
        } catch (Exception e) {
            log.error("findTopStrategies error", e);
            return java.util.Collections.emptyList();
        }
    }

    public SessionInfo startTopStrategies(String coinName, double tradingSum,
                                           String chartType, String exchangeName,
                                           double commissionRate, int topN,
                                           double buyMin, double buyMax, double buyStep,
                                           double profitMin, double profitMax, double profitStep,
                                           double lossMin, double lossMax, double lossStep) {
        return startTopStrategies(coinName, tradingSum, chartType, exchangeName, commissionRate, topN,
                buyMin, buyMax, buyStep, profitMin, profitMax, profitStep, lossMin, lossMax, lossStep, false);
    }

    public SessionInfo startTopStrategies(String coinName, double tradingSum,
                                           String chartType, String exchangeName,
                                           double commissionRate, int topN,
                                           double buyMin, double buyMax, double buyStep,
                                           double profitMin, double profitMax, double profitStep,
                                           double lossMin, double lossMax, double lossStep,
                                           boolean recapitalize) {
        String id = "optimize_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tradingSum", tradingSum); params.put("chartType", chartType);
        params.put("exchangeName", exchangeName); params.put("commissionRate", commissionRate);
        params.put("topN", topN);
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.BACKTEST, coinName, params, userId);
        String strategyKey = recapitalize ? "reversal_recap" : "reversal";
        String stratLabel = recapitalize ? " [РЕКАП]" : "";
        info.addEvent("START", "Поиск лучших стратегий" + stratLabel + ": " + coinName);
        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                List<Map<String, Object>> top = findTopStrategies(coinName, tradingSum, chartType,
                        exchangeName, commissionRate, topN, info,
                        buyMin, buyMax, buyStep, profitMin, profitMax, profitStep,
                        lossMin, lossMax, lossStep, recapitalize);
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("coinName", coinName); rm.put("chartType", chartType);
                rm.put("exchangeName", exchangeName); rm.put("commissionRate", commissionRate);
                rm.put("topStrategies", top);
                lastBacktestResults.put(strategyKey, rm);
                info.backtestResultData = rm;
                info.status = "DONE"; info.endedAt = System.currentTimeMillis();
                info.backtestProgressCurrent = info.backtestProgressTotal;
                info.addEvent("DONE", "Найдено топ-" + top.size() + " стратегий");
            } catch (Exception e) {
                info.status = "ERROR"; info.endedAt = System.currentTimeMillis();
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("Top strategies error", e);
            } finally { unregisterThread(); }
        });
        t.setDaemon(true); info.thread = t;
        sessions.put(id, info); t.start();
        return info;
    }

    public Map<String, Object> getSessionBacktestResult(String sessionId) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null || info.backtestResultData == null) return null;
        return info.backtestResultData;
    }

    public Map<String, Object> getLastBacktestResult(String strategy) {
        return lastBacktestResults.get(strategy != null ? strategy : "reversal");
    }
    public String getLastBacktestChartPath(String strategy) {
        return lastBacktestChartPaths.get(strategy != null ? strategy : "reversal");
    }
    public Map<String, Object> getLastTop10Result(String strategy) {
        return lastTop10Results.get(strategy != null ? strategy : "reversal");
    }

    // ── ATR+EMA Top Strategies (custom ranges, stores in lastBacktestResults) ─

    public SessionInfo startTopStrategiesAtrEma(String coinName, double tradingSum,
                                                 String chartType, String exchangeName,
                                                 double commissionRate, int topN,
                                                 double buyMin, double buyMax, double buyStep,
                                                 double profitMin, double profitMax, double profitStep,
                                                 double lossMin, double lossMax, double lossStep,
                                                 boolean recapitalize) {
        String id = "optimize_atrema_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tradingSum", tradingSum); params.put("chartType", chartType);
        params.put("exchangeName", exchangeName); params.put("commissionRate", commissionRate);
        params.put("topN", topN); params.put("strategy", "atr_ema");
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.BACKTEST, coinName, params, userId);
        String strategyKey = recapitalize ? "atr_ema_recap" : "atr_ema";
        String stratLabel = recapitalize ? " [ATR+EMA РЕКАП]" : " [ATR+EMA]";
        info.addEvent("START", "Поиск лучших стратегий" + stratLabel + ": " + coinName);
        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                CommissionCalculator commCalc = new CommissionCalculator(CommissionCalculator.Exchange.BINANCE);
                Coin coin = CoinsList.getCoinByName(coinName);
                info.addEvent("INFO", "Загрузка данных...");
                Chart chart = loadChart(chartType, coin, 0, 0, info);
                info.addEvent("INFO", "Данные загружены. Перебор ATR+EMA стратегий...");

                java.util.PriorityQueue<Object[]> topHeap = new java.util.PriorityQueue<>(topN + 1,
                        Comparator.comparingDouble(p -> ((AtrEmaBackTester.BackTestResult) p[0]).getProfitInUsdAfterCommission()));

                int totalIterations = (int) (((buyMax - buyMin) / buyStep + 1)
                        * ((profitMax - profitMin) / profitStep + 1)
                        * ((lossMax - lossMin) / lossStep + 1));
                info.backtestProgressTotal = totalIterations;
                int done = 0;
                for (double bg = buyMin; bg <= buyMax + 0.001; bg += buyStep) {
                    if (Thread.currentThread().isInterrupted()) break;
                    for (double spg = profitMin; spg <= profitMax + 0.001; spg += profitStep) {
                        if (Thread.currentThread().isInterrupted()) break;
                        for (double slg = lossMin; slg <= lossMax + 0.001; slg += lossStep) {
                            if (Thread.currentThread().isInterrupted()) break;
                            try {
                                AtrEmaBackTester tester = new AtrEmaBackTester(
                                        coin, chart, tradingSum, bg, spg, slg, commCalc, recapitalize);
                                AtrEmaBackTester.BackTestResult r = tester.startBackTest();
                                if (r != null) {
                                    topHeap.add(new Object[]{r, tester});
                                    while (topHeap.size() > topN) topHeap.poll();
                                }
                            } catch (Exception e) {
                                log.debug("AtrEma optimize bg={} spg={} slg={}: {}", bg, spg, slg, e.getMessage());
                            }
                            done++;
                            info.backtestProgressCurrent = done;
                        }
                    }
                }

                List<Object[]> sorted = new java.util.ArrayList<>(topHeap);
                sorted.sort(Comparator.comparingDouble(p -> -((AtrEmaBackTester.BackTestResult) p[0]).getProfitInUsdAfterCommission()));
                List<Map<String, Object>> top = new java.util.ArrayList<>();
                for (int i = 0; i < Math.min(topN, sorted.size()); i++) {
                    AtrEmaBackTester.BackTestResult r = (AtrEmaBackTester.BackTestResult) sorted.get(i)[0];
                    AtrEmaBackTester tester = (AtrEmaBackTester) sorted.get(i)[1];
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("rank", i + 1);
                    m.put("buyGap", r.getBuyGap());
                    m.put("sellWithProfitGap", r.getSellWithProfit());
                    m.put("sellWithLossGap", r.getSellWithLossGap());
                    m.put("profitUsd", r.getProfitInUsd());
                    m.put("profitPercent", r.getPercentageProfit());
                    m.put("profitUsdAfterCommission", r.getProfitInUsdAfterCommission());
                    m.put("profitPercentAfterCommission", r.getPercentageProfitAfterCommission());
                    m.put("totalTradeCount", r.getTotalTradeCount());
                    m.put("profitTradeCount", r.getProfitTradeCount());
                    m.put("lossTradeCount", r.getLossTradeCount());
                    m.put("totalCommission", r.getTotalCommission());
                    m.put("strategy", "atr_ema");
                    m.put("recapitalize", recapitalize);
                    if (r.isEarlyTermination()) m.put("earlyTermination", true);
                    // Trade events for chart visualization
                    addTradeEventsToResult(m, tester.getTradeEvents(), tester.getEquityCurve(), tester.getHoldCurve(), tester.getTradeReport());
                    top.add(m);
                }

                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("coinName", coinName); rm.put("chartType", chartType);
                rm.put("exchangeName", exchangeName); rm.put("commissionRate", commissionRate);
                rm.put("topStrategies", top);
                lastBacktestResults.put(strategyKey, rm);
                info.backtestResultData = rm;
                info.status = "DONE"; info.endedAt = System.currentTimeMillis();
                info.backtestProgressCurrent = info.backtestProgressTotal;
                info.addEvent("DONE", "[ATR+EMA] Найдено топ-" + top.size() + " стратегий");
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    info.status = "STOPPED"; info.addEvent("STOP", "Прервано");
                } else {
                    info.status = "ERROR"; info.endedAt = System.currentTimeMillis();
                    info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                    log.error("AtrEma top strategies error", e);
                }
                info.endedAt = System.currentTimeMillis();
            } finally { unregisterThread(); saveSessions(); }
        });
        t.setDaemon(true); info.thread = t;
        sessions.put(id, info); t.start();
        return info;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ATR+EMA Strategy methods
    // ══════════════════════════════════════════════════════════════════════════

    public Object startTesterTradingAtrEma(String coinName, double startAssets, double tradingSum,
            double buyGap, double spg, double slg, int timeout, int chartRefresh, long chatId, boolean recapitalize) {
        if (hasActiveSessionOfType(SessionType.TESTER)) return tooManySessionsError(SessionType.TESTER);
        String id = "tester_atrema_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, timeout, chartRefresh);
        params.put("startAssets", startAssets);
        params.put("strategy", "atr_ema");
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.TESTER, coinName, params, userId);
        info.addEvent("START", "[ATR+EMA] Тестовая сессия: " + coinName);
        info.startUsdtBalance = startAssets;
        sessions.put(id, info);
        launchAtrEmaTesterThread(info, startAssets, tradingSum, buyGap, spg, slg, timeout, null, chatId, recapitalize);
        saveSessions();
        return info;
    }

    public Object startResearchTradingAtrEma(String coinName, double startAssets, double tradingSum,
            double buyGap, double spg, double slg, int timeout, int chartRefresh, long chatId, boolean recapitalize) {
        if (hasActiveSessionOfType(SessionType.RESEARCH)) return tooManySessionsError(SessionType.RESEARCH);
        String id = "research_atrema_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, timeout, chartRefresh);
        params.put("startAssets", startAssets);
        params.put("strategy", "atr_ema");
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.RESEARCH, coinName, params, userId);
        info.addEvent("START", "[ATR+EMA] Исследование: " + coinName);
        info.startUsdtBalance = startAssets;
        sessions.put(id, info);
        launchAtrEmaTesterThread(info, startAssets, tradingSum, buyGap, spg, slg, timeout, null, chatId, recapitalize);
        saveSessions();
        return info;
    }

    public Object startBinanceTradingAtrEma(String coinName, double tradingSum, double buyGap,
            double spg, double slg, int timeout, int chartRefresh,
            long chatId, UserProfileManager.UserProfile profile, boolean recapitalize) {
        if (hasActiveSessionOfType(SessionType.BINANCE_REAL)) return tooManySessionsError(SessionType.BINANCE_REAL);
        String id = "binance_atrema_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, timeout, chartRefresh);
        params.put("strategy", "atr_ema");
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_REAL, coinName, params, userId);
        info.addEvent("START", "[ATR+EMA] Binance REAL: " + coinName);
        sessions.put(id, info);
        launchAtrEmaBinanceThread(info, tradingSum, buyGap, spg, slg, timeout, false, null, chatId, profile, recapitalize);
        saveSessions();
        return info;
    }

    public Object startBinanceTestTradingAtrEma(String coinName, double tradingSum, double buyGap,
            double spg, double slg, int timeout, int chartRefresh,
            long chatId, UserProfileManager.UserProfile profile, boolean recapitalize) {
        if (hasActiveSessionOfType(SessionType.BINANCE_TEST)) return tooManySessionsError(SessionType.BINANCE_TEST);
        String id = "binance_test_atrema_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, timeout, chartRefresh);
        params.put("strategy", "atr_ema");
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_TEST, coinName, params, userId);
        info.addEvent("START", "[ATR+EMA] Binance TEST: " + coinName);
        sessions.put(id, info);
        launchAtrEmaBinanceThread(info, tradingSum, buyGap, spg, slg, timeout, true, null, chatId, profile, recapitalize);
        saveSessions();
        return info;
    }

    private void launchAtrEmaTesterThread(SessionInfo info, double startAssets, double tradingSum,
            double buyGap, double spg, double slg, int timeout,
            TradingState savedState, long chatId, boolean recapitalize) {
        Thread t = new Thread(() -> {
            registerThread(info.id);
            try {
                Coin coin = CoinsList.getCoinByName(info.coinName);
                Map<Coin, Double> assets = new HashMap<>();
                if (savedState != null && savedState.getWalletAssets() != null && !savedState.getWalletAssets().isEmpty()) {
                    double usdtAmt = savedState.getWalletAssets().getOrDefault("USDT", startAssets);
                    double coinAmt = savedState.getWalletAssets().getOrDefault(coin.getSymbol().toUpperCase(), 0.0);
                    if (usdtAmt == 0 && coinAmt == 0) { usdtAmt = info.usdtBalance > 0 ? info.usdtBalance : startAssets; coinAmt = info.coinBalance; }
                    assets.put(Account.USD_TOKENS.USDT.getCoin(), usdtAmt);
                    assets.put(coin, coinAmt);
                } else if (savedState == null && info.coinBalance == 0 && info.usdtBalance == 0) {
                    assets.put(Account.USD_TOKENS.USDT.getCoin(), startAssets);
                    assets.put(coin, 0.0);
                } else {
                    assets.put(Account.USD_TOKENS.USDT.getCoin(), info.usdtBalance > 0 ? info.usdtBalance : startAssets);
                    assets.put(coin, info.coinBalance);
                }
                Account account = AccountBuilder.createNewTester(assets);
                boolean resume = savedState != null || info.events.stream()
                    .anyMatch(e -> "START".equals(e.type) && e.message != null && e.message.contains("возобновлена"));
                new AtrEmaTrader(account, coin, tradingSum, buyGap, spg, slg,
                        timeout, chatId, savedState, info.id, resume, userId, recapitalize).startTrading();
                setFinalStatus(info);
            } catch (Exception e) {
                info.status = "ERROR"; info.endedAt = System.currentTimeMillis();
                info.stoppedUnexpectedly = true;
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("AtrEma tester error for user {}", userId, e);
            } finally {
                unregisterThread();
                ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart.releaseForCurrentThread();
                ImageAndMessageSender.clearChatId();
                saveSessions();
            }
        });
        t.setDaemon(true); info.thread = t; info.status = "RUNNING"; t.start();
    }

    private void launchAtrEmaBinanceThread(SessionInfo info, double tradingSum,
            double buyGap, double spg, double slg, int timeout, boolean testnet,
            TradingState savedState, long chatId, UserProfileManager.UserProfile profile, boolean recapitalize) {
        Thread t = new Thread(() -> {
            registerThread(info.id);
            try {
                char[] apiKey = resolveApiKey(profile, testnet);
                char[] privKey = resolvePrivKeyPath(profile, testnet);
                if (apiKey.length == 0) throw new IllegalStateException("Binance API ключ не настроен.");
                if (privKey.length == 0) throw new IllegalStateException("Ed25519 .pem файл не загружен.");
                Coin coin = CoinsList.getCoinByName(info.coinName);
                Account account = AccountBuilder.createNewBinance(apiKey, privKey,
                        testnet ? AccountBuilder.BINANCE_BASE_URL.TESTNET : AccountBuilder.BINANCE_BASE_URL.MAINNET);
                boolean resume = savedState != null || info.events.stream()
                    .anyMatch(e -> "START".equals(e.type) && e.message != null && e.message.contains("возобновлена"));
                new AtrEmaTrader(account, coin, tradingSum, buyGap, spg, slg,
                        timeout, chatId, savedState, info.id, resume, userId, recapitalize).startTrading();
                setFinalStatus(info);
            } catch (Exception e) {
                info.status = "ERROR"; info.endedAt = System.currentTimeMillis();
                info.stoppedUnexpectedly = true;
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("AtrEma Binance error (testnet={}) for user {}", testnet, userId, e);
            } finally {
                unregisterThread();
                ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart.releaseForCurrentThread();
                ImageAndMessageSender.clearChatId();
                saveSessions();
            }
        });
        t.setDaemon(true); info.thread = t; info.status = "RUNNING"; t.start();
    }

    // ── ATR+EMA Backtest ─────────────────────────────────────────────────────

    public SessionInfo startBacktestAtrEma(String coinName, double tradingSum, double buyGap,
                                           double spg, double slg, String chartType,
                                           String exchangeName, double commissionRate,
                                           long customFrom, long customTo, boolean recapitalize) {
        String id = "backtest_atrema_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, 30, 60);
        params.put("chartType", chartType);
        params.put("strategy", "atr_ema");
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.BACKTEST, coinName, params, userId);
        String strategyKey = recapitalize ? "atr_ema_recap" : "atr_ema";
        lastBacktestResults.remove(strategyKey);
        lastBacktestChartPaths.remove(strategyKey);
        info.addEvent("START", "[ATR+EMA] Бэктест: " + coinName);
        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                Coin coin = CoinsList.getCoinByName(coinName);
                Chart chart = loadChart(chartType, coin, customFrom, customTo, info);
                info.addEvent("INFO", "Данные загружены (" + chart.getPrices().size() + " точек), запуск ATR+EMA бэктеста...");
                AtrEmaBackTester tester = new AtrEmaBackTester(coin, chart, tradingSum, buyGap, spg, slg,
                        new CommissionCalculator(CommissionCalculator.Exchange.BINANCE), recapitalize);
                info.backtestProgressTotal = chart.getPrices().size();

                Thread progressUpdater = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted() && "RUNNING".equals(info.status)) {
                        info.backtestProgressCurrent = tester.getProgressCurrent();
                        try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                    }
                });
                progressUpdater.setDaemon(true);
                progressUpdater.start();

                AtrEmaBackTester.BackTestResult result = tester.startBackTest();
                progressUpdater.interrupt();

                String chartPath = tester.generateChartImage();
                lastBacktestChartPaths.put(strategyKey, chartPath);

                if (result == null) {
                    info.status = "ERROR"; info.endedAt = System.currentTimeMillis();
                    info.addEvent("ERROR", "Бэктест завершился без результата");
                    return;
                }

                Map<String, Object> rm = buildBacktestResultMap(result, coinName, tradingSum, chartType, chartPath, recapitalize);
                addTradeEventsToResult(rm, tester.getTradeEvents(), tester.getEquityCurve(), tester.getHoldCurve(), tester.getTradeReport());
                lastBacktestResults.put(strategyKey, rm);
                info.backtestResultData = rm;
                info.status = "DONE"; info.endedAt = System.currentTimeMillis();
                info.backtestProgressCurrent = info.backtestProgressTotal;
                info.addEvent("DONE", String.format(
                    "[ATR+EMA] Готово! Прибыль: %.2f USD (%.2f%%) | С комиссией: %.2f USD | Сделок: %d",
                    result.getProfitInUsd(), result.getPercentageProfit(),
                    result.getProfitInUsdAfterCommission(), result.getTotalTradeCount()));
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    info.status = "STOPPED"; info.addEvent("STOP", "Прервано");
                } else {
                    info.status = "ERROR"; info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                    log.error("AtrEma backtest error for user {}", userId, e);
                }
                info.endedAt = System.currentTimeMillis();
            } finally { unregisterThread(); saveSessions(); }
        });
        t.setDaemon(true); info.thread = t;
        sessions.put(id, info); t.start();
        return info;
    }

    // ── ATR+EMA Top-10 search ────────────────────────────────────────────────

    public SessionInfo startTop10SearchAtrEma(String coinName, double tradingSum, String chartType, String exchange, boolean recapitalize) {
        String id = "top10_atrema_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tradingSum", tradingSum);
        params.put("chartType", chartType);
        params.put("exchange", exchange);
        params.put("strategy", "atr_ema");
        if (recapitalize) params.put("recapitalize", true);
        SessionInfo info = new SessionInfo(id, SessionType.BACKTEST, coinName, params, userId);
        String strategyKey = recapitalize ? "atr_ema_recap" : "atr_ema";
        lastTop10Results.remove(strategyKey);
        info.addEvent("START", "[ATR+EMA] Поиск ТОП-10: " + coinName);
        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                CommissionCalculator commCalc = new CommissionCalculator(CommissionCalculator.Exchange.BINANCE);
                Coin coin = CoinsList.getCoinByName(coinName);
                info.addEvent("INFO", "Загрузка данных...");
                Chart chart;
                switch (chartType) {
                    case "5yr": chart = Chart.getBinanceChart(coin, 5); break;
                    case "3yr": chart = Chart.getBinanceChart(coin, 3); break;
                    case "yearly": chart = Chart.getBinanceChart(coin, 1); break;
                    case "monthly": chart = Chart.getMonthlyChart_1hourInterval(coin, YearMonth.now().minusMonths(1)); break;
                    default: chart = Chart.get1DayUntilNowChart_5MinuteInterval(coin); break;
                }
                info.addEvent("INFO", "Данные загружены. Перебор ATR+EMA стратегий...");

                java.util.PriorityQueue<Object[]> topHeap = new java.util.PriorityQueue<>(11,
                        Comparator.comparingDouble(p -> ((AtrEmaBackTester.BackTestResult) p[0]).getProfitInUsdAfterCommission()));
                double step = 0.5;
                double startBG = 0.5, maxBG = 5;
                double startSPG = 0.5, maxSPG = 5;
                double startSLG = 1, maxSLG = 5;
                int totalIterations = (int) (((maxSLG - startSLG + step) / step)
                        * ((maxSPG - startSPG + step) / step)
                        * ((maxBG - startBG + step) / step));
                int completed = 0;
                int lastReportedPercent = 0;
                info.backtestProgressTotal = totalIterations;

                for (double bg = startBG; bg <= maxBG; bg += step) {
                    if (Thread.currentThread().isInterrupted()) break;
                    for (double spg = startSPG; spg <= maxSPG; spg += step) {
                        if (Thread.currentThread().isInterrupted()) break;
                        for (double slg = startSLG; slg <= maxSLG; slg += step) {
                            if (Thread.currentThread().isInterrupted()) break;
                            try {
                                AtrEmaBackTester tester = new AtrEmaBackTester(
                                        coin, chart, tradingSum, bg, spg, slg, commCalc, recapitalize);
                                AtrEmaBackTester.BackTestResult r = tester.startBackTest();
                                if (r != null) {
                                    topHeap.add(new Object[]{r, tester});
                                    while (topHeap.size() > 10) topHeap.poll();
                                }
                            } catch (Exception e) {
                                log.debug("AtrEma backtest failed bg={} spg={} slg={}: {}", bg, spg, slg, e.getMessage());
                            }
                            completed++;
                            info.backtestProgressCurrent = completed;
                            int pct = (int) ((double) completed / totalIterations * 100);
                            if (pct > lastReportedPercent && pct % 5 == 0) {
                                lastReportedPercent = pct;
                                info.addEvent("INFO", "Прогресс: " + pct + "% (" + completed + "/" + totalIterations + ")");
                                saveSessions();
                            }
                        }
                    }
                }

                List<Object[]> sorted = new java.util.ArrayList<>(topHeap);
                sorted.sort(Comparator.comparingDouble(p -> -((AtrEmaBackTester.BackTestResult) p[0]).getProfitInUsdAfterCommission()));
                List<Map<String, Object>> top10 = new ArrayList<>();
                for (int i = 0; i < Math.min(10, sorted.size()); i++) {
                    AtrEmaBackTester.BackTestResult r = (AtrEmaBackTester.BackTestResult) sorted.get(i)[0];
                    AtrEmaBackTester tester = (AtrEmaBackTester) sorted.get(i)[1];
                    Map<String, Object> rm = new LinkedHashMap<>();
                    rm.put("rank", i + 1);
                    rm.put("buyGap", r.getBuyGap());
                    rm.put("sellWithProfitGap", r.getSellWithProfit());
                    rm.put("sellWithLossGap", r.getSellWithLossGap());
                    rm.put("profitUsd", r.getProfitInUsd());
                    rm.put("profitPercent", r.getPercentageProfit());
                    rm.put("profitUsdAfterCommission", r.getProfitInUsdAfterCommission());
                    rm.put("profitPercentAfterCommission", r.getPercentageProfitAfterCommission());
                    rm.put("totalTradeCount", r.getTotalTradeCount());
                    rm.put("profitTradeCount", r.getProfitTradeCount());
                    rm.put("lossTradeCount", r.getLossTradeCount());
                    rm.put("totalCommission", r.getTotalCommission());
                    rm.put("strategy", "atr_ema");
                    rm.put("recapitalize", recapitalize);
                    if (r.isEarlyTermination()) rm.put("earlyTermination", true);
                    addTradeEventsToResult(rm, tester.getTradeEvents(), tester.getEquityCurve(), tester.getHoldCurve(), tester.getTradeReport());
                    top10.add(rm);
                }
                Map<String, Object> resultMap = new LinkedHashMap<>();
                resultMap.put("coinName", coinName);
                resultMap.put("chartType", chartType);
                resultMap.put("tradingSum", tradingSum);
                resultMap.put("strategy", "atr_ema");
                resultMap.put("exchange", commCalc.getExchange().getDisplayName());
                resultMap.put("totalIterations", totalIterations);
                resultMap.put("strategies", top10);
                lastTop10Results.put(strategyKey, resultMap);
                info.backtestResultData = resultMap;
                info.status = "DONE"; info.endedAt = System.currentTimeMillis();
                String best = top10.isEmpty() ? "нет результатов"
                    : String.format("Лучшая: $%.2f (с комиссией: $%.2f)",
                        top10.get(0).get("profitUsd"), top10.get(0).get("profitAfterCommission"));
                info.addEvent("DONE", "[ATR+EMA] ТОП-10 готов! " + best);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    info.status = "STOPPED"; info.addEvent("STOP", "Прервано");
                } else {
                    info.status = "ERROR"; info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                    log.error("AtrEma Top10 error for user {}", userId, e);
                }
                info.endedAt = System.currentTimeMillis();
            } finally { unregisterThread(); saveSessions(); }
        });
        t.setDaemon(true); info.thread = t;
        sessions.put(id, info); t.start();
        return info;
    }

    // ── Helper: load chart for backtest ──────────────────────────────────────

    private Chart loadChart(String chartType, Coin coin, long customFrom, long customTo, SessionInfo info) throws Exception {
        switch (chartType) {
            case "5yr": info.addEvent("INFO", "Загрузка 5-летних данных..."); return Chart.getBinanceChart(coin, 5);
            case "3yr": info.addEvent("INFO", "Загрузка 3-летних данных..."); return Chart.getBinanceChart(coin, 3);
            case "yearly": info.addEvent("INFO", "Загрузка годовых данных..."); return Chart.getBinanceChart(coin, 1);
            case "monthly": return Chart.getMonthlyChart_1hourInterval(coin, YearMonth.now().minusMonths(1));
            case "custom":
                long cfrom = customFrom > 0 ? customFrom : System.currentTimeMillis() - 365L * 24 * 3600 * 1000;
                long cto = customTo > 0 ? customTo : System.currentTimeMillis();
                info.addEvent("INFO", "Загрузка данных за произвольный период...");
                return Chart.getBinanceChart(coin, cfrom, cto);
            default: return Chart.get1DayUntilNowChart_5MinuteInterval(coin);
        }
    }

    private Map<String, Object> buildBacktestResultMap(AtrEmaBackTester.BackTestResult result,
            String coinName, double tradingSum, String chartType, String chartPath, boolean recapitalize) {
        CommissionCalculator commCalc = new CommissionCalculator(CommissionCalculator.Exchange.BINANCE);
        Map<String, Object> rm = new LinkedHashMap<>();
        rm.put("coinName", coinName); rm.put("strategy", "atr_ema");
        rm.put("buyGap", result.getBuyGap());
        rm.put("sellWithProfitGap", result.getSellWithProfit());
        rm.put("sellWithLossGap", result.getSellWithLossGap());
        rm.put("profitUsd", result.getProfitInUsd());
        rm.put("profitPercent", result.getPercentageProfit());
        rm.put("profitUsdAfterCommission", result.getProfitInUsdAfterCommission());
        rm.put("profitPercentAfterCommission", result.getPercentageProfitAfterCommission());
        rm.put("tradingSum", tradingSum); rm.put("chartType", chartType);
        rm.put("profitTradeCount", result.getProfitTradeCount());
        rm.put("lossTradeCount", result.getLossTradeCount());
        rm.put("totalTradeCount", result.getTotalTradeCount());
        rm.put("totalProfit", result.getTotalProfit());
        rm.put("totalLoss", result.getTotalLoss());
        rm.put("totalCommission", result.getTotalCommission());
        rm.put("exchangeName", result.getExchangeName());
        rm.put("commissionRate", result.getCommissionRate());
        rm.put("exchange", commCalc.getExchange().getDisplayName());
        rm.put("feePercent", commCalc.getFeePercent());
        rm.put("totalProfitAmount", result.getTotalProfitAmount());
        rm.put("totalLossAmount", result.getTotalLossAmount());
        rm.put("profitAfterCommission", result.getProfitAfterCommission());
        rm.put("winCount", result.getWinCount());
        rm.put("lossCount", result.getLossCount());
        rm.put("totalTrades", result.getTotalTrades());
        rm.put("chartImageAvailable", chartPath != null);
        rm.put("recapitalize", recapitalize);
        rm.put("earlyTermination", result.isEarlyTermination());
        return rm;
    }

    private void addTradeEventsToResult(Map<String, Object> rm, List<double[]> tradeEvents,
            List<double[]> equityCurve, List<double[]> holdCurve, List<Map<String, Object>> tradeReport) {
        List<Map<String, Object>> evList = new java.util.ArrayList<>();
        for (double[] ev : tradeEvents) {
            Map<String, Object> evm = new LinkedHashMap<>();
            evm.put("timestamp", (long) ev[0]); evm.put("price", ev[1]); evm.put("eventType", (int) ev[2]);
            evList.add(evm);
        }
        rm.put("tradeEvents", evList);
        List<Map<String, Object>> eqList = new java.util.ArrayList<>();
        for (double[] eq : equityCurve) {
            Map<String, Object> eqm = new LinkedHashMap<>();
            eqm.put("timestamp", (long) eq[0]); eqm.put("equity", eq[1]);
            eqList.add(eqm);
        }
        rm.put("equityCurve", eqList);
        List<Map<String, Object>> holdList = new java.util.ArrayList<>();
        for (double[] h : holdCurve) {
            Map<String, Object> hm = new LinkedHashMap<>();
            hm.put("timestamp", (long) h[0]); hm.put("equity", h[1]);
            holdList.add(hm);
        }
        rm.put("holdCurve", holdList);
        rm.put("tradeReport", tradeReport);
    }
}
