package ton.dariushkmetsyak.Web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ton.dariushkmetsyak.Charts.Chart;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;
import ton.dariushkmetsyak.Config.AppConfig;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.CoinsList;
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointStrategyBackTester;
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointsStrategyTrader;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Управляет сессиями торговли, запущенными из MiniApp.
 * Поддерживает журналирование событий, персистентность сессий и авто-возобновление.
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
        public String type;   // BUY, SELL, INFO, ERROR, START, STOP
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
        // Live trading state (updated by trader via updateLiveState)
        public volatile double coinBalance = 0;
        public volatile double usdtBalance = 0;
        public volatile boolean isTrading = false;        // true = holding coin
        public volatile Double maxPrice = null;
        public volatile Double buyTargetPrice = null;     // price at which we'd buy
        public volatile Double boughtAtPrice = null;      // price we actually bought at
        public volatile Double sellProfitPrice = null;
        public volatile Double sellLossPrice = null;
        // Stopped unexpectedly (not by user) — should auto-resume
        public volatile boolean stoppedUnexpectedly = false;
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
            m.put("maxPrice", maxPrice);
            m.put("buyTargetPrice", buyTargetPrice);
            m.put("boughtAtPrice", boughtAtPrice);
            m.put("sellProfitPrice", sellProfitPrice);
            m.put("sellLossPrice", sellLossPrice);
            m.put("stoppedUnexpectedly", stoppedUnexpectedly);
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
     * Called by ReversalPointsStrategyTrader on every tick to push live state.
     */
    public static void updateLiveState(double coinBalance, double usdtBalance,
                                       boolean isTrading, Double maxPrice,
                                       Double buyTargetPrice, Double boughtAtPrice,
                                       Double sellProfitPrice, Double sellLossPrice) {
        TradingSessionManager mgr = instance;
        if (mgr == null) return;
        String sid = mgr.threadToSession.get(Thread.currentThread().getId());
        if (sid == null) return;
        SessionInfo info = mgr.sessions.get(sid);
        if (info == null) return;
        info.coinBalance = coinBalance;
        info.usdtBalance = usdtBalance;
        info.isTrading = isTrading;
        info.maxPrice = maxPrice;
        info.buyTargetPrice = buyTargetPrice;
        info.boughtAtPrice = boughtAtPrice;
        info.sellProfitPrice = sellProfitPrice;
        info.sellLossPrice = sellLossPrice;
    }

    // ---- Event logging from trading threads ----

    public static void logEventFromCurrentThread(String message) {
        TradingSessionManager mgr = instance;
        if (mgr == null || message == null) return;
        String sessionId = mgr.threadToSession.get(Thread.currentThread().getId());
        if (sessionId == null) return;
        SessionInfo info = mgr.sessions.get(sessionId);
        if (info == null) return;
        info.addEvent(detectEventType(message), message);
    }

    private static String detectEventType(String msg) {
        if (msg == null) return "INFO";
        String lower = msg.toLowerCase();
        if (lower.contains("куп") || lower.contains("buy") || lower.contains("покупк")) return "BUY";
        if (lower.contains("прода") || lower.contains("sell") || lower.contains("продан")) return "SELL";
        if (lower.contains("ошибк") || lower.contains("error") || lower.contains("exception") || lower.contains("fail")) return "ERROR";
        if (lower.contains("остановл") || lower.contains("стоп") || lower.contains("stopped")) return "STOP";
        if (lower.contains("готов") || lower.contains("завершен") || lower.contains("done")) return "DONE";
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
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "Уже есть активная сессия. Остановите её перед запуском новой.");
        return err;
    }

    // ---- Helpers ----

    private static void setFinalStatus(SessionInfo info, boolean stoppedByUser) {
        if (stoppedByUser || Thread.currentThread().isInterrupted()) {
            info.status = "STOPPED";
            info.stoppedUnexpectedly = false;
            info.lastMessage = "Остановлено пользователем";
            info.addEvent("STOP", "Остановлено пользователем");
        } else {
            info.status = "STOPPED";
            info.stoppedUnexpectedly = false;
            info.lastMessage = "Торговля завершена";
            info.addEvent("STOP", "Торговля завершена");
        }
        info.endedAt = System.currentTimeMillis();
    }

    // ---- Session persistence ----

    public void saveSessions() {
        try {
            Files.createDirectories(Paths.get("trading_states"));
            List<Map<String, Object>> list = new ArrayList<>();
            for (SessionInfo s : sessions.values()) {
                Map<String, Object> m = s.toMap(true);
                // Include events for persistence
                list.add(m);
            }
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
                info.maxPrice = m.get("maxPrice") != null ? toDouble(m.get("maxPrice")) : null;
                info.buyTargetPrice = m.get("buyTargetPrice") != null ? toDouble(m.get("buyTargetPrice")) : null;
                info.boughtAtPrice = m.get("boughtAtPrice") != null ? toDouble(m.get("boughtAtPrice")) : null;
                info.sellProfitPrice = m.get("sellProfitPrice") != null ? toDouble(m.get("sellProfitPrice")) : null;
                info.sellLossPrice = m.get("sellLossPrice") != null ? toDouble(m.get("sellLossPrice")) : null;
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
                // Mark running sessions as unexpectedly stopped (JVM was killed)
                if ("RUNNING".equals(info.status)) {
                    info.status = "STOPPED";
                    info.stoppedUnexpectedly = true;
                    info.endedAt = System.currentTimeMillis();
                    info.addEvent("ERROR", "Сессия прервана (JVM завершён)");
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

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return ((Integer) v).longValue();
        if (v instanceof Number) return ((Number) v).longValue();
        return 0L;
    }
    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return 0.0;
    }

    // ---- Start tester trading ----

    public Object startTesterTrading(String coinName, double startAssets, double tradingSum,
                                          double buyGap, double sellWithProfitGap,
                                          double sellWithLossGap, int updateTimeout,
                                          int chartRefreshInterval, long chatId) {
        if (hasActiveSession()) return tooManySessionsError();
        String id = "tester_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("startAssets", startAssets);
        params.put("tradingSum", tradingSum);
        params.put("buyGap", buyGap);
        params.put("sellWithProfitGap", sellWithProfitGap);
        params.put("sellWithLossGap", sellWithLossGap);
        params.put("telegramUpdateSec", updateTimeout);
        params.put("chartRefreshSec", chartRefreshInterval);

        SessionInfo info = new SessionInfo(id, SessionType.TESTER, coinName, params);
        info.addEvent("START", "Сессия запущена: " + coinName);
        sessions.put(id, info);
        launchTesterThread(info, startAssets, tradingSum, buyGap, sellWithProfitGap,
                           sellWithLossGap, updateTimeout, chatId);
        saveSessions();
        log.info("Started tester trading session: {}", id);
        return info;
    }

    private void launchTesterThread(SessionInfo info, double startAssets, double tradingSum,
                                    double buyGap, double sellWithProfitGap, double sellWithLossGap,
                                    int updateTimeout, long chatId) {
        Thread t = new Thread(() -> {
            registerThread(info.id);
            try {
                Coin coin = CoinsList.getCoinByName(info.coinName);
                Map<Coin, Double> assets = new HashMap<>();
                assets.put(Coin.createCoin("Tether"), startAssets);
                assets.put(coin, 0d);
                Account account = AccountBuilder.createNewTester(assets);
                try { ImageAndMessageSender.setChatId(chatId); } catch (Exception ignored) {}
                new ReversalPointsStrategyTrader(account, coin, tradingSum,
                        buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chatId)
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
                                           double buyGap, double sellWithProfitGap,
                                           double sellWithLossGap, int updateTimeout,
                                           int chartRefreshInterval, long chatId) {
        if (hasActiveSession()) return tooManySessionsError();
        String id = "binance_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tradingSum", tradingSum);
        params.put("buyGap", buyGap);
        params.put("sellWithProfitGap", sellWithProfitGap);
        params.put("sellWithLossGap", sellWithLossGap);
        params.put("telegramUpdateSec", updateTimeout);
        params.put("chartRefreshSec", chartRefreshInterval);

        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_REAL, coinName, params);
        info.addEvent("START", "Binance REAL сессия запущена: " + coinName);
        sessions.put(id, info);
        launchBinanceThread(info, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
                            updateTimeout, false, chatId);
        saveSessions();
        return info;
    }

    // ---- Start Binance Testnet ----

    public Object startBinanceTestTrading(String coinName, double tradingSum,
                                               double buyGap, double sellWithProfitGap,
                                               double sellWithLossGap, int updateTimeout,
                                               int chartRefreshInterval, long chatId) {
        if (hasActiveSession()) return tooManySessionsError();
        String id = "binance_test_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tradingSum", tradingSum);
        params.put("buyGap", buyGap);
        params.put("sellWithProfitGap", sellWithProfitGap);
        params.put("sellWithLossGap", sellWithLossGap);
        params.put("telegramUpdateSec", updateTimeout);
        params.put("chartRefreshSec", chartRefreshInterval);

        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_TEST, coinName, params);
        info.addEvent("START", "Binance TEST сессия запущена: " + coinName);
        sessions.put(id, info);
        launchBinanceThread(info, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
                            updateTimeout, true, chatId);
        saveSessions();
        return info;
    }

    private void launchBinanceThread(SessionInfo info, double tradingSum,
                                     double buyGap, double sellWithProfitGap, double sellWithLossGap,
                                     int updateTimeout, boolean testnet, long chatId) {
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
                        buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chatId)
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

        info.status = "RUNNING";
        info.stoppedUnexpectedly = false;
        info.endedAt = 0;
        info.addEvent("START", "Сессия возобновлена");

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
                                   sellWithLossGap, updateTimeout, chatId);
                break;
            }
            case BINANCE_REAL:
                launchBinanceThread(info, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
                                    updateTimeout, false, chatId);
                break;
            case BINANCE_TEST:
                launchBinanceThread(info, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
                                    updateTimeout, true, chatId);
                break;
            default:
                info.status = "STOPPED";
                return Map.of("error", "Тип сессии не поддерживает возобновление");
        }
        saveSessions();
        log.info("Resumed session: {}", sessionId);
        return Map.of("resumed", true, "id", sessionId);
    }

    // ---- Stop ----

    public boolean stopSession(String sessionId) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return false;
        if (info.thread != null && info.thread.isAlive()) {
            info.thread.interrupt();
        }
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

    // ---- Delete session ----

    public boolean deleteSession(String sessionId) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return false;
        if ("RUNNING".equals(info.status)) return false; // only inactive
        sessions.remove(sessionId);
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
                                     double sellWithLossGap, String chartType) {
        String id = "backtest_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tradingSum", tradingSum);
        params.put("buyGap", buyGap);
        params.put("sellWithProfitGap", sellWithProfitGap);
        params.put("sellWithLossGap", sellWithLossGap);
        params.put("chartType", chartType);

        SessionInfo info = new SessionInfo(id, SessionType.BACKTEST, coinName, params);
        lastBacktestResult.set(null);
        info.addEvent("START", "Бэктест запущен: " + coinName);

        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                Coin coin = CoinsList.getCoinByName(coinName);
                Chart chart = "yearly".equals(chartType)
                    ? Chart.getYearlyChart_1hourInterval(coin)
                    : Chart.get1DayUntilNowChart_5MinuteInterval(coin);
                info.addEvent("INFO", "Данные загружены, запускаю расчёт...");
                ReversalPointStrategyBackTester tester = new ReversalPointStrategyBackTester(
                        coin, chart, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap);
                ReversalPointStrategyBackTester.BackTestResult result = tester.startBackTest();

                Map<String, Object> resultMap = new LinkedHashMap<>();
                resultMap.put("coinName", coinName);
                resultMap.put("buyGap", result.getBuyGap());
                resultMap.put("sellWithProfitGap", result.getSellWithProfit());
                resultMap.put("sellWithLossGap", result.getSellWithLossGap());
                resultMap.put("profitUsd", result.getProfitInUsd());
                resultMap.put("profitPercent", result.getPercentageProfit());
                resultMap.put("tradingSum", tradingSum);
                resultMap.put("chartType", chartType);
                lastBacktestResult.set(resultMap);

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

    public Map<String, Object> getLastBacktestResult() {
        return lastBacktestResult.get();
    }
}
