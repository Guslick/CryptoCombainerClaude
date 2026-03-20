package ton.dariushkmetsyak.Charts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Full OHLCV kline record from Binance.
 * Designed to be easily mapped to a PostgreSQL table via JPA (@Entity) in the future.
 *
 * Binance kline response fields:
 *   [0] openTime, [1] open, [2] high, [3] low, [4] close,
 *   [5] volume, [6] closeTime, [7] quoteAssetVolume,
 *   [8] numberOfTrades, [9] takerBuyBaseVolume, [10] takerBuyQuoteVolume
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KlineRecord {

    private long openTime;       // Kline open time (epoch ms)
    private double open;         // Open price
    private double high;         // High price
    private double low;          // Low price
    private double close;        // Close price
    private double volume;       // Base asset volume
    private long closeTime;      // Kline close time (epoch ms)
    private double quoteVolume;  // Quote asset volume
    private int numberOfTrades;  // Number of trades
    private String symbol;       // Trading pair, e.g. BTCUSDT
    private String interval;     // Interval, e.g. 1h, 4h

    public KlineRecord() {}

    public KlineRecord(long openTime, double open, double high, double low,
                       double close, double volume, long closeTime,
                       double quoteVolume, int numberOfTrades,
                       String symbol, String interval) {
        this.openTime = openTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.closeTime = closeTime;
        this.quoteVolume = quoteVolume;
        this.numberOfTrades = numberOfTrades;
        this.symbol = symbol;
        this.interval = interval;
    }

    /**
     * Convert to legacy [timestamp, closePrice] format for Chart compatibility.
     */
    public double[] toPriceArray() {
        return new double[]{openTime, close};
    }

    /**
     * Parse a single Binance kline JSON array into a KlineRecord.
     */
    public static KlineRecord fromBinanceArray(java.util.List<Object> k, String symbol, String interval) {
        return new KlineRecord(
                ((Number) k.get(0)).longValue(),
                Double.parseDouble(k.get(1).toString()),
                Double.parseDouble(k.get(2).toString()),
                Double.parseDouble(k.get(3).toString()),
                Double.parseDouble(k.get(4).toString()),
                Double.parseDouble(k.get(5).toString()),
                ((Number) k.get(6)).longValue(),
                Double.parseDouble(k.get(7).toString()),
                ((Number) k.get(8)).intValue(),
                symbol,
                interval
        );
    }

    // Getters and setters (needed for Jackson serialization and future JPA)

    public long getOpenTime() { return openTime; }
    public void setOpenTime(long openTime) { this.openTime = openTime; }

    public double getOpen() { return open; }
    public void setOpen(double open) { this.open = open; }

    public double getHigh() { return high; }
    public void setHigh(double high) { this.high = high; }

    public double getLow() { return low; }
    public void setLow(double low) { this.low = low; }

    public double getClose() { return close; }
    public void setClose(double close) { this.close = close; }

    public double getVolume() { return volume; }
    public void setVolume(double volume) { this.volume = volume; }

    public long getCloseTime() { return closeTime; }
    public void setCloseTime(long closeTime) { this.closeTime = closeTime; }

    public double getQuoteVolume() { return quoteVolume; }
    public void setQuoteVolume(double quoteVolume) { this.quoteVolume = quoteVolume; }

    public int getNumberOfTrades() { return numberOfTrades; }
    public void setNumberOfTrades(int numberOfTrades) { this.numberOfTrades = numberOfTrades; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getInterval() { return interval; }
    public void setInterval(String interval) { this.interval = interval; }
}
