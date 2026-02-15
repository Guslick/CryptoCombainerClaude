package ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy;


import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfCoinException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfUsdtException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;
import ton.dariushkmetsyak.Util.Prices;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class ReversalPointsStrategyTrader {
    TreeMap<Double, Double> prices = new TreeMap<>();
    ArrayList<Reversal> reversalArrayList = new ArrayList<>();
    boolean trading = false;
    boolean max = false;
    boolean isSold = false;
  //  double percentageGap = 0;
    double buyGap = 0;
    double sellWithProfitGap = 0;
    double sellWithLossGap = 0;

    double pointPrice=0;
    double buyPrice=0;
     Double[] currentMinPrice = {Double.MAX_VALUE};
     Double[] currentMaxPrice = {0.0};
     Double[] currentMaxPriceTimestamp = {0.0};
     Double[] currentMinPriceTimestamp = {Double.MAX_VALUE};
     String chartScreenshotMessage ="";
     Account account;
     Coin coin;
     double tradingSum;
     int updateTimeout;
     Long chatID;
     int prevMessageId=0;


    public ReversalPointsStrategyTrader(Account account, Coin coin, double tradingSum, double percentageGap, int updateTimeout, Long chatID) {
        this.account=account;
        this.coin=coin;
        this.tradingSum=tradingSum;
       // this.percentageGap = percentageGap;
        ImageAndMessageSender.sendTelegramMessage("Баланс кошелька: "+ account.wallet().getAllAssets().toString(), chatID);
        this.updateTimeout=updateTimeout;
        this.chatID=chatID;
    }
    public ReversalPointsStrategyTrader(Account account, Coin coin, double tradingSum, double buyGap, double sellWithProfitGap, double sellWithLossGap, int updateTimeout, Long chatID) {
        this.account=account;
        this.coin=coin;
        this.tradingSum=tradingSum;
        this.buyGap = buyGap;
        this.sellWithProfitGap=sellWithProfitGap;
        this.sellWithLossGap=sellWithLossGap;
        ImageAndMessageSender.sendTelegramMessage("Баланс кошелька: "+ account.wallet().getAllAssets().toString(), chatID);
        this.updateTimeout=updateTimeout;
        this.chatID=chatID;

    }

    class Reversal {
        private final double[] data;
        private final String tag;

        Reversal(double[] data, String tag) {
            this.data = data;
            this.tag = tag;
        }
    }

    BiConsumer<Double, Double> findReversalPoints = (timestamp, price) -> {

    };
    public void init (double pointTimestamp, double pointPrice){
        reversalArrayList.add(new Reversal(new double[]{pointTimestamp, pointPrice}, "initPoint"));
    }
    public  boolean startResearchingChart(double pointTimestamp, double pointPrice) throws NoSuchSymbolException, InsufficientAmountOfUsdtException {

        //   System.out.println("Trading "+ trading);
        this.pointPrice = pointPrice;
        prices.put(pointTimestamp, pointPrice);
        TradingChart.addSimplePoint(pointTimestamp, pointPrice);
        TradingChart.addSimplePriceMarker(pointTimestamp, pointPrice);
        //gap=gap+(100 - (currentMinPrice[0] / currentMaxPrice[0] * 100));
        if (trading) {
//                System.out.println(trading);
            //  TradingChart.addSimplePriceMarker(pointTimestamp,pointPrice);
            //   gap = "/n grow is: "+ (pointPrice-buyPrice)/buyPrice*100;
            if (((pointPrice - buyPrice) / buyPrice * 100) > sellWithProfitGap) {
                System.out.println("Timestamp: " + pointTimestamp + "  Price: " + pointPrice);
                try {
                    account.trader().sell(coin,pointPrice,tradingSum/buyPrice);
                } catch (InsufficientAmountOfCoinException e) {
                    //доработать;
                    throw new RuntimeException(e);
                }
                TradingChart.addSellIntervalMarker(pointTimestamp, pointPrice);
                isSold =true;
                chartScreenshotMessage = "Sold with PROFIT";
//                if (prevMessageId!=0) ImageAndMessageSender.deleteMessage(prevMessageId);
//                try {
//                    TimeUnit.SECONDS.sleep(1);
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                }
                sendPhotoToTelegram();
                prevMessageId=0;
                TradingChart.clearChart();
                ImageAndMessageSender.sendTelegramMessage("Баланс кошелька: "+ account.wallet().getAllAssets().toString());
                trading = false;
                isSold=false;
                return true;
            }
            if (((buyPrice - pointPrice) / buyPrice * 100) > sellWithLossGap) {
                System.out.println("Timestamp: " + pointTimestamp + "  Price: " + pointPrice);
                try {
                    account.trader().sell(coin,pointPrice,tradingSum/buyPrice);

                } catch (InsufficientAmountOfCoinException e) {
                    //доработать;
                    throw new RuntimeException(e);
                }
                TradingChart.addSellIntervalMarker(pointTimestamp, pointPrice);
                isSold=true;
                chartScreenshotMessage = "Sold with LOSS";
//                if (prevMessageId!=0) ImageAndMessageSender.deleteMessage(prevMessageId);
//                try {
//                    TimeUnit.SECONDS.sleep(1);
//                } catch (InterruptedException e) {
//                    throw new RuntimeException(e);
//                }
                sendPhotoToTelegram();
                prevMessageId=0;
                TradingChart.clearChart();
                ImageAndMessageSender.sendTelegramMessage("Баланс кошелька: "+ account.wallet().getAllAssets().toString());
                trading = false;
                isSold=false;
                return true;
            }
        }
        if (!prices.isEmpty() && !trading) {

            Reversal previousRec = reversalArrayList.get(reversalArrayList.toArray().length - 1);
            //  System.out.println("finding reversals");
            if (pointPrice > currentMaxPrice[0]) {
                max=true;
                currentMaxPrice[0] = pointPrice;
                currentMaxPriceTimestamp[0] = pointTimestamp;
                // gap="+"+ (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100));
                if (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100) > buyGap) {
                    if (!Objects.equals(previousRec.tag, "min")) {
                        Reversal r = new Reversal(new double[]{currentMinPriceTimestamp[0], currentMinPrice[0]}, "min");
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
//                        Reversal r = new Reversal(new double[]{currentMaxPriceTimestamp[0], currentMaxPrice[0]}, "max");
//                        reversalArrayList.add(r);
                        if (!trading){
                            if(account.trader().buy(coin,pointPrice,tradingSum/pointPrice)) {
                                TradingChart.addBuyIntervalMarker(pointTimestamp, pointPrice);
                                Reversal r = new Reversal(new double[]{currentMaxPriceTimestamp[0], currentMaxPrice[0]}, "max");
                                reversalArrayList.add(r);
                                buyPrice = pointPrice;
                                trading = true;
                                ImageAndMessageSender.sendTelegramMessage("Баланс кошелька: "+ account.wallet().getAllAssets().toString());
                                currentMaxPrice[0] = pointPrice;
                            }
                            ;
                           // sendPhotoToTelegram();
                        }
                    }


                }
            }
        }
//        if (prevMessageId!=0) ImageAndMessageSender.deleteMessage(prevMessageId);
//        try {
//            TimeUnit.SECONDS.sleep(1);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
         sendPhotoToTelegram();
        return false;
    }



    private static void clearChart (){
        TradingChart.clearChart();
    }


    public  void startTrading() {

        ReversalPointsStrategyTrader strategyTrader;

        int i=0;
        boolean result = false;

        while (true) {
//            strategyTrader = new ReversalPointsStrategyTrader(account, coin,  tradingSum, percentageGap, chatID);
            try {
                this.init(System.currentTimeMillis(), Prices.round(Account.getCurrentPrice(coin)));
            } catch (NoSuchSymbolException e) {
                throw new RuntimeException(e);
            }
            result = false;
            while (!result) {
                //      while (i < chart.getPrices().size()) {
                try {
                TimeUnit.SECONDS.sleep(updateTimeout);
                long timestamp = System.currentTimeMillis();
                double price = Prices.round(Account.getCurrentPrice(coin));

                    result = this.startResearchingChart(System.currentTimeMillis(), Prices.round(Account.getCurrentPrice(coin)));
                } catch (NoSuchSymbolException | InsufficientAmountOfUsdtException | InterruptedException e) {
                    ImageAndMessageSender.sendTelegramMessage(e.getMessage());
                } catch (NullPointerException e){
                    System.out.println("NULLPOINTER!!!");
                }
                i++;
            }


        }
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
//         if(prevMessageId!=0) ImageAndMessageSender.dele          teMessage(prevMessageId);
         if(!trading) {
             chartScreenshotMessage =
                             "Looking for entry point..." + "\n" +
                             "Current POINT: " + Prices.round(pointPrice) + "\n" +
                             "Current MAX: " + Prices.round(currentMaxPrice[0]) + "\n" +
                             "Buy gap: " + buyGap + "\n" +
                             "Drop from MAX: " + Prices.round(((pointPrice - currentMaxPrice[0]) / currentMaxPrice[0] * 100)) + "\n" +
                             "Assets: " + account.wallet().getAllAssets().toString();
             System.out.println(Prices.round(((pointPrice - currentMaxPrice[0]) / currentMaxPrice[0] * 100)));
         }
         if (trading&&!isSold) {
             chartScreenshotMessage =
                            "TRADING" + "\n" +
                             "Current POINT: " + Prices.round(pointPrice) + "\n" +
                              "Price to BUY: " + Prices.round(buyPrice + (buyPrice/100*sellWithProfitGap)) + "\n" +
                              "Price to SELL: " + Prices.round(buyPrice - (buyPrice/100*sellWithLossGap)) + "\n" +
                              "grow is: " + Prices.round(((pointPrice - buyPrice) / buyPrice * 100)) + "\n" +
                                    "Assets: " + account.wallet().getAllAssets().toString();
         }
         if (trading&&isSold) {
             chartScreenshotMessage += "\n"+
                            "Bought for: " + Prices.round(buyPrice) + "\n" +
                             "Sold for: " + Prices.round(pointPrice) + "\n" +
                             "grow is: " + Prices.round((pointPrice - buyPrice) / buyPrice * 100) + "\n" +
                     "Assets: " + account.wallet().getAllAssets().toString();
         }
        prevMessageId = ImageAndMessageSender.sendPhoto(currentPicturePath, chartScreenshotMessage);
        try {
            Files.delete(Path.of(currentPicturePath));
        } catch (IOException e) {
            System.err.println("Error deleting file created for sending to Telegram: " + currentPicturePath);
            throw new RuntimeException(e);
        }
    }
}

