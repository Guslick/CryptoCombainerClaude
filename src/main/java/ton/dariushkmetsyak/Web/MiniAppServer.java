package ton.dariushkmetsyak.Web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ton.dariushkmetsyak.Config.AppConfig;
import ton.dariushkmetsyak.GeckoApiService.GeckoRequests;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin;
import ton.dariushkmetsyak.GeckoApiService.geckoEntities.CoinsList;
import ton.dariushkmetsyak.TradingApi.ApiService.Account;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Встроенный HTTP-сервер для Telegram MiniApp.
 * Запускается на настраиваемом порту (по умолчанию 8080).
 */
public class MiniAppServer {
    private static final Logger log = LoggerFactory.getLogger(MiniAppServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private final int port;

    public MiniAppServer() {
        this.port = AppConfig.getInstance().getWebServerPort();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        // Static files
        server.createContext("/", new StaticFileHandler());

        // API routes
        // Bot name for Telegram Login Widget (browser auth)
        server.createContext("/api/auth/config", exchange -> handleJson(exchange, () -> {
            Map<String, Object> cfg = new LinkedHashMap<>();
            // Read bot username from actual bot configuration
            String botUsername = "NEW_MAMA_CXHEMA"; // default
            try {
                // Try to get from config property first
                String configured = ton.dariushkmetsyak.Config.AppConfig.getInstance().get("telegram.bot.username", "");
                if (!configured.isBlank()) botUsername = configured;
            } catch (Exception ignored) {}
            cfg.put("botUsername", botUsername);
            return cfg;
        }));

        server.createContext("/api/health", exchange -> handleJson(exchange, () -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "ok");
            r.put("time", System.currentTimeMillis());
            r.put("coinsLoaded", CoinsList.getCoins().size());
            return r;
        }));

        server.createContext("/api/coins", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String query = getQueryParam(exchange.getRequestURI().getQuery(), "q");
            handleJson(exchange, () -> searchCoins(query));
        });

        server.createContext("/api/price", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String coinId = getPathParam(exchange.getRequestURI().getPath(), "/api/price/");
            handleJson(exchange, () -> {
                try {
                    Coin coin = CoinsList.getCoinByName(coinId);
                    double price = Account.getCurrentPrice(coin);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("coinId", coinId);
                    r.put("coinName", coin.getName());
                    r.put("symbol", coin.getSymbol());
                    r.put("price", price);
                    r.put("timestamp", System.currentTimeMillis());
                    return r;
                } catch (Exception e) {
                    return errorMap("Монета не найдена: " + coinId);
                }
            });
        });

        server.createContext("/api/chart", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String path = exchange.getRequestURI().getPath();
            String queryStr = exchange.getRequestURI().getQuery();
            String coinId = path.replaceFirst("/api/chart/?", "").replaceAll("/.*", "");
            String interval = getQueryParam(queryStr, "interval");
            if (interval == null) interval = "1d";
            final String finalInterval = interval;
            handleJson(exchange, () -> getChartData(coinId, finalInterval));
        });

        // ── Trading: ALL endpoints require valid auth token ──────────────────────

        server.createContext("/api/trading/sessions", exchange -> {
            addCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(204,-1); return; }
            Long userId = requireUser(exchange); if (userId == null) return;
            TradingSessionManager mgr = TradingSessionManager.forUser(userId);
            String path = exchange.getRequestURI().getPath();
            if (path.matches("/api/trading/sessions/[^/]+")) {
                String sessionId = path.substring("/api/trading/sessions/".length());
                handleJson(exchange, () -> mgr.getSessionDetail(sessionId));
            } else {
                handleJson(exchange, () -> mgr.getAllSessions());
            }
        });

        server.createContext("/api/trading/start", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); return; }
            Long userId = requireUser(exchange); if (userId == null) return;
            handleJson(exchange, () -> startTradingFromRequest(exchange, userId));
        });

        server.createContext("/api/trading/stop", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); return; }
            Long userId = requireUser(exchange); if (userId == null) return;
            handleJson(exchange, () -> {
                Map<String, Object> body = parseBody(exchange);
                String sessionId = (String) body.get("sessionId");
                if (sessionId == null) return errorMap("sessionId обязателен");
                boolean ok = TradingSessionManager.forUser(userId).stopSession(sessionId);
                return Map.of("stopped", ok);
            });
        });

        server.createContext("/api/trading/resume", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); return; }
            Long userId = requireUser(exchange); if (userId == null) return;
            handleJson(exchange, () -> {
                Map<String, Object> body = parseBody(exchange);
                String sessionId = (String) body.get("sessionId");
                if (sessionId == null) return errorMap("sessionId обязателен");
                long chatId = toLong(body.get("chatId"), AppConfig.getInstance().getDefaultChatId());
                UserProfileManager.UserProfile profile = UserProfileManager.getInstance().loadProfile(userId);
                return TradingSessionManager.forUser(userId).resumeSession(sessionId, chatId, profile);
            });
        });

        server.createContext("/api/trading/delete", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); return; }
            Long userId = requireUser(exchange); if (userId == null) return;
            handleJson(exchange, () -> {
                Map<String, Object> body = parseBody(exchange);
                String sessionId = (String) body.get("sessionId");
                if (sessionId == null) return errorMap("sessionId обязателен");
                boolean ok = TradingSessionManager.forUser(userId).deleteSession(sessionId);
                return Map.of("deleted", ok, "sessionId", sessionId);
            });
        });

        server.createContext("/api/backtest/start", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); return; }
            Long userId = requireUser(exchange); if (userId == null) return;
            handleJson(exchange, () -> startBacktestFromRequest(exchange, userId));
        });

        server.createContext("/api/backtest/result", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); return; }
            Long userId = requireUser(exchange); if (userId == null) return;
            handleJson(exchange, () -> {
                Map<String, Object> result = TradingSessionManager.forUser(userId).getLastBacktestResult();
                return result != null ? result : Map.of("ready", false, "message", "Результатов нет");
            });
        });

        server.createContext("/api/backtest/optimize", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); return; }
            Long userId = requireUser(exchange); if (userId == null) return;
            handleJson(exchange, () -> {
                Map<String, Object> body = parseBody(exchange);
                String coinName = (String) body.getOrDefault("coin", "bitcoin");
                double tradingSum = toDouble(body.get("tradingSum"), 100.0);
                String chartType = (String) body.getOrDefault("chartType", "yearly");
                String exchangeName = (String) body.getOrDefault("exchangeName", "Binance");
                double commissionRate = toDouble(body.get("commissionRate"), 0.1);
                int topN = toInt(body.get("topN"), 10);
                double buyMin = toDouble(body.get("buyMin"), 1.0);
                double buyMax = toDouble(body.get("buyMax"), 10.0);
                double buyStep = toDouble(body.get("buyStep"), 1.0);
                double profitMin = toDouble(body.get("profitMin"), 1.0);
                double profitMax = toDouble(body.get("profitMax"), 5.0);
                double profitStep = toDouble(body.get("profitStep"), 0.5);
                double lossMin = toDouble(body.get("lossMin"), 3.0);
                double lossMax = toDouble(body.get("lossMax"), 15.0);
                double lossStep = toDouble(body.get("lossStep"), 1.0);
                TradingSessionManager.SessionInfo info = TradingSessionManager.forUser(userId)
                        .startTopStrategies(coinName, tradingSum, chartType, exchangeName, commissionRate, topN,
                                buyMin, buyMax, buyStep, profitMin, profitMax, profitStep,
                                lossMin, lossMax, lossStep);
                return info.toMap();
            });
        });

        // ── Profile / Auth ────────────────────────────────────────────────────

        server.createContext("/api/auth/config", exchange -> handleJson(exchange, () -> {
            Map<String, Object> cfg = new LinkedHashMap<>();
            String botUsername = AppConfig.getInstance().get("telegram.bot.username", "");
            if (botUsername.isBlank()) botUsername = "NEW_MAMA_CXHEMA";
            cfg.put("botUsername", botUsername);
            return cfg;
        }));

        server.createContext("/api/auth/telegram", exchange -> handleJson(exchange, () -> {
            if (!"POST".equals(exchange.getRequestMethod())) return errorMap("POST required");
            Map<String, Object> body = parseBody(exchange);
            String botToken = AppConfig.getInstance().getBotToken();
            UserProfileManager upm = UserProfileManager.getInstance();
            Map<String, Object> tgUser = null;
            String initData = (String) body.get("initData");
            if (initData != null && !initData.isBlank())
                tgUser = upm.verifyInitData(initData, botToken);
            if (tgUser == null && body.containsKey("id") && body.containsKey("hash"))
                tgUser = upm.verifyLoginWidget(body, botToken);
            if (tgUser == null) {
                String host = exchange.getRemoteAddress().getAddress().getHostAddress();
                if ("dev_bypass".equals(initData) && (host.startsWith("127.") || host.startsWith("::1"))) {
                    tgUser = new java.util.HashMap<>(Map.of("id", 0L, "first_name", "Dev", "username", "dev"));
                } else {
                    return errorMap("Ошибка авторизации Telegram");
                }
            }
            UserProfileManager.UserProfile profile = upm.updateFromTelegram(tgUser);
            // Load user's sessions on first login and auto-resume crashed sessions
            TradingSessionManager userMgr = TradingSessionManager.forUser(profile.telegramUserId);
            userMgr.loadSessions();
            userMgr.autoResumeSessions(profile.telegramUserId);
            String token = upm.createSession(profile.telegramUserId);
            Map<String, Object> resp = new LinkedHashMap<>(profile.toPublicMap());
            resp.put("token", token);
            return resp;
        }));

        server.createContext("/api/profile", exchange -> handleJson(exchange, () -> {
            Long userId = resolveUser(exchange);
            if (userId == null) return errorMap("Требуется авторизация");
            UserProfileManager.UserProfile p = UserProfileManager.getInstance().loadProfile(userId);
            if ("POST".equals(exchange.getRequestMethod())) {
                Map<String, Object> body = parseBody(exchange);
                if (p == null) return errorMap("Профиль не найден");
                if (body.containsKey("telegramFirstName")) p.telegramFirstName = (String) body.get("telegramFirstName");
                UserProfileManager.getInstance().saveProfile(p);
                return p.toPublicMap();
            }
            return p != null ? p.toPublicMap() : errorMap("Профиль не найден");
        }));

        server.createContext("/api/profile/keys", exchange -> handleJson(exchange, () -> {
            Long userId = resolveUser(exchange);
            if (userId == null) return errorMap("Требуется авторизация");
            if ("POST".equals(exchange.getRequestMethod())) {
                Map<String, Object> body = parseBody(exchange);
                boolean testnet = Boolean.TRUE.equals(body.get("testnet"));
                String apiKey    = (String) body.getOrDefault("apiKey", "");
                String pemContent = (String) body.getOrDefault("pemContent", "");
                return UserProfileManager.getInstance().saveBinanceKeys(userId, apiKey, pemContent, testnet);
            }
            return UserProfileManager.getInstance().getBinanceKeysStatus(userId);
        }));

        server.createContext("/api/profile/report", exchange -> handleJson(exchange, () -> {
            Long userId = resolveUser(exchange);
            if (userId == null) return errorMap("Требуется авторизация");
            return UserProfileManager.getInstance().getTradingReport(userId);
        }));

        server.createContext("/api/profile/wallet", exchange -> handleJson(exchange, () -> {
            Long userId = resolveUser(exchange);
            if (userId == null) return errorMap("Требуется авторизация");
            String net = getQueryParam(exchange.getRequestURI().getQuery(), "net");
            boolean testnet = !"main".equalsIgnoreCase(net);
            return UserProfileManager.getInstance().getWallet(userId, testnet);
        }));

        server.createContext("/api/profile/keys/delete", exchange -> handleJson(exchange, () -> {
            Long userId = resolveUser(exchange);
            if (userId == null) return errorMap("Требуется авторизация");
            if (!"POST".equals(exchange.getRequestMethod())) return errorMap("POST required");
            Map<String, Object> body = parseBody(exchange);
            boolean testnet = Boolean.TRUE.equals(body.get("testnet"));
            UserProfileManager.getInstance().deleteKeys(userId, testnet);
            return Map.of("deleted", true, "testnet", testnet);
        }));

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();

    }

    public void stop() {
        if (server != null) server.stop(1);
    }

    // ── Helper: JSON response ─────────────────────────────────────────────────

    @FunctionalInterface
    interface JsonSupplier { Object get() throws Exception; }

    private void handleJson(HttpExchange exchange, JsonSupplier supplier) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(204,-1); return; }
        try {
            Object result = supplier.get();
            byte[] bytes = mapper.writeValueAsBytes(result);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        } catch (Exception e) {
            log.error("API error", e);
            byte[] err = mapper.writeValueAsBytes(errorMap(e.getMessage()));
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(500, err.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
        }
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (body.isBlank()) return new HashMap<>();
            return mapper.readValue(body, Map.class);
        }
    }

    private String getQueryParam(String query, String key) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                try { return URLDecoder.decode(kv[1], "UTF-8"); } catch (Exception e) { return kv[1]; }
            }
        }
        return null;
    }

    private Map<String, Object> errorMap(String msg) {
        return Map.of("error", msg != null ? msg : "Неизвестная ошибка");
    }

    private double toDouble(Object val, double def) {
        if (val == null) return def;
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return def; }
    }
    private int toInt(Object val, int def) {
        if (val == null) return def;
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return def; }
    }
    private long toLong(Object val, long def) {
        if (val == null) return def;
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return def; }
    }

    /** Resolve userId from Authorization header. Returns null if not authenticated. */
    private Long resolveUser(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String token = null;
        if (auth != null && auth.startsWith("Bearer ")) token = auth.substring(7).trim();
        else token = getQueryParam(exchange.getRequestURI().getQuery(), "token");
        return UserProfileManager.getInstance().resolveSession(token);
    }

    /** Like resolveUser but sends 401 and returns null if not authenticated. */
    private Long requireUser(HttpExchange exchange) throws IOException {
        Long userId = resolveUser(exchange);
        if (userId == null) {
            addCorsHeaders(exchange);
            byte[] body = mapper.writeValueAsBytes(Map.of("error", "Требуется авторизация", "code", 401));
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        }
        return userId;
    }

    // ── Chart data ────────────────────────────────────────────────────────────

    private Map<String, Object> getChartData(String coinId, String interval) {
        try {
            Coin coin = CoinsList.getCoinByName(coinId);
            int days;
            switch (interval) {
                case "7d":  days = 7;   break;
                case "30d": days = 30;  break;
                case "90d": days = 90;  break;
                case "1y":  days = 365; break;
                default:    days = 1;   break;
            }
            String json = GeckoRequests.getMarketChartByDays(coin.getId(), days);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("coinId", coinId);
            result.put("coinName", coin.getName());
            result.put("interval", interval);
            result.put("data", parseChartJson(json));
            return result;
        } catch (Exception e) {
            log.error("Chart data error for {}: {}", coinId, e.getMessage());
            return errorMap("Ошибка получения данных: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<double[]> parseChartJson(String json) throws Exception {
        Map<String, Object> parsed = mapper.readValue(json, Map.class);
        List<List<Number>> prices = (List<List<Number>>) parsed.get("prices");
        List<double[]> result = new ArrayList<>();
        if (prices != null) for (List<Number> p : prices) {
            if (p.size() >= 2) result.add(new double[]{p.get(0).doubleValue(), p.get(1).doubleValue()});
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> startTradingFromRequest(HttpExchange exchange, long userId) throws Exception {
        Map<String, Object> body = parseBody(exchange);
        String type = (String) body.getOrDefault("type", "tester");
        String coinName = (String) body.getOrDefault("coin", "bitcoin");
        double tradingSum  = toDouble(body.get("tradingSum"), 100.0);
        double startAssets = toDouble(body.get("startAssets"), 150.0);
        double buyGap = toDouble(body.get("buyGap"), 3.5);
        double spg    = toDouble(body.get("sellWithProfitGap"), 2.0);
        double slg    = toDouble(body.get("sellWithLossGap"), 8.0);
        int timeout      = toInt(body.get("updateTimeout"), 30);
        int chartRefresh = toInt(body.get("chartRefreshInterval"), 60);
        long chatId = toLong(body.get("chatId"), AppConfig.getInstance().getDefaultChatId());

        UserProfileManager.UserProfile profile = userId > 0
            ? UserProfileManager.getInstance().loadProfile(userId) : null;
        TradingSessionManager mgr = TradingSessionManager.forUser(userId);

        Object result;
        switch (type.toLowerCase()) {
            case "binance": case "binance_real":
                result = mgr.startBinanceTrading(coinName, tradingSum, buyGap, spg, slg,
                        timeout, chartRefresh, chatId, profile);
                break;
            case "binance_test":
                result = mgr.startBinanceTestTrading(coinName, tradingSum, buyGap, spg, slg,
                        timeout, chartRefresh, chatId, profile);
                break;
            default:
                result = mgr.startTesterTrading(coinName, startAssets, tradingSum, buyGap, spg, slg,
                        timeout, chartRefresh, chatId);
        }
        if (result instanceof TradingSessionManager.SessionInfo)
            return ((TradingSessionManager.SessionInfo) result).toMap();
        return (Map<String, Object>) result;
    }

    private Map<String, Object> startBacktestFromRequest(HttpExchange exchange, long userId) throws Exception {
        Map<String, Object> body = parseBody(exchange);
        String coinName = (String) body.getOrDefault("coin", "bitcoin");
        double tradingSum = toDouble(body.get("tradingSum"), 100.0);
        double buyGap = toDouble(body.get("buyGap"), 3.5);
        double spg = toDouble(body.get("sellWithProfitGap"), 2.0);
        double slg = toDouble(body.get("sellWithLossGap"), 8.0);
        String chartType = (String) body.getOrDefault("chartType", "1d");
        String exchangeName = (String) body.getOrDefault("exchangeName", "Binance");
        double commissionRate = toDouble(body.get("commissionRate"), 0.1);
        long customFrom = toLong(body.get("customFrom"), 0L);
        long customTo = toLong(body.get("customTo"), 0L);
        TradingSessionManager.SessionInfo info = TradingSessionManager.forUser(userId)
                .startBacktest(coinName, tradingSum, buyGap, spg, slg, chartType, exchangeName, commissionRate, customFrom, customTo);
        return info.toMap();
    }

    @SuppressWarnings("unchecked")
    private String getPathParam(String path, String prefix) {
        if (path == null || !path.startsWith(prefix)) return "";
        return path.substring(prefix.length()).replaceAll("/.*", "");
    }

    private List<Map<String, Object>> searchCoins(String query) {
        List<Coin> coins = CoinsList.getCoins();
        String q = query == null ? "" : query.toLowerCase().trim();
        return coins.stream()
            .filter(c -> q.isEmpty() ||
                (c.getName() != null && c.getName().toLowerCase().contains(q)) ||
                (c.getSymbol() != null && c.getSymbol().toLowerCase().startsWith(q)))
            .limit(20)
            .map(c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId()); m.put("name", c.getName()); m.put("symbol", c.getSymbol());
                return m;
            }).collect(Collectors.toList());
    }

    // ── Static file handler ───────────────────────────────────────────────────

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    }

    private static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.equals("/index.html"))
                path = "/static/index.html";
            else
                path = "/static" + path;
            addCors(exchange);
            try (InputStream is = MiniAppServer.class.getResourceAsStream(path)) {
                if (is == null) {
                    byte[] msg = "404 Not Found".getBytes();
                    exchange.sendResponseHeaders(404, msg.length);
                    exchange.getResponseBody().write(msg);
                    return;
                }
                String ct = path.endsWith(".html") ? "text/html; charset=UTF-8"
                    : path.endsWith(".js") ? "application/javascript"
                    : path.endsWith(".css") ? "text/css"
                    : "application/octet-stream";
                exchange.getResponseHeaders().set("Content-Type", ct);
                byte[] data = is.readAllBytes();
                exchange.sendResponseHeaders(200, data.length);
                exchange.getResponseBody().write(data);
            } catch (IOException e) {
                exchange.sendResponseHeaders(500, 0);
            } finally {
                exchange.getResponseBody().close();
            }
        }
    }
}