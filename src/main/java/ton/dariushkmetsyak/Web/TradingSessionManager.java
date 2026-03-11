package ton.dariushkmetsyak.Web;

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

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Управляет сессиями торговли, запущенными из MiniApp.
 * Поддерживает журналирование событий (покупка/продажа/ошибки) по каждой сессии.
 * Ограничение: не более 1 активной торговой сессии одновременно.
 */
public class TradingSessionManager {
    private static final Logger log = LoggerFactory.getLogger(TradingSessionManager.class);
    private static TradingSessionManager instance;

    public enum SessionType { TESTER, BINANCE_TEST, BINANCE_REAL, RESEARCH, BACKTEST }

    // ---- SessionEvent ----
    public static class SessionEvent {
        public final long timestamp;
        public final String type;   // BUY, SELL, INFO, ERROR, START, STOP
        public final String message;

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
        public final String id;
        public final SessionType type;
        public final String coinName;
        public final Map<String, Object> params;
        public volatile String status;
        public volatile String lastMessage;
        public volatile long startedAt;
        public volatile long endedAt = 0;
        public Thread thread;
        public final List<SessionEvent> events = new CopyOnWriteArrayList<>();

        SessionInfo(String id, SessionType type, String coinName, Map<String, Object> params) {
            this.id = id;
            this.type = type;
            this.coinName = coinName;
            this.params = new HashMap<>(params);
            this.status = "RUNNING";
            this.startedAt = System.currentTimeMillis();
            this.lastMessage = "Запущено";
        }

        public Map<String, Object> toMap() {
            return toMap(false);
        }

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
            if (includeEvents) {
                m.put("events", events.stream().map(SessionEvent::toMap).collect(Collectors.toList()));
            }
            return m;
        }

        public void addEvent(String type, String message) {
            events.add(new SessionEvent(type, message));
            // Keep max 1000 events per session
            while (events.size() > 1000) events.remove(0);
            lastMessage = message;
        }
    }

    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final AtomicReference<Map<String, Object>> lastBacktestResult = new AtomicReference<>(null);
    /** Mapping: threadId → sessionId, used to route log messages to the right session */
    private final ConcurrentHashMap<Long, String> threadToSession = new ConcurrentHashMap<>();

    private TradingSessionManager() {}

    public static synchronized TradingSessionManager getInstance() {
        if (instance == null) instance = new TradingSessionManager();
        return instance;
    }

    // ---- Event logging from trading threads ----

    /**
     * Called by ImageAndMessageSender to log a message to the current session.
     * Auto-detects event type (BUY / SELL / ERROR / INFO) from message content.
     */
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

    private void registerThread(String sessionId) {
        threadToSession.put(Thread.currentThread().getId(), sessionId);
    }

    private void unregisterThread() {
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

    private static void setFinalStatus(SessionInfo info) {
        if (Thread.currentThread().isInterrupted()) {
            info.status = "STOPPED";
            info.lastMessage = "Остановлено пользователем";
            info.addEvent("STOP", "Остановлено пользователем");
        } else {
            info.status = "STOPPED";
            info.lastMessage = "Торговля завершена";
            info.addEvent("STOP", "Торговля завершена");
        }
        info.endedAt = System.currentTimeMillis();
    }

    // ---- Start tester trading ----

    public SessionInfo startTesterTrading(String coinName, double startAssets, double tradingSum,
                                          double buyGap, double sellWithProfitGap,
                                          double sellWithLossGap, int updateTimeout,
                                          int chartRefreshInterval, long chatId) {
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

        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                Coin coin = CoinsList.getCoinByName(coinName);
                Map<Coin, Double> assets = new HashMap<>();
                assets.put(Coin.createCoin("Tether"), startAssets);
                assets.put(coin, 0d);
                Account account = AccountBuilder.createNewTester(assets);
                try { ImageAndMessageSender.setChatId(chatId); } catch (Exception ignored) {}
                new ReversalPointsStrategyTrader(account, coin, tradingSum,
                        buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chatId)
                        .startTrading();
                setFinalStatus(info);
            } catch (Exception e) {
                info.status = "ERROR";
                info.endedAt = System.currentTimeMillis();
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("Tester trading error", e);
            } finally {
                unregisterThread();
            }
        });
        t.setDaemon(true);
        info.thread = t;
        sessions.put(id, info);
        t.start();
        log.info("Started tester trading session: {}", id);
        return info;
    }

    // ---- Start Binance Real ----

    public SessionInfo startBinanceTrading(String coinName, double tradingSum,
                                           double buyGap, double sellWithProfitGap,
                                           double sellWithLossGap, int updateTimeout,
                                           int chartRefreshInterval, long chatId) {
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

        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                AppConfig cfg = AppConfig.getInstance();
                char[] apiKey = cfg.getBinanceApiKey().toCharArray();
                char[] privKeyPath = cfg.resolvePrivateKeyPath(cfg.getBinancePrivateKeyPath()).toCharArray();
                Coin coin = CoinsList.getCoinByName(coinName);
                Account account = AccountBuilder.createNewBinance(apiKey, privKeyPath,
                        AccountBuilder.BINANCE_BASE_URL.MAINNET);
                try { ImageAndMessageSender.setChatId(chatId); } catch (Exception ignored) {}
                new ReversalPointsStrategyTrader(account, coin, tradingSum,
                        buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chatId)
                        .startTrading();
                setFinalStatus(info);
            } catch (Exception e) {
                info.status = "ERROR";
                info.endedAt = System.currentTimeMillis();
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("Binance trading error", e);
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

    // ---- Start Binance Testnet ----

    public SessionInfo startBinanceTestTrading(String coinName, double tradingSum,
                                               double buyGap, double sellWithProfitGap,
                                               double sellWithLossGap, int updateTimeout,
                                               int chartRefreshInterval, long chatId) {
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

        Thread t = new Thread(() -> {
            registerThread(id);
            try {
                AppConfig cfg = AppConfig.getInstance();
                char[] apiKey = cfg.getBinanceTestApiKey().toCharArray();
                char[] privKeyPath = cfg.resolvePrivateKeyPath(cfg.getBinanceTestPrivateKeyPath()).toCharArray();
                Coin coin = CoinsList.getCoinByName(coinName);
                Account account = AccountBuilder.createNewBinance(apiKey, privKeyPath,
                        AccountBuilder.BINANCE_BASE_URL.TESTNET);
                try { ImageAndMessageSender.setChatId(chatId); } catch (Exception ignored) {}
                new ReversalPointsStrategyTrader(account, coin, tradingSum,
                        buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chatId)
                        .startTrading();
                setFinalStatus(info);
            } catch (Exception e) {
                info.status = "ERROR";
                info.endedAt = System.currentTimeMillis();
                info.addEvent("ERROR", "Ошибка: " + e.getMessage());
                log.error("Binance test trading error", e);
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

    // ---- Stop ----

    public boolean stopSession(String sessionId) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return false;
        if (info.thread != null && info.thread.isAlive()) {
            info.thread.interrupt();
        }
        info.status = "STOPPED";
        info.endedAt = System.currentTimeMillis();
        info.addEvent("STOP", "Остановлено пользователем");
        log.info("Stopped session: {}", sessionId);
        return true;
    }

    public void stopAllSessions() {
        sessions.values().forEach(s -> stopSession(s.id));
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
                Chart chart;
                if ("yearly".equals(chartType)) {
                    chart = Chart.getYearlyChart_1hourInterval(coin);
                } else {
                    chart = Chart.get1DayUntilNowChart_5MinuteInterval(coin);
                }
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
                String msg = String.format("Готово! Прибыль: %.2f USD (%.2f%%)",
                        result.getProfitInUsd(), result.getPercentageProfit());
                info.addEvent("DONE", msg);
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
