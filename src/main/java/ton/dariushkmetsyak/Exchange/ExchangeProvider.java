package ton.dariushkmetsyak.Exchange;

import java.util.List;
import java.util.Map;

/**
 * Abstraction for exchange data providers.
 * Each exchange (Binance, Bybit, etc.) implements this interface
 * to supply coin lists, prices, chart data, and commission info.
 */
public interface ExchangeProvider {

    /** Internal exchange identifier, e.g. "binance" */
    String getId();

    /** Human-readable name, e.g. "Binance" */
    String getDisplayName();

    /** Get list of available trading pairs (base/USDT) */
    List<CoinInfo> getAvailableCoins();

    /** Search coins by query (name or symbol), limited results */
    List<CoinInfo> searchCoins(String query, int limit);

    /** Get coin info by symbol, e.g. "BTC" */
    CoinInfo getCoinBySymbol(String symbol);

    /** Get current price for a symbol pair, e.g. "BTCUSDT" */
    double getCurrentPrice(String symbol) throws Exception;

    /**
     * Get chart (kline/candlestick) data.
     * Returns list of [timestamp, open, high, low, close] arrays.
     * @param symbol trading pair, e.g. "BTCUSDT"
     * @param interval e.g. "1m", "5m", "1h", "1d"
     * @param limit number of candles
     */
    List<double[]> getKlineData(String symbol, String interval, int limit) throws Exception;

    /**
     * Get commission info for trading on this exchange.
     * @param hasBnbDiscount whether user pays with BNB (Binance-specific)
     * @return commission rate as percentage (e.g. 0.1 for 0.1%)
     */
    CommissionInfo getCommissionInfo(boolean hasBnbDiscount);

    /** Commission details */
    class CommissionInfo {
        public final double makerPercent;
        public final double takerPercent;
        public final String description;

        public CommissionInfo(double makerPercent, double takerPercent, String description) {
            this.makerPercent = makerPercent;
            this.takerPercent = takerPercent;
            this.description = description;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("makerPercent", makerPercent);
            m.put("takerPercent", takerPercent);
            m.put("description", description);
            return m;
        }
    }

    /** Universal coin info from exchange */
    class CoinInfo {
        public final String symbol;       // e.g. "BTC"
        public final String name;         // e.g. "Bitcoin"
        public final String tradingPair;  // e.g. "BTCUSDT"
        public final String baseAsset;    // e.g. "BTC"
        public final String quoteAsset;   // e.g. "USDT"

        public CoinInfo(String symbol, String name, String tradingPair, String baseAsset, String quoteAsset) {
            this.symbol = symbol;
            this.name = name;
            this.tradingPair = tradingPair;
            this.baseAsset = baseAsset;
            this.quoteAsset = quoteAsset;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("symbol", symbol);
            m.put("name", name);
            m.put("tradingPair", tradingPair);
            m.put("baseAsset", baseAsset);
            m.put("quoteAsset", quoteAsset);
            return m;
        }
    }
}
