package ton.dariushkmetsyak.Config;

/**
 * Совместимый слой поверх AppConfig.
 * Читает значения в порядке: системное свойство → переменная окружения → config.properties.
 * Добавлен для обратной совместимости с кодом, который вызывает RuntimeConfig.getRequired().
 */
public final class RuntimeConfig {
    private RuntimeConfig() {}

    public static boolean has(String key) {
        String value = get(key, null);
        return value != null && !value.isBlank();
    }

    /**
     * Получить обязательное значение.
     * Порядок поиска: System.getProperty → System.getenv → config.properties (через AppConfig).
     * @throws IllegalStateException если значение не найдено ни в одном из источников
     */
    public static String getRequired(String key) {
        String value = get(key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required configuration missing: " + key);
        }
        return value;
    }

    public static String get(String key, String defaultValue) {
        // 1. System property (напр. -DTELEGRAM_BOT_TOKEN=...)
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) return val;

        // 2. Environment variable (напр. export TELEGRAM_BOT_TOKEN=...)
        val = System.getenv(key);
        if (val != null && !val.isBlank()) return val;

        // 3. AppConfig / config.properties — маппинг ключей RuntimeConfig → AppConfig
        val = mapToAppConfig(key);
        if (val != null && !val.isBlank()) return val;

        return defaultValue;
    }

    /** Маппинг имён ключей RuntimeConfig на методы AppConfig */
    private static String mapToAppConfig(String key) {
        AppConfig cfg = AppConfig.getInstance();
        switch (key) {
            case "TELEGRAM_BOT_TOKEN":   return cfg.getBotToken();
            case "GECKO_API_KEY":        return cfg.getGeckoApiKey();
            case "BINANCE_API_KEY":      return cfg.getBinanceApiKey();
            case "BINANCE_PRIVATE_KEY_PATH": return cfg.getBinancePrivateKeyPath();
            case "WEB_SERVER_PORT":      return String.valueOf(cfg.getWebServerPort());
            default:
                // Попробуем через AppConfig напрямую (snake_case → dot.case)
                String dotKey = key.toLowerCase().replace('_', '.');
                return cfg.get(dotKey, cfg.get(key, null));
        }
    }
}
