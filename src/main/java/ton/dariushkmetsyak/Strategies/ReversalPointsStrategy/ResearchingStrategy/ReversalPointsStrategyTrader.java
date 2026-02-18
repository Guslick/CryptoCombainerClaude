package ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy;

import com.binance.connector.client.exceptions.BinanceConnectorException;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ReversalPointsStrategyTrader {

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

    // ---- Persistence ----
    private final StateManager stateManager;
    private final String accountType;
    private int tickCounter = 0;

    class Reversal {
        private final double[] data;
        private final String tag;
        Reversal(double[] data, String tag) {
            this.data = data;
            this.tag = tag;
        }
    }

    // ---- Конструкторы ----

    public ReversalPointsStrategyTrader(Account account, Coin coin, double tradingSum,
                                        double percentageGap, int updateTimeout, Long chatID) {
        this(account, coin, tradingSum, percentageGap, percentageGap / 2, percentageGap, updateTimeout, chatID, null);
    }

    public ReversalPointsStrategyTrader(Account account, Coin coin, double tradingSum,
                                        double buyGap, double sellWithProfitGap,
                                        double sellWithLossGap, int updateTimeout, Long chatID) {
        this(account, coin, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chatID, null);
    }

    /**
     * Главный конструктор с возможностью восстановления из сохранённого состояния.
     * 
     * @param savedState Если не null - восстанавливает торговлю из этого состояния, 
     *                   игнорируя параметры tradingSum, buyGap и т.д.
     */
    public ReversalPointsStrategyTrader(Account account, Coin coin, double tradingSum,
                                        double buyGap, double sellWithProfitGap,
                                        double sellWithLossGap, int updateTimeout, Long chatID,
                                        TradingState savedState) {
        this.account = account;
        this.coin = coin;
        this.chatID = chatID;
        this.accountType = account.getClass().getSimpleName().toUpperCase();
        this.stateManager = new StateManager();

        // Если передано сохранённое состояние - восстанавливаем из него
        if (savedState != null && tryRestoreFromState(savedState)) {
            // Параметры восстановлены из savedState
            ImageAndMessageSender.sendTelegramMessage(
                    "✅ Торговля восстановлена!\n" +
                    "Монета: " + this.coin.getName() + "\n" +
                    "Сохранено: " + new Date(savedState.getTimestamp()) + "\n" +
                    "Сумма сделки: " + this.tradingSum + " USDT\n" +
                    "Коэффициенты: buy=" + this.buyGap + "%, profit=" + 
                    this.sellWithProfitGap + "%, loss=" + this.sellWithLossGap + "%\n" +
                    "Статус: " + (trading ? "В торговле, куплено за " + Prices.round(boughtFor) : "Ищет точку входа"),
                    chatID);
        } else {
            // Новая сессия - используем переданные параметры
            this.tradingSum = tradingSum;
            this.buyGap = buyGap;
            this.sellWithProfitGap = sellWithProfitGap;
            this.sellWithLossGap = sellWithLossGap;
            this.updateTimeout = updateTimeout;
            
            ImageAndMessageSender.sendTelegramMessage(
                    "🆕 Новая торговая сессия\nБаланс: " + account.wallet().getAllAssets(), chatID);
        }

        // Автосохранение каждые 30 секунд
        stateManager.startAutosave();

        // Сохраняем при завершении JVM
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Trader] Завершение — финальное сохранение...");
            persistState();
            stateManager.shutdown();
        }));
    }

    // ---- Persistence: восстановление ----

    /**
     * Восстановление из переданного состояния.
     * Возвращает true если успешно.
     */
    private boolean tryRestoreFromState(TradingState state) {
        if (state == null) return false;

        // Восстанавливаем ВСЕ параметры из состояния
        this.tradingSum = state.getTradingSum();
        this.buyGap = state.getBuyGap();
        this.sellWithProfitGap = state.getSellWithProfitGap();
        this.sellWithLossGap = state.getSellWithLossGap();
        this.updateTimeout = state.getUpdateTimeout();
        
        // Восстанавливаем торговое состояние
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

        if (state.getPriceHistory() != null)
            this.prices.putAll(state.getPriceHistory());

        if (state.getReversals() != null) {
            for (TradingState.ReversalPoint rp : state.getReversals())
                reversalArrayList.add(new Reversal(
                        new double[]{rp.getTimestamp(), rp.getPrice()}, rp.getTag()));
        }

        return true;
    }

    // ---- Persistence: сохранение ----

    void persistState() {
        TradingState state = new TradingState();
        state.setCoinName(coin.getName());
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
        state.setAccountType(accountType);

        // Не более 1000 последних цен
        if (prices.size() > 1000) {
            TreeMap<Double, Double> recent = new TreeMap<>();
            prices.descendingMap().entrySet().stream()
                    .limit(1000).forEach(e -> recent.put(e.getKey(), e.getValue()));
            state.setPriceHistory(recent);
        } else {
            state.setPriceHistory(new TreeMap<>(prices));
        }

        List<TradingState.ReversalPoint> rps = new ArrayList<>();
        for (Reversal r : reversalArrayList)
            rps.add(new TradingState.ReversalPoint(r.data[0], r.data[1], r.tag));
        state.setReversals(rps);

        stateManager.setCurrentState(state);
        stateManager.saveState(state);
    }

    // ---- Основная логика ----

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
                persistState(); // сохранить после продажи
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
                ImageAndMessageSender.sendTelegramMessage(
                        "Баланс кошелька: " + account.wallet().getAllAssets().toString());
                trading = false;
                isSold = false;
                currentMinPrice[0] = pointPrice;
                currentMaxPrice[0] = pointPrice;
                persistState(); // сохранить после продажи
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
                if (100 - (currentMinPrice[0] / currentMaxPrice[0] * 100) > buyGap) {
                    if (!Objects.equals(previousRec.tag, "max")) {
                        if (!trading) {
                            boughtFor = account.trader().buy(coin, pointPrice, tradingSum / pointPrice);
                            if (boughtFor != null) {
                                TradingChart.addBuyIntervalMarker(pointTimestamp, boughtFor);
                                reversalArrayList.add(new Reversal(
                                        new double[]{currentMaxPriceTimestamp[0], currentMaxPrice[0]}, "max"));
                                buyPrice = boughtFor;
                                trading = true;
                                ImageAndMessageSender.sendTelegramMessage(
                                        "Баланс кошелька: " + account.wallet().getAllAssets().toString());
                                currentMaxPrice[0] = boughtFor;
                                persistState(); // сохранить после покупки
                            }
                        }
                    }
                }
            }
        }

        // Периодическое сохранение каждые 10 тиков
        tickCounter++;
        if (tickCounter % 10 == 0) persistState();

        sendPhotoToTelegram();
        return false;
    }

    public void startTrading() {
        try {
            this.init(System.currentTimeMillis(), Prices.round(Account.getCurrentPrice(coin)));
        } catch (NoSuchSymbolException e) {
            throw new RuntimeException(e);
        }
        while (true) {
            try {
                TimeUnit.SECONDS.sleep(updateTimeout);
                this.startResearchingChart(
                        System.currentTimeMillis(),
                        Prices.round(Account.getCurrentPrice(coin)));
            } catch (NoSuchSymbolException | InsufficientAmountOfUsdtException |
                     BinanceConnectorException e) {
                e.printStackTrace();
                ImageAndMessageSender.sendTelegramMessage("Ошибка: " + e.getMessage());
                persistState(); // сохранить при ошибке
            } catch (NullPointerException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                System.out.println("[Trader] Прерван — сохраняем состояние");
                persistState();
                stateManager.shutdown();
                return;
            }
        }
    }

    public void sendPhotoToTelegram() {
        String currentPicturePath = LocalDateTime.now().toString();
        TradingChart.makeScreenShot(currentPicturePath);

        if (!trading) {
            chartScreenshotMessage =
                    "Ищу точку входа..." + "\n" +
                    "Текущая цена: " + Prices.round(pointPrice) + "\n" +
                    "Максимальная цена: " + Prices.round(currentMaxPrice[0]) + "\n" +
                    "Коэффициент покупки: " + buyGap + "%" + "\n" +
                    "Осталось снизиться на " + String.format("%.2f",
                            ((pointPrice - currentMaxPrice[0]) / currentMaxPrice[0] * 100) + buyGap) + "%" + "\n" +
                    "Баланс USDT: " + account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin());
        }
        if (trading && !isSold) {
            chartScreenshotMessage =
                    "ТОРГУЕМ" + "\n" +
                    "Цена ТЕКУЩАЯ: " + Prices.round(pointPrice) + "\n" +
                    "Цена продажи в ПРИБЫЛЬ: " + Prices.round(boughtFor + (boughtFor / 100 * sellWithProfitGap)) + "\n" +
                    "Цена продажи в УБЫТОК: " + Prices.round(boughtFor - (buyPrice / 100 * sellWithLossGap)) + "\n" +
                    "Изменение цены: " + String.format("%.2f", ((pointPrice - boughtFor) / boughtFor * 100)) + "%" + "\n" +
                    "Стоимость торгуемого актива: " + Prices.round((tradingSum / boughtFor) * pointPrice) + "\n" +
                    "Баланс USDT: " + account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin());
        }
        if (trading && isSold) {
            chartScreenshotMessage += "\n" +
                    "Куплено за: " + Prices.round(boughtFor) + "\n" +
                    "Продано за: " + Prices.round(soldFor) + "\n" +
                    "Рост: " + String.format("%.2f", (soldFor - boughtFor) / boughtFor * 100) + "%" + "\n" +
                    "Баланс USDT: " + account.wallet().getAmountOfCoin(Account.USD_TOKENS.USDT.getCoin());
            boughtFor = soldFor = null;
        }

        prevMessageId = ImageAndMessageSender.sendPhoto(currentPicturePath, chartScreenshotMessage);
        try {
            Files.delete(Path.of(currentPicturePath));
        } catch (IOException e) {
            System.err.println("Error deleting file: " + currentPicturePath);
            throw new RuntimeException(e);
        }
    }
}
