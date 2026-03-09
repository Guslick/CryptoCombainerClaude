package ton.dariushkmetsyak.Config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Properties;

/**
 * Централизованная загрузка конфигурации.
 * Порядок приоритетов: переменные окружения > config.properties > значения по умолчанию
 */
public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static final String CONFIG_FILE = "config.properties";
    private static AppConfig instance;
    private final Properties props = new Properties();

    private AppConfig() {
        loadFromFile();
    }

    public static synchronized AppConfig getInstance() {
        if (instance == null) {
            instance = new AppConfig();
        }
        return instance;
    }

    private void loadFromFile() {
        // Try to load from current directory first
        File f = new File(CONFIG_FILE);
        if (f.exists()) {
            try (InputStream is = new FileInputStream(f)) {
                props.load(is);
                log.info("Конфигурация загружена из {}", f.getAbsolutePath());
                return;
            } catch (IOException e) {
                log.warn("Не удалось загрузить {}: {}", CONFIG_FILE, e.getMessage());
            }
        }
        // Try classpath
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                props.load(is);
                log.info("Конфигурация загружена из classpath/{}", CONFIG_FILE);
            } else {
                log.warn("Файл {} не найден, используются значения по умолчанию", CONFIG_FILE);
            }
        } catch (IOException e) {
            log.warn("Ошибка загрузки конфигурации: {}", e.getMessage());
        }
    }

    /** Получить значение: сначала env, потом props, потом defaultValue */
    public String get(String key, String defaultValue) {
        String envKey = key.toUpperCase().replace(".", "_");
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) return envVal;
        return props.getProperty(key, defaultValue);
    }

    public String get(String key) { return get(key, null); }

    public int getInt(String key, int defaultValue) {
        String v = get(key, String.valueOf(defaultValue));
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    // ---- Удобные методы доступа к конкретным параметрам ----

    public String getBotToken() {
        return get("telegram.bot.token", "YOUR_BOT_TOKEN_HERE");
    }

    public String getGeckoApiKey() {
        return get("gecko.api.key", "");
    }

    public String getBinanceApiKey() {
        return get("binance.api.key", "");
    }

    public String getBinancePrivateKeyPath() {
        return get("binance.private.key.path", "");
    }

    public String getBinanceTestApiKey() {
        return get("binance.test.api.key", "");
    }

    public String getBinanceTestPrivateKeyPath() {
        return get("binance.test.private.key.path", "");
    }

    public long getDefaultChatId() {
        String v = get("telegram.default.chat.id", "0");
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return 0L; }
    }

    public int getWebServerPort() {
        return getInt("web.server.port", 8080);
    }

    public String getWebServerHost() {
        return get("web.server.host", "0.0.0.0");
    }

    public boolean isBotEnabled() {
        return Boolean.parseBoolean(get("telegram.bot.enabled", "true"));
    }

    public boolean isWebServerEnabled() {
        return Boolean.parseBoolean(get("web.server.enabled", "true"));
    }
}
