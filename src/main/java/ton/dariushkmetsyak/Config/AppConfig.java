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

    /**
     * Принимает значение из конфига и возвращает путь к PEM-файлу.
     *
     * Поддерживает два формата в config.properties:
     *
     * Формат 1 — путь к файлу (старый):
     *   binance.private.key.path=/home/user/Ed_PV.pem
     *
     * Формат 2 — содержимое ключа напрямую (новый):
     *   binance.private.key.path=-----BEGIN PRIVATE KEY-----\nqwerty123...\n-----END PRIVATE KEY-----
     *   или просто base64-тело без заголовков:
     *   binance.private.key.path=qwerty123...
     *
     * Если значение не является путём к существующему файлу —
     * содержимое записывается во временный файл и удаляется при завершении JVM.
     */
    public String resolvePrivateKeyPath(String configValue) {
        if (configValue == null || configValue.isBlank()) return configValue;

        // Если это путь к существующему файлу — используем как есть
        File asFile = new File(configValue);
        if (asFile.exists() && asFile.isFile()) {
            return configValue;
        }

        // Иначе — считаем, что это содержимое ключа
        try {
            // Заменяем литеральные \n (как пишут в .properties) на реальные переносы строк
            String content = configValue.replace("\\n", "\n");

            // Если нет PEM-заголовков — добавляем стандартные
            if (!content.contains("-----BEGIN")) {
                content = "-----BEGIN PRIVATE KEY-----\n"
                        + content.trim() + "\n"
                        + "-----END PRIVATE KEY-----\n";
            }

            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("binance_pk_", ".pem");
            java.nio.file.Files.writeString(tmp, content);
            tmp.toFile().deleteOnExit();
            log.debug("PEM-ключ записан во временный файл: {}", tmp);
            return tmp.toString();
        } catch (IOException e) {
            log.error("Не удалось записать PEM-ключ во временный файл: {}", e.getMessage());
            return configValue;
        }
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
