package ton.dariushkmetsyak.Telegram;

import com.binance.connector.client.exceptions.BinanceClientException;
import org.jetbrains.annotations.NotNull;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ton.dariushkmetsyak.Charts.Chart;
import ton.dariushkmetsyak.ErrorHandling.ErrorHandler;
import ton.dariushkmetsyak.ErrorHandling.RetryPolicy;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.CoinsList;
import ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart;
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointStrategyBackTester;
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointsStrategyTrader;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder;
import ton.dariushkmetsyak.Persistence.StateManager;
import ton.dariushkmetsyak.Persistence.TradingState;
import ton.dariushkmetsyak.Config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class MenuHandler extends TelegramLongPollingBot {
    private static final Logger log = LoggerFactory.getLogger(MenuHandler.class);
    private static final String BOT_TOKEN = AppConfig.getInstance().getBotToken();
  //  final private static String chatId = "-1002382149738";
    ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
    Thread processThread;
    TradingState savedState = null;  // Для хранения найденного сохранённого состояния
    enum UserState {
        IDLE,          // Ничего не ждём
        WAITING_FOR_OPTION,  // Ждём выбор варианта (1-4)
        BEFORE_REAL_TIME_RESEARCH,
        CONDUCTING_REAL_TIME_RESEARCH,  // Ждём строку
        BEFORE_BACK_TESTING_RESEARCH,
        CONDUCTING_BACK_TESTING_RESEARCH,  // Ждём строку
        CHECK_SAVED_STATE_BINANCE,  // Проверяем наличие сохранённого состояния для Binance
        CHECK_SAVED_STATE_BINANCE_TEST,  // Проверяем наличие сохранённого состояния для Binance Test
        BEFORE_BINANCE_TRADING,
        CONDUCTING_BINANCE_TRADING,
        BEFORE_BINANCE_TEST_TRADING,
        CONDUCTING_BINANCE_TEST_TRADING,
        WAITING_FOR_INT_1,   // Ждём первое число
        WAITING_FOR_INT_2    // Ждём второе число
    }


    UserState currentState = UserState.WAITING_FOR_OPTION;

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            ImageAndMessageSender.setChatId(chatId);

            switch (currentState){
                case WAITING_FOR_OPTION: {
                    if (messageText.equals("/start")) {
                        setMenu(chatId, "Что будем делать?");
                        break;
                    } else if (messageText.equals("Остановить")) {
                        setMenu(chatId, "Что будем делать");
                        break;
                    } else if (messageText.equals("Торговля")) {
                        currentState = UserState.CHECK_SAVED_STATE_BINANCE;
                        checkSavedStateAndPrompt(chatId, "BINANCEACCOUNT");
                        break;
                    } else if (messageText.equals("Тестовая торговля на Binance")) {
                        currentState = UserState.CHECK_SAVED_STATE_BINANCE_TEST;
                        checkSavedStateAndPrompt(chatId, "TESTERACCOUNT");
                        break;
                    } else if (messageText.equals("Историческое исследование")) {
                        currentState = UserState.BEFORE_BACK_TESTING_RESEARCH;
                        SendMessage message = new SendMessage();
                        message.setChatId(String.valueOf(chatId));
                        message.setReplyMarkup(keyboardMarkup); // Устанавливаем на клавиатуре кнопку "Остановить"
                        message.setText("Какую валюту будем исследовать?");
                        setCancelKeyboard(chatId,message);
                        break;
                    } else if (messageText.equals("Исследование в реальном времени")) {
                        currentState = UserState.BEFORE_REAL_TIME_RESEARCH;
                        SendMessage message = new SendMessage();
                        message.setChatId(String.valueOf(chatId));
                        message.setReplyMarkup(keyboardMarkup); // Устанавливаем на клавиатуре кнопку "Остановить"
                        message.setText(" Введите параметры для исследования. \n\n" +
                                "Образец: монета, стартовая_сумма_в_USDT, сумма_сделки_в_USDT, коэффициент_покупки_(в %), коэффициент_продажи_в_прибыль_(в %),коэффициент_продажи_в_убыток_(в %) частота_обновления_графика(в сек) \n\n"+
                                "Пример: bitcoin, 150, 100, 3.5, 2, 8, 30 ");

                        setCancelKeyboard(chatId,message);
                        break;
                    } else if (messageText.equals("Отмена")) {
                        setMenu(chatId, "Что будем делать");
                        break;
                    }
                    break;
                }
                case BEFORE_BACK_TESTING_RESEARCH: {
                    if (messageText.equals("Отмена")) {
                        //    processThread.interrupt();
                        currentState = UserState.WAITING_FOR_OPTION;
                        setMenu(chatId, "Отменено. Что будем делать в этот раз?");
                        return;
                    }
                    conducting_back_testing_research(chatId, messageText);
                    break;
                }

                case CONDUCTING_BACK_TESTING_RESEARCH: {
                    if (messageText.equals("Остановить")){
                        processThread.interrupt();
                        System.out.println("conducting_back_testing_research was interrupted");
                        currentState=UserState.WAITING_FOR_OPTION;
                        setMenu(chatId, "Остановлено. Что будем делать в этот раз?");
                        break;
                    }
                    break;
                }

                case CHECK_SAVED_STATE_BINANCE: {
                    if (messageText.equals("Отмена")) {
                        currentState = UserState.WAITING_FOR_OPTION;
                        setMenu(chatId, "Отменено. Что будем делать?");
                        return;
                    } else if (messageText.equals("📊 Продолжить торговлю")) {
                        // Восстановить торговлю из сохранённого состояния
                        resumeBinanceTrading(chatId, savedState);
                        return;
                    } else if (messageText.equals("🆕 Начать заново")) {
                        // Удалить старое состояние и запросить новые параметры
                        if (savedState != null) {
                            savedState = null;
                        }
                        currentState = UserState.BEFORE_BINANCE_TRADING;
                        SendMessage message = new SendMessage();
                        message.setChatId(String.valueOf(chatId));
                        message.setText(" Введите параметры для торговли на Binance. \n\n" +
                                "Образец: монета, сумма_сделки_в_USDT, коэффициент_покупки_(в %), коэффициент_продажи_в_прибыль_(в %),коэффициент_продажи_в_убыток_(в %) частота_обновления_графика(в сек) \n\n"+
                                "Пример: bitcoin, 100, 3.5, 2, 8, 30 ");
                        setCancelKeyboard(chatId, message);
                        return;
                    }
                    break;
                }

                case CHECK_SAVED_STATE_BINANCE_TEST: {
                    if (messageText.equals("Отмена")) {
                        currentState = UserState.WAITING_FOR_OPTION;
                        setMenu(chatId, "Отменено. Что будем делать?");
                        return;
                    } else if (messageText.equals("📊 Продолжить торговлю")) {
                        // Восстановить торговлю из сохранённого состояния
                        resumeBinanceTestTrading(chatId, savedState);
                        return;
                    } else if (messageText.equals("🆕 Начать заново")) {
                        // Удалить старое состояние и запросить новые параметры
                        if (savedState != null) {
                            savedState = null;
                        }
                        currentState = UserState.BEFORE_BINANCE_TEST_TRADING;
                        SendMessage message = new SendMessage();
                        message.setChatId(String.valueOf(chatId));
                        message.setText(" Введите параметры для тестовой торговли на Binance. \n\n" +
                                "Образец: монета, сумма_сделки_в_USDT, коэффициент_покупки_(в %), коэффициент_продажи_в_прибыль_(в %),коэффициент_продажи_в_убыток_(в %) частота_обновления_графика(в сек) \n\n"+
                                "Пример: bitcoin, 100, 3.5, 2, 8, 30 ");
                        setCancelKeyboard(chatId, message);
                        return;
                    }
                    break;
                }

                case BEFORE_BINANCE_TRADING:{
                    if (messageText.equals("Отмена")) {
                        //    processThread.interrupt();
                        currentState = UserState.WAITING_FOR_OPTION;
                        setMenu(chatId, "Отменено. Что будем делать в этот раз?");
                        return;
                    }
                    conducting_binance_trading(update, chatId, messageText);
                    break;
                }

                case CONDUCTING_BINANCE_TRADING:{
                    if (messageText.equals("Остановить")){
                        processThread.interrupt();
                        currentState=UserState.WAITING_FOR_OPTION;
                        setMenu(chatId, "Остановлено. Что будем делать в этот раз?");
                        break;
                    }

                }

                case BEFORE_BINANCE_TEST_TRADING:{
                    if (messageText.equals("Отмена")) {
                        //    processThread.interrupt();
                        currentState = UserState.WAITING_FOR_OPTION;
                        setMenu(chatId, "Отменено. Что будем делать в этот раз?");
                        return;
                    }
                    conducting_binance_test_trading(update, chatId, messageText);
                    break;
                }

                case CONDUCTING_BINANCE_TEST_TRADING:{
                    if (messageText.equals("Остановить")){
                        processThread.interrupt();
                        currentState=UserState.WAITING_FOR_OPTION;
                        setMenu(chatId, "Остановлено. Что будем делать в этот раз?");
                        break;
                    }

                }

                case BEFORE_REAL_TIME_RESEARCH: {
                    if (messageText.equals("Отмена")) {
                        //    processThread.interrupt();
                        currentState = UserState.WAITING_FOR_OPTION;
                        setMenu(chatId, "Отменено. Что будем делать в этот раз?");
                        return;
                    }
                    conducting_real_time_research(update, chatId, messageText);
                    break;
                }

                case CONDUCTING_REAL_TIME_RESEARCH:{
                    if (messageText.equals("Остановить")){
                        processThread.interrupt();
                        currentState=UserState.WAITING_FOR_OPTION;
                        setMenu(chatId, "Остановлено. Что будем делать в этот раз?");
                        break;
                    }
                    break;
            }





//                    ReplyKeyboardRemove keyboardRemove = new ReplyKeyboardRemove();
//                    keyboardRemove.setRemoveKeyboard(true); // Скрыть клавиатуру
//                    keyboardRemove.setSelective(false); // Для всех пользователей
//
//                    SendMessage message = new SendMessage();
//                    message.setChatId(String.valueOf(chatId));
//                    message.setText("Клава удалена");
//                    message.setReplyMarkup(keyboardRemove); // Устанавливаем "пустую" клавиатуру
//
//                    try {
//                        execute(message);
//                    } catch (TelegramApiException e) {
//                        e.printStackTrace();
//                    }

                }




            }
        }



    @Override
    public String getBotToken() {
        return BOT_TOKEN; // Токен от @BotFather
    }

    @Override
    public String getBotUsername() {
        return "NEW_MAMA_CXHEMA";
    }

    public void start() {
        try {
            CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));
        } catch (Exception e) {
            log.warn("Не удалось загрузить список монет: {}", e.getMessage());
        }
        // Retry-логика для [409] — старый экземпляр не успел освободить соединение
        int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
                botsApi.registerBot(new MenuHandler());
                System.out.println("Бот запущен!");
                log.info("Telegram бот зарегистрирован (попытка {})", attempt);
                return;
            } catch (TelegramApiException e) {
                if (e.getMessage() != null && e.getMessage().contains("409")) {
                    log.warn("Бот уже запущен [409], жду 10 сек перед попыткой {}/{}", attempt, maxAttempts);
                    try { Thread.sleep(10_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                } else {
                    log.error("Ошибка запуска бота: {}", e.getMessage(), e);
                    return;
                }
            } catch (Exception e) {
                log.error("Ошибка запуска бота: {}", e.getMessage(), e);
                return;
            }
        }
        log.error("Не удалось запустить бота после {} попыток", maxAttempts);
    }
    public void setMenu (long chatId, String text) {


        keyboardMarkup.setResizeKeyboard(true); // Подгоняем размер кнопок
        keyboardMarkup.setOneTimeKeyboard(true); // Скрыть после выбора

        // Создаем ряды кнопок
        List<KeyboardRow> keyboard = new ArrayList<>();

        // Первый ряд
        KeyboardRow row1 = new KeyboardRow();
        row1.add("Торговля");
        row1.add("Тестовая торговля на Binance");

        // Второй ряд
        KeyboardRow row2 = new KeyboardRow();
        row2.add("Исследование в реальном времени");
        row2.add("Историческое исследование");

        // Третий ряд (одна кнопка)
        KeyboardRow row3 = new KeyboardRow();
        row3.add("Отмена");

        // Добавляем ряды в клавиатуру
        keyboard.add(row1);
        keyboard.add(row2);
        keyboard.add(row3);

        // Устанавливаем клавиатуру
        keyboardMarkup.setKeyboard(keyboard);

        // Создаем сообщение
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setReplyMarkup(keyboardMarkup);

        try {
            execute(message); // Отправка
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Отправка простого текста
    private void sendText(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
    }
}


private void setCancelKeyboard(long chatId, SendMessage message) {
        try {
            List<KeyboardRow> keyboardCancel = new ArrayList<>();
            KeyboardRow keyboardRow = new KeyboardRow(1);
            keyboardRow.add("Отмена");
            keyboardCancel.add(keyboardRow);
            keyboardMarkup.setKeyboard(keyboardCancel);
            keyboardMarkup.setResizeKeyboard(true);
            execute(message); // Отправка
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
}

private  void conducting_back_testing_research (long chatId, String stringCoin){
    Coin coin;
    Chart chart;

    try {
        CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));
    } catch (IOException e) {
        sendText(chatId, " При загрузки из базы списка криптовалют");
        return;
    }
    try {
        coin=CoinsList.getCoinByName(stringCoin);
    } catch (Exception e) {
        sendText(chatId, " При попытке создать монету была допущена ошибка или нет такой криптовалюты");
        return;
    }
    try {
        chart = Chart.loadFromJson(new File("YearlyCharts/" + coin.getName()+"/Yearlychart.json"));
    } catch (Exception e) {
        sendText(chatId, " При попытке загрузить график для " + coin.getName()+ " произошла ошибка");
        return;
    }
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                Application.launch(TradingChart.class);
//            }
//        }).start();
    try {
        List<KeyboardRow> keyboardCancel = new ArrayList<>();
        KeyboardRow keyboardRow = new KeyboardRow(1);
        keyboardRow.add("Остановить");
        keyboardCancel.add(keyboardRow);
        keyboardMarkup.setKeyboard(keyboardCancel);
        keyboardMarkup.setResizeKeyboard(true);
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Начинаем историческое исследование монеты: " + coin.getName());
        message.setReplyMarkup(keyboardMarkup); // Устанавливаем на клавиатуре кнопку "Остановить"
        execute(message); // Отправка
    }catch (TelegramApiException e) {
        throw new RuntimeException(e);
    }


    currentState=UserState.CONDUCTING_BACK_TESTING_RESEARCH;
    Thread progressThread=null;
    processThread = new Thread() {
        @Override
        public void run() {
            TreeSet<ReversalPointStrategyBackTester.BackTestResult> backTestResults = new TreeSet<>();
            double step = 0.1;
            double startBuyGap = 0.1;
            double maxBuyGap = 5;
            double startSellWithProfitGap = 1;
            double maxSellWithProfitGap = 5;
            double startSellWithLossGap = 1;
            double maxSellWithLossGap = 5;
            final int  iterations  = (int) (((maxSellWithLossGap - startSellWithLossGap+step) / step) * ((maxSellWithProfitGap - startSellWithProfitGap+step) / step) * ((maxBuyGap - startBuyGap+step) / step)-step);
            AtomicInteger counter = new AtomicInteger(iterations);

            Thread progressThread = new Thread() {
                public void run() {

                    while (!(Thread.currentThread().isInterrupted()||processThread.isInterrupted())) {
                        try {
                            ImageAndMessageSender.sendTelegramMessage("Progress: " + Math.round(100- (double)counter.get() / iterations * 100) + "%", chatId);
                            TimeUnit.SECONDS.sleep(15);
                        } catch (InterruptedException e) {
                            System.out.println("Progress thread in conducting_back_testing_research was interrupted");
                            interrupt();
//                            processThread.interrupt();
                        }
                    }
                }
            };
            progressThread.start();
            for (double buyGap = startBuyGap; buyGap <= maxBuyGap; buyGap += step) {
                if (Thread.currentThread().isInterrupted()) {
                    progressThread.interrupt();
                    return;
                }
                for (double sellWithProfitGapGap = startSellWithProfitGap; sellWithProfitGapGap <= maxSellWithProfitGap; sellWithProfitGapGap += step) {
                    for (double sellWithLossGap = startSellWithLossGap; sellWithLossGap <= maxSellWithLossGap; sellWithLossGap += step) {
                        try {
                            backTestResults.add(new ReversalPointStrategyBackTester(Coin.createCoin(coin.getName()), chart, 80, buyGap, sellWithProfitGapGap, sellWithLossGap).startBackTest());
                            if (backTestResults.size() > 5) backTestResults.remove(backTestResults.last());
                            counter.getAndDecrement();
                        } catch (NullPointerException e) {
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        ; //подавляем Exception, когда результат = 0
//                        System.out.println(counter-- + " iterations left");

                    }
                }
            }
            progressThread.interrupt();
            ImageAndMessageSender.sendTelegramMessage("Progress: 100%", chatId);
            ImageAndMessageSender.sendTelegramMessage(backTestResults.size() + " лучших результатов:",chatId);
            for (ReversalPointStrategyBackTester.BackTestResult result : backTestResults) {
                ImageAndMessageSender.sendTelegramMessage(result.toString(),chatId);
                try {
                    TimeUnit.SECONDS.sleep(3);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            currentState=UserState.WAITING_FOR_OPTION;
            setMenu(chatId," Готово. Что будем делать дальше?");
        }
    };

        processThread.start();
        //      processThread.join();

        //processThread.interrupt();





    }









private void conducting_real_time_research (Update update, long chatId, String messageText){
    Coin coin;
    double buyGap, sellWithProfitGap, sellWithLossGap, startAssets, tradingSum;
    int updateTimeout;
    String[] parameters = messageText.trim().split(",");
    parameters=Stream.of(parameters)
            .map(String::trim)
            .toArray(String[]::new);
    if (parameters.length!=7) {sendText(chatId, " При вводе строки параметров была допущена ошибка"); return;}
    try {
        coin = CoinsList.getCoinByName(parameters[0]);
    } catch (Exception e) {
        sendText(chatId, " При попытке создать монету была допущена ошибка или нет такой криптовалюты");
        return;
    }
    try {
        startAssets= Double.parseDouble(parameters[1]);
    } catch (Exception e) {
        sendText(chatId, " Ошибка определения стартовой суммы");
        return;
    }
    try {
        tradingSum= Double.parseDouble(parameters[2]);
    } catch (Exception e) {
        sendText(chatId, " Ошибка определения суммы сделки");
        return;
    }
    try {
        buyGap= Double.parseDouble(parameters[3]);
    } catch (Exception e) {
        sendText(chatId, " Ошибка определения коэффициента покупки");
        return;
    }
    try {
        sellWithProfitGap= Double.parseDouble(parameters[4]);
    } catch (Exception e) {
        sendText(chatId, " Ошибка определения коэффициента продажи в прибыль");
        return;
    }
    try {
        sellWithLossGap= Double.parseDouble(parameters[5]);
    } catch (Exception e) {
        sendText(chatId, " Ошибка определения коэффициента продажи в убыток");
        return;
    }
    try {
        updateTimeout= Integer.parseInt(parameters[6]);
    } catch (Exception e) {
        sendText(chatId, " Ошибка определения частоты обновления графика");
        return;
    }

    try {
        try {
            List<KeyboardRow> keyboardCancel = new ArrayList<>();
            KeyboardRow keyboardRow = new KeyboardRow(1);
            keyboardRow.add("Остановить");
            keyboardCancel.add(keyboardRow);
            keyboardMarkup.setKeyboard(keyboardCancel);
            keyboardMarkup.setResizeKeyboard(true);
            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText("Начинаем исследование в реальном времени \n" +
            "Монета: " + coin.getName() + "\n" +
            "Стартовый баланс: " + parameters[1] + " USDT" + "\n" +
            "Сумма сделки: " + parameters[2] + " USDT" + "\n" +
            "Коэффициент покупки: " + buyGap + "%" + "\n" +
            "Коэффициент продажи в прибыль: " + sellWithProfitGap + "%" + "\n" +
            "Коэффициент продажи в убыток: " + sellWithLossGap + "%" + "\n" +
            "Частота обновления графика: " + updateTimeout + " сек" + "\n"
            );
            message.setReplyMarkup(keyboardMarkup); // Устанавливаем на клавиатуре кнопку "Остановить"
            execute(message); // Отправка
            processThread = new Thread() {
                @Override
                public void run() {
                    try {
                        CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));
                        Map<Coin, Double> testAssets = new HashMap();
                        testAssets.put(Coin.createCoin("Tether"), startAssets);
                        testAssets.put(coin, 0d);
                        Account testAccount = AccountBuilder.createNewTester(testAssets);
                        currentState=UserState.CONDUCTING_REAL_TIME_RESEARCH;
                        new ReversalPointsStrategyTrader(testAccount,coin,tradingSum,buyGap,sellWithProfitGap, sellWithLossGap, updateTimeout, chatId).startTrading();
                    } catch (Exception e) {
                        System.err.println("Ошибка во время проведения исследования в реальном времени");
                        e.printStackTrace();
                        ImageAndMessageSender.sendTelegramMessage(e.getMessage());
                        //throw new RuntimeException(e);
                    }
                }
            };
            processThread.start();

        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    } catch (Exception e) {
        throw new RuntimeException(e);
    }finally {

        TradingChart.clearChart();
    }
}


    private void conducting_binance_test_trading (Update update, long chatId, String messageText){
        Coin coin;
        double buyGap, sellWithProfitGap, sellWithLossGap, startAssets, tradingSum;
        int updateTimeout;
        char[] TEST_Ed25519_PRIVATE_KEY = AppConfig.getInstance().resolvePrivateKeyPath(AppConfig.getInstance().getBinanceTestPrivateKeyPath()).toCharArray();
        char[] TEST_Ed25519_API_KEY = AppConfig.getInstance().getBinanceTestApiKey().toCharArray();

        Account testBinanceAccount = null;
        try {
            testBinanceAccount = AccountBuilder.createNewBinance(TEST_Ed25519_API_KEY, TEST_Ed25519_PRIVATE_KEY, AccountBuilder.BINANCE_BASE_URL.TESTNET);
        } catch (IOException e) {
            e.printStackTrace();
            ImageAndMessageSender.sendTelegramMessage("Ошибка при создани аккаунта Binance:\n" + e.getMessage());
        }

        String[] parameters = messageText.trim().split(",");
        parameters=Stream.of(parameters)
                .map(String::trim)
                .toArray(String[]::new);
        if (parameters.length!=6) {sendText(chatId, " При вводе строки параметров была допущена ошибка"); return;}
        try {
            coin = CoinsList.getCoinByName(parameters[0].toLowerCase());
            System.out.println(parameters[0]);
        } catch (Exception e) {
            sendText(chatId, " При попытке создать монету была допущена ошибка или нет такой криптовалюты");
            return;
        }
        try {
            tradingSum= Double.parseDouble(parameters[1]);
        } catch (Exception e) {
            sendText(chatId, " Ошибка определения суммы сделки");
            return;
        }
        try {
            buyGap= Double.parseDouble(parameters[2]);
        } catch (Exception e) {
            sendText(chatId, " Ошибка определения коэффициента покупки");
            return;
        }
        try {
            sellWithProfitGap= Double.parseDouble(parameters[3]);
        } catch (Exception e) {
            sendText(chatId, " Ошибка определения коэффициента продажи в прибыль");
            return;
        }
        try {
            sellWithLossGap= Double.parseDouble(parameters[4]);
        } catch (Exception e) {
            sendText(chatId, " Ошибка определения коэффициента продажи в убыток");
            return;
        }
        try {
            updateTimeout= Integer.parseInt(parameters[5]);
        } catch (Exception e) {
            sendText(chatId, " Ошибка определения частоты обновления графика");
            return;
        }

        try {
            try {
                List<KeyboardRow> keyboardCancel = new ArrayList<>();
                KeyboardRow keyboardRow = new KeyboardRow(1);
                keyboardRow.add("Остановить");
                keyboardCancel.add(keyboardRow);
                keyboardMarkup.setKeyboard(keyboardCancel);
                keyboardMarkup.setResizeKeyboard(true);
                SendMessage message = new SendMessage();
                message.setChatId(String.valueOf(chatId));
                message.setText("Начинаем тестовую торговлю на Binance \n" +
                        "Монета: " + coin.getName() + "\n" +
                        "Текущее количество USDT: " + testBinanceAccount.wallet().getCoinBalance(Account.USD_TOKENS.USDT.getCoin()) + "\n" +
                        "Сумма сделки: " + parameters[1] + " USDT" + "\n" +
                        "Коэффициент покупки: " + buyGap + "%" + "\n" +
                        "Коэффициент продажи в прибыль: " + sellWithProfitGap + "%" + "\n" +
                        "Коэффициент продажи в убыток: " + sellWithLossGap + "%" + "\n" +
                        "Частота обновления графика: " + updateTimeout + " сек" + "\n"
                );
                message.setReplyMarkup(keyboardMarkup); // Устанавливаем на клавиатуре кнопку "Остановить"
                execute(message); // Отправка
                Account finalTestBinanceAccount = testBinanceAccount;  // создаем final переменную, чтобы можно было ее использовать в innerClass
                processThread = new Thread() {
                    @Override
                    public void run() {
                        try {
                            CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));
                            currentState=UserState.CONDUCTING_REAL_TIME_RESEARCH;
                            new ReversalPointsStrategyTrader(finalTestBinanceAccount,coin,tradingSum,buyGap,sellWithProfitGap, sellWithLossGap, updateTimeout, chatId).startTrading();

                        } catch (BinanceClientException | IOException | NullPointerException e) {
                            System.err.println("Ошибка во время проведения исследования в реальном времени");
                            e.printStackTrace();
                            ImageAndMessageSender.sendTelegramMessage(e.getMessage());
                        } catch (Exception e) {
                            e.printStackTrace();
                            ImageAndMessageSender.sendTelegramMessage(e.getMessage());
                        }
                    }
                };
                processThread.start();

            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }finally {

            TradingChart.clearChart();
        }
    }

    private void conducting_binance_trading (Update update, long chatId, String messageText){
        Coin coin;
        double buyGap, sellWithProfitGap, sellWithLossGap, startAssets, tradingSum;
        int updateTimeout;
        final  char[] Ed25519_PRIVATE_KEY = AppConfig.getInstance().resolvePrivateKeyPath(AppConfig.getInstance().getBinancePrivateKeyPath()).toCharArray();
        final  char[] Ed25519_API_KEY = AppConfig.getInstance().getBinanceApiKey().toCharArray();

        Account BinanceAccount = null;
        try {
            BinanceAccount = AccountBuilder.createNewBinance(Ed25519_API_KEY, Ed25519_PRIVATE_KEY, AccountBuilder.BINANCE_BASE_URL.MAINNET);
        } catch (IOException e) {
            e.printStackTrace();
            ImageAndMessageSender.sendTelegramMessage("Ошибка при создани аккаунта Binance:\n" + e.getMessage());
        }

        String[] parameters = messageText.trim().split(",");
        parameters=Stream.of(parameters)
                .map(String::trim)
                .toArray(String[]::new);
        if (parameters.length!=6) {sendText(chatId, " При вводе строки параметров была допущена ошибка"); return;}
        try {
            coin = CoinsList.getCoinByName(parameters[0].toLowerCase());
            System.out.println(parameters[0]);
        } catch (Exception e) {
            sendText(chatId, " При попытке создать монету была допущена ошибка или нет такой криптовалюты");
            return;
        }
        try {
            tradingSum= Double.parseDouble(parameters[1]);
        } catch (Exception e) {
            sendText(chatId, " Ошибка определения суммы сделки");
            return;
        }
        try {
            buyGap= Double.parseDouble(parameters[2]);
        } catch (Exception e) {
            sendText(chatId, " Ошибка определения коэффициента покупки");
            return;
        }
        try {
            sellWithProfitGap= Double.parseDouble(parameters[3]);
        } catch (Exception e) {
            sendText(chatId, " Ошибка определения коэффициента продажи в прибыль");
            return;
        }
        try {
            sellWithLossGap= Double.parseDouble(parameters[4]);
        } catch (Exception e) {
            sendText(chatId, " Ошибка определения коэффициента продажи в убыток");
            return;
        }
        try {
            updateTimeout= Integer.parseInt(parameters[5]);
        } catch (Exception e) {
            sendText(chatId, " Ошибка определения частоты обновления графика");
            return;
        }

        try {
            try {
                List<KeyboardRow> keyboardCancel = new ArrayList<>();
                KeyboardRow keyboardRow = new KeyboardRow(1);
                keyboardRow.add("Остановить");
                keyboardCancel.add(keyboardRow);
                keyboardMarkup.setKeyboard(keyboardCancel);
                keyboardMarkup.setResizeKeyboard(true);
                SendMessage message = new SendMessage();
                message.setChatId(String.valueOf(chatId));
                message.setText("Начинаем торговлю на Binance \n" +
                        "Монета: " + coin.getName() + "\n" +
                        "Текущее количество USDT: " + BinanceAccount.wallet().getCoinBalance(Account.USD_TOKENS.USDT.getCoin()) + "\n" +
                        "Сумма сделки: " + parameters[1] + " USDT" + "\n" +
                        "Коэффициент покупки: " + buyGap + "%" + "\n" +
                        "Коэффициент продажи в прибыль: " + sellWithProfitGap + "%" + "\n" +
                        "Коэффициент продажи в убыток: " + sellWithLossGap + "%" + "\n" +
                        "Частота обновления графика: " + updateTimeout + " сек" + "\n"
                );
                message.setReplyMarkup(keyboardMarkup); // Устанавливаем на клавиатуре кнопку "Остановить"
                execute(message); // Отправка
                Account finalBinanceAccount = BinanceAccount;  // создаем final переменную, чтобы можно было ее использовать в innerClass
                processThread = new Thread() {
                    @Override
                    public void run() {
                        try {
                            CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));
                            currentState=UserState.CONDUCTING_REAL_TIME_RESEARCH;
                            new ReversalPointsStrategyTrader(finalBinanceAccount,coin,tradingSum,buyGap,sellWithProfitGap, sellWithLossGap, updateTimeout, chatId).startTrading();

                        } catch (BinanceClientException | IOException | NullPointerException e) {
                            System.err.println("Ошибка во время проведения исследования в реальном времени");
                            e.printStackTrace();
                            ImageAndMessageSender.sendTelegramMessage(e.getMessage());
                        } catch (Exception e) {
                            e.printStackTrace();
                            ImageAndMessageSender.sendTelegramMessage(e.getMessage());
                        }
                    }
                };
                processThread.start();

            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }finally {

            TradingChart.clearChart();
        }
    }

    /**
     * Проверяет наличие сохранённого состояния и показывает кнопки выбора
     */
    private void checkSavedStateAndPrompt(long chatId, String accountType) {
        savedState = findAnyLegacyState(accountType);
        
        if (savedState != null) {
            // Есть сохранённое состояние - показать выбор
            List<KeyboardRow> keyboard = new ArrayList<>();
            KeyboardRow row1 = new KeyboardRow();
            row1.add("📊 Продолжить торговлю");
            KeyboardRow row2 = new KeyboardRow();
            row2.add("🆕 Начать заново");
            KeyboardRow row3 = new KeyboardRow();
            row3.add("Отмена");
            keyboard.add(row1);
            keyboard.add(row2);
            keyboard.add(row3);
            keyboardMarkup.setKeyboard(keyboard);
            keyboardMarkup.setResizeKeyboard(true);
            
            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText("🔄 Найдена незавершённая торговая сессия!\n\n" +
                    "Монета: " + savedState.getCoinName() + "\n" +
                    "Сохранено: " + new Date(savedState.getTimestamp()) + "\n" +
                    "Сумма сделки: " + savedState.getTradingSum() + " USDT\n" +
                    "Коэффициенты: buy=" + savedState.getBuyGap() + "%, profit=" + 
                    savedState.getSellWithProfitGap() + "%, loss=" + savedState.getSellWithLossGap() + "%\n" +
                    "Статус: " + (savedState.isTrading() ? "В торговле" : "Ищет точку входа") + "\n\n" +
                    "Что делать?");
            message.setReplyMarkup(keyboardMarkup);
            
            try {
                execute(message);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }
        } else {
            // Нет сохранённого состояния - сразу запросить параметры
            savedState = null;
            if (accountType.equals("BINANCEACCOUNT")) {
                currentState = UserState.BEFORE_BINANCE_TRADING;
            } else {
                currentState = UserState.BEFORE_BINANCE_TEST_TRADING;
            }
            
            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            String tradingType = accountType.equals("BINANCEACCOUNT") ? "торговли" : "тестовой торговли";
            message.setText(" Введите параметры для " + tradingType + " на Binance. \n\n" +
                    "Образец: монета, сумма_сделки_в_USDT, коэффициент_покупки_(в %), коэффициент_продажи_в_прибыль_(в %),коэффициент_продажи_в_убыток_(в %) частота_обновления_графика(в сек) \n\n"+
                    "Пример: bitcoin, 100, 3.5, 2, 8, 30 ");
            setCancelKeyboard(chatId, message);
        }
    }

    /**
     * Восстановить торговлю на Binance из сохранённого состояния
     */
    private void resumeBinanceTrading(long chatId, TradingState state) {
        if (state == null) {
            ErrorHandler.handleError(new IllegalStateException("State is null"),
                "Resume Binance Trading", "State validation", false);
            sendText(chatId, "❌ Ошибка: состояние не найдено");
            return;
        }
        
        try {
            // Загрузка списка монет с retry
            RetryPolicy fileRetry = RetryPolicy.forFileOperations();
            fileRetry.executeVoid(() -> {
                try {
                    CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, "Load coins list");
            
            Coin coin = CoinsList.getCoinByName(state.getCoinName());
            
            final char[] Ed25519_PRIVATE_KEY = AppConfig.getInstance().resolvePrivateKeyPath(AppConfig.getInstance().getBinancePrivateKeyPath()).toCharArray();
            final char[] Ed25519_API_KEY = AppConfig.getInstance().getBinanceApiKey().toCharArray();
            
            // Создание аккаунта с retry
            Account binanceAccount;
            try {
                RetryPolicy accountRetry = RetryPolicy.forApiCalls();
                binanceAccount = accountRetry.execute(() -> {
                    try {
                        return AccountBuilder.createNewBinance(Ed25519_API_KEY, Ed25519_PRIVATE_KEY, 
                            AccountBuilder.BINANCE_BASE_URL.MAINNET);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, "Create Binance account");
            } catch (Exception e) {
                ErrorHandler.handleFatalError(e, "Binance Account Creation",
                    "Creating Binance account for resume trading");
                sendText(chatId, "❌ Не удалось создать Binance аккаунт. Проверьте API ключи.");
                return;
            }
            
            List<KeyboardRow> keyboard = new ArrayList<>();
            KeyboardRow row = new KeyboardRow(1);
            row.add("Остановить");
            keyboard.add(row);
            keyboardMarkup.setKeyboard(keyboard);
            keyboardMarkup.setResizeKeyboard(true);
            
            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText("🔄 Восстанавливаю торговлю...");
            message.setReplyMarkup(keyboardMarkup);
            execute(message);
            
            Account finalAccount = binanceAccount;
            processThread = new Thread() {
                @Override
                public void run() {
                    try {
                        currentState = UserState.CONDUCTING_BINANCE_TRADING;
                        // Передаём savedState в конструктор трейдера
                        new ReversalPointsStrategyTrader(
                                finalAccount, coin, 0, 0, 0, 0, 0, chatId, state, null
                        ).startTrading();
                    } catch (Exception e) {
                        log.error("Error during resumed Binance trading", e);
                        ErrorHandler.handleFatalError(e, "Binance Trading", 
                            "Running resumed trading session");
                    }
                }
            };
            processThread.start();
            
        } catch (Exception e) {
            log.error("Failed to resume Binance trading", e);
            ErrorHandler.handleFatalError(e, "Resume Binance Trading",
                "Setting up resumed trading session");
            sendText(chatId, "❌ Ошибка при восстановлении торговли: " + e.getMessage());
        } finally {
            savedState = null;
        }
    }

    /**
     * Восстановить тестовую торговлю на Binance из сохранённого состояния
     */
    private void resumeBinanceTestTrading(long chatId, TradingState state) {
        if (state == null) {
            ErrorHandler.handleError(new IllegalStateException("State is null"),
                "Resume Binance Test Trading", "State validation", false);
            sendText(chatId, "❌ Ошибка: состояние не найдено");
            return;
        }
        
        try {
            // Загрузка списка монет с retry
            RetryPolicy fileRetry = RetryPolicy.forFileOperations();
            fileRetry.executeVoid(() -> {
                try {
                    CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }, "Load coins list");
            
            Coin coin = CoinsList.getCoinByName(state.getCoinName());
            
            char[] TEST_Ed25519_PRIVATE_KEY = AppConfig.getInstance().resolvePrivateKeyPath(AppConfig.getInstance().getBinanceTestPrivateKeyPath()).toCharArray();
            char[] TEST_Ed25519_API_KEY = AppConfig.getInstance().getBinanceTestApiKey().toCharArray();
            
            // Создание тестового аккаунта с retry
            Account testBinanceAccount;
            try {
                RetryPolicy accountRetry = RetryPolicy.forApiCalls();
                testBinanceAccount = accountRetry.execute(() -> {
                    try {
                        return AccountBuilder.createNewBinance(TEST_Ed25519_API_KEY, TEST_Ed25519_PRIVATE_KEY, 
                            AccountBuilder.BINANCE_BASE_URL.TESTNET);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, "Create Binance test account");
            } catch (Exception e) {
                ErrorHandler.handleFatalError(e, "Binance Test Account Creation",
                    "Creating Binance test account for resume trading");
                sendText(chatId, "❌ Не удалось создать Binance тестовый аккаунт. Проверьте API ключи.");
                return;
            }
            
            List<KeyboardRow> keyboard = new ArrayList<>();
            KeyboardRow row = new KeyboardRow(1);
            row.add("Остановить");
            keyboard.add(row);
            keyboardMarkup.setKeyboard(keyboard);
            keyboardMarkup.setResizeKeyboard(true);
            
            SendMessage message = new SendMessage();
            message.setChatId(String.valueOf(chatId));
            message.setText("🔄 Восстанавливаю тестовую торговлю...");
            message.setReplyMarkup(keyboardMarkup);
            execute(message);
            
            Account finalAccount = testBinanceAccount;
            processThread = new Thread() {
                @Override
                public void run() {
                    try {
                        currentState = UserState.CONDUCTING_BINANCE_TEST_TRADING;
                        // Передаём savedState в конструктор трейдера
                        new ReversalPointsStrategyTrader(
                                finalAccount, coin, 0, 0, 0, 0, 0, chatId, state, null
                        ).startTrading();
                    } catch (Exception e) {
                        log.error("Error during resumed Binance test trading", e);
                        ErrorHandler.handleFatalError(e, "Binance Test Trading", 
                            "Running resumed test trading session");
                    }
                }
            };
            processThread.start();
            
        } catch (Exception e) {
            log.error("Failed to resume Binance test trading", e);
            ErrorHandler.handleFatalError(e, "Resume Binance Test Trading",
                "Setting up resumed test trading session");
            sendText(chatId, "❌ Ошибка при восстановлении тестовой торговли: " + e.getMessage());
        } finally {
            savedState = null;
        }
    }

    /**
     * Scan trading_states/ for any state file matching the account type suffix.
     * Replaces the removed StateManager.findAnyState() for legacy Telegram-bot flows.
     */
    private TradingState findAnyLegacyState(String accountType) {
        java.io.File dir = new java.io.File("trading_states");
        if (!dir.exists() || !dir.isDirectory()) return null;
        // Files are named <sessionId>.json — scan all and match by accountType inside
        java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json") && !n.contains(".bak."));
        if (files == null || files.length == 0) return null;
        // Sort newest first
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (java.io.File f : files) {
            try {
                TradingState state = TradingState.loadFromFile(f.getPath());
                if (state == null || state.getAccountType() == null) continue;
                if (!state.getAccountType().equalsIgnoreCase(accountType)) continue;
                long ageMs = System.currentTimeMillis() - state.getTimestamp();
                if (ageMs > java.util.concurrent.TimeUnit.HOURS.toMillis(24)) continue;
                return state;
            } catch (Exception ignored) {}
        }
        return null;
    }

}

