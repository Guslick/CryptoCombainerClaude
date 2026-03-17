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
        updateLiveState(coinBalance, usdtBalance, isTrading, currentPrice,
            maxPrice, buyTargetPrice, boughtAtPrice, sellProfitPrice, sellLossPrice,
            0, 0, 0, 0, 0, 0, false);
    }

    public static void updateLiveState(double coinBalance, double usdtBalance,
                                       boolean isTrading, double currentPrice,
                                       Double maxPrice, Double buyTargetPrice, Double boughtAtPrice,
                                       Double sellProfitPrice, Double sellLossPrice,
                                       int winCount, int lossCount,
                                       double totalProfit, double totalLoss,
                                       double totalCommission, double startBalance,
                                       boolean isTesterAccount) {
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
                info.winCount        = winCount;
                info.lossCount       = lossCount;
                info.totalProfit     = totalProfit;
                info.totalLoss       = totalLoss;
                info.totalCommission = totalCommission;
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
    private final AtomicReference<Map<String, Object>> lastBacktestResult = new AtomicReference<>(null);
    private final AtomicReference<String> lastBacktestChartPath = new AtomicReference<>(null);
    private final AtomicReference<Map<String, Object>> lastTop10Result = new AtomicReference<>(null);

    private TradingSessionManager(long userId) {
        this.userId = userId;
        String dir = userId == 0 ? "trading_states/global" : "trading_states/user_" + userId;
        this.sessionStoreFile = dir + "/sessions.json";
        try { Files.createDirectories(Paths.get(dir)); } catch (IOException ignored) {}
    }

    // ── Session guards ────────────────────────────────────────────────────────

    /** Only this user's sessions count toward the limit */
    public boolean hasActiveSession() {
        return sessions.values().stream().anyMatch(s -> "RUNNING".equals(s.status));
    }

    private Map<String, Object> tooManySessionsError() {
        return Map.of("error", "Уже есть активная сессия. Остановите её перед запуском новой.");
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

    @SuppressWarnings("unchecked")
    public void loadSessions() {
        File f = new File(sessionStoreFile);
        if (!f.exists()) return;
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
                sessions.put(info.id, info);
            }
            log.info("Loaded {} sessions for user {}", sessions.size(), userId);
        } catch (Exception e) {
            log.warn("Failed to load sessions for user {}: {}", userId, e.getMessage());
        }
    }

    public void autoResumeSessions(long chatId) {
        sessions.values().stream()
            .filter(s -> s.stoppedUnexpectedly && s.type != SessionType.BACKTEST)
            .forEach(s -> {
                log.info("Auto-resuming session {} for user {}", s.id, userId);
                s.addEvent("INFO", "Авто-возобновление после сбоя JVM");
                resumeSession(s.id, chatId, null);
            });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private StateManager stateManager() {
        return new StateManager(userId);
    }

    private TradingState loadTradingState(String sessionId) {
        StateManager sm = stateManager();
        if (!sm.hasState(sessionId)) return null;
        TradingState state = sm.loadState(sessionId);
        if (state != null)
            log.info("Loaded TradingState for session {}: isTrading={}", sessionId, state.isTrading());
        return state;
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
        return p;
    }

    // ── Start Tester ──────────────────────────────────────────────────────────

    public Object startTesterTrading(String coinName, double startAssets, double tradingSum,
            double buyGap, double spg, double slg, int timeout, int chartRefresh, long chatId) {
        if (hasActiveSession()) return tooManySessionsError();
        String id = "tester_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, timeout, chartRefresh);
        params.put("startAssets", startAssets);
        SessionInfo info = new SessionInfo(id, SessionType.TESTER, coinName, params, userId);
        info.addEvent("START", "Сессия запущена: " + coinName);
        sessions.put(id, info);
        launchTesterThread(info, startAssets, tradingSum, buyGap, spg, slg, timeout, null, chatId);
        saveSessions();
        return info;
    }

    private void launchTesterThread(SessionInfo info, double startAssets, double tradingSum,
            double buyGap, double spg, double slg, int timeout,
            TradingState savedState, long chatId) {
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
                        timeout, chatId, savedState, info.id, resume).startTrading();
                setFinalStatus(info);
            } catch (Exception e) {
                info.status = "ERROR"; info.endedAt = System.currentTimeMillis();
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("Tester trading error for user {}", userId, e);
            } finally { unregisterThread(); saveSessions(); }
        });
        t.setDaemon(true); info.thread = t; info.status = "RUNNING"; t.start();
    }

    // ── Start Binance ─────────────────────────────────────────────────────────

    public Object startBinanceTrading(String coinName, double tradingSum, double buyGap,
            double spg, double slg, int timeout, int chartRefresh,
            long chatId, UserProfileManager.UserProfile profile) {
        if (hasActiveSession()) return tooManySessionsError();
        String id = "binance_" + System.currentTimeMillis();
        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_REAL, coinName,
                buildParams(tradingSum, buyGap, spg, slg, timeout, chartRefresh), userId);
        info.addEvent("START", "Binance REAL сессия запущена: " + coinName);
        sessions.put(id, info);
        launchBinanceThread(info, tradingSum, buyGap, spg, slg, timeout, false, null, chatId, profile);
        saveSessions();
        return info;
    }

    public Object startBinanceTestTrading(String coinName, double tradingSum, double buyGap,
            double spg, double slg, int timeout, int chartRefresh,
            long chatId, UserProfileManager.UserProfile profile) {
        if (hasActiveSession()) return tooManySessionsError();
        String id = "binance_test_" + System.currentTimeMillis();
        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_TEST, coinName,
                buildParams(tradingSum, buyGap, spg, slg, timeout, chartRefresh), userId);
        info.addEvent("START", "Binance TEST сессия запущена: " + coinName);
        sessions.put(id, info);
        launchBinanceThread(info, tradingSum, buyGap, spg, slg, timeout, true, null, chatId, profile);
        saveSessions();
        return info;
    }

    private void launchBinanceThread(SessionInfo info, double tradingSum,
            double buyGap, double spg, double slg, int timeout, boolean testnet,
            TradingState savedState, long chatId, UserProfileManager.UserProfile profile) {
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
                        timeout, chatId, savedState, info.id, resume).startTrading();
                setFinalStatus(info);
            } catch (Exception e) {
                info.status = "ERROR"; info.endedAt = System.currentTimeMillis();
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("Binance trading error (testnet={}) for user {}", testnet, userId, e);
            } finally { unregisterThread(); saveSessions(); }
        });
        t.setDaemon(true); info.thread = t; info.status = "RUNNING"; t.start();
    }

    // ── Resume ────────────────────────────────────────────────────────────────

    public Map<String, Object> resumeSession(String sessionId, long chatId,
                                              UserProfileManager.UserProfile profile) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return Map.of("error", "Сессия не найдена");
        if ("RUNNING".equals(info.status)) return Map.of("error", "Сессия уже запущена");
        if (hasActiveSession()) return Map.of("error", "Уже есть активная сессия");
        if (info.type == SessionType.BACKTEST) return Map.of("error", "Бэктест нельзя возобновить");

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

        switch (info.type) {
            case TESTER:
                launchTesterThread(info, toDouble(p.getOrDefault("startAssets", 150.0)),
                        tradingSum, buyGap, spg, slg, timeout, savedState, chatId);
                break;
            case BINANCE_REAL:
                launchBinanceThread(info, tradingSum, buyGap, spg, slg, timeout,
                        false, savedState, chatId, profile);
                break;
            case BINANCE_TEST:
                launchBinanceThread(info, tradingSum, buyGap, spg, slg, timeout,
                        true, savedState, chatId, profile);
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
        if (info.thread != null && info.thread.isAlive()) info.thread.interrupt();
        info.status = "STOPPED"; info.stoppedUnexpectedly = false;
        info.endedAt = System.currentTimeMillis();
        info.addEvent("STOP", "Остановлено пользователем");
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
        sessions.values().forEach(s -> result.add(s.toMap(false)));
        result.sort((a, b) -> Long.compare((Long) b.get("startedAt"), (Long) a.get("startedAt")));
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
        return startBacktest(coinName, tradingSum, buyGap, spg, slg, chartType, "binance");
    }

    public SessionInfo startBacktest(String coinName, double tradingSum, double buyGap,
                                     double spg, double slg, String chartType, String exchange) {
        String id = "backtest_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, spg, slg, 30, 60);
        params.put("chartType", chartType);
        params.put("exchange", exchange);
        SessionInfo info = new SessionInfo(id, SessionType.BACKTEST, coinName, params, userId);
        lastBacktestResult.set(null);
        lastBacktestChartPath.set(null);
        info.addEvent("START", "Бэктест запущен: " + coinName);
        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                CommissionCalculator commCalc = new CommissionCalculator(CommissionCalculator.Exchange.BINANCE);
                Coin coin = CoinsList.getCoinByName(coinName);
                info.addEvent("INFO", "Загрузка данных графика...");
                Chart chart;
                switch (chartType) {
                    case "yearly":
                        chart = Chart.getYearlyChart_1hourInterval(coin);
                        break;
                    case "monthly":
                        chart = Chart.getMonthlyChart_1hourInterval(coin, YearMonth.now().minusMonths(1));
                        break;
                    default:
                        chart = Chart.get1DayUntilNowChart_5MinuteInterval(coin);
                        break;
                }
                info.addEvent("INFO", "Данные загружены (" + chart.getPrices().size() + " точек). Запуск бэктеста...");
                ReversalPointStrategyBackTester tester = new ReversalPointStrategyBackTester(
                        coin, chart, tradingSum, buyGap, spg, slg, commCalc);
                ReversalPointStrategyBackTester.BackTestResult result = tester.startBackTest();
                // Generate chart image
                String chartPath = tester.generateChartImage();
                lastBacktestChartPath.set(chartPath);
                Map<String, Object> rm = new LinkedHashMap<>();
                rm.put("coinName", coinName); rm.put("buyGap", result.getBuyGap());
                rm.put("sellWithProfitGap", result.getSellWithProfit());
                rm.put("sellWithLossGap", result.getSellWithLossGap());
                rm.put("profitUsd", result.getProfitInUsd());
                rm.put("profitPercent", result.getPercentageProfit());
                rm.put("tradingSum", tradingSum); rm.put("chartType", chartType);
                rm.put("totalCommission", result.getTotalCommission());
                rm.put("profitAfterCommission", result.getProfitAfterCommission());
                rm.put("winCount", result.getWinCount());
                rm.put("lossCount", result.getLossCount());
                rm.put("totalTrades", result.getTotalTrades());
                rm.put("totalProfitAmount", result.getTotalProfitAmount());
                rm.put("totalLossAmount", result.getTotalLossAmount());
                rm.put("exchange", commCalc.getExchange().getDisplayName());
                rm.put("feePercent", commCalc.getFeePercent());
                rm.put("chartImageAvailable", chartPath != null);
                lastBacktestResult.set(rm);
                info.status = "DONE"; info.endedAt = System.currentTimeMillis();
                // Add detailed event to session history
                String detailedMsg = String.format(
                    "Готово! Прибыль: $%.2f (%.2f%%)\n" +
                    "Сделок: %d (✅%d / ❌%d)\n" +
                    "Комиссия %s: $%.4f\n" +
                    "Прибыль с комиссией: $%.2f",
                    result.getProfitInUsd(), result.getPercentageProfit(),
                    result.getTotalTrades(), result.getWinCount(), result.getLossCount(),
                    commCalc.getExchange().getDisplayName(), result.getTotalCommission(),
                    result.getProfitAfterCommission()
                );
                info.addEvent("DONE", detailedMsg);
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
        String id = "top10_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tradingSum", tradingSum);
        params.put("chartType", chartType);
        params.put("exchange", exchange);
        SessionInfo info = new SessionInfo(id, SessionType.BACKTEST, coinName, params, userId);
        lastTop10Result.set(null);
        info.addEvent("START", "Поиск ТОП-10 стратегий: " + coinName);
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
                                        coin, chart, tradingSum, bg, spg, slg, commCalc);
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
                lastTop10Result.set(resultMap);

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

    public Map<String, Object> getLastBacktestResult() { return lastBacktestResult.get(); }
    public String getLastBacktestChartPath() { return lastBacktestChartPath.get(); }
    public Map<String, Object> getLastTop10Result() { return lastTop10Result.get(); }
}
