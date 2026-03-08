package ExecPack;

import ton.dariushkmetsyak.Api.TelegramMiniAppApiServer;
import ton.dariushkmetsyak.Api.TradingSessionManager;
import ton.dariushkmetsyak.Config.RuntimeConfig;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.CoinsList;
import ton.dariushkmetsyak.Telegram.MenuHandler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class App {

    public static void main(String[] args) throws Exception {
        initDefaultLogDirectory();
        CoinsList.loadCoinsWithMarketDataFormJsonFile(new File("coins"));

        String appMode = RuntimeConfig.get("APP_MODE", "BOTH").toUpperCase();
        System.out.println("Starting app with APP_MODE=" + appMode);

        if ("API".equals(appMode) || "BOTH".equals(appMode)) {
            TradingSessionManager manager = new TradingSessionManager();
            new TelegramMiniAppApiServer(manager).start();
        }

        if ("BOT".equals(appMode) || "BOTH".equals(appMode)) {
            if (RuntimeConfig.has("TELEGRAM_BOT_TOKEN")) {
                try {
                    new MenuHandler().start();
                } catch (Exception e) {
                    System.err.println("Failed to start Telegram bot part. API stays available.");
                    e.printStackTrace();
                }
            } else {
                System.err.println("TELEGRAM_BOT_TOKEN is missing. Skipping BOT startup, API mode remains available.");
            }
        }

        if ("API".equals(appMode)) {
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
            e.printStackTrace();
        }
    }
}
