package ton.dariushkmetsyak.Strategies.LadderStrategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.Strategies.Core.LadderDecisionEngine;
import ton.dariushkmetsyak.Strategies.Core.LadderExecutionOrchestrator;
import ton.dariushkmetsyak.Strategies.Core.LadderTradeExecutor;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.Util.Prices;
import ton.dariushkmetsyak.Web.TradingSessionManager;

public class LadderStrategyTrader {
    private static final Logger log = LoggerFactory.getLogger(LadderStrategyTrader.class);

    private final Account account;
    private final Coin coin;
    private final double orderUsdt;
    private final double stepPercent;
    private final int updateTimeoutSec;

    private final LadderDecisionEngine engine = new LadderDecisionEngine();
    private final LadderDecisionEngine.State state = new LadderDecisionEngine.State();
    private final LadderDecisionEngine.Params params;
    private final LadderExecutionOrchestrator orchestrator;

    public LadderStrategyTrader(Account account, Coin coin, double orderUsdt, double stepPercent, int updateTimeoutSec) {
        this.account = account;
        this.coin = coin;
        this.orderUsdt = orderUsdt;
        this.stepPercent = stepPercent;
        this.updateTimeoutSec = Math.max(5, updateTimeoutSec);
        this.params = new LadderDecisionEngine.Params(orderUsdt, stepPercent);
        this.orchestrator = new LadderExecutionOrchestrator(engine, new LiveExecutor());
    }

    public void startTrading() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                double price = Account.getCurrentPrice(coin);
                orchestrator.onPrice(price, params, state);

                double coinBalance = account.wallet().getAmountOfCoin(coin);
                double usdtBalance = account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin());

                TradingSessionManager.updateLiveState(
                        coinBalance, usdtBalance, coinBalance > 0.0, price,
                        null, null, null, null, null
                );

                Thread.sleep(updateTimeoutSec * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Ladder trader tick error: {}", e.getMessage(), e);
                TradingSessionManager.logTypedEventFromCurrentThread("ERROR", "Ladder tick error: " + e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private class LiveExecutor implements LadderTradeExecutor {
        @Override
        public void buy(double price, double usdtAmount) throws Exception {
            double qty = usdtAmount / price;
            account.trader().buy(coin, price, qty);
            TradingSessionManager.logTypedEventFromCurrentThread("BUY",
                    "🪜 BUY " + coin.getSymbol() + " qty=" + Prices.round(qty) + " @ " + Prices.round(price));
        }

        @Override
        public void sell(double price, double quantity) throws Exception {
            account.trader().sell(coin, price, quantity);
            TradingSessionManager.logTypedEventFromCurrentThread("SELL",
                    "🪜 SELL " + coin.getSymbol() + " qty=" + Prices.round(quantity) + " @ " + Prices.round(price));
        }
    }
}
