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
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointStrategyBackTester;
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointsStrategyTrader;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Manages MiniApp trading sessions with full state persistence and auto-resume.
 */
public class TradingSessionManager {
    private static final Logger log = LoggerFactory.getLogger(TradingSessionManager.class);
    private static TradingSessionManager instance;
    private static final String SESSION_STORE_FILE = "trading_states/miniapp_sessions.json";
    private static final ObjectMapper mapper = new ObjectMapper();

    public enum SessionType { TESTER, BINANCE_TEST, BINANCE_REAL, RESEARCH, BACKTEST }

    // ---- SessionEvent ----
    public static class SessionEvent {
        public long timestamp;
        public String type;
        public String message;

        public SessionEvent() {}
        SessionEvent(String type, String message) {
            this.timestamp = System.currentTimeMillis();
            this.type = type;
            this.message = message;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timestamp", timestamp);
            m.put("type", type);
            m.put("message", message != null ? message : "");
            return m;
        }
    }

    // ---- SessionInfo ----
    public static class SessionInfo {
        public String id;
        public SessionType type;
        public String coinName;
        public Map<String, Object> params;
        public volatile String status;
        public volatile String lastMessage;
        public volatile long startedAt;
        public volatile long endedAt = 0;
        // Live state — updated every tick by updateLiveState()
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
        public volatile int totalTrades = 0;
        public volatile int profitableTrades = 0;
        public volatile int losingTrades = 0;
        public volatile double grossProfitUsd = 0;
        public volatile double grossLossUsd = 0;
        public volatile double sessionPnlUsd = 0;
        public volatile double estimatedCommissionUsd = 0;
        public volatile double startCoinBalance = 0;
        public volatile double endCoinBalance = 0;
        public volatile int backtestProgress = 0;
        public transient Thread thread;
        public final List<SessionEvent> events = new CopyOnWriteArrayList<>();

        public SessionInfo() {}
        SessionInfo(String id, SessionType type, String coinName, Map<String, Object> params) {
            this.id = id;
            this.type = type;
            this.coinName = coinName;
            this.params = new HashMap<>(params);
            this.status = "RUNNING";
            this.startedAt = System.currentTimeMillis();
            this.lastMessage = "Запущено";
        }

        public Map<String, Object> toMap() { return toMap(false); }

        public Map<String, Object> toMap(boolean includeEvents) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("type", type.name());
            m.put("coinName", coinName);
            m.put("status", status);
            m.put("lastMessage", lastMessage);
            m.put("startedAt", startedAt);
            m.put("endedAt", endedAt);
            m.put("params", params);
            m.put("coinBalance", coinBalance);
            m.put("usdtBalance", usdtBalance);
            m.put("isTrading", isTrading);
            m.put("currentPrice", currentPrice);
            m.put("maxPrice", maxPrice);
            m.put("buyTargetPrice", buyTargetPrice);
            m.put("boughtAtPrice", boughtAtPrice);
            m.put("sellProfitPrice", sellProfitPrice);
            m.put("sellLossPrice", sellLossPrice);
            m.put("stoppedUnexpectedly", stoppedUnexpectedly);
            m.put("totalTrades", totalTrades);
            m.put("profitableTrades", profitableTrades);
            m.put("losingTrades", losingTrades);
            m.put("grossProfitUsd", grossProfitUsd);
            m.put("grossLossUsd", grossLossUsd);
            m.put("sessionPnlUsd", sessionPnlUsd);
            m.put("estimatedCommissionUsd", estimatedCommissionUsd);
            m.put("startCoinBalance", startCoinBalance);
            m.put("endCoinBalance", endCoinBalance);
            m.put("backtestProgress", backtestProgress);
            if (includeEvents) {
                m.put("events", events.stream().map(SessionEvent::toMap).collect(Collectors.toList()));
            }
            return m;
        }

        public void addEvent(String type, String message) {
            events.add(new SessionEvent(type, message));
            while (events.size() > 1000) events.remove(0);
            lastMessage = message;
        }
    }

    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final AtomicReference<Map<String, Object>> lastBacktestResult = new AtomicReference<>(null);
    private final ConcurrentHashMap<Long, String> threadToSession = new ConcurrentHashMap<>();

    private TradingSessionManager() {}

    public static synchronized TradingSessionManager getInstance() {
        if (instance == null) instance = new TradingSessionManager();
        return instance;
    }

    // ---- Live state update from trading thread ----

    /**
     * Called by ReversalPointsStrategyTrader on every tick.
     * All nullable fields are represented as Double (null = not applicable).
     */
    public static void updateLiveState(double coinBalance, double usdtBalance,
                                       boolean isTrading,
                                       double currentPrice,
                                       Double maxPrice,
                                       Double buyTargetPrice,
                                       Double boughtAtPrice,
                                       Double sellProfitPrice,
                                       Double sellLossPrice,
                                       int totalTrades,
                                       int profitableTrades,
                                       int losingTrades,
                                       double grossProfitUsd,
                                       double grossLossUsd,
                                       double sessionPnlUsd,
                                       double estimatedCommissionUsd,
                                       double startCoinBalance,
                                       double endCoinBalance) {
        TradingSessionManager mgr = instance;
        if (mgr == null) return;
        String sid = mgr.threadToSession.get(Thread.currentThread().getId());
        if (sid == null) return;
        SessionInfo info = mgr.sessions.get(sid);
        if (info == null) return;

        info.coinBalance    = coinBalance;
        info.usdtBalance    = usdtBalance;
        info.isTrading      = isTrading;
        info.currentPrice   = currentPrice;
        info.maxPrice       = maxPrice;
        info.buyTargetPrice = buyTargetPrice;
        info.boughtAtPrice  = boughtAtPrice;
        info.sellProfitPrice = sellProfitPrice;
        info.sellLossPrice  = sellLossPrice;
        info.totalTrades = totalTrades;
        info.profitableTrades = profitableTrades;
        info.losingTrades = losingTrades;
        info.grossProfitUsd = grossProfitUsd;
        info.grossLossUsd = grossLossUsd;
        info.sessionPnlUsd = sessionPnlUsd;
        info.estimatedCommissionUsd = estimatedCommissionUsd;
        info.startCoinBalance = startCoinBalance;
        info.endCoinBalance = endCoinBalance;

        // Persist session list on every live update so balances are always current
        mgr.saveSessions();
    }

    // ---- Event logging ----

    public static void logEventFromCurrentThread(String message) {
        logTypedEventFromCurrentThread(detectEventType(message), message);
    }

    /**
     * Log event with explicit type — bypasses keyword-based auto-detection.
     * Use this when the message content could be misclassified (e.g., restore messages,
     * error messages mentioning sell/buy in stack trace context).
     */
    public static void logTypedEventFromCurrentThread(String type, String message) {
        TradingSessionManager mgr = instance;
        if (mgr == null || message == null) return;
        String sid = mgr.threadToSession.get(Thread.currentThread().getId());
        if (sid == null) return;
        SessionInfo info = mgr.sessions.get(sid);
        if (info == null) return;
        info.addEvent(type != null ? type : "INFO", message);
        mgr.saveSessions();
    }

    private static String detectEventType(String msg) {
        if (msg == null) return "INFO";
        String lower = msg.toLowerCase();
        // Check ERROR/STOP first — if message contains stack trace or error keyword, it's an error
        // regardless of other words in the message
        if (lower.contains("ошибк") || lower.contains("❌ ошибка") || lower.contains("❌ error")
                || lower.contains("exception") || lower.contains("критическ") || lower.contains("⛔")) return "ERROR";
        if (lower.contains("остановл") || lower.contains("🛑") || lower.contains("stopped")) return "STOP";
        if (lower.contains("завершен") || lower.contains("done")) return "DONE";
        // BUY/SELL only match explicit trading action phrases, not method names
        if (lower.contains("✅ покупка") || lower.contains("покупка совершена") || lower.contains("покупк выполн")) return "BUY";
        if (lower.contains("📈 продажа") || lower.contains("📉 продажа") || lower.contains("продажа в прибыль") || lower.contains("продажа в убыток")) return "SELL";
        return "INFO";
    }

    public void registerThread(String sessionId) {
        threadToSession.put(Thread.currentThread().getId(), sessionId);
    }
    public void unregisterThread() {
        threadToSession.remove(Thread.currentThread().getId());
    }

    // ---- Active session check ----

    public boolean hasActiveSession() {
        return sessions.values().stream().anyMatch(s -> "RUNNING".equals(s.status));
    }

    private Map<String, Object> tooManySessionsError() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("error", "Уже есть активная сессия. Остановите её перед запуском новой.");
        return e;
    }

    // ---- Persistence: sessions list ----

    public void saveSessions() {
        try {
            Files.createDirectories(Paths.get("trading_states"));
            List<Map<String, Object>> list = sessions.values().stream()
                .map(s -> s.toMap(true))
                .collect(Collectors.toList());
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(SESSION_STORE_FILE), list);
        } catch (Exception e) {
            log.warn("Failed to save sessions: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void loadSessions() {
        File f = new File(SESSION_STORE_FILE);
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
                info.totalTrades = toInt(m.get("totalTrades"));
                info.profitableTrades = toInt(m.get("profitableTrades"));
                info.losingTrades = toInt(m.get("losingTrades"));
                info.grossProfitUsd = toDouble(m.get("grossProfitUsd"));
                info.grossLossUsd = toDouble(m.get("grossLossUsd"));
                info.sessionPnlUsd = toDouble(m.get("sessionPnlUsd"));
                info.estimatedCommissionUsd = toDouble(m.get("estimatedCommissionUsd"));
                info.startCoinBalance = toDouble(m.get("startCoinBalance"));
                info.endCoinBalance = toDouble(m.get("endCoinBalance"));
                info.backtestProgress = toInt(m.get("backtestProgress"));
                // Restore events
                List<Map<String, Object>> evts = (List<Map<String, Object>>) m.get("events");
                if (evts != null) {
                    for (Map<String, Object> ev : evts) {
                        SessionEvent e = new SessionEvent();
                        e.timestamp = toLong(ev.get("timestamp"));
                        e.type = (String) ev.get("type");
                        e.message = (String) ev.get("message");
                        info.events.add(e);
                    }
                }
                // Mark running sessions as interrupted (JVM was killed)
                if ("RUNNING".equals(info.status)) {
                    info.status = "STOPPED";
                    info.stoppedUnexpectedly = true;
                    info.endedAt = System.currentTimeMillis();
                    info.addEvent("ERROR", "Сессия прервана (JVM завершён) — ожидает возобновления");
                }
                sessions.put(info.id, info);
            }
            log.info("Loaded {} sessions from store", sessions.size());
        } catch (Exception e) {
            log.warn("Failed to load sessions: {}", e.getMessage());
        }
    }

    /** Auto-resume sessions that were stopped unexpectedly */
    public void autoResumeSessions(long chatId) {
        sessions.values().stream()
            .filter(s -> s.stoppedUnexpectedly && s.type != SessionType.BACKTEST)
            .forEach(s -> {
                log.info("Auto-resuming session: {}", s.id);
                s.addEvent("INFO", "Авто-возобновление после сбоя JVM");
                resumeSession(s.id, chatId);
            });
    }

    // ---- Helper: load TradingState for a session ----

    private TradingState loadTradingState(String sessionId) {
        StateManager sm = new StateManager();
        if (!sm.hasState(sessionId)) {
            log.info("No TradingState found for session {}", sessionId);
            return null;
        }
        TradingState state = sm.loadState(sessionId);
        if (state != null) {
            log.info("Loaded TradingState for session {}: isTrading={}, boughtFor={}",
                sessionId, state.isTrading(), state.getBoughtFor());
        }
        return state;
    }

    // ---- Start tester trading ----

    public Object startTesterTrading(String coinName, double startAssets, double tradingSum,
                                     double buyGap, double sellWithProfitGap, double sellWithLossGap,
                                     int updateTimeout, int chartRefreshInterval, long chatId) {
        if (hasActiveSession()) return tooManySessionsError();
        String id = "tester_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
                                                  updateTimeout, chartRefreshInterval);
        params.put("startAssets", startAssets);

        SessionInfo info = new SessionInfo(id, SessionType.TESTER, coinName, params);
        info.addEvent("START", "Сессия запущена: " + coinName);
        sessions.put(id, info);
        launchTesterThread(info, startAssets, tradingSum, buyGap, sellWithProfitGap,
                           sellWithLossGap, updateTimeout, null, chatId);
        saveSessions();
        log.info("Started tester session: {}", id);
        return info;
    }

    private void launchTesterThread(SessionInfo info, double startAssets, double tradingSum,
                                    double buyGap, double sellWithProfitGap, double sellWithLossGap,
                                    int updateTimeout, TradingState savedState, long chatId) {
        Thread t = new Thread(() -> {
            registerThread(info.id);
            try {
                Coin coin = CoinsList.getCoinByName(info.coinName);

                // Build wallet: from saved state if resuming, else from startAssets
                Map<Coin, Double> assets = new HashMap<>();
                if (savedState != null && savedState.getWalletAssets() != null
                        && !savedState.getWalletAssets().isEmpty()) {
                    // Restore exact wallet from saved state
                    double usdtAmt = savedState.getWalletAssets().getOrDefault("USDT", startAssets);
                    double coinAmt = savedState.getWalletAssets().getOrDefault(
                            coin.getSymbol().toUpperCase(), 0.0);
                    // Fallback: use Session-level balances if state wallet is empty
                    if (usdtAmt == 0 && coinAmt == 0) {
                        usdtAmt = info.usdtBalance > 0 ? info.usdtBalance : startAssets;
                        coinAmt = info.coinBalance;
                    }
                    assets.put(Account.USD_TOKENS.USDT.getCoin(), usdtAmt);
                    assets.put(coin, coinAmt);
                    log.info("Restoring tester wallet: {} USDT, {} {}", usdtAmt, coinAmt, coin.getSymbol());
                } else if (savedState == null && info.coinBalance == 0 && info.usdtBalance == 0) {
                    // Fresh session
                    assets.put(Account.USD_TOKENS.USDT.getCoin(), startAssets);
                    assets.put(coin, 0.0);
                } else {
                    // Resume without full state file — use session balances
                    double usdtAmt = info.usdtBalance > 0 ? info.usdtBalance : startAssets;
                    double coinAmt = info.coinBalance;
                    assets.put(Account.USD_TOKENS.USDT.getCoin(), usdtAmt);
                    assets.put(coin, coinAmt);
                    log.info("Resuming tester wallet from session: {} USDT, {} {}", usdtAmt, coinAmt, coin.getSymbol());
                }

                Account account = AccountBuilder.createNewTester(assets);
                try { ImageAndMessageSender.setChatId(chatId); } catch (Exception ignored) {}
                new ReversalPointsStrategyTrader(account, coin, tradingSum,
                        buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chatId,
                        savedState, info.id)
                        .startTrading();
                setFinalStatus(info, false);
            } catch (Exception e) {
                info.status = "ERROR";
                info.endedAt = System.currentTimeMillis();
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("Tester trading error", e);
            } finally {
                unregisterThread();
                saveSessions();
            }
        });
        t.setDaemon(true);
        info.thread = t;
        info.status = "RUNNING";
        t.start();
    }

    // ---- Start Binance Real ----

    public Object startBinanceTrading(String coinName, double tradingSum,
                                      double buyGap, double sellWithProfitGap, double sellWithLossGap,
                                      int updateTimeout, int chartRefreshInterval, long chatId) {
        if (hasActiveSession()) return tooManySessionsError();
        String id = "binance_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
                                                  updateTimeout, chartRefreshInterval);
        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_REAL, coinName, params);
        info.addEvent("START", "Binance REAL сессия запущена: " + coinName);
        sessions.put(id, info);
        launchBinanceThread(info, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
                            updateTimeout, false, null, chatId);
        saveSessions();
        return info;
    }

    // ---- Start Binance Testnet ----

    public Object startBinanceTestTrading(String coinName, double tradingSum,
                                          double buyGap, double sellWithProfitGap, double sellWithLossGap,
                                          int updateTimeout, int chartRefreshInterval, long chatId) {
        if (hasActiveSession()) return tooManySessionsError();
        String id = "binance_test_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
                                                  updateTimeout, chartRefreshInterval);
        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_TEST, coinName, params);
        info.addEvent("START", "Binance TEST сессия запущена: " + coinName);
        sessions.put(id, info);
        launchBinanceThread(info, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
                            updateTimeout, true, null, chatId);
        saveSessions();
        return info;
    }

    private void launchBinanceThread(SessionInfo info, double tradingSum,
                                     double buyGap, double sellWithProfitGap, double sellWithLossGap,
                                     int updateTimeout, boolean testnet,
                                     TradingState savedState, long chatId) {
        Thread t = new Thread(() -> {
            registerThread(info.id);
            try {
                AppConfig cfg = AppConfig.getInstance();
                char[] apiKey = testnet
                    ? cfg.getBinanceTestApiKey().toCharArray()
                    : cfg.getBinanceApiKey().toCharArray();
                char[] privKeyPath = testnet
                    ? cfg.resolvePrivateKeyPath(cfg.getBinanceTestPrivateKeyPath()).toCharArray()
                    : cfg.resolvePrivateKeyPath(cfg.getBinancePrivateKeyPath()).toCharArray();
                Coin coin = CoinsList.getCoinByName(info.coinName);
                Account account = AccountBuilder.createNewBinance(apiKey, privKeyPath,
                        testnet ? AccountBuilder.BINANCE_BASE_URL.TESTNET : AccountBuilder.BINANCE_BASE_URL.MAINNET);
                try { ImageAndMessageSender.setChatId(chatId); } catch (Exception ignored) {}
                new ReversalPointsStrategyTrader(account, coin, tradingSum,
                        buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chatId,
                        savedState, info.id)
                        .startTrading();
                setFinalStatus(info, false);
            } catch (Exception e) {
                info.status = "ERROR";
                info.endedAt = System.currentTimeMillis();
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("Binance trading error (testnet={})", testnet, e);
            } finally {
                unregisterThread();
                saveSessions();
            }
        });
        t.setDaemon(true);
        info.thread = t;
        info.status = "RUNNING";
        t.start();
    }

    // ---- Resume session ----

    public Map<String, Object> resumeSession(String sessionId, long chatId) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return Map.of("error", "Сессия не найдена");
        if ("RUNNING".equals(info.status)) return Map.of("error", "Сессия уже запущена");
        if (hasActiveSession()) return Map.of("error", "Уже есть активная сессия");
        if (info.type == SessionType.BACKTEST) return Map.of("error", "Бэктест нельзя возобновить");

        // Load saved TradingState (full strategy state)
        TradingState savedState = loadTradingState(sessionId);

        info.status = "RUNNING";
        info.stoppedUnexpectedly = false;
        info.endedAt = 0;
        info.addEvent("START", savedState != null
            ? "Сессия возобновлена (состояние восстановлено: isTrading=" + savedState.isTrading() + ")"
            : "Сессия возобновлена (состояние не найдено — начинаем заново)");

        Map<String, Object> p = info.params;
        double tradingSum = toDouble(p.get("tradingSum"));
        double buyGap = toDouble(p.get("buyGap"));
        double sellWithProfitGap = toDouble(p.get("sellWithProfitGap"));
        double sellWithLossGap = toDouble(p.get("sellWithLossGap"));
        int updateTimeout = (int) toLong(p.get("telegramUpdateSec"));
        int chartRefresh = (int) toLong(p.get("chartRefreshSec"));
        if (updateTimeout == 0) updateTimeout = 30;

        switch (info.type) {
            case TESTER: {
                double startAssets = toDouble(p.getOrDefault("startAssets", 150.0));
                launchTesterThread(info, startAssets, tradingSum, buyGap, sellWithProfitGap,
                                   sellWithLossGap, updateTimeout, savedState, chatId);
                break;
            }
            case BINANCE_REAL:
                launchBinanceThread(info, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
                                    updateTimeout, false, savedState, chatId);
                break;
            case BINANCE_TEST:
                launchBinanceThread(info, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
                                    updateTimeout, true, savedState, chatId);
                break;
            default:
                info.status = "STOPPED";
                return Map.of("error", "Тип сессии не поддерживает возобновление");
        }
        saveSessions();
        log.info("Resumed session: {} (state loaded: {})", sessionId, savedState != null);
        return Map.of("resumed", true, "id", sessionId,
                      "stateRestored", savedState != null,
                      "wasTrading", savedState != null && savedState.isTrading());
    }

    // ---- Stop ----

    public boolean stopSession(String sessionId) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return false;
        if (info.thread != null && info.thread.isAlive()) info.thread.interrupt();
        info.status = "STOPPED";
        info.stoppedUnexpectedly = false;
        info.endedAt = System.currentTimeMillis();
        info.addEvent("STOP", "Остановлено пользователем");
        saveSessions();
        log.info("Stopped session: {}", sessionId);
        return true;
    }

    public void stopAllSessions() {
        sessions.values().forEach(s -> stopSession(s.id));
    }

    // ---- Delete ----

    public boolean deleteSession(String sessionId) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return false;
        if ("RUNNING".equals(info.status)) return false;
        sessions.remove(sessionId);
        // Also delete TradingState file
        new StateManager().deleteState(sessionId);
        saveSessions();
        log.info("Deleted session: {}", sessionId);
        return true;
    }

    // ---- Queries ----

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

    // ---- Backtest ----

    public SessionInfo startBacktest(String coinName, double tradingSum,
                                     double buyGap, double sellWithProfitGap,
                                     double sellWithLossGap, String chartType,
                                     String exchange, double feeRate) {
        String id = "backtest_" + System.currentTimeMillis();
        Map<String, Object> params = buildParams(tradingSum, buyGap, sellWithProfitGap, sellWithLossGap, 30, 60);
        params.put("chartType", chartType);
        params.put("exchange", exchange);
        params.put("feeRate", feeRate);

        SessionInfo info = new SessionInfo(id, SessionType.BACKTEST, coinName, params);
        lastBacktestResult.set(null);
        info.addEvent("START", "Бэктест запущен: " + coinName);

        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                Coin coin = CoinsList.getCoinByName(coinName);
                Chart chart = switch (chartType) {
                    case "yearly" -> Chart.getYearlyChart_1hourInterval(coin);
                    case "monthly" -> Chart.getMonthlyChart_1hourInterval(coin, java.time.YearMonth.now());
                    default -> Chart.get1DayUntilNowChart_5MinuteInterval(coin);
                };
                info.addEvent("INFO", "Данные загружены, запускаю расчёт...");
                ReversalPointStrategyBackTester tester = new ReversalPointStrategyBackTester(
                        coin, chart, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap, feeRate,
                        progress -> { info.backtestProgress = progress; saveSessions(); });
                ReversalPointStrategyBackTester.BackTestResult result = tester.startBackTest();

                Map<String, Object> resultMap = new LinkedHashMap<>();
                resultMap.put("coinName", coinName);
                resultMap.put("buyGap", result.getBuyGap());
                resultMap.put("sellWithProfitGap", result.getSellWithProfit());
                resultMap.put("sellWithLossGap", result.getSellWithLossGap());
                resultMap.put("profitUsd", result.getProfitInUsd());
                resultMap.put("profitPercent", result.getPercentageProfit());
                resultMap.put("estimatedCommissionUsd", result.getEstimatedCommissionUsd());
                resultMap.put("profitAfterCommissionUsd", result.getProfitAfterCommissionUsd());
                resultMap.put("totalTrades", result.getTotalTrades());
                resultMap.put("profitableTrades", result.getProfitableTrades());
                resultMap.put("losingTrades", result.getLosingTrades());
                resultMap.put("tradingSum", tradingSum);
                resultMap.put("chartType", chartType);
                resultMap.put("exchange", exchange);
                resultMap.put("feeRate", feeRate);
                resultMap.put("tradeEvents", tester.getTradeEvents());
                resultMap.put("chartPoints", tester.getBacktestChartPoints());
                lastBacktestResult.set(resultMap);

                info.totalTrades = result.getTotalTrades();
                info.profitableTrades = result.getProfitableTrades();
                info.losingTrades = result.getLosingTrades();
                info.estimatedCommissionUsd = result.getEstimatedCommissionUsd();
                info.sessionPnlUsd = result.getProfitInUsd();
                info.backtestProgress = 100;

                info.status = "DONE";
                info.endedAt = System.currentTimeMillis();
                info.addEvent("DONE", String.format("Готово! Прибыль: %.2f USD (%.2f%%)",
                        result.getProfitInUsd(), result.getPercentageProfit()));
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    info.status = "STOPPED";
                    info.addEvent("STOP", "Прервано");
                } else {
                    info.status = "ERROR";
                    info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                    log.error("Backtest error", e);
                }
                info.endedAt = System.currentTimeMillis();
            } finally {
                unregisterThread();
            }
        });
        t.setDaemon(true);
        info.thread = t;
        sessions.put(id, info);
        t.start();
        return info;
    }

    public List<Map<String, Object>> findTopBacktests(String coinName, double tradingSum, String chartType,
                                                      String exchange, double feeRate) throws NoSuchSymbolException {
        Coin coin = CoinsList.getCoinByName(coinName);
        Chart chart = switch (chartType) {
            case "yearly" -> Chart.getYearlyChart_1hourInterval(coin);
            case "monthly" -> Chart.getMonthlyChart_1hourInterval(coin, java.time.YearMonth.now());
            default -> Chart.get1DayUntilNowChart_5MinuteInterval(coin);
        };
        List<ReversalPointStrategyBackTester.BackTestResult> results = new ArrayList<>();
        double[] buy = {2, 3, 4, 5, 6};
        double[] sellP = {1, 2, 3, 4};
        double[] sellL = {4, 6, 8, 10};
        for (double b : buy) for (double sp : sellP) for (double sl : sellL) {
            ReversalPointStrategyBackTester t = new ReversalPointStrategyBackTester(coin, chart, tradingSum, b, sp, sl, feeRate, null);
            ReversalPointStrategyBackTester.BackTestResult r = t.startBackTest();
            if (r != null) results.add(r);
        }
        Collections.sort(results);
        return results.stream().limit(10).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("buyGap", r.getBuyGap());
            m.put("sellWithProfitGap", r.getSellWithProfit());
            m.put("sellWithLossGap", r.getSellWithLossGap());
            m.put("profitUsd", r.getProfitInUsd());
            m.put("profitAfterCommissionUsd", r.getProfitAfterCommissionUsd());
            m.put("estimatedCommissionUsd", r.getEstimatedCommissionUsd());
            m.put("totalTrades", r.getTotalTrades());
            m.put("exchange", exchange);
            m.put("feeRate", feeRate);
            return m;
        }).collect(Collectors.toList());
    }

    public Map<String, Object> getLastBacktestResult() {
        return lastBacktestResult.get();
    }

    // ---- Helpers ----

    private static Map<String, Object> buildParams(double tradingSum, double buyGap,
                                                   double sellWithProfitGap, double sellWithLossGap,
                                                   int updateTimeout, int chartRefresh) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tradingSum", tradingSum);
        p.put("buyGap", buyGap);
        p.put("sellWithProfitGap", sellWithProfitGap);
        p.put("sellWithLossGap", sellWithLossGap);
        p.put("telegramUpdateSec", updateTimeout);
        p.put("chartRefreshSec", chartRefresh);
        return p;
    }

    private static void setFinalStatus(SessionInfo info, boolean stoppedByUser) {
        info.status = "STOPPED";
        info.stoppedUnexpectedly = false;
        if (stoppedByUser || Thread.currentThread().isInterrupted()) {
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

    private static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        return 0;
    }
}
