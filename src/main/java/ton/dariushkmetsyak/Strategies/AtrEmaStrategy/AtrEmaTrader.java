package ton.dariushkmetsyak.Strategies.AtrEmaStrategy;

import com.binance.connector.client.exceptions.BinanceConnectorException;
import ton.dariushkmetsyak.ErrorHandling.ErrorHandler;
import ton.dariushkmetsyak.ErrorHandling.RetryPolicy;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart;
import ton.dariushkmetsyak.Persistence.StateManager;
import ton.dariushkmetsyak.Persistence.TradingState;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;
import ton.dariushkmetsyak.Commission.CommissionCalculator;
import ton.dariushkmetsyak.Commission.TradeStatistics;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfCoinException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfUsdtException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;
import ton.dariushkmetsyak.Util.Prices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * ATR+EMA Adaptive Reversal Trader.
 * Same reversal-point core but with:
 *  - EMA trend filter (buy only above EMA)
 *  - ATR-adaptive thresholds
 *  - Trend-break forced exit
 */
public class AtrEmaTrader {
    private static final Logger log = LoggerFactory.getLogger(AtrEmaTrader.class);

    private static final Set<AtrEmaTrader> activeTraders = Collections.synchronizedSet(new HashSet<>());
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[AtrEmaTrader] JVM shutdown — saving state for {} active trader(s)...", activeTraders.size());
            synchronized (activeTraders) {
                for (AtrEmaTrader trader : activeTraders) {
                    try { trader.persistState(); trader.stateManager.shutdown(); }
                    catch (Exception e) { log.error("[AtrEmaTrader] Failed to save state on shutdown", e); }
                }
            }
        }, "AtrEma-ShutdownHook"));
    }

    // ── Strategy parameters ──
    private final double baseBuyGap;
    private final double baseSellProfitGap;
    private final double baseSellLossGap;
    private final int atrPeriod;
    private final int emaPeriod;
    private final int atrSmoothingPeriod;

    // ── Adaptive gaps (recalculated each tick) ──
    private double buyGap;
    private double sellWithProfitGap;
    private double sellWithLossGap;

    // ── ATR/EMA state ──
    private double currentEma = Double.NaN;
    private double currentAtr = Double.NaN;
    private double avgAtr = Double.NaN;
    private final List<Double> atrHistory = new ArrayList<>();
    private int emaSeedCount = 0;
    private double emaSeedSum = 0;
    private double emaMult;
    private double prevClose = Double.NaN;

    // ── Reversal state ──
    TreeMap<Double, Double> prices = new TreeMap<>();
    boolean trading = false;
    boolean max = false;
    double pointPrice = 0;
    double buyPrice = 0;
    Double boughtFor = null;
    Double soldFor = null;
    Double[] currentMinPrice = {Double.MAX_VALUE};
    Double[] currentMaxPrice = {0.0};
    Double[] currentMaxPriceTimestamp = {0.0};
    Double[] currentMinPriceTimestamp = {Double.MAX_VALUE};

    // ── Account ──
    Account account;
    Coin coin;
    double tradingSum;
    double initialTradingSum;
    boolean recapitalize = false;
    int updateTimeout;
    Long chatID;
    int prevMessageId = 0;

    private boolean restoredFromState = false;
    private boolean isResume = false;
    private final StateManager stateManager;
    private final String sessionId;
    private final String accountType;
    private int tickCounter = 0;
    private long lastBuyFailureNotificationAt = 0;
    private String lastBuyFailureSignature = "";
    private static final long BUY_FAILURE_NOTIFICATION_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(5);

    private final CommissionCalculator commissionCalc = new CommissionCalculator(CommissionCalculator.Exchange.BINANCE);
    private final TradeStatistics tradeStats = new TradeStatistics();
    private boolean isTesterAccount = false;

    // ── Constructor ──

    public AtrEmaTrader(Account account, Coin coin, double tradingSum,
                        double baseBuyGap, double baseSellProfitGap, double baseSellLossGap,
                        int updateTimeout, Long chatID, TradingState savedState,
                        String sessionId, boolean isResume, long storageUserId, boolean recapitalize) {
        this(account, coin, tradingSum, baseBuyGap, baseSellProfitGap, baseSellLossGap,
             14, 50, 100, updateTimeout, chatID, savedState, sessionId, isResume, storageUserId, recapitalize);
    }

    public AtrEmaTrader(Account account, Coin coin, double tradingSum,
                        double baseBuyGap, double baseSellProfitGap, double baseSellLossGap,
                        int atrPeriod, int emaPeriod, int atrSmoothingPeriod,
                        int updateTimeout, Long chatID, TradingState savedState,
                        String sessionId, boolean isResume, long storageUserId, boolean recapitalize) {
        this.account = account;
        this.coin = coin;
        this.recapitalize = recapitalize;
        this.chatID = chatID;
        this.sessionId = sessionId != null ? sessionId : "atrema_" + System.currentTimeMillis();
        this.accountType = account.getClass().getSimpleName().toUpperCase();
        this.isTesterAccount = accountType.contains("TESTER");
        this.baseBuyGap = baseBuyGap;
        this.baseSellProfitGap = baseSellProfitGap;
        this.baseSellLossGap = baseSellLossGap;
        this.buyGap = baseBuyGap;
        this.sellWithProfitGap = baseSellProfitGap;
        this.sellWithLossGap = baseSellLossGap;
        this.atrPeriod = atrPeriod;
        this.emaPeriod = emaPeriod;
        this.atrSmoothingPeriod = atrSmoothingPeriod;
        this.emaMult = 2.0 / (emaPeriod + 1);

        long stateUserId = storageUserId > 0 ? storageUserId : (chatID != null ? chatID : 0L);
        this.stateManager = new StateManager(stateUserId);
        this.isResume = isResume;

        if (savedState != null && tryRestoreFromState(savedState)) {
            restoredFromState = true;
            String tradingStatus = trading
                ? "В торговле, куплено за $" + Prices.round(boughtFor)
                : "Ищет точку входа";
            String restoreMsg = "✅ [ATR+EMA] Состояние восстановлено!\n" +
                "Монета: " + this.coin.getName() + "\n" +
                "Статус: " + tradingStatus + "\n" +
                "ATR=" + atrPeriod + ", EMA=" + emaPeriod;
            ImageAndMessageSender.sendTelegramMessage(restoreMsg, chatID);
        } else {
            this.tradingSum = tradingSum;
            this.initialTradingSum = tradingSum;
            this.updateTimeout = updateTimeout;

            if (!this.isResume) {
                double usdtBal = 0, coinBal = 0;
                try { usdtBal = account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin()); } catch (Exception ignored) {}
                try { coinBal = account.wallet().getAmountOfCoin(coin); } catch (Exception ignored) {}
                String newMsg = "🆕 [ATR+EMA] Новая торговая сессия\n" +
                    "Монета: " + coin.getName() + "\n" +
                    "Баланс: " + Prices.round(coinBal) + " " + coin.getSymbol() +
                    ", " + Prices.round(usdtBal) + " USDT\n" +
                    "ATR=" + atrPeriod + ", EMA=" + emaPeriod;
                ImageAndMessageSender.sendTelegramMessage(newMsg, chatID);
            }
        }

        if (tradeStats.getStartBalance() <= 0) {
            try {
                double initUsdtBal = account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin());
                tradeStats.setStartBalance(initUsdtBal);
            } catch (Exception ignored) {}
        }
        stateManager.startAutosave();
    }

    // ── State Restore ──

    private boolean tryRestoreFromState(TradingState state) {
        if (state == null) return false;
        try {
            this.tradingSum = state.getTradingSum();
            this.initialTradingSum = state.getTradingSum();
            this.updateTimeout = state.getUpdateTimeout();
            this.trading = state.isTrading();
            this.buyPrice = state.getBuyPrice() != null ? state.getBuyPrice() : 0;
            this.boughtFor = state.getBoughtFor();
            this.soldFor = state.getSoldFor();
            this.currentMinPrice[0] = state.getCurrentMinPrice() != null ? state.getCurrentMinPrice() : Double.MAX_VALUE;
            this.currentMaxPrice[0] = state.getCurrentMaxPrice() != null ? state.getCurrentMaxPrice() : 0.0;
            this.currentMinPriceTimestamp[0] = state.getCurrentMinPriceTimestamp() != null ? state.getCurrentMinPriceTimestamp() : Double.MAX_VALUE;
            this.currentMaxPriceTimestamp[0] = state.getCurrentMaxPriceTimestamp() != null ? state.getCurrentMaxPriceTimestamp() : 0.0;
            if (state.getPriceHistory() != null) this.prices.putAll(state.getPriceHistory());
            if (state.getWinCount() > 0 || state.getLossCount() > 0) {
                tradeStats.setWinCount(state.getWinCount());
                tradeStats.setLossCount(state.getLossCount());
                tradeStats.setTotalProfit(state.getTotalProfit());
                tradeStats.setTotalLoss(state.getTotalLoss());
                tradeStats.setTotalCommission(state.getTotalCommission());
                if (state.getStartBalance() > 0) tradeStats.setStartBalance(state.getStartBalance());
            }
            return true;
        } catch (Exception e) {
            log.error("[AtrEmaTrader] Failed to restore state", e);
            return false;
        }
    }

    // ── State Save ──

    void persistState() {
        TradingState state = new TradingState();
        state.setSessionId(sessionId);
        state.setCoinName(coin.getName());
        state.setAccountType(accountType);
        state.setTrading(trading);
        state.setBuyPrice(buyPrice);
        state.setBoughtFor(boughtFor);
        state.setSoldFor(soldFor);
        state.setCurrentMinPrice(currentMinPrice[0]);
        state.setCurrentMaxPrice(currentMaxPrice[0]);
        state.setCurrentMinPriceTimestamp(currentMinPriceTimestamp[0]);
        state.setCurrentMaxPriceTimestamp(currentMaxPriceTimestamp[0]);
        state.setTradingSum(tradingSum);
        state.setBuyGap(baseBuyGap);
        state.setSellWithProfitGap(baseSellProfitGap);
        state.setSellWithLossGap(baseSellLossGap);
        state.setUpdateTimeout(updateTimeout);
        state.setChatId(chatID);

        TreeMap<String, Double> walletAssets = new TreeMap<>();
        try { walletAssets.put("USDT", account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin())); } catch (Exception ignored) {}
        try { walletAssets.put(coin.getSymbol().toUpperCase(), account.wallet().getAmountOfCoin(coin)); } catch (Exception ignored) {}
        state.setWalletAssets(walletAssets);

        if (prices.size() > 1000) {
            TreeMap<Double, Double> recent = new TreeMap<>();
            prices.descendingMap().entrySet().stream().limit(1000).forEach(e -> recent.put(e.getKey(), e.getValue()));
            state.setPriceHistory(recent);
        } else {
            state.setPriceHistory(new TreeMap<>(prices));
        }

        state.setWinCount(tradeStats.getWinCount());
        state.setLossCount(tradeStats.getLossCount());
        state.setTotalProfit(tradeStats.getTotalProfit());
        state.setTotalLoss(tradeStats.getTotalLoss());
        state.setTotalCommission(tradeStats.getTotalCommission());
        state.setStartBalance(tradeStats.getStartBalance());

        stateManager.setCurrentState(state);
        stateManager.saveState(state);
    }

    // ── ATR / EMA ──

    private void updateIndicators(double price) {
        double close = price;
        if (!Double.isNaN(prevClose)) {
            double tr = Math.abs(close - prevClose);
            if (Double.isNaN(currentAtr)) {
                atrHistory.add(tr);
                if (atrHistory.size() >= atrPeriod) {
                    double sum = 0;
                    for (double v : atrHistory) sum += v;
                    currentAtr = sum / atrPeriod;
                }
            } else {
                currentAtr = (currentAtr * (atrPeriod - 1) + tr) / atrPeriod;
            }
            if (!Double.isNaN(currentAtr)) {
                atrHistory.add(currentAtr);
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

        if (Double.isNaN(currentEma)) {
            emaSeedSum += close;
            emaSeedCount++;
            if (emaSeedCount >= emaPeriod) {
                currentEma = emaSeedSum / emaPeriod;
            }
        } else {
            currentEma = (close - currentEma) * emaMult + currentEma;
        }
    }

    private double getAtrMultiplier() {
        if (Double.isNaN(currentAtr) || Double.isNaN(avgAtr) || avgAtr <= 0) return 1.0;
        return Math.max(0.5, Math.min(2.0, currentAtr / avgAtr));
    }

    private void updateAdaptiveGaps() {
        double mult = getAtrMultiplier();
        this.buyGap = baseBuyGap * mult;
        this.sellWithProfitGap = baseSellProfitGap * mult;
        this.sellWithLossGap = baseSellLossGap * mult;
    }

    private boolean isAboveEma(double price) {
        if (Double.isNaN(currentEma)) return false;
        return price > currentEma;
    }

    private boolean isBelowEma(double price) {
        return !Double.isNaN(currentEma) && price < currentEma;
    }

    private double getDropFromMaxPercent(double currentPrice) {
        if (currentMaxPrice[0] == null || currentMaxPrice[0] <= 0) return 0;
        return 100 - (currentPrice / currentMaxPrice[0] * 100);
    }

    // ── Strategy tick ──

    public void init(double pointTimestamp, double pointPrice) {
        currentMinPrice[0] = pointPrice;
        currentMaxPrice[0] = pointPrice;
        currentMinPriceTimestamp[0] = pointTimestamp;
        currentMaxPriceTimestamp[0] = pointTimestamp;
        persistState();
    }

    public boolean processTickStrategy(double pointTimestamp, double pointPrice)
            throws NoSuchSymbolException, InsufficientAmountOfUsdtException {

        this.pointPrice = pointPrice;
        prices.put(pointTimestamp, pointPrice);
        TradingChart.addSimplePoint(pointTimestamp, pointPrice);
        TradingChart.addSimplePriceMarker(pointTimestamp, pointPrice);

        updateIndicators(pointPrice);
        updateAdaptiveGaps();

        // ── SELL ──
        if (trading) {
            boolean trendBreak = isBelowEma(pointPrice);

            // Sell profit
            if (((pointPrice - buyPrice) / buyPrice * 100) > sellWithProfitGap) {
                return executeSellLive(pointTimestamp, pointPrice, true);
            }
            // Sell loss or trend break
            if (((buyPrice - pointPrice) / buyPrice * 100) > sellWithLossGap || trendBreak) {
                boolean isProfit = pointPrice >= buyPrice;
                return executeSellLive(pointTimestamp, pointPrice, isProfit);
            }
        }

        // ── BUY ──
        if (!prices.isEmpty() && !trading) {
            if (pointPrice > currentMaxPrice[0]) {
                max = true;
                currentMaxPrice[0] = pointPrice;
                currentMaxPriceTimestamp[0] = pointTimestamp;
                if (getDropFromMaxPercent(currentMinPrice[0]) > buyGap) {
                    currentMinPrice[0] = pointPrice;
                }
            }
            if (pointPrice < currentMinPrice[0]) {
                max = false;
                currentMinPrice[0] = pointPrice;
                currentMinPriceTimestamp[0] = pointTimestamp;
            }

            double dropPct = getDropFromMaxPercent(pointPrice);
            if (dropPct > buyGap && isAboveEma(pointPrice) && !trading) {
                attemptBuy(pointTimestamp, pointPrice);
            }
        }

        tickCounter++;
        if (tickCounter % 5 == 0) persistState();
        return false;
    }

    private boolean executeSellLive(double ts, double price, boolean isProfit)
            throws NoSuchSymbolException, InsufficientAmountOfUsdtException {
        try {
            soldFor = account.trader().sell(coin, price, tradingSum / buyPrice);
        } catch (InsufficientAmountOfCoinException e) {
            throw new RuntimeException(e);
        }

        if (isProfit) {
            TradingChart.addSellProfitMarker(ts, soldFor);
        } else {
            TradingChart.addSellLossMarker(ts, soldFor);
        }

        double pnlUsd = (soldFor - boughtFor) * (tradingSum / boughtFor);
        double pnlPct = (soldFor - boughtFor) / boughtFor * 100;
        double quantity = tradingSum / boughtFor;
        double commBuy = commissionCalc.calcCommission(tradingSum);
        double commSell = commissionCalc.calcCommission(soldFor * quantity);
        double totalComm = commBuy + commSell;
        tradeStats.recordTrade(boughtFor, soldFor, quantity, totalComm);

        double coinAmt = 0, usdtAmt = 0;
        try { coinAmt = account.wallet().getAmountOfCoin(coin); } catch (Exception ignored) {}
        try { usdtAmt = account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin()); } catch (Exception ignored) {}

        String commLine = isTesterAccount
            ? String.format("\nПредп. комиссия: $%.4f", totalComm) : "";
        String statsLine = String.format(
            "\n📊 Статистика: ✅%d / ❌%d | Итого: $%.2f%s",
            tradeStats.getWinCount(), tradeStats.getLossCount(), tradeStats.getNetPnl(),
            isTesterAccount ? String.format(" (с комиссией: $%.2f)", tradeStats.getNetPnlAfterCommission()) : "");
        String atrInfo = String.format("\n📐 ATR mult: %.2f | EMA: $%s | Адапт. пороги: buy=%.2f%% profit=%.2f%% loss=%.2f%%",
            getAtrMultiplier(), Double.isNaN(currentEma) ? "—" : Prices.round(currentEma), buyGap, sellWithProfitGap, sellWithLossGap);

        String emoji = isProfit ? "📈" : "📉";
        String label = isProfit ? "ПРОДАЖА В ПРИБЫЛЬ" : "ПРОДАЖА В УБЫТОК";
        String msg = String.format(
            "%s [ATR+EMA] %s\nМонета: %s\nКуплено: $%s → Продано: $%s\n" +
            "P&L: %+.2f%% ($%+.2f)\nБаланс: %.2f USDT%s%s%s",
            emoji, label, coin.getName(),
            Prices.round(boughtFor), Prices.round(soldFor),
            pnlPct, pnlUsd, usdtAmt, commLine, atrInfo, statsLine);
        ImageAndMessageSender.sendTelegramMessage(msg, chatID);

        prevMessageId = 0;
        TradingChart.clearChart();
        trading = false;

        if (recapitalize) {
            try { tradingSum = account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin()); }
            catch (Exception ignored) {}
            if (tradingSum < 0.01) {
                String zeroMsg = "⛔ [ATR+EMA] Баланс исчерпан. Торговля остановлена.";
                ImageAndMessageSender.sendTelegramMessage(zeroMsg, chatID);
                ton.dariushkmetsyak.Web.TradingSessionManager.logTypedEventFromCurrentThread("STOP", zeroMsg);
                return true;
            }
        }

        currentMinPrice[0] = price;
        currentMaxPrice[0] = price;
        persistState();
        return true;
    }

    private void attemptBuy(double pointTimestamp, double executionPrice)
            throws NoSuchSymbolException, InsufficientAmountOfUsdtException {
        Double buyResult = null;
        String failureReason = "";
        // Retry up to 3 times with backoff for transient failures
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                double currentExecPrice = executionPrice;
                if (attempt > 1) {
                    try {
                        currentExecPrice = Prices.round(Account.getCurrentPrice(coin));
                        log.info("[ATR+EMA] Buy retry #{}: updated price ${} -> ${}", attempt, Prices.round(executionPrice), Prices.round(currentExecPrice));
                    } catch (Exception ignored) {}
                }
                buyResult = account.trader().buy(coin, currentExecPrice, tradingSum / currentExecPrice);
                if (buyResult != null) {
                    if (attempt > 1) log.info("[ATR+EMA] Buy succeeded on attempt #{}", attempt);
                    break;
                }
                failureReason = "null result from exchange";
                if (attempt < 3) {
                    log.warn("[ATR+EMA] Buy attempt #{} returned null, retrying in {}s...", attempt, attempt * 2);
                    try { TimeUnit.SECONDS.sleep(attempt * 2L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            } catch (InsufficientAmountOfUsdtException | NoSuchSymbolException e) {
                failureReason = e.getMessage();
                throw e;
            } catch (RuntimeException e) {
                failureReason = e.getMessage();
                log.warn("[ATR+EMA] Buy attempt #{} failed: {}", attempt, failureReason);
                if (attempt < 3) {
                    try { TimeUnit.SECONDS.sleep(attempt * 2L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            }
        }

        if (buyResult != null) {
            boughtFor = buyResult;
            TradingChart.addBuyIntervalMarker(pointTimestamp, boughtFor);
            buyPrice = boughtFor;
            trading = true;
            currentMaxPrice[0] = boughtFor;

            double coinAmt = 0, usdtAmt = 0;
            try { coinAmt = account.wallet().getAmountOfCoin(coin); } catch (Exception ignored) {}
            try { usdtAmt = account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin()); } catch (Exception ignored) {}

            String atrInfo = String.format(
                "📐 ATR mult: %.2f | EMA: $%s\nАдапт. пороги: buy=%.2f%% profit=%.2f%% loss=%.2f%%",
                getAtrMultiplier(), Double.isNaN(currentEma) ? "—" : Prices.round(currentEma),
                buyGap, sellWithProfitGap, sellWithLossGap);
            String buyMsg = String.format(
                "✅ [ATR+EMA] ПОКУПКА\nМонета: %s\nЦена: $%s\nСумма: %.2f USDT\n" +
                "Падение от макс.: %.2f%%\nЦель продажи+: $%s\nЦель продажи-: $%s\n%s\n" +
                "Баланс: %.6f %s, %.2f USDT",
                coin.getName(), Prices.round(boughtFor), tradingSum,
                getDropFromMaxPercent(boughtFor),
                Prices.round(boughtFor * (1 + sellWithProfitGap / 100.0)),
                Prices.round(boughtFor * (1 - sellWithLossGap / 100.0)),
                atrInfo, coinAmt, coin.getSymbol(), usdtAmt);
            ImageAndMessageSender.sendTelegramMessage(buyMsg, chatID);
            persistState();
            return;
        }

        // Notify failure (throttled)
        String sig = Prices.round(executionPrice) + "|" + failureReason;
        long now = System.currentTimeMillis();
        if (!sig.equals(lastBuyFailureSignature) || now - lastBuyFailureNotificationAt >= BUY_FAILURE_NOTIFICATION_COOLDOWN_MS) {
            lastBuyFailureSignature = sig;
            lastBuyFailureNotificationAt = now;
            String message = "⚠️ [ATR+EMA] Сигнал на покупку, но вход не выполнен\nПричина: " + failureReason;
            ImageAndMessageSender.sendTelegramMessage(message, chatID);
        }
    }

    // ── Main trading loop ──

    public void startTrading() {
        activeTraders.add(this);
        log.info("[AtrEmaTrader] Starting for {} (restored={})", coin.getName(), restoredFromState);

        if (!restoredFromState) {
            RetryPolicy initRetry = RetryPolicy.forApiCalls();
            try {
                initRetry.executeVoid(() -> {
                    try { this.init(System.currentTimeMillis(), Prices.round(Account.getCurrentPrice(coin))); }
                    catch (NoSuchSymbolException e) { throw new RuntimeException(e); }
                }, "AtrEma initialization");
            } catch (Exception e) {
                ErrorHandler.handleFatalError(e, "AtrEma Init", "Initializing for " + coin.getName());
                return;
            }
        } else {
            persistState();
        }

        int consecutiveErrors = 0;
        boolean firstTick = true;
        while (true) {
            try {
                if (!firstTick) TimeUnit.SECONDS.sleep(updateTimeout);
                firstTick = false;

                double currentPrice;
                try {
                    RetryPolicy priceRetry = RetryPolicy.forApiCalls();
                    currentPrice = priceRetry.execute(() -> {
                        try { return Prices.round(Account.getCurrentPrice(coin)); }
                        catch (NoSuchSymbolException e) { throw new RuntimeException(e); }
                    }, "Get current price");
                } catch (Exception e) {
                    ErrorHandler.handleWarning(e, "Price Fetching", "Getting price for " + coin.getName());
                    consecutiveErrors++;
                    if (consecutiveErrors >= 10) {
                        ErrorHandler.handleFatalError(e, "Price Fetching", "Failed 10 times");
                        break;
                    }
                    continue;
                }

                this.processTickStrategy(System.currentTimeMillis(), currentPrice);
                pushLiveState(currentPrice);
                logTickEvent(currentPrice);
                consecutiveErrors = 0;

            } catch (NoSuchSymbolException | InsufficientAmountOfUsdtException e) {
                consecutiveErrors++;
                ErrorHandler.handleError(e, "Trading Loop", "Processing for " + coin.getName(), true);
                persistState();
                if (consecutiveErrors >= 10) break;
                try { TimeUnit.SECONDS.sleep(30); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            } catch (BinanceConnectorException e) {
                consecutiveErrors++;
                ErrorHandler.handleError(e, "Binance API", "API call", true);
                persistState();
                if (consecutiveErrors >= 10) break;
                try { TimeUnit.SECONDS.sleep(60); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            } catch (InterruptedException e) {
                log.info("[AtrEmaTrader] Interrupted — saving and shutting down");
                persistState();
                stateManager.shutdown();
                activeTraders.remove(this);
                try { ImageAndMessageSender.sendTelegramMessage("🛑 [ATR+EMA] Торговля остановлена. Состояние сохранено."); }
                catch (Exception ignored) {}
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                consecutiveErrors++;
                log.error("[AtrEmaTrader] Unexpected error", e);
                persistState();
                if (consecutiveErrors >= 5) break;
            }
        }

        log.info("[AtrEmaTrader] Trading stopped");
        persistState();
        stateManager.shutdown();
        activeTraders.remove(this);
        try { ImageAndMessageSender.sendTelegramMessage("⛔ [ATR+EMA] Торговля остановлена. Состояние сохранено."); }
        catch (Exception ignored) {}
    }

    private void logTickEvent(double currentPrice) {
        if (trading && boughtFor != null) {
            double changePct = (currentPrice - boughtFor) / boughtFor * 100;
            String msg = String.format(
                "📊 [ATR+EMA] %s $%s | Куплено: $%s | Δ: %+.2f%% | ATR×%.2f | EMA: $%s",
                coin.getSymbol(), Prices.round(currentPrice), Prices.round(boughtFor),
                changePct, getAtrMultiplier(),
                Double.isNaN(currentEma) ? "—" : Prices.round(currentEma));
            ton.dariushkmetsyak.Web.TradingSessionManager.logTypedEventFromCurrentThread("INFO", msg);
        } else {
            double dropPct = getDropFromMaxPercent(currentPrice);
            String msg = String.format(
                "🔍 [ATR+EMA] %s $%s | Макс: $%s | Падение: %.2f%% | Нужно: %.2f%% | EMA: $%s",
                coin.getSymbol(), Prices.round(currentPrice),
                currentMaxPrice[0] > 0 ? Prices.round(currentMaxPrice[0]) : "—",
                dropPct, buyGap,
                Double.isNaN(currentEma) ? "—" : Prices.round(currentEma));
            ton.dariushkmetsyak.Web.TradingSessionManager.logTypedEventFromCurrentThread("INFO", msg);
        }
    }

    private void pushLiveState(double currentPrice) {
        try {
            double coinBal = 0, usdtBal = 0;
            try { coinBal = account.wallet().getAmountOfCoin(coin); } catch (Exception ignored) {}
            try { usdtBal = account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin()); } catch (Exception ignored) {}

            Double maxPriceVal = (currentMaxPrice[0] != null && currentMaxPrice[0] > 0) ? currentMaxPrice[0] : null;
            Double buyTarget = (!trading && maxPriceVal != null) ? maxPriceVal * (1 - buyGap / 100.0) : null;
            Double profitTarget = (trading && boughtFor != null) ? boughtFor * (1 + sellWithProfitGap / 100.0) : null;
            Double lossTarget = (trading && boughtFor != null) ? boughtFor * (1 - sellWithLossGap / 100.0) : null;

            tradeStats.setCurrentBalance(usdtBal);
            ton.dariushkmetsyak.Web.TradingSessionManager.updateLiveState(
                coinBal, usdtBal, trading, currentPrice,
                maxPriceVal, buyTarget, boughtFor, profitTarget, lossTarget,
                tradeStats.getWinCount(), tradeStats.getLossCount(),
                tradeStats.getTotalProfit(), tradeStats.getTotalLoss(),
                tradeStats.getTotalCommission(), tradeStats.getStartBalance(),
                isTesterAccount);
        } catch (Exception ignored) {}
    }
}
