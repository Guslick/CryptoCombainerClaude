package ton.dariushkmetsyak.Web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ton.dariushkmetsyak.Charts.Chart;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;
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
import ton.dariushkmetsyak.TradingApi.ApiService.TesterAccount;
import ton.dariushkmetsyak.Util.Prices;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Управляет сессиями торговли, запущенными из MiniApp.
 * Потокобезопасный синглтон.
 */
public class TradingSessionManager {
    private static final Logger log = LoggerFactory.getLogger(TradingSessionManager.class);
    private static TradingSessionManager instance;

    public enum SessionType { TESTER, BINANCE_TEST, BINANCE_REAL, RESEARCH, BACKTEST }

    public static class SessionInfo {
        public final String id;
        public final SessionType type;
        public final String coinName;
        public final Map<String, Object> params;
        public volatile String status; // RUNNING, STOPPED, ERROR
        public volatile String lastMessage;
        public volatile long startedAt;
        public Thread thread;

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
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("type", type.name());
            m.put("coinName", coinName);
            m.put("status", status);
            m.put("lastMessage", lastMessage);
            m.put("startedAt", startedAt);
            m.put("params", params);
            return m;
        }
    }

    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final AtomicReference<Map<String, Object>> lastBacktestResult = new AtomicReference<>(null);

    private TradingSessionManager() {}

    public static synchronized TradingSessionManager getInstance() {
        if (instance == null) instance = new TradingSessionManager();
        return instance;
    }

    /** Запустить тестовую торговлю (виртуальный аккаунт) */
    public SessionInfo startTesterTrading(String coinName, double startAssets, double tradingSum,
                                          double buyGap, double sellWithProfitGap,
                                          double sellWithLossGap, int updateTimeout, long chatId) {
        String id = "tester_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("startAssets", startAssets);
        params.put("tradingSum", tradingSum);
        params.put("buyGap", buyGap);
        params.put("sellWithProfitGap", sellWithProfitGap);
        params.put("sellWithLossGap", sellWithLossGap);
        params.put("updateTimeout", updateTimeout);

        SessionInfo info = new SessionInfo(id, SessionType.TESTER, coinName, params);

        Thread t = new Thread(() -> {
            try {
                Coin coin = CoinsList.getCoinByName(coinName);
                Map<Coin, Double> assets = new HashMap<>();
                assets.put(Coin.createCoin("Tether"), startAssets);
                assets.put(coin, 0d);
                Account account = AccountBuilder.createNewTester(assets);
                ImageAndMessageSender.setChatId(chatId);
                new ReversalPointsStrategyTrader(account, coin, tradingSum,
                        buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chatId)
                        .startTrading();
                info.status = "STOPPED";
                info.lastMessage = "Торговля завершена";
            } catch (InterruptedException e) {
                info.status = "STOPPED";
                info.lastMessage = "Остановлено пользователем";
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                info.status = "ERROR";
                info.lastMessage = "Ошибка: " + e.getMessage();
                log.error("Tester trading error", e);
            }
        });
        t.setDaemon(true);
        info.thread = t;
        sessions.put(id, info);
        t.start();
        log.info("Started tester trading session: {}", id);
        return info;
    }

    /** Запустить торговлю на Binance Real */
    public SessionInfo startBinanceTrading(String coinName, double tradingSum,
                                           double buyGap, double sellWithProfitGap,
                                           double sellWithLossGap, int updateTimeout, long chatId) {
        String id = "binance_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tradingSum", tradingSum);
        params.put("buyGap", buyGap);
        params.put("sellWithProfitGap", sellWithProfitGap);
        params.put("sellWithLossGap", sellWithLossGap);
        params.put("updateTimeout", updateTimeout);

        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_REAL, coinName, params);

        Thread t = new Thread(() -> {
            try {
                AppConfig cfg = AppConfig.getInstance();
                char[] apiKey = cfg.getBinanceApiKey().toCharArray();
                char[] privKeyPath = cfg.getBinancePrivateKeyPath().toCharArray();
                Coin coin = CoinsList.getCoinByName(coinName);
                Account account = AccountBuilder.createNewBinance(apiKey, privKeyPath,
                        AccountBuilder.BINANCE_BASE_URL.MAINNET);
                ImageAndMessageSender.setChatId(chatId);
                new ReversalPointsStrategyTrader(account, coin, tradingSum,
                        buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chatId)
                        .startTrading();
                info.status = "STOPPED";
                info.lastMessage = "Торговля завершена";
            } catch (InterruptedException e) {
                info.status = "STOPPED";
                info.lastMessage = "Остановлено пользователем";
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                info.status = "ERROR";
                info.lastMessage = "Ошибка: " + e.getMessage();
                log.error("Binance trading error", e);
            }
        });
        t.setDaemon(true);
        info.thread = t;
        sessions.put(id, info);
        t.start();
        return info;
    }

    /** Запустить торговлю на Binance Testnet */
    public SessionInfo startBinanceTestTrading(String coinName, double tradingSum,
                                               double buyGap, double sellWithProfitGap,
                                               double sellWithLossGap, int updateTimeout, long chatId) {
        String id = "binance_test_" + System.currentTimeMillis();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tradingSum", tradingSum);
        params.put("buyGap", buyGap);
        params.put("sellWithProfitGap", sellWithProfitGap);
        params.put("sellWithLossGap", sellWithLossGap);
        params.put("updateTimeout", updateTimeout);

        SessionInfo info = new SessionInfo(id, SessionType.BINANCE_TEST, coinName, params);

        Thread t = new Thread(() -> {
            try {
                AppConfig cfg = AppConfig.getInstance();
                char[] apiKey = cfg.getBinanceTestApiKey().toCharArray();
                char[] privKeyPath = cfg.getBinanceTestPrivateKeyPath().toCharArray();
                Coin coin = CoinsList.getCoinByName(coinName);
                Account account = AccountBuilder.createNewBinance(apiKey, privKeyPath,
                        AccountBuilder.BINANCE_BASE_URL.TESTNET);
                ImageAndMessageSender.setChatId(chatId);
                new ReversalPointsStrategyTrader(account, coin, tradingSum,
                        buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chatId)
                        .startTrading();
                info.status = "STOPPED";
                info.lastMessage = "Торговля завершена";
            } catch (InterruptedException e) {
                info.status = "STOPPED";
                info.lastMessage = "Остановлено пользователем";
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                info.status = "ERROR";
                info.lastMessage = "Ошибка: " + e.getMessage();
                log.error("Binance test trading error", e);
            }
        });
        t.setDaemon(true);
        info.thread = t;
        sessions.put(id, info);
        t.start();
        return info;
    }

    /** Остановить сессию */
    public boolean stopSession(String sessionId) {
        SessionInfo info = sessions.get(sessionId);
        if (info == null) return false;
        if (info.thread != null && info.thread.isAlive()) {
            info.thread.interrupt();
        }
        info.status = "STOPPED";
        info.lastMessage = "Остановлено пользователем";
        log.info("Stopped session: {}", sessionId);
        return true;
    }

    /** Остановить все активные сессии */
    public void stopAllSessions() {
        sessions.values().forEach(s -> stopSession(s.id));
    }

    /** Получить список всех сессий */
    public List<Map<String, Object>> getAllSessions() {
        List<Map<String, Object>> result = new ArrayList<>();
        sessions.values().forEach(s -> result.add(s.toMap()));
        result.sort((a, b) -> Long.compare((Long) b.get("startedAt"), (Long) a.get("startedAt")));
        return result;
    }

    /** Запустить бэктест в фоне */
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

        Thread t = new Thread(() -> {
            try {
                Coin coin = CoinsList.getCoinByName(coinName);
                Chart chart;
                if ("yearly".equals(chartType)) {
                    chart = Chart.getYearlyChart_1hourInterval(coin);
                } else {
                    chart = Chart.get1DayUntilNowChart_5MinuteInterval(coin);
                }
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
                info.lastMessage = String.format("Готово! Прибыль: %.2f USD (%.2f%%)",
                        result.getProfitInUsd(), result.getPercentageProfit());
            } catch (InterruptedException e) {
                info.status = "STOPPED";
                info.lastMessage = "Прервано";
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                info.status = "ERROR";
                info.lastMessage = "Ошибка: " + e.getMessage();
                log.error("Backtest error", e);
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
