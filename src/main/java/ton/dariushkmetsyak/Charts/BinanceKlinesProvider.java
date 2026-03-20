package ton.dariushkmetsyak.Charts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Fetches historical klines (candlestick) data from Binance public API.
 * Supports up to 5+ years of data with local file caching.
 *
 * Binance klines API: GET /api/v3/klines
 *   - symbol: e.g. BTCUSDT
 *   - interval: 1h, 4h, 1d, etc.
 *   - startTime/endTime: milliseconds
 *   - limit: max 1000 per request
 *
 * Cache strategy:
 *   - Data cached per month files: chart_cache/{SYMBOL}/{YYYY-MM}.json
 *   - Completed months are immutable (never re-fetched)
 *   - Current month is re-fetched if older than 1 hour
 */
public class BinanceKlinesProvider {
    private static final Logger log = LoggerFactory.getLogger(BinanceKlinesProvider.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    private static final String BINANCE_BASE = "https://api.binance.com";
    private static final String CACHE_DIR = "chart_cache";
    private static final int KLINE_LIMIT = 1000;
    // Max age for current-month cache before refresh (1 hour)
    private static final long CURRENT_MONTH_CACHE_MAX_AGE_MS = 3_600_000L;

    /**
     * Get chart data for a coin over a specified number of years.
     * Uses 1h interval for up to 3 years, 4h for 5 years.
     *
     * @param coin   the coin (uses getUsdtPair() for Binance symbol)
     * @param years  number of years of data (1, 3, or 5)
     * @return Chart with prices populated
     */
    public static Chart getChart(Coin coin, int years) {
        String interval = years <= 3 ? "1h" : "4h";
        long endTime = System.currentTimeMillis();
        long startTime = endTime - (long) years * 365L * 24 * 3600 * 1000;
        return getChart(coin, interval, startTime, endTime);
    }

    /**
     * Get chart data for a coin between custom dates.
     */
    public static Chart getChart(Coin coin, String interval, long startTimeMs, long endTimeMs) {
        String symbol = coin.getUsdtPair();
        log.info("[BinanceKlines] Fetching {} {} from {} to {}", symbol, interval,
                Instant.ofEpochMilli(startTimeMs), Instant.ofEpochMilli(endTimeMs));

        List<double[]> allPrices = new ArrayList<>();

        // Iterate month-by-month for cache granularity
        YearMonth startYm = YearMonth.from(Instant.ofEpochMilli(startTimeMs).atZone(ZoneOffset.UTC).toLocalDate());
        YearMonth endYm = YearMonth.from(Instant.ofEpochMilli(endTimeMs).atZone(ZoneOffset.UTC).toLocalDate());
        YearMonth currentYm = YearMonth.now(ZoneOffset.UTC);

        for (YearMonth ym = startYm; !ym.isAfter(endYm); ym = ym.plusMonths(1)) {
            long monthStart = Math.max(startTimeMs, ymToMs(ym));
            long monthEnd = Math.min(endTimeMs, ymEndToMs(ym));

            boolean isCurrentMonth = ym.equals(currentYm);
            List<double[]> monthData = loadFromCache(symbol, interval, ym);

            if (monthData != null && !isCurrentMonth) {
                // Completed month — cache is definitive
                filterAndAdd(allPrices, monthData, monthStart, monthEnd);
                continue;
            }

            if (monthData != null && isCurrentMonth) {
                // Current month — check if cache is fresh enough
                long cacheAge = getCacheAge(symbol, interval, ym);
                if (cacheAge < CURRENT_MONTH_CACHE_MAX_AGE_MS) {
                    filterAndAdd(allPrices, monthData, monthStart, monthEnd);
                    continue;
                }
            }

            // Fetch from Binance
            monthData = fetchFromBinance(symbol, interval, monthStart, monthEnd);
            if (monthData != null && !monthData.isEmpty()) {
                saveToCache(symbol, interval, ym, monthData);
                filterAndAdd(allPrices, monthData, monthStart, monthEnd);
            }

            // Small delay between requests to avoid rate limits
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        // Sort by timestamp
        allPrices.sort(Comparator.comparingDouble(a -> a[0]));

        // Build Chart object
        Chart chart = new Chart();
        chart.coin = coin.getName();
        chart.prices = new ArrayList<>(allPrices);
        chart.market_caps = new ArrayList<>();
        chart.total_volumes = new ArrayList<>();

        log.info("[BinanceKlines] Loaded {} data points for {} ({})", allPrices.size(), symbol, interval);
        return chart;
    }

    /**
     * Fetch klines from Binance API, paginating as needed (max 1000 per request).
     */
    private static List<double[]> fetchFromBinance(String symbol, String interval, long startTime, long endTime) {
        List<double[]> result = new ArrayList<>();
        long cursor = startTime;

        while (cursor < endTime) {
            String url = String.format("%s/api/v3/klines?symbol=%s&interval=%s&startTime=%d&endTime=%d&limit=%d",
                    BINANCE_BASE, symbol, interval, cursor, endTime, KLINE_LIMIT);

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    log.warn("[BinanceKlines] HTTP {} for {}: {}", response.statusCode(), symbol,
                            response.body().substring(0, Math.min(200, response.body().length())));
                    break;
                }

                // Binance returns: [[openTime, open, high, low, close, volume, closeTime, ...], ...]
                List<List<Object>> klines = mapper.readValue(response.body(), new TypeReference<>() {});

                if (klines == null || klines.isEmpty()) break;

                for (List<Object> k : klines) {
                    double timestamp = ((Number) k.get(0)).doubleValue();  // open time in ms
                    double closePrice = Double.parseDouble(k.get(4).toString()); // close price
                    result.add(new double[]{timestamp, closePrice});
                }

                // Move cursor past last received kline
                long lastTimestamp = ((Number) klines.get(klines.size() - 1).get(0)).longValue();
                if (lastTimestamp <= cursor) break; // No progress, avoid infinite loop
                cursor = lastTimestamp + 1;

                // Rate limit: max 1200 req/min, but we'll be conservative
                Thread.sleep(100);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[BinanceKlines] Error fetching {} klines: {}", symbol, e.getMessage());
                break;
            }
        }

        return result;
    }

    // ── Cache operations ───────────────────────────────────────────────────────

    private static Path cachePath(String symbol, String interval, YearMonth ym) {
        return Path.of(CACHE_DIR, symbol, interval, ym.toString() + ".json");
    }

    private static List<double[]> loadFromCache(String symbol, String interval, YearMonth ym) {
        Path path = cachePath(symbol, interval, ym);
        if (!Files.exists(path)) return null;
        try {
            return mapper.readValue(path.toFile(), new TypeReference<List<double[]>>() {});
        } catch (Exception e) {
            log.warn("[BinanceKlines] Failed to read cache {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static long getCacheAge(String symbol, String interval, YearMonth ym) {
        Path path = cachePath(symbol, interval, ym);
        try {
            return System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private static void saveToCache(String symbol, String interval, YearMonth ym, List<double[]> data) {
        Path path = cachePath(symbol, interval, ym);
        try {
            Files.createDirectories(path.getParent());
            mapper.writeValue(path.toFile(), data);
        } catch (Exception e) {
            log.warn("[BinanceKlines] Failed to write cache {}: {}", path, e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static long ymToMs(YearMonth ym) {
        return ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static long ymEndToMs(YearMonth ym) {
        return ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1;
    }

    private static void filterAndAdd(List<double[]> target, List<double[]> source, long startMs, long endMs) {
        for (double[] p : source) {
            if (p[0] >= startMs && p[0] <= endMs) {
                target.add(p);
            }
        }
    }
}
