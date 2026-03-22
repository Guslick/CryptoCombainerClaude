package ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy;
import ton.dariushkmetsyak.Charts.Chart;
import ton.dariushkmetsyak.Commission.CommissionCalculator;
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
    private CommissionCalculator commissionCalc = new CommissionCalculator(CommissionCalculator.Exchange.BINANCE);
    private double totalCommission = 0;
    private int winCount = 0;
    private int lossCount = 0;
    private double totalProfitAmount = 0;
    private double totalLossAmount = 0;

    // Progress tracking
    private final AtomicInteger progressCurrent = new AtomicInteger(0);
    private int progressTotal = 0;

    // Trade events for chart visualization
    private final List<double[]> tradeEvents = new ArrayList<>(); // [timestamp, price, type] type: 0=buy, 1=sell_profit, 2=sell_loss

    // Equity curve: [timestamp, equityValue] — recorded at each trade event, starting from 0
    private final List<double[]> equityCurve = new ArrayList<>();

    // Hold curve: [timestamp, holdEquity] — shows profit if user just held long from start
    private final List<double[]> holdCurve = new ArrayList<>();

    // For chart generation
    private final List<double[]> buyPoints = new ArrayList<>();
    private final List<double[]> sellProfitPoints = new ArrayList<>();
    private final List<double[]> sellLossPoints = new ArrayList<>();
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
        this(coin, chart, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap, new CommissionCalculator(CommissionCalculator.Exchange.BINANCE));
    }

    public ReversalPointStrategyBackTester(Coin coin, Chart chart, double tradingSum, double buyGap, double sellWithProfitGap, double sellWithLossGap, String exchangeName, double commissionRate) {
        this(coin, chart, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap, new CommissionCalculator(CommissionCalculator.Exchange.BINANCE));
    }

    public ReversalPointStrategyBackTester(Coin coin, Chart chart, double tradingSum, double buyGap, double sellWithProfitGap, double sellWithLossGap, CommissionCalculator commissionCalc) {
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
            this.commissionCalc = commissionCalc;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public class BackTestResult implements Comparable<BackTestResult>{
        double buyGap;
        double sellWithProfitGap;
        double sellWithLossGap;
        double profitInUsd;
        double percentageProfit;
        double totalCommission;
        double profitAfterCommission;
        int winCount;
        int lossCount;
        double totalProfitAmount;
        double totalLossAmount;
        String exchangeName;
        double commissionRate;

        public double getProfitInUsd() { return profitInUsd; }
        public double getBuyGap() { return buyGap; }
        public double getSellWithProfit() { return sellWithProfitGap; }
        public double getSellWithLossGap() { return sellWithLossGap; }
        public double getPercentageProfit() { return percentageProfit; }
        public double getTotalCommission() { return totalCommission; }
        public double getProfitAfterCommission() { return profitAfterCommission; }
        public double getProfitInUsdAfterCommission() { return profitAfterCommission; }
        public double getPercentageProfitAfterCommission() { return percentageProfit != 0 && profitInUsd != 0 ? profitAfterCommission / profitInUsd * percentageProfit : 0; }
        public int getWinCount() { return winCount; }
        public int getLossCount() { return lossCount; }
        public int getTotalTrades() { return winCount + lossCount; }
        public int getProfitTradeCount() { return winCount; }
        public int getLossTradeCount() { return lossCount; }
        public int getTotalTradeCount() { return winCount + lossCount; }
        public double getTotalProfitAmount() { return totalProfitAmount; }
        public double getTotalLossAmount() { return totalLossAmount; }
        public double getTotalProfit() { return totalProfitAmount; }
        public double getTotalLoss() { return totalLossAmount; }
        public String getExchangeName() { return exchangeName; }
        public double getCommissionRate() { return commissionRate; }

        @Override
        public String toString() {
            return coin.getName() + "\n" +
                    "Сделок: " + getTotalTrades() + " (✅" + winCount + " / ❌" + lossCount + ")\n" +
                    "Прибыль: $" + String.format("%.2f", profitInUsd) + "\n" +
                    "Прибыль в %: " + String.format("%.2f", percentageProfit) + "%\n" +
                    "Комиссия (" + commissionCalc.getExchange().getDisplayName() + "): $" + String.format("%.4f", totalCommission) + "\n" +
                    "Прибыль с комиссией: $" + String.format("%.2f", profitAfterCommission) + "\n" +
                    "Коэфф. покупки: " + String.format("%.1f", buyGap) + "%\n" +
                    "Коэфф. продажи в прибыль: " + String.format("%.1f", sellWithProfitGap) + "%\n" +
                    "Коэфф. продажи в убыток: " + String.format("%.1f", sellWithLossGap) + "%\n";
        }

        public BackTestResult(double buyGap, double sellWithProfit, double sellWithLossGap,
                             double profitInUsd, double percentageProfit,
                             double totalCommission, int winCount, int lossCount,
                             double totalProfitAmount, double totalLossAmount) {
            this.buyGap = buyGap;
            this.sellWithProfitGap = sellWithProfit;
            this.sellWithLossGap = sellWithLossGap;
            this.profitInUsd = profitInUsd;
            this.percentageProfit = percentageProfit;
            this.totalCommission = totalCommission;
            this.profitAfterCommission = profitInUsd - totalCommission;
            this.winCount = winCount;
            this.lossCount = lossCount;
            this.totalProfitAmount = totalProfitAmount;
            this.totalLossAmount = totalLossAmount;
            this.exchangeName = commissionCalc.getExchange().getDisplayName();
            this.commissionRate = commissionCalc.getFeePercent();
        }

        @Override
        public int compareTo(BackTestResult backTestResult) {
            return Double.compare(backTestResult.profitAfterCommission, this.profitAfterCommission);
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

        double finalUsdt = account.wallet().getAllAssets().get(USDT);
        double profitInUsd = finalUsdt - tradingSum;
        double percentageProfit = profitInUsd / tradingSum * 100;
        backTestResult = new BackTestResult(buyGap, sellWithProfitGap, sellWithLossGap,
                profitInUsd, percentageProfit, totalCommission,
                winCount, lossCount, totalProfitAmount, totalLossAmount);
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
                    double sellValue = coinQuantityInWallet * pointPrice;
                    double commBuy = commissionCalc.calcCommission(buyPrice * coinQuantityInWallet);
                    double commSell = commissionCalc.calcCommission(sellValue);
                    totalCommission += commBuy + commSell;
                    double pnl = (pointPrice - buyPrice) * coinQuantityInWallet;
                    winCount++;
                    totalProfitAmount += pnl;
                    UsdtQuantityInWallet += sellValue;
                    coinQuantityInWallet=0d;
                    account.wallet().getAllAssets().replace(coin, coinQuantityInWallet);
                    account.wallet().getAllAssets().replace(USDT,UsdtQuantityInWallet);
                    sellProfitPoints.add(new double[]{pointTimestamp, pointPrice});

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
                    double sellValue = coinQuantityInWallet * pointPrice;
                    double commBuy = commissionCalc.calcCommission(buyPrice * coinQuantityInWallet);
                    double commSell = commissionCalc.calcCommission(sellValue);
                    totalCommission += commBuy + commSell;
                    double pnl = (pointPrice - buyPrice) * coinQuantityInWallet;
                    lossCount++;
                    totalLossAmount += Math.abs(pnl);
                    UsdtQuantityInWallet += sellValue;
                    coinQuantityInWallet=0d;
                    account.wallet().getAllAssets().replace(coin, coinQuantityInWallet);
                    account.wallet().getAllAssets().replace(USDT,UsdtQuantityInWallet);
                    sellLossPoints.add(new double[]{pointTimestamp, pointPrice});

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
                                buyPoints.add(new double[]{pointTimestamp, pointPrice});
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



    /** Generate chart image with buy/sell markers and return file path */
    public String generateChartImage() {
        TradingChart.clearChart();
        // Draw price line
        for (double[] p : chart.getPrices()) {
            TradingChart.addSimplePoint(p[0], p[1]);
        }
        // Draw buy points (blue)
        for (double[] p : buyPoints) {
            TradingChart.addBuyIntervalMarker(p[0], p[1]);
        }
        // Draw sell profit points (green)
        for (double[] p : sellProfitPoints) {
            TradingChart.addSellProfitMarker(p[0], p[1]);
        }
        // Draw sell loss points (red)
        for (double[] p : sellLossPoints) {
            TradingChart.addSellLossMarker(p[0], p[1]);
        }
        String path = "backtest_chart_" + System.currentTimeMillis();
        TradingChart.makeScreenShot(path);
        TradingChart.clearChart();
        return path;
    }

    public List<double[]> getBuyPoints() { return buyPoints; }
    public List<double[]> getSellProfitPoints() { return sellProfitPoints; }
    public List<double[]> getSellLossPoints() { return sellLossPoints; }
    public Chart getChart() { return chart; }

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
