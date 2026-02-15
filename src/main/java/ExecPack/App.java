package ExecPack;

import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.CoinsList;
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointsStrategyTrader;
import ton.dariushkmetsyak.Telegram.MenuHandler;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder;
import ton.dariushkmetsyak.Util.Prices;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class App {

    final static String GECKO_API_KEY = "CG-cbW7FEWxc6mZiUAxSY3VDCd9";
    final static char[] Ed25519_PRIVATE_KEY = "/home/darik/Desktop//Ed PV.pem".toCharArray();
    final static char[] Ed25519_API_KEY = "cPhdnHOtrzMU2fxBnY8zG68H1ZujKCs8oZCn1YBNLPqh98F0aaD2PfWl9HwpXKCo".toCharArray();
    final static char[] TEST_Ed25519_PRIVATE_KEY = "/home/darik/Desktop//test-prv-key.pem".toCharArray();
    final static char[] TEST_Ed25519_API_KEY = "dLlBZX4SsOwXuDioeLWfOFCldwqgwGrIGhGEZdIUWtBCSKsTvqXyl0eYm6lepcAr".toCharArray();
    final static String chatId = "-1002453915115";
    static String telegramPicturePath = "";
    final static String telegramPictureCaption = "Ценушка";


    public static void main(String[] args) throws Exception {


//        Map<Coin, Double> assets = new HashMap<>();
//
//        assets.put(Coin.createCoin("Tether"),100d);
//        Account testAccount = AccountBuilder.createNewTester(assets);
//        Coin bitcoin = Coin.createCoin("Bitcoin");
//        new ReversalPointsStrategyTrader(testAccount,bitcoin,30,3).startResearchingChart(System.currentTimeMillis(), Prices.round(Account.getCurrentPrice(bitcoin)));


        //  TradingChart.makeScreenShot("");


        new MenuHandler().start();
    }
}


























       /*

        CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));
       // CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));
        Chart chart = Chart.loadFromJson(new File("YearlyCharts/Ethereum/Yearlychart.json"));
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                Application.launch(TradingChart.class);
//            }
//        }).start();


        TreeSet<ReversalPointStrategyBackTester.BackTestResult> backTestResults = new TreeSet<>();
        double step = 0.1;
        double startBuyGap = 1; double maxBuyGap = 10;
        double startSellWithProfitGap = 1; double maxSellWithProfitGap = 10;
        double startSellWithLossGap = 1; double maxSellWithLossGap = 10;


        double counter = ((maxSellWithLossGap-startSellWithLossGap)/step)*((maxSellWithProfitGap-startSellWithProfitGap)/step)*((maxBuyGap-startBuyGap)/step);
        for (double buyGap = startBuyGap; buyGap <= maxBuyGap; buyGap += step){
                for (double sellWithProfitGapGap = startSellWithProfitGap; sellWithProfitGapGap <= maxSellWithProfitGap; sellWithProfitGapGap += step){
                    for (double sellWithLossGap = startSellWithLossGap; sellWithLossGap <= maxSellWithLossGap; sellWithLossGap += step){
                    try {
                        backTestResults.add(new ReversalPointStrategyBackTester(Coin.createCoin("Ethereum"), chart, 80, buyGap, sellWithProfitGapGap, sellWithLossGap).startBackTest());
                        if (backTestResults.size()>30) backTestResults.remove(backTestResults.last());
                    } catch (NullPointerException e){}; //подавляем Exception, когда результат = 0
                    System.out.println( counter-- + " iterations left") ;
                }
        }
                }
        System.out.println("Found " +backTestResults.size() + " results");
        for (ReversalPointStrategyBackTester.BackTestResult result:backTestResults){
            ImageAndMessageSender.sendTelegramMessage(result.toString());
            TimeUnit.SECONDS.sleep(3);
        }


        */
































//        ReversalPointsStrategyResearcher.startResearchingChartArray(Path.of("YearlyCharts"));



        //CoinsList.getCoinsWithMarketData_And_SaveToJsonFile(new File("coins"));
//        try {
//            CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//        CoinsList.getCoins().remove(Coin.createCoin("Bitcoin"));
//
//
//        System.out.println(CoinsList.getCoins().size());
//        System.out.println(CoinsList.getCoins());
//        List<Coin> desiredArrayList = new ArrayList<>();
//        for (Coin c: CoinsList.getCoins()){
//            if (    c.getMarket_cap()!=null &&c.getTotal_volume()!=null&&c.getCirculating_supply()!=null&&c.getTotal_supply()!=null
//                    &&
//                    c.getMax_supply().compareTo(c.getCirculating_supply())==0
//                    &&
//                    c.getMarket_cap().compareTo(BigInteger.valueOf(100_000_000))>0 && c.getTotal_volume().compareTo(BigInteger.valueOf(100_000_000))>0){
//                desiredArrayList.add(c);
//            }
//        }
//        System.out.println(desiredArrayList.size());////        System.out.println(desiredArrayList.size());
//        System.out.println(desiredArrayList);
//        File file = new File("YearlyCharts/Solana/Yearlychart.json");
//        File file2 = new File("YearlyCharts/Raydium/Yearlychart.json");
//        File file3 = new File("YearlyCharts/Ethereum/Yearlychart.json");
//        File file4 = new File("YearlyCharts/Fantom/Yearlychart.json");
//        Chart chart = Chart.loadFromJson(file);
//        Chart chart2 = Chart.loadFromJson(file2);
//        Chart chart3 = Chart.loadFromJson(file3);
//        Chart chart4 = Chart.loadFromJson(file4);
//        Chart[] charts = new Chart []{chart,chart2,chart3,chart4};


        //    File file = new File("first");
//    Chart chart1 = Chart.get1DayUntilNowChart_5MinuteInterval(Coin.createCoin("Bitcoin"));
//    chart1.saveToJson(file);
//    Chart chart2 = Chart.loadFromJson(file);
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                ReversalPointsBackTest.startTesting(chart2);
//                String currentPicturePath = telegramPicturePath + LocalDateTime.now();
//                TradingChart.makeScreenShot(currentPicturePath);
//                ImageSender.sendPhoto(chatId, currentPicturePath, telegramPictureCaption);
//                System.exit(0);
//            }
//        }).start();
//        Application.launch(TradingChart.class);

 /*       CoinsList.loadCoinsFromCoinGecko();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Application.launch(TradingChart.class);
            }
        }).start();
//          ReversalPointsStrategyResearcher.startResearchingChartArray(Path.of("YearlyCharts"));
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                while (true) {
//                    try {
//                        TimeUnit.SECONDS.sleep(60);
//                    } catch (InterruptedException e) {
//                        throw new RuntimeException(e);
//                    }
//                    strategyTrader.sendPhotoToTelegram();
//                }
//            }
//        }).start();
        try {

            ReversalPointsStrategyTrader strategyTrader;
            Coin bitcoin = Coin.createCoin("Bitcoin");
            boolean result = false;
            // Chart chart = Chart.loadFromJson(new File("YearlyCharts/" + "Bitcoin" + "/Yearlychart.json"));
            int i = 0;
            Map<Coin, Double> testAssets = new HashMap();
            testAssets.put(Coin.createCoin("Tether"), 150d);
            testAssets.put(Coin.createCoin("Bitcoin"), 0d);
            Account testAccount = AccountBuilder.createNewTester(testAssets);
            while (true) {
                strategyTrader = new ReversalPointsStrategyTrader(testAccount, Coin.createCoin("Bitcoin"), 100, 3);
                strategyTrader.init(System.currentTimeMillis(), Prices.round(Account.getCurrentPrice(bitcoin)));
                result = false;
                while (!result) {
                    //      while (i < chart.getPrices().size()) {
                    TimeUnit.SECONDS.sleep(60);
                    long timestamp = System.currentTimeMillis();
                    double price = Prices.round(Account.getCurrentPrice(bitcoin));
                    result = strategyTrader.startResearchingChart(System.currentTimeMillis(), Prices.round(Account.getCurrentPrice(bitcoin)));
                    i++;
                }
            }
                //  TradingChart.makeScreenShot("");
            } catch(IOException | InterruptedException e){
                throw new RuntimeException(e);
            }
    }
*/



    //  ReversalPointsStrategyResearcher.startResearchingChartArray(Path.of("YearlyCharts"));
    //   System.exit(0);

//        try {
//            CoinsList.loadCoinsWithMarketDataFormJsonFile(new File ("coins"));
//            new Thread(new Runnable() {
//                @Override
//                public void run() {
//                    while (true) {
//                        //  System.out.println("Ping: " + GeckoRequests.testConnectionToGeckoServer());
//                        try {
//                            TimeUnit.SECONDS.sleep(120);
//                        } catch (InterruptedException e) {
//                            throw new RuntimeException(e);
//                        }
//                    }
//                }
//            }).start();
//            for (Coin coin: CoinsList.getCoins()) {
//                System.out.println(Coin.createCoin(coin.getName()));
//                Path path = Paths.get("YearlyCharts/" + coin.getName() + "/Yearlychart.json");
//
//                if (Files.exists(path)) {
//                    System.out.println(path + "already exists.");
//                    continue;
//                }
//                Chart chart = Chart.getYearlyChart_1hourInterval(coin);
//                Files.createDirectories(path.getParent());
//                chart.saveToJson(new File(path.toUri()));
//                System.out.println(coin.getName() + " saved to: " + path);
//            }
////
//        } catch (Exception e) {
//            System.out.println(e.getMessage());
//        }
//
////
////
//            //TimeUnit.SECONDS.sleep(60);
//        }

    //System.out.println(GeckoRequests.getCoinListWithMarketData());
//    File file = new File("first");
//    Chart chart1 = Chart.get1DayUntilNowChart_5MinuteInterval(Coin.createCoin("Bitcoin"));
//    chart1.saveToJson(file);
//    Chart chart2 = Chart.loadFromJson(file);
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                ReversalPointsBackTest.startTesting(chart2);
//                String currentPicturePath = telegramPicturePath + LocalDateTime.now();
//                TradingChart.makeScreenShot(currentPicturePath);
//                ImageSender.sendPhoto(chatId, currentPicturePath, telegramPictureCaption);
//                System.exit(0);
//            }
//        }).start();
//        Application.launch(TradingChart.class);






        /*    Пример использования стратегии (вставляем в main)
      Coin coin = Coin.createCoin("Bitcoin");
        Chart discoveredChart = Chart.get1DayUntilNowChart_5MinuteInterval(coin);
        new Thread(new Runnable() {
            @Override
            public void run() {
                ReversalPointsBackTest.startTesting(discoveredChart);
                String currentPicturePath = telegramPicturePath + LocalDateTime.now();
                TradingChart.makeScreenShot(currentPicturePath);
                ImageSender.sendPhoto(chatId, currentPicturePath, telegramPictureCaption);
                System.exit(0);
            }
        }).start();
        Application.launch(TradingChart.class);
        */
