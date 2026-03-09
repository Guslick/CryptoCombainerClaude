package ExecPack;

import ton.dariushkmetsyak.Config.AppConfig;
import ton.dariushkmetsyak.GeckoApiService.GeckoRequests;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.CoinsList;
import ton.dariushkmetsyak.Telegram.MenuHandler;
import ton.dariushkmetsyak.Web.MiniAppServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        initDefaultLogDirectory();

        AppConfig cfg = AppConfig.getInstance();
        log.info("=== CryptoCombainer запускается ===");

        // Настройка CoinGecko API
        GeckoRequests.setApiKey(cfg.getGeckoApiKey());

        // Загрузка списка монет
        File coinsFile = new File("coins");
        if (coinsFile.exists()) {
            CoinsList.loadCoinsWithMarketDataFormJsonFile(coinsFile);
            log.info("Загружено {} монет из файла", CoinsList.getCoins().size());
        } else {
            log.warn("Файл coins не найден! Загружаю из CoinGecko...");
            CoinsList.getCoinsWithMarketData_And_SaveToJsonFile(coinsFile);
            log.info("Загружено {} монет из CoinGecko", CoinsList.getCoins().size());
        }

        // Запуск веб-сервера (MiniApp)
        if (cfg.isWebServerEnabled()) {
            MiniAppServer webServer = new MiniAppServer();
            webServer.start();
            log.info("MiniApp доступен: http://localhost:{}", cfg.getWebServerPort());
            Runtime.getRuntime().addShutdownHook(new Thread(webServer::stop));
        } else {
            log.info("Веб-сервер отключён в конфигурации");
        }

        // Запуск Telegram бота
        if (cfg.isBotEnabled()) {
            new MenuHandler().start();
        } else {
            log.info("Telegram бот отключён в конфигурации");
            Thread.currentThread().join();
        }
    }

    private static void initDefaultLogDirectory() {
        String configuredLogDir = System.getProperty("LOG_DIR");
        if (configuredLogDir == null || configuredLogDir.isBlank()) {
            configuredLogDir = System.getenv("LOG_DIR");
        }
        if (configuredLogDir == null || configuredLogDir.isBlank()) {
            configuredLogDir = "logs";
        }
        System.setProperty("LOG_DIR", configuredLogDir);
        try {
            Files.createDirectories(Path.of(configuredLogDir));
        } catch (IOException e) {
            System.err.println("Не удалось создать каталог логов: " + configuredLogDir);
        }
    }
}
