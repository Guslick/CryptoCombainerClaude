package ton.dariushkmetsyak.Exchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of available exchange providers.
 * New exchanges are added here via register().
 */
public class ExchangeRegistry {
    private static final Logger log = LoggerFactory.getLogger(ExchangeRegistry.class);
    private static final Map<String, ExchangeProvider> providers = new ConcurrentHashMap<>();
    private static volatile String defaultExchangeId = "binance";

    static {
        // Register built-in providers
        register(new BinanceProvider());
    }

    public static void register(ExchangeProvider provider) {
        providers.put(provider.getId(), provider);
        log.info("Registered exchange provider: {} ({})", provider.getDisplayName(), provider.getId());
    }

    public static ExchangeProvider get(String exchangeId) {
        if (exchangeId == null || exchangeId.isBlank()) {
            return providers.get(defaultExchangeId);
        }
        ExchangeProvider p = providers.get(exchangeId.toLowerCase());
        return p != null ? p : providers.get(defaultExchangeId);
    }

    public static ExchangeProvider getDefault() {
        return providers.get(defaultExchangeId);
    }

    public static void setDefaultExchangeId(String id) {
        defaultExchangeId = id;
    }

    /** Get all registered exchanges for UI display */
    public static List<Map<String, Object>> listExchanges() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ExchangeProvider p : providers.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("displayName", p.getDisplayName());
            list.add(m);
        }
        return list;
    }

    public static boolean has(String exchangeId) {
        return providers.containsKey(exchangeId);
    }
}
