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

        double profitInUsd;
        double percentageProfit;

        @Override
        public String toString() {
            return coin.getName() +  "\n" +
                    "Assets: " + account.wallet().getAllAssets()+ "\n" +
                    "Profit in USD: " + (account.wallet().getBalance() - 100) +"\n" +
                    "Profit in %: " + (account.wallet().getBalance() - 100)/100*100 + "\n" +
                    "Buy gap: " + buyGap + "\n" +
                    "Sell with profit gap: " + sellWithProfitGap + "\n" +
                    "Sell with loss gap: " + sellWithLossGap + "\n";
        }

        public BackTestResult(double buyGap, double sellWithProfit, double sellWithLossGap, double profitInUsd, double percentageProfit) {
            this.buyGap = buyGap;
            this.sellWithProfitGap = sellWithProfit;
            this.sellWithLossGap = sellWithLossGap;
            this.profitInUsd = profitInUsd;
            this.percentageProfit = percentageProfit;
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
        backTestResult = new BackTestResult(buyGap, sellWithProfitGap, sellWithLossGap, (account.wallet().getAllAssets().get(USDT) - tradingSum),(account.wallet().getAllAssets().get(USDT) - tradingSum)/tradingSum*100);
      //  ImageAndMessageSender.sendTelegramMessage(backTestResult.toString());
      //  clearChart();
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
                    UsdtQuantityInWallet+=coinQuantityInWallet*pointPrice;
                    coinQuantityInWallet=0d;
                    account.wallet().getAllAssets().replace(coin, coinQuantityInWallet);
                    account.wallet().getAllAssets().replace(USDT,UsdtQuantityInWallet);

//                TradingChart.addSellIntervalMarker(pointTimestamp, pointPrice);
                isSold =true;
                chartScreenshotMessage = "SOLD WITH PROFIT";
             //   System.out.println("PROFIT " + account.wallet().getBalance()+ " PRICE: " + pointPrice);

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
            if (((buyPrice - pointPrice) / buyPrice * 100) > sellWithLossGap) {


                //account.trader().sell(coin,pointPrice,tradingSum/buyPrice);
                    Double coinQuantityInWallet = account.wallet().getAllAssets().get(coin);
                    Double UsdtQuantityInWallet = account.wallet().getAllAssets().get(USDT);
                    UsdtQuantityInWallet+=coinQuantityInWallet*pointPrice;
                    coinQuantityInWallet=0d;
                    account.wallet().getAllAssets().replace(coin, coinQuantityInWallet);
                    account.wallet().getAllAssets().replace(USDT,UsdtQuantityInWallet);

//                TradingChart.addSellIntervalMarker(pointTimestamp, pointPrice);
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
//                                TradingChart.addBuyIntervalMarker(pointTimestamp, pointPrice);
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


