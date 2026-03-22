package ton.dariushkmetsyak.Exchange;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Binance exchange data provider.
 * Fetches coin list, prices, klines, and commission info from Binance public API.
 */
public class BinanceProvider implements ExchangeProvider {
    private static final Logger log = LoggerFactory.getLogger(BinanceProvider.class);
    private static final String BASE_URL = "https://api.binance.com";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    // Cache for available coins (refreshed periodically)
    private volatile List<CoinInfo> cachedCoins = null;
    private volatile long cacheTimestamp = 0;
    private static final long CACHE_TTL_MS = 30 * 60 * 1000; // 30 minutes

    // Known coin names (Binance exchangeInfo doesn't provide full names)
    private static final Map<String, String> KNOWN_NAMES = new ConcurrentHashMap<>();
    static {
        KNOWN_NAMES.put("BTC", "Bitcoin");
        KNOWN_NAMES.put("ETH", "Ethereum");
        KNOWN_NAMES.put("BNB", "BNB");
        KNOWN_NAMES.put("SOL", "Solana");
        KNOWN_NAMES.put("XRP", "XRP");
        KNOWN_NAMES.put("DOGE", "Dogecoin");
        KNOWN_NAMES.put("ADA", "Cardano");
        KNOWN_NAMES.put("AVAX", "Avalanche");
        KNOWN_NAMES.put("DOT", "Polkadot");
        KNOWN_NAMES.put("MATIC", "Polygon");
        KNOWN_NAMES.put("LINK", "Chainlink");
        KNOWN_NAMES.put("UNI", "Uniswap");
        KNOWN_NAMES.put("ATOM", "Cosmos");
        KNOWN_NAMES.put("LTC", "Litecoin");
        KNOWN_NAMES.put("FIL", "Filecoin");
        KNOWN_NAMES.put("NEAR", "NEAR Protocol");
        KNOWN_NAMES.put("APT", "Aptos");
        KNOWN_NAMES.put("ARB", "Arbitrum");
        KNOWN_NAMES.put("OP", "Optimism");
        KNOWN_NAMES.put("SUI", "Sui");
        KNOWN_NAMES.put("TRX", "TRON");
        KNOWN_NAMES.put("SHIB", "Shiba Inu");
        KNOWN_NAMES.put("PEPE", "Pepe");
        KNOWN_NAMES.put("WIF", "dogwifhat");
        KNOWN_NAMES.put("INJ", "Injective");
        KNOWN_NAMES.put("SEI", "Sei");
        KNOWN_NAMES.put("TIA", "Celestia");
        KNOWN_NAMES.put("AAVE", "Aave");
        KNOWN_NAMES.put("MKR", "Maker");
        KNOWN_NAMES.put("RENDER", "Render");
        KNOWN_NAMES.put("FET", "Fetch.ai");
        KNOWN_NAMES.put("GRT", "The Graph");
        KNOWN_NAMES.put("ALGO", "Algorand");
        KNOWN_NAMES.put("XLM", "Stellar");
        KNOWN_NAMES.put("ETC", "Ethereum Classic");
        KNOWN_NAMES.put("HBAR", "Hedera");
        KNOWN_NAMES.put("VET", "VeChain");
        KNOWN_NAMES.put("FTM", "Fantom");
        KNOWN_NAMES.put("SAND", "The Sandbox");
        KNOWN_NAMES.put("MANA", "Decentraland");
        KNOWN_NAMES.put("AXS", "Axie Infinity");
        KNOWN_NAMES.put("THETA", "Theta Network");
        KNOWN_NAMES.put("ICP", "Internet Computer");
    }

    /** Enrich known names from CoinGecko data if available */
    public static void enrichNames(Map<String, String> symbolToName) {
        KNOWN_NAMES.putAll(symbolToName);
    }

    @Override
    public String getId() {
        return "binance";
    }

    @Override
    public String getDisplayName() {
        return "Binance";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<CoinInfo> getAvailableCoins() {
        if (cachedCoins != null && System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return cachedCoins;
        }
        try {
            String json = httpGet(BASE_URL + "/api/v3/exchangeInfo");
            Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> symbols = (List<Map<String, Object>>) data.get("symbols");
            List<CoinInfo> coins = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> sym : symbols) {
                String status = (String) sym.get("status");
                String quoteAsset = (String) sym.get("quoteAsset");
                String baseAsset = (String) sym.get("baseAsset");
                String pair = (String) sym.get("symbol");
                if (!"TRADING".equals(status)) continue;
                if (!"USDT".equals(quoteAsset)) continue;
                if (seen.contains(baseAsset)) continue;
                seen.add(baseAsset);
                String name = KNOWN_NAMES.getOrDefault(baseAsset, baseAsset);
                coins.add(new CoinInfo(baseAsset, name, pair, baseAsset, quoteAsset));
            }
            // Sort by known popularity (BTC, ETH, BNB first, then alphabetically)
            List<String> priority = List.of("BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "AVAX", "DOT", "LINK");
            coins.sort((a, b) -> {
                int ia = priority.indexOf(a.symbol);
                int ib = priority.indexOf(b.symbol);
                if (ia >= 0 && ib >= 0) return Integer.compare(ia, ib);
                if (ia >= 0) return -1;
                if (ib >= 0) return 1;
                return a.symbol.compareTo(b.symbol);
            });
            cachedCoins = coins;
            cacheTimestamp = System.currentTimeMillis();
            log.info("Loaded {} USDT trading pairs from Binance", coins.size());
            return coins;
        } catch (Exception e) {
            log.error("Failed to load Binance exchange info: {}", e.getMessage());
            return cachedCoins != null ? cachedCoins : Collections.emptyList();
        }
    }

    @Override
    public List<CoinInfo> searchCoins(String query, int limit) {
        List<CoinInfo> all = getAvailableCoins();
        if (query == null || query.isBlank()) {
            return all.stream().limit(limit).collect(Collectors.toList());
        }
        String q = query.toLowerCase().trim();
        return all.stream()
                .filter(c -> c.symbol.toLowerCase().startsWith(q)
                        || c.name.toLowerCase().contains(q)
                        || c.tradingPair.toLowerCase().startsWith(q))
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Override
    public CoinInfo getCoinBySymbol(String symbol) {
        if (symbol == null) return null;
        String upper = symbol.toUpperCase();
        return getAvailableCoins().stream()
                .filter(c -> c.symbol.equals(upper))
                .findFirst().orElse(null);
    }

    @Override
    public double getCurrentPrice(String symbol) throws Exception {
        String pair = symbol.toUpperCase();
        if (!pair.endsWith("USDT")) pair = pair + "USDT";
        String json = httpGet(BASE_URL + "/api/v3/ticker/price?symbol=" + pair);
        Map<String, Object> data = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        if (data.containsKey("msg")) {
            throw new RuntimeException("Binance error: " + data.get("msg"));
        }
        return Double.parseDouble((String) data.get("price"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<double[]> getKlineData(String symbol, String interval, int limit) throws Exception {
        String pair = symbol.toUpperCase();
        if (!pair.endsWith("USDT")) pair = pair + "USDT";
        String url = BASE_URL + "/api/v3/klines?symbol=" + pair + "&interval=" + interval + "&limit=" + limit;
        String json = httpGet(url);
        List<List<Object>> raw = mapper.readValue(json, new TypeReference<List<List<Object>>>() {});
        List<double[]> result = new ArrayList<>();
        for (List<Object> candle : raw) {
            // [openTime, open, high, low, close, volume, closeTime, ...]
            double time = ((Number) candle.get(0)).doubleValue();
            double open = Double.parseDouble(candle.get(1).toString());
            double high = Double.parseDouble(candle.get(2).toString());
            double low = Double.parseDouble(candle.get(3).toString());
            double close = Double.parseDouble(candle.get(4).toString());
            result.add(new double[]{time, open, high, low, close});
        }
        return result;
    }

    @Override
    public CommissionInfo getCommissionInfo(boolean hasBnbDiscount) {
        if (hasBnbDiscount) {
            return new CommissionInfo(0.075, 0.075, "Binance: 0.075% (BNB скидка 25%)");
        }
        return new CommissionInfo(0.1, 0.1, "Binance: 0.1% maker/taker (стандарт)");
    }

    private String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
