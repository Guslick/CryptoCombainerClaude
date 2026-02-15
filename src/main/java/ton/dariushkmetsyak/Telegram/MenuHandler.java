package ton.dariushkmetsyak.Telegram;

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
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.CoinsList;
import ton.dariushkmetsyak.Graphics.DrawTradingChart.TradingChart;
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointStrategyBackTester;
import ton.dariushkmetsyak.Strategies.ReversalPointsStrategy.ResearchingStrategy.ReversalPointsStrategyTrader;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;
import ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder;


import javax.sound.midi.Soundbank;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class MenuHandler extends TelegramLongPollingBot {
    private static final String BOT_TOKEN = "7420980540:AAENPop_SY3bBVHl8kNxT97Mxazxthvk8Jo";
  //  final private static String chatId = "-1002382149738";
    ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
    Thread processThread;
    enum UserState {
        IDLE,          // Ничего не ждём
        WAITING_FOR_OPTION,  // Ждём выбор варианта (1-4)
        BEFORE_REAL_TIME_RESEARCH,
        CONDUCTING_REAL_TIME_RESEARCH,  // Ждём строку
        BEFORE_BACK_TESTING_RESEARCH,
        CONDUCTING_BACK_TESTING_RESEARCH,  // Ждём строку
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

            switch (currentState){
                case WAITING_FOR_OPTION: {
                    if (messageText.equals("/start")) {
                        setMenu(chatId, "Что будем делать?");
                        break;
                    } else if (messageText.equals("Остановить")) {
                        setMenu(chatId, "Что будем делать");
                        break;
                    } else if (messageText.equals("Торговля")) {
                        sendText(chatId, "Еще не готово");
                        break;
                    } else if (messageText.equals("Тестовая торговля на Binance")) {
                        sendText(chatId, "Еще не готово");
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
    try{
        CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(new MenuHandler());
        System.out.println("Бот запущен!");
    } catch(
    Exception e)

    {
        e.printStackTrace();
    }
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
            double maxBuyGap = 3;
            double startSellWithProfitGap = 0.1;
            double maxSellWithProfitGap = 3;
            double startSellWithLossGap = 0.1;
            double maxSellWithLossGap = 3;
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

}

