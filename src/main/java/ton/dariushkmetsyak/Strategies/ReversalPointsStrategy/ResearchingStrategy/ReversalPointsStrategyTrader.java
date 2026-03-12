package ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy;

import com.binance.connector.client.exceptions.BinanceConnectorException;
import ton.dariushkmetsyak.ErrorHandling.ErrorHandler;
import ton.dariushkmetsyak.ErrorHandling.RetryPolicy;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart;
import ton.dariushkmetsyak.Persistence.StateManager;
import ton.dariushkmetsyak.Persistence.TradingState;
import ton.dariushkmetsyak.Telegram.ImageAndMessageSender;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfCoinException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.InsufficientAmountOfUsdtException;
import ton.dariushkmetsyak.TradingApi.ApiService.Exceptions.NoSuchSymbolException;
import ton.dariushkmetsyak.Util.Prices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ReversalPointsStrategyTrader {
    private static final Logger log = LoggerFactory.getLogger(ReversalPointsStrategyTrader.class);

    TreeMap<Double, Double> prices = new TreeMap<>();
    ArrayList<Reversal> reversalArrayList = new ArrayList<>();
    boolean trading = false;
    boolean max = false;
    boolean isSold = false;
    double buyGap = 0;
    double sellWithProfitGap = 0;
    double sellWithLossGap = 0;
    double pointPrice = 0;
    double buyPrice = 0;
    Double boughtFor = null;
    Double soldFor = null;
    Double[] currentMinPrice = {Double.MAX_VALUE};
    Double[] currentMaxPrice = {0.0};
    Double[] currentMaxPriceTimestamp = {0.0};
    Double[] currentMinPriceTimestamp = {Double.MAX_VALUE};
    String chartScreenshotMessage = "";
    Account account;
    Coin coin;
    double tradingSum;
    int updateTimeout;
    Long chatID;
    int prevMessageId = 0;

    /** True when state was successfully restored — skip init() call on first tick */
    private boolean restoredFromState = false;

    private final StateManager stateManager;
    private final String sessionId;
    private final String accountType;
    private int tickCounter = 0;
    private long lastBuyFailureNotificationAt = 0;
    private String lastBuyFailureSignature = "";
    private static final long BUY_FAILURE_NOTIFICATION_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(5);

    class Reversal {
        private final double[] data;
        private final String tag;
        Reversal(double[] data, String tag) { this.data = data; this.tag = tag; }
    }

    // ---- Constructors ----

    public ReversalPointsStrategyTrader(Account account, Coin coin, double tradingSum,
                                        double percentageGap, int updateTimeout, Long chatID) {
        this(account, coin, tradingSum, percentageGap, percentageGap / 2, percentageGap,
             updateTimeout, chatID, null, null);
    }

    public ReversalPointsStrategyTrader(Account account, Coin coin, double tradingSum,
                                        double buyGap, double sellWithProfitGap,
                                        double sellWithLossGap, int updateTimeout, Long chatID) {
        this(account, coin, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
             updateTimeout, chatID, null, null);
    }

    /** Backward-compat: 9-arg constructor without sessionId (used by MenuHandler). */
    public ReversalPointsStrategyTrader(Account account, Coin coin, double tradingSum,
                                        double buyGap, double sellWithProfitGap,
                                        double sellWithLossGap, int updateTimeout, Long chatID,
                                        TradingState savedState) {
        this(account, coin, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap,
             updateTimeout, chatID, savedState, null);
    }

    /**
     * Full constructor. savedState != null → restore from saved state instead of fresh start.
     * sessionId must match the MiniApp session ID for state file naming.
     */
    public ReversalPointsStrategyTrader(Account account, Coin coin, double tradingSum,
                                        double buyGap, double sellWithProfitGap,
                                        double sellWithLossGap, int updateTimeout, Long chatID,
                                        TradingState savedState, String sessionId) {
        this.account = account;
        this.coin = coin;
        this.chatID = chatID;
        this.sessionId = sessionId != null ? sessionId : "trader_" + System.currentTimeMillis();
        this.accountType = account.getClass().getSimpleName().toUpperCase();
        this.stateManager = new StateManager();

        if (savedState != null && tryRestoreFromState(savedState)) {
            restoredFromState = true;
            String tradingStatus = trading
                ? "В торговле, куплено за $" + Prices.round(boughtFor)
                : "Ищет точку входа (макс.волна: " + (currentMaxPrice[0] > 0 ? "$" + Prices.round(currentMaxPrice[0]) : "—") + ")";
            ImageAndMessageSender.sendTelegramMessage(
                "✅ Состояние восстановлено!\n" +
                "Монета: " + this.coin.getName() + "\n" +
                "Сохранено: " + new Date(savedState.getTimestamp()) + "\n" +
                "Статус: " + tradingStatus + "\n" +
                "Параметры: buy=" + this.buyGap + "%, profit=" + this.sellWithProfitGap +
                "%, loss=" + this.sellWithLossGap + "%", chatID);
            log.info("[Trader] Restored state for session {}: isTrading={}, boughtFor={}",
                    this.sessionId, trading, boughtFor);
        } else {
            // Fresh session
            this.tradingSum = tradingSum;
            this.buyGap = buyGap;
            this.sellWithProfitGap = sellWithProfitGap;
            this.sellWithLossGap = sellWithLossGap;
            this.updateTimeout = updateTimeout;

            double usdtBal = 0, coinBal = 0;
            try { usdtBal = account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin()); } catch (Exception ignored) {}
            try { coinBal = account.wallet().getAmountOfCoin(coin); } catch (Exception ignored) {}
            ImageAndMessageSender.sendTelegramMessage(
                "🆕 Новая торговая сессия\n" +
                "Монета: " + coin.getName() + "\n" +
                "Баланс: " + Prices.round(coinBal) + " " + coin.getSymbol() +
                ", " + Prices.round(usdtBal) + " USDT", chatID);
        }

        // Register shutdown hook for JVM kill (SIGTERM / kill)
        stateManager.startAutosave();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[Trader] JVM shutdown — final state save...");
            persistState();
            stateManager.shutdown();
        }));
    }

    // ---- State Restore ----

    private boolean tryRestoreFromState(TradingState state) {
        if (state == null) return false;
        try {
            this.tradingSum = state.getTradingSum();
            this.buyGap = state.getBuyGap();
            this.sellWithProfitGap = state.getSellWithProfitGap();
            this.sellWithLossGap = state.getSellWithLossGap();
            this.updateTimeout = state.getUpdateTimeout();

            this.trading = state.isTrading();
            this.buyPrice = state.getBuyPrice() != null ? state.getBuyPrice() : 0;
            this.boughtFor = state.getBoughtFor();
            this.soldFor = state.getSoldFor();
            this.currentMinPrice[0] = state.getCurrentMinPrice() != null
                    ? state.getCurrentMinPrice() : Double.MAX_VALUE;
            this.currentMaxPrice[0] = state.getCurrentMaxPrice() != null
                    ? state.getCurrentMaxPrice() : 0.0;
            this.currentMinPriceTimestamp[0] = state.getCurrentMinPriceTimestamp() != null
                    ? state.getCurrentMinPriceTimestamp() : Double.MAX_VALUE;
            this.currentMaxPriceTimestamp[0] = state.getCurrentMaxPriceTimestamp() != null
                    ? state.getCurrentMaxPriceTimestamp() : 0.0;

            if (state.getPriceHistory() != null) this.prices.putAll(state.getPriceHistory());

            if (state.getReversals() != null) {
                for (TradingState.ReversalPoint rp : state.getReversals())
                    reversalArrayList.add(new Reversal(
                            new double[]{rp.getTimestamp(), rp.getPrice()}, rp.getTag()));
            }

            // Ensure reversalArrayList has at least one entry (required by the strategy logic)
            if (reversalArrayList.isEmpty()) {
                reversalArrayList.add(new Reversal(
                        new double[]{(double) System.currentTimeMillis(),
                                     currentMaxPrice[0] > 0 ? currentMaxPrice[0] : 0}, "initPoint"));
            }

            return true;
        } catch (Exception e) {
            log.error("[Trader] Failed to restore state", e);
            return false;
        }
    }

    // ---- State Save ----

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
        state.setBuyGap(buyGap);
        state.setSellWithProfitGap(sellWithProfitGap);
        state.setSellWithLossGap(sellWithLossGap);
        state.setUpdateTimeout(updateTimeout);
        state.setChatId(chatID);

        // Save wallet snapshot
        TreeMap<String, Double> walletAssets = new TreeMap<>();
        try {
            walletAssets.put("USDT", account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin()));
        } catch (Exception ignored) {}
        try {
            walletAssets.put(coin.getSymbol().toUpperCase(), account.wallet().getAmountOfCoin(coin));
        } catch (Exception ignored) {}
        state.setWalletAssets(walletAssets);

        // Price history (cap at 1000)
        if (prices.size() > 1000) {
            TreeMap<Double, Double> recent = new TreeMap<>();
            prices.descendingMap().entrySet().stream()
                    .limit(1000).forEach(e -> recent.put(e.getKey(), e.getValue()));
            state.setPriceHistory(recent);
        } else {
            state.setPriceHistory(new TreeMap<>(prices));
        }

        // Reversals (cap at 500)
        List<TradingState.ReversalPoint> rps = new ArrayList<>();
        List<Reversal> revCopy = new ArrayList<>(reversalArrayList);
        int start = Math.max(0, revCopy.size() - 500);
        for (int i = start; i < revCopy.size(); i++) {
            Reversal r = revCopy.get(i);
            rps.add(new TradingState.ReversalPoint(r.data[0], r.data[1], r.tag));
        }
        state.setReversals(rps);

        stateManager.setCurrentState(state);
        stateManager.saveState(state);
    }

    // ---- Strategy ----

    public void init(double pointTimestamp, double pointPrice) {
        reversalArrayList.add(new Reversal(new double[]{pointTimestamp, pointPrice}, "initPoint"));
        persistState();
    }

    public boolean startResearchingChart(double pointTimestamp, double pointPrice)
            throws NoSuchSymbolException, InsufficientAmountOfUsdtException {

        this.pointPrice = pointPrice;
        prices.put(pointTimestamp, pointPrice);
        TradingChart.addSimplePoint(pointTimestamp, pointPrice);
        TradingChart.addSimplePriceMarker(pointTimestamp, pointPrice);

        if (trading) {
            if (((pointPrice - buyPrice) / buyPrice * 100) > sellWithProfitGap) {
                System.out.println("Timestamp: " + pointTimestamp + "  Price: " + pointPrice);
                try {
                    soldFor = account.trader().sell(coin, pointPrice, tradingSum / buyPrice);
                } catch (InsufficientAmountOfCoinException e) {
                    throw new RuntimeException(e);
                }
                TradingChart.addSellIntervalMarker(pointTimestamp, soldFor);
                isSold = true;
                chartScreenshotMessage = "Продано в ПРИБЫЛЬ";
                sendPhotoToTelegram();
                prevMessageId = 0;
                TradingChart.clearChart();
                trading = false;
                isSold = false;
                currentMinPrice[0] = pointPrice;
                currentMaxPrice[0] = pointPrice;
                persistState();
                return true;
            }
            if (((buyPrice - pointPrice) / buyPrice * 100) > sellWithLossGap) {
                System.out.println("Timestamp: " + pointTimestamp + "  Price: " + pointPrice);
                try {
                    soldFor = account.trader().sell(coin, pointPrice, tradingSum / buyPrice);
                } catch (InsufficientAmountOfCoinException e) {
                    throw new RuntimeException(e);
                }
                TradingChart.addSellIntervalMarker(pointTimestamp, soldFor);
                isSold = true;
                chartScreenshotMessage = "Продано в УБЫТОК";
                sendPhotoToTelegram();
                prevMessageId = 0;
                TradingChart.clearChart();
                trading = false;
                isSold = false;
                currentMinPrice[0] = pointPrice;
                currentMaxPrice[0] = pointPrice;
                persistState();
                return true;
            }
        }

        if (!prices.isEmpty() && !trading) {
            Reversal previousRec = reversalArrayList.get(reversalArrayList.toArray().length - 1);

            if (pointPrice > currentMaxPrice[0]) {
                max = true;
                currentMaxPrice[0] = pointPrice;
                currentMaxPriceTimestamp[0] = pointTimestamp;
                if (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100) > buyGap) {
                    if (!Objects.equals(previousRec.tag, "min")) {
                        reversalArrayList.add(new Reversal(
                                new double[]{currentMinPriceTimestamp[0], currentMinPrice[0]}, "min"));
                    }
                    currentMinPrice[0] = pointPrice;
                }
            }

            if (pointPrice < currentMinPrice[0]) {
                max = false;
                currentMinPrice[0] = pointPrice;
                currentMinPriceTimestamp[0] = pointTimestamp;
            }

            boolean buySignalReached = isBuySignalReached(pointPrice);
            if (buySignalReached && !Objects.equals(previousRec.tag, "max") && !trading) {
                attemptBuy(pointTimestamp, pointPrice);
            }
        }

        // Persist every 5 ticks (more frequent than before)
        tickCounter++;
        if (tickCounter % 5 == 0) persistState();

        sendPhotoToTelegram();
        return false;
    }

    private boolean isBuySignalReached(double currentPrice) {
        if (currentMaxPrice[0] == null || currentMaxPrice[0] <= 0) return false;
        return getDropFromMaxPercent(currentPrice) > buyGap;
    }

    private double getDropFromMaxPercent(double currentPrice) {
        if (currentMaxPrice[0] == null || currentMaxPrice[0] <= 0) return 0;
        return 100 - (currentPrice / currentMaxPrice[0] * 100);
    }

    private void attemptBuy(double pointTimestamp, double executionPrice)
            throws NoSuchSymbolException, InsufficientAmountOfUsdtException {
        Double buyResult = null;
        String failureReason = "";
        try {
            buyResult = account.trader().buy(coin, executionPrice, tradingSum / executionPrice);
            if (buyResult == null) failureReason = "биржа не подтвердила исполнение ордера (null)";
        } catch (InsufficientAmountOfUsdtException | NoSuchSymbolException e) {
            failureReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw e;
        } catch (RuntimeException e) {
            failureReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Buy attempt failed", e);
        }

        if (buyResult != null) {
            boughtFor = buyResult;
            TradingChart.addBuyIntervalMarker(pointTimestamp, boughtFor);
            reversalArrayList.add(new Reversal(
                    new double[]{currentMaxPriceTimestamp[0], currentMaxPrice[0]}, "max"));
            buyPrice = boughtFor;
            trading = true;
            currentMaxPrice[0] = boughtFor;
            persistState(); // Immediate save on buy — most critical moment
            return;
        }
        notifyBuyFailure(failureReason, executionPrice);
    }

    private void notifyBuyFailure(String reason, double executionPrice) {
        String normalizedReason = (reason == null || reason.isBlank()) ? "неизвестная причина" : reason;
        String signature = Prices.round(executionPrice) + "|" + normalizedReason;
        long now = System.currentTimeMillis();
        if (signature.equals(lastBuyFailureSignature)
                && now - lastBuyFailureNotificationAt < BUY_FAILURE_NOTIFICATION_COOLDOWN_MS) return;
        lastBuyFailureSignature = signature;
        lastBuyFailureNotificationAt = now;

        double dropFromMax = getDropFromMaxPercent(executionPrice);
        String message = "⚠️ Сигнал на покупку сработал, но вход не выполнен\n" +
                "Монета: " + coin.getName() + "\n" +
                "Текущая цена: " + Prices.round(executionPrice) + "\n" +
                "Максимум волны: " + Prices.round(currentMaxPrice[0]) + "\n" +
                "Падение от максимума: " + String.format("%.2f", dropFromMax) + "%\n" +
                "Порог покупки: " + buyGap + "%\n" +
                "Причина: " + normalizedReason;
        log.warn("Buy signal reached but order not executed. {}", message);
        ImageAndMessageSender.sendTelegramMessage(message, chatID);
    }

    // ---- Main trading loop ----

    public void startTrading() {
        log.info("[Trader] Starting trading for {} (restored={})", coin.getName(), restoredFromState);

        if (!restoredFromState) {
            // Fresh start — initialise with current price
            RetryPolicy initRetry = RetryPolicy.forApiCalls();
            try {
                initRetry.executeVoid(() -> {
                    try {
                        this.init(System.currentTimeMillis(), Prices.round(Account.getCurrentPrice(coin)));
                    } catch (NoSuchSymbolException e) {
                        throw new RuntimeException(e);
                    }
                }, "Trading initialization");
            } catch (Exception e) {
                ErrorHandler.handleFatalError(e, "Trading Initialization",
                    "Initializing trader for " + coin.getName());
                return;
            }
        } else {
            // Restored — persist immediately so the state file has the latest timestamp
            log.info("[Trader] Restored session — skipping init(), continuing from saved state");
            persistState();
        }

        int consecutiveErrors = 0;
        final int maxConsecutiveErrors = 10;

        while (true) {
            try {
                TimeUnit.SECONDS.sleep(updateTimeout);

                // Fetch current price with retry
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
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        ErrorHandler.handleFatalError(e, "Price Fetching",
                            "Failed to get price " + maxConsecutiveErrors + " times");
                        break;
                    }
                    continue;
                }

                // Run strategy tick
                this.startResearchingChart(System.currentTimeMillis(), currentPrice);

                // Push live state to MiniApp (includes currentPrice)
                pushLiveState(currentPrice);

                consecutiveErrors = 0;

            } catch (NoSuchSymbolException | InsufficientAmountOfUsdtException e) {
                consecutiveErrors++;
                ErrorHandler.handleError(e, "Trading Loop",
                    "Processing trading logic for " + coin.getName(), true);
                persistState();
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    ErrorHandler.handleFatalError(e, "Trading Loop",
                        "Too many consecutive errors (" + maxConsecutiveErrors + ")");
                    break;
                }
                try { TimeUnit.SECONDS.sleep(30); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            } catch (BinanceConnectorException e) {
                consecutiveErrors++;
                ErrorHandler.handleError(e, "Binance API", "API call during trading", true);
                persistState();
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    ErrorHandler.handleFatalError(e, "Binance API", "Binance API unavailable");
                    break;
                }
                try { TimeUnit.SECONDS.sleep(60); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); break;
                }
            } catch (NullPointerException e) {
                log.error("[Trader] Unexpected NPE", e);
                ErrorHandler.handleError(e, "Trading Loop", "Null pointer in trading logic", true);
                persistState();
                consecutiveErrors++;
                if (consecutiveErrors >= 3) {
                    ErrorHandler.handleFatalError(e, "Trading Loop", "Repeated NPE — data corruption");
                    break;
                }
            } catch (InterruptedException e) {
                log.info("[Trader] Trading interrupted — saving and shutting down");
                persistState();
                stateManager.shutdown();
                try {
                    ImageAndMessageSender.sendTelegramMessage(
                        "🛑 Торговля остановлена по команде пользователя\nСостояние сохранено.");
                } catch (Exception ignored) {}
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                consecutiveErrors++;
                log.error("[Trader] Unexpected error", e);
                ErrorHandler.handleError(e, "Trading Loop",
                    "Unexpected: " + e.getClass().getSimpleName(), true);
                persistState();
                if (consecutiveErrors >= 5) {
                    ErrorHandler.handleFatalError(e, "Trading Loop", "Too many unexpected errors");
                    break;
                }
            }
        }

        log.info("[Trader] Trading stopped");
        persistState();
        stateManager.shutdown();
        try {
            ImageAndMessageSender.sendTelegramMessage(
                "⛔ Торговля остановлена из-за критических ошибок\nСостояние сохранено.");
        } catch (Exception ignored) {}
    }

    /** Push all live fields to TradingSessionManager */
    private void pushLiveState(double currentPrice) {
        try {
            double coinBal = 0;
            try { coinBal = account.wallet().getAmountOfCoin(coin); } catch (Exception ignored) {}
            double usdtBal = 0;
            try { usdtBal = account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin()); } catch (Exception ignored) {}

            Double maxPriceVal = (currentMaxPrice[0] != null && currentMaxPrice[0] > 0) ? currentMaxPrice[0] : null;
            Double buyTarget = (!trading && maxPriceVal != null)
                ? maxPriceVal * (1 - buyGap / 100.0) : null;
            Double profitTarget = (trading && boughtFor != null)
                ? boughtFor * (1 + sellWithProfitGap / 100.0) : null;
            Double lossTarget = (trading && boughtFor != null)
                ? boughtFor * (1 - sellWithLossGap / 100.0) : null;

            ton.dariushkmetsyak.Web.TradingSessionManager.updateLiveState(
                coinBal, usdtBal, trading, currentPrice,
                maxPriceVal, buyTarget, boughtFor, profitTarget, lossTarget
            );
        } catch (Exception ignored) {}
    }

    // ---- Telegram ----

    public void sendPhotoToTelegram() {
        String currentPicturePath = LocalDateTime.now().toString();
        try {
            TradingChart.makeScreenShot(currentPicturePath);
        } catch (Exception e) {
            log.error("Failed to create chart screenshot", e);
            ErrorHandler.handleWarning(e, "Chart Screenshot", "Creating chart image");
            return;
        }

        if (!trading) {
            double dropFromMax = getDropFromMaxPercent(pointPrice);
            double leftToDrop = buyGap - dropFromMax;
            String buySignalStatus = leftToDrop > 0
                    ? "Осталось снизиться на " + String.format("%.2f", leftToDrop) + "%"
                    : "Сигнал входа превышен на " + String.format("%.2f", Math.abs(leftToDrop)) + "%";
            chartScreenshotMessage =
                "Ищу точку входа...\n" +
                "Текущая цена: " + Prices.round(pointPrice) + "\n" +
                "Максимальная цена: " + Prices.round(currentMaxPrice[0]) + "\n" +
                "Коэффициент покупки: " + buyGap + "%\n" +
                buySignalStatus + "\n" +
                "Баланс USDT: " + account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin());
        }
        if (trading && !isSold) {
            chartScreenshotMessage =
                "ТОРГУЕМ\n" +
                "Цена ТЕКУЩАЯ: " + Prices.round(pointPrice) + "\n" +
                "Цена продажи в ПРИБЫЛЬ: " + Prices.round(boughtFor + (boughtFor / 100 * sellWithProfitGap)) + "\n" +
                "Цена продажи в УБЫТОК: " + Prices.round(boughtFor - (buyPrice / 100 * sellWithLossGap)) + "\n" +
                "Изменение цены: " + String.format("%.2f", ((pointPrice - boughtFor) / boughtFor * 100)) + "%\n" +
                "Баланс USDT: " + account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin());
        }
        if (trading && isSold) {
            chartScreenshotMessage += "\nКуплено за: " + Prices.round(boughtFor) +
                "\nПродано за: " + Prices.round(soldFor) +
                "\nРост: " + String.format("%.2f", (soldFor - boughtFor) / boughtFor * 100) + "%" +
                "\nБаланс USDT: " + account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin());
            boughtFor = soldFor = null;
        }

        try {
            prevMessageId = ImageAndMessageSender.sendPhoto(currentPicturePath, chartScreenshotMessage);
        } catch (Exception e) {
            log.error("Failed to send photo to Telegram", e);
            ErrorHandler.handleWarning(e, "Telegram Photo", "Sending chart to Telegram");
        }

        try {
            Files.delete(Path.of(currentPicturePath));
        } catch (IOException e) {
            log.warn("Failed to delete temp file: {}", currentPicturePath);
        }
    }
}
