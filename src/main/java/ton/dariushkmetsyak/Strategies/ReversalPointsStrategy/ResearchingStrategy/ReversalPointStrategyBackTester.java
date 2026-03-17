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
import java.util.function.BiConsumer;


public class ReversalPointStrategyBackTester {
    TreeMap<Double, Double> prices = new TreeMap<>();
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

    public ReversalPointStrategyBackTester(Coin coin, Chart chart, double tradingSum, double buyGap, double sellWithProfitGap, double sellWithLossGap) {
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

        public double getProfitInUsd() { return profitInUsd; }
        public double getBuyGap() { return buyGap; }
        public double getSellWithProfit() { return sellWithProfitGap; }
        public double getSellWithLossGap() { return sellWithLossGap; }
        public double getPercentageProfit() { return percentageProfit; }
        public double getTotalCommission() { return totalCommission; }
        public double getProfitAfterCommission() { return profitAfterCommission; }
        public int getWinCount() { return winCount; }
        public int getLossCount() { return lossCount; }
        public int getTotalTrades() { return winCount + lossCount; }
        public double getTotalProfitAmount() { return totalProfitAmount; }
        public double getTotalLossAmount() { return totalLossAmount; }

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

    public BackTestResult startBackTest(){
        init(chart.getPrices().get(0)[0],chart.getPrices().get(0)[1]);
        for (int i=0; i<chart.getPrices().size();i++){
            try {
                if (!startBackTestingPoint(chart.getPrices().get(i)[0],chart.getPrices().get(i)[1])) return null;
              //  System.out.println(chart.getPrices().size()-i);
            } catch (NoSuchSymbolException | InsufficientAmountOfUsdtException e) {
                throw new RuntimeException(e);
            }

        }

      //  System.out.println(account.wallet().getAllAssets());
     //   System.out.println(account.wallet().getBalance());

      //  System.out.println("Last price: "+ chart.getPrices().get(chart.getPrices().size()-1)[1]);
        if (account.wallet().getAllAssets().get(coin)!=0){
           Double USDTinWallet =  account.wallet().getAllAssets().get(USDT)+(account.wallet().getAllAssets().get(coin)*chart.getPrices().get(chart.getPrices().size()-1)[1]); //конвертируем оставшиеся монеты в USDT по последней в графике цене
            account.wallet().getAllAssets().replace(USDT, USDTinWallet);
            account.wallet().getAllAssets().replace(coin, 0d);
        }
      //  System.out.println(account.wallet().getAllAssets());
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
    private   boolean startBackTestingPoint(double pointTimestamp, double pointPrice) throws NoSuchSymbolException, InsufficientAmountOfUsdtException {
        //   System.out.println("Trading "+ trading);

        this.pointPrice = pointPrice;
        prices.put(pointTimestamp, pointPrice);
//        TradingChart.addSimplePoint(pointTimestamp, pointPrice);
//        TradingChart.addSimplePriceMarker(pointTimestamp, pointPrice);
        //gap=gap+(100 - (currentMinPrice[0] / currentMaxPrice[0] * 100));
        if (trading) {
//                System.out.println(trading);
            //  TradingChart.addSimplePriceMarker(pointTimestamp,pointPrice);
            //   gap = "/n grow is: "+ (pointPrice-buyPrice)/buyPrice*100;
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

                isSold =true;
                chartScreenshotMessage = "SOLD WITH PROFIT";

                trading = false;
                isSold=false;
//                sendPhotoToTelegram();
//                ImageAndMessageSender.sendTelegramMessage(account.wallet().getAllAssets().toString());
//                try {
//                    TimeUnit.SECONDS.sleep(5);
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                }

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

                isSold=true;
                chartScreenshotMessage = "SOLD WITH LOSS";
            //    System.out.println("LOSS " + account.wallet().getBalance()+ " PRICE: " + pointPrice);
//                TradingChart.clearChart();

                trading = false;
                isSold=false;
//                sendPhotoToTelegram();
//                ImageAndMessageSender.sendTelegramMessage(account.wallet().getAllAssets().toString());
//                try {
//                    TimeUnit.SECONDS.sleep(5);
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                }
                return true;
            }
        }
        if (!prices.isEmpty() && !trading) {

            ReversalPointStrategyBackTester.Reversal previousRec = reversalArrayList.get(reversalArrayList.toArray().length - 1);
            //  System.out.println("finding reversals");
            if (pointPrice > currentMaxPrice[0]) {
                max=true;
                currentMaxPrice[0] = pointPrice;
                currentMaxPriceTimestamp[0] = pointTimestamp;
                // gap="+"+ (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100));
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
                //gap="-"+(100-currentMinPrice[0] / currentMaxPrice[0] * 100);
                if (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100) > buyGap) {
                    if (!Objects.equals(previousRec.tag, "max")) {
                        ReversalPointStrategyBackTester.Reversal r = new ReversalPointStrategyBackTester.Reversal(new double[]{currentMaxPriceTimestamp[0], currentMaxPrice[0]}, "max");
                        reversalArrayList.add(r);
                        if (!trading){
                            //account.trader().buy(coin,pointPrice,tradingSum/pointPrice);
                                Double coinQuantityInWallet = account.wallet().getAllAssets().get(coin);
                                Double UsdtQuantityInWallet = account.wallet().getAllAssets().get(USDT);
                                coinQuantityInWallet+=(account.wallet().getAmountOfCoin(USDT)*1)/pointPrice;
                                UsdtQuantityInWallet=0d;
                           // System.out.println("BOUGHT   USDT: "+ UsdtQuantityInWallet + "COIN: "+ coinQuantityInWallet);
                                if (UsdtQuantityInWallet<0){
//                                    chartScreenshotMessage=coin.getName() +  "\n" +
//                                            "PercentageGap: " + percentageGap + "\n" +
//                                            "Buy ratio: " + buyRatio + "\n" +
//                                            "Sell ratio: " + sellRatio + "\n"+
//                                            "Out of money. Current USDT Balance: "+ UsdtQuantityInWallet;
//                                    System.out.println(chartScreenshotMessage);
                      //              ImageAndMessageSender.sendTelegramMessage(chartScreenshotMessage);
                                    return  false;
                                }
                                account.wallet().getAllAssets().replace(coin, coinQuantityInWallet);
                                account.wallet().getAllAssets().replace(USDT,UsdtQuantityInWallet);
                                buyPoints.add(new double[]{pointTimestamp, pointPrice});
                                buyPrice = pointPrice;
                                trading = true;
                              //  ImageAndMessageSender.sendTelegramMessage(account.wallet().getAllAssets().toString());
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
        double growth;
//         if(!trading&&max) {
//
//             chartScreenshotMessage =
//                          "Finding entry point..." + "\n" +
//                          "Current POINT: " + Prices.round(pointPrice) + "\n" +
//                          "Current MIN: " + Prices.round(currentMinPrice[0]) + "\n" +
//                          "+" + Prices.round(((pointPrice - currentMinPrice[0]) / currentMinPrice[0] * 100));
//             Prices.round(((pointPrice - currentMinPrice[0]) / currentMinPrice[0] * 100));
//             System.out.println(Prices.round(((pointPrice - currentMinPrice[0]) / currentMinPrice[0] * 100)));
//
//         }
//        if(!trading) {
//            chartScreenshotMessage =
//                    "Looking for entry point..." + "\n" +
//                            "Current POINT: " + Prices.round(pointPrice) + "\n" +
//                            "Current MAX: " + Prices.round(currentMaxPrice[0]) + "\n" +
//                            "Percentage gap: " + percentageGap + "\n" +
//                            "Drop from MAX: " + Prices.round(((pointPrice - currentMaxPrice[0]) / currentMaxPrice[0] * 100));
//        }
//        if (trading&&!isSold) {
//            chartScreenshotMessage =
//                    "TRADING" + "\n" +
//                            "Current POINT: " + Prices.round(pointPrice) + "\n" +
//                            "Price to BUY: " + Prices.round(buyPrice + (buyPrice/100*percentageGap)) + "\n" +
//                            "Price to SELL: " + Prices.round(buyPrice - (buyPrice/100*percentageGap/3)) + "\n" +
//                            "grow is: " + Prices.round(((pointPrice - buyPrice) / buyPrice * 100));
//        }
//        if (trading&&isSold) {
//            chartScreenshotMessage += "\n"+
//                    "Bought for: " + Prices.round(buyPrice) + "\n" +
//                    "Sold for: " + Prices.round(pointPrice) + "\n" +
//                    "grow is: " + Prices.round((pointPrice - buyPrice) / buyPrice * 100);
//        }
        ImageAndMessageSender.sendPhoto(currentPicturePath, chartScreenshotMessage);
        try {
            Files.delete(Path.of(currentPicturePath));
        } catch (IOException e) {
            System.err.println("Error deleting file created for sending to Telegram: " + currentPicturePath);
            throw new RuntimeException(e);
        }

    }
}


