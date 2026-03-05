package ton.dariushkmetsyak.Api;

import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointsStrategyTrader;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder;

import java.util.HashMap;
import java.util.Map;

public class TradingSessionManager {
    private Thread tradingThread;
    private String status = "IDLE";
    private String lastError = "";

    public synchronized String getStatusJson() {
        String threadState = tradingThread == null ? "NONE" : tradingThread.getState().name();
        return "{\"status\":\"" + escape(status) + "\","
                + "\"threadState\":\"" + escape(threadState) + "\","
                + "\"lastError\":\"" + escape(lastError) + "\"}";
    }

    public synchronized String startTestTrading(String coinName, double tradingSum, double buyGap,
                                                double sellWithProfitGap, double sellWithLossGap,
                                                int updateTimeoutSeconds, long chatId) {
        if (tradingThread != null && tradingThread.isAlive()) {
            return "{\"ok\":false,\"message\":\"Trading is already running\"}";
        }

        try {
            Map<Coin, Double> assets = new HashMap<>();
            assets.put(Coin.createCoin("Tether"), tradingSum * 2);
            Account testAccount = AccountBuilder.createNewTester(assets);
            Coin coin = Coin.createCoin(coinName);

            ReversalPointsStrategyTrader trader = new ReversalPointsStrategyTrader(
                    testAccount,
                    coin,
                    tradingSum,
                    buyGap,
                    sellWithProfitGap,
                    sellWithLossGap,
                    updateTimeoutSeconds,
                    chatId
            );

            tradingThread = new Thread(trader::startTrading, "api-trading-session");
            tradingThread.setDaemon(true);
            tradingThread.start();
            status = "RUNNING_TEST";
            lastError = "";
            return "{\"ok\":true,\"message\":\"Test trading started\"}";
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            status = "ERROR";
            return "{\"ok\":false,\"message\":\"" + escape(lastError) + "\"}";
        }
    }

    public synchronized String stopTrading() {
        if (tradingThread == null || !tradingThread.isAlive()) {
            status = "IDLE";
            return "{\"ok\":true,\"message\":\"Trading is not running\"}";
        }

        tradingThread.interrupt();
        status = "STOPPING";
        return "{\"ok\":true,\"message\":\"Stop signal sent\"}";
    }

    private String escape(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
