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
import java.util.function.Consumer;

public class ReversalPointStrategyBackTester {
    TreeMap<Double, Double> prices = new TreeMap<>();
    ArrayList<Reversal> reversalArrayList = new ArrayList<>();
    boolean trading = false;
    boolean max = false;
    boolean isSold = false;
    double buyGap = 0;
    double pointPrice = 0;
    double buyPrice = 0;
    double sellWithProfitGap = 0;
    double sellWithLossGap = 0;
    Double[] currentMinPrice = {Double.MAX_VALUE};
    Double[] currentMaxPrice = {0.0};
    Double[] currentMaxPriceTimestamp = {0.0};
    Double[] currentMinPriceTimestamp = {Double.MAX_VALUE};
    String chartScreenshotMessage = "";
    Account account;
    Coin coin;
    Chart chart;
    double tradingSum;
    private final double feeRate;
    private final Consumer<Integer> progressCallback;
    private int totalTrades = 0;
    private int profitableTrades = 0;
    private int losingTrades = 0;
    private double estimatedCommissionUsd = 0;
    private final List<Map<String, Object>> tradeEvents = new ArrayList<>();

    final static Coin USDT;
    BackTestResult backTestResult;

    static {
        try {
            USDT = Coin.createCoin("Tether");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public BackTestResult getBackTestResult() {
        return backTestResult;
    }

    public ReversalPointStrategyBackTester(Coin coin, Chart chart, double tradingSum, double buyGap, double sellWithProfitGap, double sellWithLossGap) {
        this(coin, chart, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap, 0.0, null);
    }

    public ReversalPointStrategyBackTester(Coin coin, Chart chart, double tradingSum, double buyGap, double sellWithProfitGap,
                                           double sellWithLossGap, double feeRate, Consumer<Integer> progressCallback) {
        try {
            this.coin = Coin.createCoin(chart.getCoinName());
            Map<Coin, Double> testAssets = new HashMap<>();
            testAssets.put(USDT, tradingSum);
            testAssets.put(coin, 0d);
            this.account = AccountBuilder.createNewTester(testAssets);
            this.tradingSum = tradingSum;
            this.buyGap = buyGap;
            this.chart = chart;
            this.sellWithProfitGap = sellWithProfitGap;
            this.sellWithLossGap = sellWithLossGap;
            this.feeRate = feeRate;
            this.progressCallback = progressCallback;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public List<Map<String, Object>> getTradeEvents() {
        return new ArrayList<>(tradeEvents);
    }

    public List<double[]> getBacktestChartPoints() {
        return chart.getPrices();
    }

    public class BackTestResult implements Comparable<BackTestResult> {
        double buyGap;
        double sellWithProfitGap;
        double sellWithLossGap;
        double profitInUsd;
        double percentageProfit;
        double estimatedCommissionUsd;
        double profitAfterCommissionUsd;
        int totalTrades;
        int profitableTrades;
        int losingTrades;

        public double getProfitInUsd() { return profitInUsd; }
        public double getBuyGap() { return buyGap; }
        public double getSellWithProfit() { return sellWithProfitGap; }
        public double getSellWithLossGap() { return sellWithLossGap; }
        public double getPercentageProfit() { return percentageProfit; }
        public double getEstimatedCommissionUsd() { return estimatedCommissionUsd; }
        public double getProfitAfterCommissionUsd() { return profitAfterCommissionUsd; }
        public int getTotalTrades() { return totalTrades; }
        public int getProfitableTrades() { return profitableTrades; }
        public int getLosingTrades() { return losingTrades; }

        public BackTestResult(double buyGap, double sellWithProfit, double sellWithLossGap,
                              double profitInUsd, double percentageProfit,
                              double estimatedCommissionUsd, double profitAfterCommissionUsd,
                              int totalTrades, int profitableTrades, int losingTrades) {
            this.buyGap = buyGap;
            this.sellWithProfitGap = sellWithProfit;
            this.sellWithLossGap = sellWithLossGap;
            this.profitInUsd = profitInUsd;
            this.percentageProfit = percentageProfit;
            this.estimatedCommissionUsd = estimatedCommissionUsd;
            this.profitAfterCommissionUsd = profitAfterCommissionUsd;
            this.totalTrades = totalTrades;
            this.profitableTrades = profitableTrades;
            this.losingTrades = losingTrades;
        }

        @Override
        public int compareTo(BackTestResult other) {
            return Double.compare(other.profitAfterCommissionUsd, this.profitAfterCommissionUsd);
        }
    }

    private class Reversal {
        private final double[] data;
        private final String tag;
        Reversal(double[] data, String tag) { this.data = data; this.tag = tag; }
    }

    BiConsumer<Double, Double> findReversalPoints = (timestamp, price) -> {};

    public BackTestResult startBackTest() {
        init(chart.getPrices().get(0)[0], chart.getPrices().get(0)[1]);
        int totalPoints = chart.getPrices().size();
        for (int i = 0; i < totalPoints; i++) {
            try {
                if (!startBackTestingPoint(chart.getPrices().get(i)[0], chart.getPrices().get(i)[1])) return null;
                if (progressCallback != null && (i % 50 == 0 || i == totalPoints - 1)) {
                    progressCallback.accept((int) (((i + 1) * 100.0) / totalPoints));
                }
            } catch (NoSuchSymbolException | InsufficientAmountOfUsdtException e) {
                throw new RuntimeException(e);
            }
        }

        if (account.wallet().getAllAssets().get(coin) != 0) {
            Double usdtInWallet = account.wallet().getAllAssets().get(USDT) +
                    (account.wallet().getAllAssets().get(coin) * chart.getPrices().get(chart.getPrices().size() - 1)[1]);
            account.wallet().getAllAssets().replace(USDT, usdtInWallet);
            account.wallet().getAllAssets().replace(coin, 0d);
        }

        double profitUsd = account.wallet().getAllAssets().get(USDT) - tradingSum;
        double percentageProfit = profitUsd / tradingSum * 100;
        double profitAfterCommission = profitUsd - estimatedCommissionUsd;
        backTestResult = new BackTestResult(
                buyGap, sellWithProfitGap, sellWithLossGap,
                profitUsd, percentageProfit,
                estimatedCommissionUsd, profitAfterCommission,
                totalTrades, profitableTrades, losingTrades
        );
        return backTestResult;
    }

    private void init(double pointTimestamp, double pointPrice) {
        reversalArrayList.add(new Reversal(new double[]{pointTimestamp, pointPrice}, "initPoint"));
    }

    private boolean startBackTestingPoint(double pointTimestamp, double pointPrice) throws NoSuchSymbolException, InsufficientAmountOfUsdtException {
        this.pointPrice = pointPrice;
        prices.put(pointTimestamp, pointPrice);

        if (trading) {
            if (((pointPrice - buyPrice) / buyPrice * 100) > sellWithProfitGap) {
                double coinQty = account.wallet().getAllAssets().get(coin);
                double usdtQty = account.wallet().getAllAssets().get(USDT);
                usdtQty += coinQty * pointPrice;
                estimatedCommissionUsd += (coinQty * buyPrice + coinQty * pointPrice) * feeRate;
                totalTrades++;
                profitableTrades++;
                tradeEvents.add(Map.of("type", "SELL_PROFIT", "timestamp", pointTimestamp, "price", pointPrice));
                account.wallet().getAllAssets().replace(coin, 0d);
                account.wallet().getAllAssets().replace(USDT, usdtQty);
                isSold = true;
                chartScreenshotMessage = "SOLD WITH PROFIT";
                trading = false;
                isSold = false;
                return true;
            }
            if (((buyPrice - pointPrice) / buyPrice * 100) > sellWithLossGap) {
                double coinQty = account.wallet().getAllAssets().get(coin);
                double usdtQty = account.wallet().getAllAssets().get(USDT);
                usdtQty += coinQty * pointPrice;
                estimatedCommissionUsd += (coinQty * buyPrice + coinQty * pointPrice) * feeRate;
                totalTrades++;
                losingTrades++;
                tradeEvents.add(Map.of("type", "SELL_LOSS", "timestamp", pointTimestamp, "price", pointPrice));
                account.wallet().getAllAssets().replace(coin, 0d);
                account.wallet().getAllAssets().replace(USDT, usdtQty);
                isSold = true;
                chartScreenshotMessage = "SOLD WITH LOSS";
                trading = false;
                isSold = false;
                return true;
            }
        }

        if (!prices.isEmpty() && !trading) {
            Reversal previousRec = reversalArrayList.get(reversalArrayList.size() - 1);
            if (pointPrice > currentMaxPrice[0]) {
                max = true;
                currentMaxPrice[0] = pointPrice;
                currentMaxPriceTimestamp[0] = pointTimestamp;
                if (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100) > buyGap) {
                    if (!Objects.equals(previousRec.tag, "min")) {
                        reversalArrayList.add(new Reversal(new double[]{currentMinPriceTimestamp[0], currentMinPrice[0]}, "min"));
                    }
                    currentMinPrice[0] = pointPrice;
                }
            }
            if (pointPrice < currentMinPrice[0]) {
                max = false;
                currentMinPrice[0] = pointPrice;
                currentMinPriceTimestamp[0] = pointTimestamp;
                if (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100) > buyGap) {
                    if (!Objects.equals(previousRec.tag, "max")) {
                        reversalArrayList.add(new Reversal(new double[]{currentMaxPriceTimestamp[0], currentMaxPrice[0]}, "max"));
                        if (!trading) {
                            double coinQty = account.wallet().getAllAssets().get(coin);
                            double usdtQty = account.wallet().getAllAssets().get(USDT);
                            coinQty += account.wallet().getAmountOfCoin(USDT) / pointPrice;
                            usdtQty = 0d;
                            if (usdtQty < 0) return false;
                            account.wallet().getAllAssets().replace(coin, coinQty);
                            account.wallet().getAllAssets().replace(USDT, usdtQty);
                            buyPrice = pointPrice;
                            trading = true;
                            tradeEvents.add(Map.of("type", "BUY", "timestamp", pointTimestamp, "price", pointPrice));
                        }
                    }
                    currentMaxPrice[0] = pointPrice;
                }
            }
        }
        return true;
    }

    private static void clearChart() { TradingChart.clearChart(); }

    public void sendPhotoToTelegram() {
        String currentPicturePath = "" + LocalDateTime.now();
        TradingChart.makeScreenShot(currentPicturePath);
        ImageAndMessageSender.sendPhoto(currentPicturePath, chartScreenshotMessage);
        try {
            Files.delete(Path.of(currentPicturePath));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
