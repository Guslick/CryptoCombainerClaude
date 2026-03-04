package ton.dariushkmetsyak.Config;

public final class RuntimeConfig {
    private RuntimeConfig() {}

    public static String getRequired(String key) {
        String value = get(key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required configuration missing: " + key);
        }
        return value;
    }

    public static String get(String key, String defaultValue) {
        String system = System.getProperty(key);
        if (system != null && !system.isBlank()) return system;

        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env;

        return defaultValue;
    }

    public static int getInt(String key, int defaultValue) {
        String value = get(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
