package ton.dariushkmetsyak.Strategies.AtrEmaStrategy;

import ton.dariushkmetsyak.Charts.Chart;
import ton.dariushkmetsyak.Commission.CommissionCalculator;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfUsdtException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ATR+EMA Adaptive Reversal — BackTester.
 *
 * Same reversal-point core as ReversalPointStrategyBackTester, but with:
 *  - EMA-50 trend filter: buys only when price > EMA (uptrend)
 *  - ATR-adaptive thresholds: buyGap, sellProfit, sellLoss scale with volatility
 *  - Trend-break exit: forced sell if price crosses below EMA while in trade
 */
public class AtrEmaBackTester {

    // ── Strategy parameters ──────────────────────────────────────────────────
    private final double baseBuyGap;
    private final double baseSellProfitGap;
    private final double baseSellLossGap;
    private final int atrPeriod;
    private final int emaPeriod;
    private final int atrSmoothingPeriod;

    // ── ATR/EMA state ────────────────────────────────────────────────────────
    private final List<double[]> ohlcBuffer = new ArrayList<>(); // [timestamp, open, high, low, close]
    private double currentEma = Double.NaN;
    private double currentAtr = Double.NaN;
    private double avgAtr = Double.NaN;
    private final List<Double> atrHistory = new ArrayList<>();
    private double emaMult; // EMA multiplier = 2/(period+1)
    private double prevClose = Double.NaN;

    // ── Reversal-point state ─────────────────────────────────────────────────
    boolean hasPrices = false;
    boolean trading = false;
    boolean max = false;
    double buyGap = 0;
    double sellWithProfitGap = 0;
    double sellWithLossGap = 0;
    double pointPrice = 0;
    double buyPrice = 0;
    Double[] currentMinPrice = {Double.MAX_VALUE};
    Double[] currentMaxPrice = {0.0};
    Double[] currentMaxPriceTimestamp = {0.0};
    Double[] currentMinPriceTimestamp = {Double.MAX_VALUE};

    // ── Account / chart ──────────────────────────────────────────────────────
    Account account;
    Coin coin;
    Chart chart;
    double tradingSum;
    double initialTradingSum;
    boolean recapitalize = false;
    static final Coin USDT;

    // ── Commission ───────────────────────────────────────────────────────────
    private CommissionCalculator commissionCalc = new CommissionCalculator(CommissionCalculator.Exchange.BINANCE);
    private double totalCommission = 0;
    private int winCount = 0;
    private int lossCount = 0;
    private double totalProfitAmount = 0;
    private double totalLossAmount = 0;

    // ── Progress tracking ────────────────────────────────────────────────────
    private final AtomicInteger progressCurrent = new AtomicInteger(0);
    private int progressTotal = 0;

    // ── Trade events for chart ───────────────────────────────────────────────
    private final List<double[]> tradeEvents = new ArrayList<>();
    private final List<double[]> equityCurve = new ArrayList<>();
    private final List<double[]> holdCurve = new ArrayList<>();
    private final List<double[]> buyPoints = new ArrayList<>();
    private final List<double[]> sellProfitPoints = new ArrayList<>();
    private final List<double[]> sellLossPoints = new ArrayList<>();
    private final List<Map<String, Object>> tradeReport = new ArrayList<>();
    private double lastMaxPriceForReport = 0;

    BackTestResult backTestResult;

    static {
        try {
            USDT = Coin.createCoin("Tether");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Constructors ─────────────────────────────────────────────────────────

    public AtrEmaBackTester(Coin coin, Chart chart, double tradingSum,
                            double baseBuyGap, double baseSellProfitGap, double baseSellLossGap,
                            CommissionCalculator commissionCalc, boolean recapitalize) {
        this(coin, chart, tradingSum, baseBuyGap, baseSellProfitGap, baseSellLossGap,
             14, 50, 100, commissionCalc, recapitalize);
    }

    public AtrEmaBackTester(Coin coin, Chart chart, double tradingSum,
                            double baseBuyGap, double baseSellProfitGap, double baseSellLossGap,
                            int atrPeriod, int emaPeriod, int atrSmoothingPeriod,
                            CommissionCalculator commissionCalc, boolean recapitalize) {
        try {
            this.coin = Coin.createCoin(chart.getCoinName());
            Map<Coin, Double> testAssets = new HashMap<>();
            testAssets.put(USDT, tradingSum);
            testAssets.put(coin, 0d);
            this.account = AccountBuilder.createNewTester(testAssets);
            this.tradingSum = tradingSum;
            this.initialTradingSum = tradingSum;
            this.baseBuyGap = baseBuyGap;
            this.baseSellProfitGap = baseSellProfitGap;
            this.baseSellLossGap = baseSellLossGap;
            this.atrPeriod = atrPeriod;
            this.emaPeriod = emaPeriod;
            this.atrSmoothingPeriod = atrSmoothingPeriod;
            this.chart = chart;
            this.commissionCalc = commissionCalc;
            this.recapitalize = recapitalize;
            this.emaMult = 2.0 / (emaPeriod + 1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public BackTestResult getBackTestResult() { return backTestResult; }
    public int getProgressCurrent() { return progressCurrent.get(); }
    public int getProgressTotal() { return progressTotal; }
    public List<double[]> getTradeEvents() { return tradeEvents; }
    public List<double[]> getEquityCurve() { return equityCurve; }
    public List<double[]> getHoldCurve() { return holdCurve; }
    public List<Map<String, Object>> getTradeReport() { return tradeReport; }
    public boolean isRecapitalize() { return recapitalize; }

    // ── ATR / EMA calculation ────────────────────────────────────────────────

    private void updateIndicators(double price) {
        // Build synthetic OHLC candle from single price point
        // For backtesting with price-only data, O=H=L=C=price
        double high = price;
        double low = price;
        double close = price;

        // ATR: True Range = max(high-low, |high-prevClose|, |low-prevClose|)
        if (!Double.isNaN(prevClose)) {
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            if (Double.isNaN(currentAtr)) {
                // Collect initial ATR values
                atrHistory.add(tr);
                if (atrHistory.size() >= atrPeriod) {
                    double sum = 0;
                    for (double v : atrHistory) sum += v;
                    currentAtr = sum / atrPeriod;
                }
            } else {
                // Wilder's smoothed ATR
                currentAtr = (currentAtr * (atrPeriod - 1) + tr) / atrPeriod;
            }
            if (!Double.isNaN(currentAtr)) {
                atrHistory.add(currentAtr);
                // Average ATR over smoothing period
                if (atrHistory.size() >= atrSmoothingPeriod) {
                    double sum = 0;
                    int count = 0;
                    for (int i = atrHistory.size() - atrSmoothingPeriod; i < atrHistory.size(); i++) {
                        sum += atrHistory.get(i);
                        count++;
                    }
                    avgAtr = sum / count;
                }
            }
        }
        prevClose = close;

        // EMA
        if (Double.isNaN(currentEma)) {
            // Seed: collect first emaPeriod prices for SMA
            ohlcBuffer.add(new double[]{0, 0, 0, 0, close});
            if (ohlcBuffer.size() >= emaPeriod) {
                double sum = 0;
                for (int i = ohlcBuffer.size() - emaPeriod; i < ohlcBuffer.size(); i++) {
                    sum += ohlcBuffer.get(i)[4];
                }
                currentEma = sum / emaPeriod;
            }
        } else {
            currentEma = (close - currentEma) * emaMult + currentEma;
        }
    }

    private double getAtrMultiplier() {
        if (Double.isNaN(currentAtr) || Double.isNaN(avgAtr) || avgAtr <= 0) return 1.0;
        double mult = currentAtr / avgAtr;
        // Clamp to [0.5, 2.0] to prevent extreme adjustments
        return Math.max(0.5, Math.min(2.0, mult));
    }

    private void updateAdaptiveGaps() {
        double mult = getAtrMultiplier();
        this.buyGap = baseBuyGap * mult;
        this.sellWithProfitGap = baseSellProfitGap * mult;
        this.sellWithLossGap = baseSellLossGap * mult;
    }

    private boolean isAboveEma(double price) {
        // Don't allow buys until EMA is initialized (needs ~50 data points)
        if (Double.isNaN(currentEma)) return false;
        return price > currentEma;
    }

    private boolean isBelowEma(double price) {
        return !Double.isNaN(currentEma) && price < currentEma;
    }

    // ── Equity tracking ──────────────────────────────────────────────────────

    private void recordEquity(double timestamp, double currentPrice) {
        double usdt = account.wallet().getAllAssets().get(USDT);
        double coinAmt = account.wallet().getAllAssets().get(coin);
        double equity = usdt + coinAmt * currentPrice - initialTradingSum;
        equityCurve.add(new double[]{timestamp, equity});
    }

    // ── BackTest entry point ─────────────────────────────────────────────────

    public BackTestResult startBackTest() {
        progressTotal = chart.getPrices().size();
        progressCurrent.set(0);
        equityCurve.add(new double[]{chart.getPrices().get(0)[0], 0.0});

        // Initialize reversal array
        double[] first = chart.getPrices().get(0);
        init(first[0], first[1]);

        boolean earlyTermination = false;
        int lastProcessedIndex = chart.getPrices().size() - 1;

        for (int i = 0; i < chart.getPrices().size(); i++) {
            try {
                progressCurrent.set(i + 1);
                if (!processPoint(chart.getPrices().get(i)[0], chart.getPrices().get(i)[1])) {
                    if (recapitalize) { earlyTermination = true; lastProcessedIndex = i; break; }
                    return null;
                }
            } catch (NoSuchSymbolException | InsufficientAmountOfUsdtException e) {
                throw new RuntimeException(e);
            }
        }

        // Close open position at end
        if (account.wallet().getAllAssets().get(coin) != 0) {
            Double USDTinWallet = account.wallet().getAllAssets().get(USDT)
                    + (account.wallet().getAllAssets().get(coin) * chart.getPrices().get(lastProcessedIndex)[1]);
            account.wallet().getAllAssets().replace(USDT, USDTinWallet);
            account.wallet().getAllAssets().replace(coin, 0d);
        }

        double lastTs = chart.getPrices().get(lastProcessedIndex)[0];
        double lastPrice = chart.getPrices().get(lastProcessedIndex)[1];
        recordEquity(lastTs, lastPrice);

        // Build hold curve
        double firstPrice = chart.getPrices().get(0)[1];
        List<double[]> prices = chart.getPrices();
        int sampleEnd = earlyTermination ? lastProcessedIndex + 1 : prices.size();
        int step = Math.max(1, sampleEnd / 500);
        for (int hi = 0; hi < sampleEnd; hi += step) {
            double ts = prices.get(hi)[0];
            double pr = prices.get(hi)[1];
            double holdProfit = (pr - firstPrice) / firstPrice * initialTradingSum;
            holdCurve.add(new double[]{ts, holdProfit});
        }
        if (holdCurve.isEmpty() || holdCurve.get(holdCurve.size() - 1)[0] != lastTs) {
            holdCurve.add(new double[]{lastTs, (lastPrice - firstPrice) / firstPrice * initialTradingSum});
        }

        // Cleanup
        ohlcBuffer.clear();

        double finalUsdt = account.wallet().getAllAssets().get(USDT);
        double profitInUsd = finalUsdt - initialTradingSum;
        double percentageProfit = profitInUsd / initialTradingSum * 100;
        backTestResult = new BackTestResult(baseBuyGap, baseSellProfitGap, baseSellLossGap,
                profitInUsd, percentageProfit, totalCommission,
                winCount, lossCount, totalProfitAmount, totalLossAmount,
                atrPeriod, emaPeriod);
        backTestResult.earlyTermination = earlyTermination;
        return backTestResult;
    }

    private void init(double pointTimestamp, double pointPrice) {
        currentMinPrice[0] = pointPrice;
        currentMaxPrice[0] = pointPrice;
        currentMinPriceTimestamp[0] = pointTimestamp;
        currentMaxPriceTimestamp[0] = pointTimestamp;
    }

    private boolean processPoint(double pointTimestamp, double pointPrice)
            throws NoSuchSymbolException, InsufficientAmountOfUsdtException {

        this.pointPrice = pointPrice;
        hasPrices = true;

        // Update ATR and EMA indicators
        updateIndicators(pointPrice);
        updateAdaptiveGaps();

        // ── SELL logic ───────────────────────────────────────────────────────
        if (trading) {
            // Trend-break exit: price crossed below EMA
            boolean trendBreak = isBelowEma(pointPrice) && !Double.isNaN(currentEma);

            // Profit target
            if (((pointPrice - buyPrice) / buyPrice * 100) > sellWithProfitGap) {
                return executeSell(pointTimestamp, pointPrice, true);
            }
            // Stop loss OR trend break
            if (((buyPrice - pointPrice) / buyPrice * 100) > sellWithLossGap || trendBreak) {
                boolean isLoss = pointPrice < buyPrice;
                return executeSell(pointTimestamp, pointPrice, !isLoss);
            }
        }

        // ── BUY logic (reversal detection + EMA filter) ──────────────────────
        if (hasPrices && !trading) {
            if (pointPrice > currentMaxPrice[0]) {
                max = true;
                currentMaxPrice[0] = pointPrice;
                currentMaxPriceTimestamp[0] = pointTimestamp;
                // When price makes a new high and previous drop was > buyGap, reset min
                if (getDropFromMaxPercent(currentMinPrice[0]) > buyGap) {
                    currentMinPrice[0] = pointPrice;
                }
            }
            if (pointPrice < currentMinPrice[0]) {
                max = false;
                currentMinPrice[0] = pointPrice;
                currentMinPriceTimestamp[0] = pointTimestamp;
                // Buy only at new local minimum when drop from max exceeds buyGap AND price is above EMA
                if (getDropFromMaxPercent(pointPrice) > buyGap && isAboveEma(pointPrice)) {
                    return executeBuy(pointTimestamp, pointPrice);
                }
            }
        }

        return true;
    }

    private double getDropPercent() {
        if (currentMaxPrice[0] == null || currentMaxPrice[0] <= 0) return 0;
        return 100 - (currentMinPrice[0] / currentMaxPrice[0] * 100);
    }

    private double getDropFromMaxPercent(double price) {
        if (currentMaxPrice[0] == null || currentMaxPrice[0] <= 0) return 0;
        return 100 - (price / currentMaxPrice[0] * 100);
    }

    private boolean executeBuy(double ts, double price)
            throws NoSuchSymbolException, InsufficientAmountOfUsdtException {
        Double coinQty = account.wallet().getAllAssets().get(coin);
        Double usdtQty = account.wallet().getAllAssets().get(USDT);
        double spendAmount = Math.min(tradingSum, usdtQty);
        double buyQty = spendAmount / price;
        coinQty += buyQty;
        usdtQty -= spendAmount;
        if (usdtQty < 0) return false;
        account.wallet().getAllAssets().replace(coin, coinQty);
        account.wallet().getAllAssets().replace(USDT, usdtQty);
        buyPoints.add(new double[]{ts, price});
        buyPrice = price;
        trading = true;
        tradeEvents.add(new double[]{ts, price, 0});
        recordEquity(ts, price);

        // Trade report
        double dropFromMax = currentMaxPrice[0] > 0 ? ((currentMaxPrice[0] - price) / currentMaxPrice[0] * 100) : 0;
        double buyComm = commissionCalc.calcCommission(spendAmount);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("type", "BUY");
        rec.put("timestamp", (long) ts);
        rec.put("quantity", buyQty);
        rec.put("price", price);
        rec.put("totalUsdt", spendAmount);
        rec.put("maxPrice", currentMaxPrice[0]);
        rec.put("dropFromMaxPct", dropFromMax);
        rec.put("adaptiveBuyGap", buyGap);
        rec.put("ema", currentEma);
        rec.put("atrMult", getAtrMultiplier());
        rec.put("commission", buyComm);
        rec.put("walletUsdt", usdtQty);
        rec.put("walletCoin", coinQty);
        tradeReport.add(rec);

        currentMaxPrice[0] = price;
        return true;
    }

    private boolean executeSell(double ts, double price, boolean isProfit) {
        Double coinQty = account.wallet().getAllAssets().get(coin);
        Double usdtQty = account.wallet().getAllAssets().get(USDT);
        double sellValue = coinQty * price;
        double commBuy = commissionCalc.calcCommission(buyPrice * coinQty);
        double commSell = commissionCalc.calcCommission(sellValue);
        totalCommission += commBuy + commSell;
        double pnl = (price - buyPrice) * coinQty;

        if (isProfit) {
            winCount++;
            totalProfitAmount += pnl;
            sellProfitPoints.add(new double[]{ts, price});
            tradeEvents.add(new double[]{ts, price, 1});
        } else {
            lossCount++;
            totalLossAmount += Math.abs(pnl);
            sellLossPoints.add(new double[]{ts, price});
            tradeEvents.add(new double[]{ts, price, 2});
        }

        usdtQty += sellValue;
        account.wallet().getAllAssets().replace(coin, 0d);
        account.wallet().getAllAssets().replace(USDT, usdtQty);
        recordEquity(ts, price);

        // Trade report
        double changePct = buyPrice > 0 ? ((price - buyPrice) / buyPrice * 100) : 0;
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("type", isProfit ? "SELL_PROFIT" : "SELL_LOSS");
        rec.put("timestamp", (long) ts);
        rec.put("quantity", coinQty);
        rec.put("price", price);
        rec.put("totalUsdt", sellValue);
        rec.put("changeUsdt", pnl);
        rec.put("changePct", changePct);
        rec.put("commission", commBuy + commSell);
        rec.put("adaptiveSellGap", isProfit ? sellWithProfitGap : sellWithLossGap);
        rec.put("ema", currentEma);
        rec.put("atrMult", getAtrMultiplier());
        rec.put("walletUsdt", usdtQty);
        rec.put("walletCoin", 0.0);
        tradeReport.add(rec);

        if (recapitalize) {
            tradingSum = account.wallet().getAllAssets().get(USDT);
            if (tradingSum < 0.01) return false;
        }

        trading = false;
        currentMinPrice[0] = price;
        currentMaxPrice[0] = price;
        return true;
    }

    // ── Chart image generation ───────────────────────────────────────────────

    public String generateChartImage() {
        TradingChart.clearChart();
        for (double[] p : chart.getPrices()) TradingChart.addSimplePoint(p[0], p[1]);
        for (double[] p : buyPoints) TradingChart.addBuyIntervalMarker(p[0], p[1]);
        for (double[] p : sellProfitPoints) TradingChart.addSellProfitMarker(p[0], p[1]);
        for (double[] p : sellLossPoints) TradingChart.addSellLossMarker(p[0], p[1]);
        try {
            String path = "backtest_atrema_" + coin.getName() + "_" + System.currentTimeMillis() + ".png";
            TradingChart.makeScreenShot(path);
            return path;
        } catch (Exception e) {
            return null;
        }
    }

    // ── BackTestResult ───────────────────────────────────────────────────────

    public class BackTestResult implements Comparable<BackTestResult> {
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
        int atrPeriod;
        int emaPeriod;
        boolean earlyTermination = false;

        public double getProfitInUsd() { return profitInUsd; }
        public double getBuyGap() { return buyGap; }
        public double getSellWithProfit() { return sellWithProfitGap; }
        public double getSellWithLossGap() { return sellWithLossGap; }
        public double getPercentageProfit() { return percentageProfit; }
        public double getTotalCommission() { return totalCommission; }
        public double getProfitAfterCommission() { return profitAfterCommission; }
        public double getProfitInUsdAfterCommission() { return profitAfterCommission; }
        public double getPercentageProfitAfterCommission() {
            return percentageProfit != 0 && profitInUsd != 0 ? profitAfterCommission / profitInUsd * percentageProfit : 0;
        }
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
        public String getExchangeName() { return commissionCalc.getExchange().getDisplayName(); }
        public double getCommissionRate() { return commissionCalc.getFeePercent(); }
        public boolean isEarlyTermination() { return earlyTermination; }
        public int getAtrPeriod() { return atrPeriod; }
        public int getEmaPeriod() { return emaPeriod; }

        @Override
        public String toString() {
            return coin.getName() + " [ATR+EMA]\n" +
                    "Сделок: " + getTotalTrades() + " (✅" + winCount + " / ❌" + lossCount + ")\n" +
                    "Прибыль: $" + String.format("%.2f", profitInUsd) + "\n" +
                    "Прибыль в %: " + String.format("%.2f", percentageProfit) + "%\n" +
                    "Комиссия: $" + String.format("%.4f", totalCommission) + "\n" +
                    "Прибыль с комиссией: $" + String.format("%.2f", profitAfterCommission) + "\n" +
                    "Баз. коэфф. покупки: " + String.format("%.1f", buyGap) + "%\n" +
                    "Баз. коэфф. продажи+: " + String.format("%.1f", sellWithProfitGap) + "%\n" +
                    "Баз. коэфф. продажи-: " + String.format("%.1f", sellWithLossGap) + "%\n" +
                    "ATR период: " + atrPeriod + " | EMA период: " + emaPeriod + "\n";
        }

        public BackTestResult(double buyGap, double sellWithProfitGap, double sellWithLossGap,
                             double profitInUsd, double percentageProfit, double totalCommission,
                             int winCount, int lossCount, double totalProfitAmount, double totalLossAmount,
                             int atrPeriod, int emaPeriod) {
            this.buyGap = buyGap;
            this.sellWithProfitGap = sellWithProfitGap;
            this.sellWithLossGap = sellWithLossGap;
            this.profitInUsd = profitInUsd;
            this.percentageProfit = percentageProfit;
            this.totalCommission = totalCommission;
            this.profitAfterCommission = profitInUsd - totalCommission;
            this.winCount = winCount;
            this.lossCount = lossCount;
            this.totalProfitAmount = totalProfitAmount;
            this.totalLossAmount = totalLossAmount;
            this.atrPeriod = atrPeriod;
            this.emaPeriod = emaPeriod;
        }

        @Override
        public int compareTo(BackTestResult other) {
            int cmp = Double.compare(other.profitAfterCommission, this.profitAfterCommission);
            if (cmp != 0) return cmp;
            // Break ties by parameters so TreeSet doesn't treat different strategies as duplicates
            cmp = Double.compare(this.buyGap, other.buyGap);
            if (cmp != 0) return cmp;
            cmp = Double.compare(this.sellWithProfitGap, other.sellWithProfitGap);
            if (cmp != 0) return cmp;
            return Double.compare(this.sellWithLossGap, other.sellWithLossGap);
        }
    }
}
