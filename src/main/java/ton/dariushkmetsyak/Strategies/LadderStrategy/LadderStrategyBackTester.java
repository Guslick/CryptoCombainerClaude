package ton.dariushkmetsyak.Strategies.LadderStrategy;

import ton.dariushkmetsyak.Charts.Chart;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.Strategies.Core.LadderDecisionEngine;

import java.util.ArrayList;
import java.util.List;

public class LadderStrategyBackTester {
    private final Coin coin;
    private final Chart chart;
    private final double tradingSum;
    private final double orderUsdt;
    private final double stepPercent;

    private final List<double[]> tradeEvents = new ArrayList<>();
    private final List<double[]> equityCurve = new ArrayList<>();
    private final List<double[]> holdCurve = new ArrayList<>();

    public LadderStrategyBackTester(Coin coin, Chart chart, double tradingSum, double orderUsdt, double stepPercent) {
        this.coin = coin;
        this.chart = chart;
        this.tradingSum = tradingSum;
        this.orderUsdt = orderUsdt;
        this.stepPercent = stepPercent;
    }

    public BackTestResult run() {
        ArrayList<double[]> prices = chart.getPrices();
        if (prices == null || prices.isEmpty()) return null;

        LadderDecisionEngine engine = new LadderDecisionEngine();
        LadderDecisionEngine.State state = new LadderDecisionEngine.State();
        LadderDecisionEngine.Params params = new LadderDecisionEngine.Params(orderUsdt, stepPercent);

        double usdt = tradingSum;
        double coinBalance = 0.0;
        int buyCount = 0;
        int sellCount = 0;

        double firstPrice = prices.get(0)[1];
        double holdQty = firstPrice > 0 ? tradingSum / firstPrice : 0.0;

        for (double[] p : prices) {
            long ts = (long) p[0];
            double price = p[1];

            LadderDecisionEngine.Decision d = engine.evaluate(price, params, state);

            if (d.shouldBuy && usdt >= d.buyUsdt) {
                double qty = d.buyUsdt / d.buyPrice;
                usdt -= d.buyUsdt;
                coinBalance += qty;
                buyCount++;
                tradeEvents.add(new double[]{ts, d.buyPrice, 0});
            }

            if (d.shouldSell && coinBalance >= d.sellQuantity) {
                usdt += d.sellQuantity * d.sellPrice;
                coinBalance -= d.sellQuantity;
                sellCount++;
                tradeEvents.add(new double[]{ts, d.sellPrice, 1});
            }

            equityCurve.add(new double[]{ts, usdt + coinBalance * price});
            holdCurve.add(new double[]{ts, holdQty * price});
        }

        double lastPrice = prices.get(prices.size() - 1)[1];
        double finalEquity = usdt + coinBalance * lastPrice;
        double profitUsd = finalEquity - tradingSum;
        double profitPercent = tradingSum > 0 ? (profitUsd / tradingSum) * 100.0 : 0.0;

        BackTestResult result = new BackTestResult();
        result.coinName = coin.getName();
        result.orderUsdt = orderUsdt;
        result.stepPercent = stepPercent;
        result.profitUsd = profitUsd;
        result.profitPercent = profitPercent;
        result.totalTrades = buyCount + sellCount;
        result.buyCount = buyCount;
        result.sellCount = sellCount;
        result.finalEquity = finalEquity;
        return result;
    }

    public List<double[]> getTradeEvents() { return tradeEvents; }
    public List<double[]> getEquityCurve() { return equityCurve; }
    public List<double[]> getHoldCurve() { return holdCurve; }

    public static class BackTestResult {
        public String coinName;
        public double orderUsdt;
        public double stepPercent;
        public double profitUsd;
        public double profitPercent;
        public int totalTrades;
        public int buyCount;
        public int sellCount;
        public double finalEquity;
    }
}
