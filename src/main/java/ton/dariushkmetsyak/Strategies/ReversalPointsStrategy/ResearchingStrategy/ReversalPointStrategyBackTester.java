package ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy;
import ton.dariushkmetsyak.Charts.Chart;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfUsdtException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;


public class ReversalPointStrategyBackTester {
    boolean hasPrices = false;
    ArrayList<ReversalPointStrategyBackTester.Reversal> reversalArrayList = new ArrayList<>();
    boolean trading = false;
    boolean max = false;
    boolean isSold = false;
    double buyGap = 0;
    double pointPrice=0;
    double buyPrice=0;
    double sellWithProfitGap =0;
    double sellWithLossGap =0;
    Double[] currentMinPrice = {Double.MAX_VALUE};
    Double[] currentMaxPrice = {0.0};
    Double[] currentMaxPriceTimestamp = {0.0};
    Double[] currentMinPriceTimestamp = {Double.MAX_VALUE};
    String chartScreenshotMessage ="";
    Account account;
    Coin coin;
    Chart chart;
    double tradingSum;
    final static Coin USDT;
    BackTestResult backTestResult;

    // Commission tracking
    double commissionRate = 0.1; // Binance default 0.1% per trade
    String exchangeName = "Binance";

    // Trade statistics
    int profitTradeCount = 0;
    int lossTradeCount = 0;
    double totalProfit = 0.0;
    double totalLoss = 0.0;
    double totalCommission = 0.0;
    double buyPriceForCurrentTrade = 0.0;

    // Progress tracking
    private final AtomicInteger progressCurrent = new AtomicInteger(0);
    private int progressTotal = 0;

    // Trade events for chart visualization
    private final List<double[]> tradeEvents = new ArrayList<>(); // [timestamp, price, type] type: 0=buy, 1=sell_profit, 2=sell_loss

    // Equity curve: [timestamp, equityValue] — recorded at each trade event, starting from 0
    private final List<double[]> equityCurve = new ArrayList<>();

    // Hold curve: [timestamp, holdEquity] — shows profit if user just held long from start
    private final List<double[]> holdCurve = new ArrayList<>();

    static {
        try {
            USDT=Coin.createCoin("Tether");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public BackTestResult getBackTestResult() {
        return backTestResult;
    }

    public int getProgressCurrent() { return progressCurrent.get(); }
    public int getProgressTotal() { return progressTotal; }

    public List<double[]> getTradeEvents() { return tradeEvents; }
    public List<double[]> getEquityCurve() { return equityCurve; }
    public List<double[]> getHoldCurve() { return holdCurve; }

    public ReversalPointStrategyBackTester(Coin coin, Chart chart, double tradingSum, double buyGap, double sellWithProfitGap, double sellWithLossGap) {
        this(coin, chart, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap, "Binance", 0.1);
    }

    public ReversalPointStrategyBackTester(Coin coin, Chart chart, double tradingSum, double buyGap, double sellWithProfitGap, double sellWithLossGap, String exchangeName, double commissionRate) {

        try {
            this.coin=Coin.createCoin(chart.getCoinName());
            Map<Coin, Double> testAssets = new HashMap<>();
            testAssets.put(USDT, 100d);
            testAssets.put(coin, 0d);
            this.account= AccountBuilder.createNewTester(testAssets);
            this.tradingSum=tradingSum;
            this.buyGap = buyGap;
            this.chart=chart;
            this.sellWithProfitGap = sellWithProfitGap;
            this.sellWithLossGap = sellWithLossGap;
            this.exchangeName = exchangeName;
            this.commissionRate = commissionRate;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
    public class BackTestResult implements Comparable<BackTestResult>{
        double buyGap;
        double sellWithProfitGap;
        double sellWithLossGap;

        public double getProfitInUsd() {
            return profitInUsd;
        }

        public double getBuyGap() { return buyGap; }
        public double getSellWithProfit() { return sellWithProfitGap; }
        public double getSellWithLossGap() { return sellWithLossGap; }
        public double getPercentageProfit() { return percentageProfit; }
        public int getProfitTradeCount() { return profitTradeCount; }
        public int getLossTradeCount() { return lossTradeCount; }
        public int getTotalTradeCount() { return profitTradeCount + lossTradeCount; }
        public double getTotalProfit() { return totalProfit; }
        public double getTotalLoss() { return totalLoss; }
        public double getTotalCommission() { return totalCommission; }
        public double getProfitInUsdAfterCommission() { return profitInUsd - totalCommission; }
        public double getPercentageProfitAfterCommission() { return (profitInUsd - totalCommission) / tradingSum * 100; }
        public String getExchangeName() { return exchangeName; }
        public double getCommissionRate() { return commissionRate; }

        double profitInUsd;
        double percentageProfit;
        int profitTradeCount;
        int lossTradeCount;
        double totalProfit;
        double totalLoss;
        double totalCommission;
        double tradingSum;
        String exchangeName;
        double commissionRate;

        @Override
        public String toString() {
            return coin.getName() +  "\n" +
                    "Assets: " + account.wallet().getAllAssets()+ "\n" +
                    "Profit in USD: " + profitInUsd +"\n" +
                    "Profit in %: " + percentageProfit + "\n" +
                    "Profit after commission: " + getProfitInUsdAfterCommission() + "\n" +
                    "Total trades: " + getTotalTradeCount() + " (+" + profitTradeCount + "/-" + lossTradeCount + ")\n" +
                    "Total commission: " + String.format("%.4f", totalCommission) + " USD\n" +
                    "Buy gap: " + buyGap + "\n" +
                    "Sell with profit gap: " + sellWithProfitGap + "\n" +
                    "Sell with loss gap: " + sellWithLossGap + "\n";
        }

        public BackTestResult(double buyGap, double sellWithProfit, double sellWithLossGap,
                              double profitInUsd, double percentageProfit,
                              int profitTradeCount, int lossTradeCount,
                              double totalProfit, double totalLoss, double totalCommission,
                              double tradingSum, String exchangeName, double commissionRate) {
            this.buyGap = buyGap;
            this.sellWithProfitGap = sellWithProfit;
            this.sellWithLossGap = sellWithLossGap;
            this.profitInUsd = profitInUsd;
            this.percentageProfit = percentageProfit;
            this.profitTradeCount = profitTradeCount;
            this.lossTradeCount = lossTradeCount;
            this.totalProfit = totalProfit;
            this.totalLoss = totalLoss;
            this.totalCommission = totalCommission;
            this.tradingSum = tradingSum;
            this.exchangeName = exchangeName;
            this.commissionRate = commissionRate;
        }


        @Override
        public int compareTo(BackTestResult backTestResult) {
            return Double.compare(backTestResult.profitInUsd, this.profitInUsd);
        }
    }
    private class Reversal {
        private final double[] data;
        private final String tag;

        Reversal(double[] data, String tag) {
            this.data = data;
            this.tag = tag;
        }
    }

    BiConsumer<Double, Double> findReversalPoints = (timestamp, price) -> {

    };

    private void recordEquity(double timestamp, double currentPrice) {
        double usdt = account.wallet().getAllAssets().get(USDT);
        double coinAmt = account.wallet().getAllAssets().get(coin);
        double equity = usdt + coinAmt * currentPrice - tradingSum; // profit relative to 0
        equityCurve.add(new double[]{timestamp, equity});
    }

    public BackTestResult startBackTest(){
        progressTotal = chart.getPrices().size();
        progressCurrent.set(0);
        // Record initial equity = 0
        equityCurve.add(new double[]{chart.getPrices().get(0)[0], 0.0});
        init(chart.getPrices().get(0)[0],chart.getPrices().get(0)[1]);
        for (int i=0; i<chart.getPrices().size();i++){
            try {
                progressCurrent.set(i + 1);
                if (!startBackTestingPoint(chart.getPrices().get(i)[0],chart.getPrices().get(i)[1])) return null;
            } catch (NoSuchSymbolException | InsufficientAmountOfUsdtException e) {
                throw new RuntimeException(e);
            }

        }

        if (account.wallet().getAllAssets().get(coin)!=0){
           Double USDTinWallet =  account.wallet().getAllAssets().get(USDT)+(account.wallet().getAllAssets().get(coin)*chart.getPrices().get(chart.getPrices().size()-1)[1]);
            account.wallet().getAllAssets().replace(USDT, USDTinWallet);
            account.wallet().getAllAssets().replace(coin, 0d);
        }
        // Record final equity
        double lastTs = chart.getPrices().get(chart.getPrices().size()-1)[0];
        double lastPrice = chart.getPrices().get(chart.getPrices().size()-1)[1];
        recordEquity(lastTs, lastPrice);

        // Build hold curve: profit if user just bought at first price and held
        double firstPrice = chart.getPrices().get(0)[1];
        List<double[]> prices = chart.getPrices();
        // Sample up to ~500 points to keep response size reasonable
        int step = Math.max(1, prices.size() / 500);
        for (int hi = 0; hi < prices.size(); hi += step) {
            double ts = prices.get(hi)[0];
            double pr = prices.get(hi)[1];
            double holdProfit = (pr - firstPrice) / firstPrice * tradingSum;
            holdCurve.add(new double[]{ts, holdProfit});
        }
        // Always include last point
        if (holdCurve.isEmpty() || holdCurve.get(holdCurve.size()-1)[0] != lastTs) {
            holdCurve.add(new double[]{lastTs, (lastPrice - firstPrice) / firstPrice * tradingSum});
        }

        // Free memory - reversalArrayList is not needed after backtest
        reversalArrayList.clear();
        reversalArrayList.trimToSize();

        double rawProfit = account.wallet().getAllAssets().get(USDT) - tradingSum;
        backTestResult = new BackTestResult(buyGap, sellWithProfitGap, sellWithLossGap,
                rawProfit, rawProfit / tradingSum * 100,
                profitTradeCount, lossTradeCount,
                totalProfit, totalLoss, totalCommission,
                tradingSum, exchangeName, commissionRate);
        return backTestResult;
    }

    private void init (double pointTimestamp, double pointPrice){
        reversalArrayList.add(new ReversalPointStrategyBackTester.Reversal(new double[]{pointTimestamp, pointPrice}, "initPoint"));
    }
    private boolean startBackTestingPoint(double pointTimestamp, double pointPrice) throws NoSuchSymbolException, InsufficientAmountOfUsdtException {

        this.pointPrice = pointPrice;
        hasPrices = true;

        if (trading) {
            if (((pointPrice - buyPrice) / buyPrice * 100) > sellWithProfitGap) {
                    Double coinQuantityInWallet = account.wallet().getAllAssets().get(coin);
                    Double UsdtQuantityInWallet = account.wallet().getAllAssets().get(USDT);
                    double sellAmount = coinQuantityInWallet * pointPrice;
                    UsdtQuantityInWallet += sellAmount;
                    coinQuantityInWallet = 0d;
                    account.wallet().getAllAssets().replace(coin, coinQuantityInWallet);
                    account.wallet().getAllAssets().replace(USDT,UsdtQuantityInWallet);

                // Commission for sell
                double commissionForSell = sellAmount * commissionRate / 100.0;
                totalCommission += commissionForSell;

                // Trade P&L
                double tradePnl = (pointPrice - buyPriceForCurrentTrade) * (tradingSum / buyPriceForCurrentTrade);
                totalProfit += tradePnl;
                profitTradeCount++;

                // Record trade event for chart
                tradeEvents.add(new double[]{pointTimestamp, pointPrice, 1}); // 1=sell_profit
                recordEquity(pointTimestamp, pointPrice);

                isSold =true;
                chartScreenshotMessage = "SOLD WITH PROFIT";
                trading = false;
                isSold=false;
                return true;
            }
            if (((buyPrice - pointPrice) / buyPrice * 100) > sellWithLossGap) {

                    Double coinQuantityInWallet = account.wallet().getAllAssets().get(coin);
                    Double UsdtQuantityInWallet = account.wallet().getAllAssets().get(USDT);
                    double sellAmount = coinQuantityInWallet * pointPrice;
                    UsdtQuantityInWallet += sellAmount;
                    coinQuantityInWallet = 0d;
                    account.wallet().getAllAssets().replace(coin, coinQuantityInWallet);
                    account.wallet().getAllAssets().replace(USDT,UsdtQuantityInWallet);

                // Commission for sell
                double commissionForSell = sellAmount * commissionRate / 100.0;
                totalCommission += commissionForSell;

                // Trade P&L
                double tradePnl = (pointPrice - buyPriceForCurrentTrade) * (tradingSum / buyPriceForCurrentTrade);
                totalLoss += tradePnl; // negative value
                lossTradeCount++;

                // Record trade event for chart
                tradeEvents.add(new double[]{pointTimestamp, pointPrice, 2}); // 2=sell_loss
                recordEquity(pointTimestamp, pointPrice);

                isSold=true;
                chartScreenshotMessage = "SOLD WITH LOSS";
                trading = false;
                isSold=false;
                return true;
            }
        }
        if (hasPrices && !trading) {

            ReversalPointStrategyBackTester.Reversal previousRec = reversalArrayList.get(reversalArrayList.toArray().length - 1);
            if (pointPrice > currentMaxPrice[0]) {
                max=true;
                currentMaxPrice[0] = pointPrice;
                currentMaxPriceTimestamp[0] = pointTimestamp;
                if (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100) > buyGap) {
                    if (!Objects.equals(previousRec.tag, "min")) {
                        ReversalPointStrategyBackTester.Reversal r = new ReversalPointStrategyBackTester.Reversal(new double[]{currentMinPriceTimestamp[0], currentMinPrice[0]}, "min");
                        reversalArrayList.add(r);

                    }
                    currentMinPrice[0] = pointPrice;
                }
            }
            if (pointPrice < currentMinPrice[0]) {
                max=false;
                currentMinPrice[0] = pointPrice;
                currentMinPriceTimestamp[0] = pointTimestamp;
                if (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100) > buyGap) {
                    if (!Objects.equals(previousRec.tag, "max")) {
                        ReversalPointStrategyBackTester.Reversal r = new ReversalPointStrategyBackTester.Reversal(new double[]{currentMaxPriceTimestamp[0], currentMaxPrice[0]}, "max");
                        reversalArrayList.add(r);
                        if (!trading){
                                Double coinQuantityInWallet = account.wallet().getAllAssets().get(coin);
                                Double UsdtQuantityInWallet = account.wallet().getAllAssets().get(USDT);
                                double buyQty = (account.wallet().getAmountOfCoin(USDT) * 1) / pointPrice;
                                coinQuantityInWallet += buyQty;
                                UsdtQuantityInWallet = 0d;
                                if (UsdtQuantityInWallet < 0){
                                    return false;
                                }
                                account.wallet().getAllAssets().replace(coin, coinQuantityInWallet);
                                account.wallet().getAllAssets().replace(USDT,UsdtQuantityInWallet);
                                // Commission for buy
                                double commissionForBuy = tradingSum * commissionRate / 100.0;
                                totalCommission += commissionForBuy;
                                buyPriceForCurrentTrade = pointPrice;
                                buyPrice = pointPrice;
                                trading = true;
                                // Record trade event for chart
                                tradeEvents.add(new double[]{pointTimestamp, pointPrice, 0}); // 0=buy
                                recordEquity(pointTimestamp, pointPrice);
                        }
                    }
                    currentMaxPrice[0] = pointPrice;

                }
            }
        }

        return true;
    }



    private static void clearChart (){
        TradingChart.clearChart();
    }

    public void sendPhotoToTelegram (){
        String telegramPicturePath = "";
        String currentPicturePath = telegramPicturePath + LocalDateTime.now();
        TradingChart.makeScreenShot(currentPicturePath);
        ImageAndMessageSender.sendPhoto(currentPicturePath, chartScreenshotMessage);
        try {
            Files.delete(Path.of(currentPicturePath));
        } catch (IOException e) {
            System.err.println("Error deleting file created for sending to Telegram: " + currentPicturePath);
            throw new RuntimeException(e);
        }

    }
}
