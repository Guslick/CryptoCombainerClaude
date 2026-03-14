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
            cfg.put("botUsername", AppConfig.getInstance().getBotUsername());
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

        server.createContext("/api/trading/sessions", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            Long userId = resolveUser(exchange);
            if (userId == null || userId == 0L) {
                handleJson(exchange, () -> errorMap("Требуется авторизация"));
                return;
            }
            String path = exchange.getRequestURI().getPath();
            // /api/trading/sessions/{id} → session detail with events
            if (path.matches("/api/trading/sessions/[^/]+")) {
                String sessionId = path.substring("/api/trading/sessions/".length());
                handleJson(exchange, () -> TradingSessionManager.getInstance().getSessionDetailForOwner(sessionId, userId));
            } else {
                handleJson(exchange, () -> TradingSessionManager.getInstance().getSessionsByOwner(userId));
            }
        });

        server.createContext("/api/trading/start", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            final Long startOwnerId = requireUser(exchange);
            if (startOwnerId == null) {
                handleJson(exchange, () -> errorMap("Требуется авторизация"));
                return;
            }
            handleJson(exchange, () -> startTradingFromRequest(exchange, startOwnerId));
        });

        server.createContext("/api/trading/stop", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            final Long stopOwner = requireUser(exchange);
            if (stopOwner == null) {
                handleJson(exchange, () -> errorMap("Требуется авторизация"));
                return;
            }
            handleJson(exchange, () -> {
                Map<String, Object> body = parseBody(exchange);
                String sessionId = (String) body.get("sessionId");
                if (sessionId == null) return errorMap("sessionId обязателен");
                boolean ok = TradingSessionManager.getInstance().stopSession(sessionId, stopOwner);
                if (!ok) return errorMap("Нет доступа или сессия не найдена");
                return Map.of("stopped", true, "sessionId", sessionId);
            });
        });

        server.createContext("/api/trading/resume", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            final Long resumeOwner = requireUser(exchange);
            if (resumeOwner == null) {
                handleJson(exchange, () -> errorMap("Требуется авторизация"));
                return;
            }
            handleJson(exchange, () -> {
                Map<String, Object> body = parseBody(exchange);
                String sessionId = (String) body.get("sessionId");
                long chatId = toLong(body.get("chatId"), AppConfig.getInstance().getDefaultChatId());
                if (sessionId == null) return errorMap("sessionId обязателен");
                Map<String, Object> check = TradingSessionManager.getInstance().getSessionDetailForOwner(sessionId, resumeOwner);
                if (check.containsKey("error")) return check;
                return TradingSessionManager.getInstance().resumeSession(sessionId, chatId);
            });
        });

        server.createContext("/api/trading/delete", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            final Long delOwner = requireUser(exchange);
            if (delOwner == null) {
                handleJson(exchange, () -> errorMap("Требуется авторизация"));
                return;
            }
            handleJson(exchange, () -> {
                Map<String, Object> body = parseBody(exchange);
                String sessionId = (String) body.get("sessionId");
                if (sessionId == null) return errorMap("sessionId обязателен");
                boolean ok = TradingSessionManager.getInstance().deleteSession(sessionId, delOwner);
                if (!ok) return errorMap("Нет доступа, сессия запущена или не найдена");
                return Map.of("deleted", true, "sessionId", sessionId);
            });
        });

        server.createContext("/api/backtest/start", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            if (requireUser(exchange) == null) {
                handleJson(exchange, () -> errorMap("Требуется авторизация"));
                return;
            }
            handleJson(exchange, () -> startBacktestFromRequest(exchange));
        });

        server.createContext("/api/backtest/result", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            if (requireUser(exchange) == null) {
                handleJson(exchange, () -> errorMap("Требуется авторизация"));
                return;
            }
            handleJson(exchange, () -> {
                Map<String, Object> result = TradingSessionManager.getInstance().getLastBacktestResult();
                return result != null ? result : Map.of("ready", false, "message", "Результатов нет");
            });
        });

        // ---- Profile / Auth / Keys ----

        server.createContext("/api/auth/telegram", exchange -> handleJson(exchange, () -> {
            if (!"POST".equals(exchange.getRequestMethod())) return errorMap("POST required");
            Map<String, Object> body = parseBody(exchange);
            String botToken = ton.dariushkmetsyak.Config.AppConfig.getInstance().getBotToken();
            if (botToken == null || botToken.isBlank() || "YOUR_BOT_TOKEN_HERE".equals(botToken)) {
                return errorMap("Бот не настроен: укажите telegram.bot.token в config.properties");
            }
            UserProfileManager upm = UserProfileManager.getInstance();
            Map<String, Object> tgUser = null;

            // Mode 1: Telegram WebApp initData (verified)
            String initData = (String) body.get("initData");
            if (initData != null && !initData.isBlank()) {
                tgUser = upm.verifyInitData(initData, botToken);
            }

            // Mode 1b: Telegram WebApp unsafe fallback (some clients provide empty initData)
            // Controlled by config: telegram.webapp.unsafe.auth.enabled=true|false
            if (tgUser == null && AppConfig.getInstance().isUnsafeTelegramWebAppAuthEnabled()) {
                Object unsafeUserObj = body.get("unsafeUser");
                if (unsafeUserObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> unsafeUser = (Map<String, Object>) unsafeUserObj;
                    if (unsafeUser.get("id") != null) {
                        tgUser = new java.util.HashMap<>(unsafeUser);
                    }
                }
            }

            // Mode 2: Telegram Login Widget data (browser)
            if (tgUser == null && body.containsKey("id") && body.containsKey("hash")) {
                tgUser = upm.verifyLoginWidget(body, botToken);
            }

            // Mode 3: dev bypass (localhost only)
            if (tgUser == null) {
                String host = exchange.getRemoteAddress().getAddress().getHostAddress();
                if ("dev_bypass".equals(initData) && (host.startsWith("127.") || host.startsWith("::1"))) {
                    tgUser = new java.util.HashMap<>(Map.of("id", 0L, "first_name", "Dev", "username", "dev"));
                } else {
                    return errorMap("Ошибка авторизации Telegram: проверьте telegram.bot.token и telegram.bot.username");
                }
            }

            UserProfileManager.UserProfile profile = upm.updateFromTelegram(tgUser);
            String token = upm.createSession(profile.telegramUserId);
            Map<String, Object> resp = new LinkedHashMap<>(profile.toPublicMap());
            resp.put("token", token);
            return resp;
        }));

        server.createContext("/api/profile", exchange -> handleJson(exchange, () -> {
            Long userId = resolveUser(exchange);
            if (userId == null) return errorMap("Требуется авторизация");
            if ("POST".equals(exchange.getRequestMethod())) {
                Map<String, Object> body = parseBody(exchange);
                UserProfileManager upm = UserProfileManager.getInstance();
                UserProfileManager.UserProfile p = upm.loadProfile(userId);
                if (p == null) return errorMap("Профиль не найден");
                if (body.containsKey("telegramFirstName")) p.telegramFirstName = (String) body.get("telegramFirstName");
                upm.saveProfile(p);
                return p.toPublicMap();
            }
            UserProfileManager.UserProfile p = UserProfileManager.getInstance().loadProfile(userId);
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
            return UserProfileManager.getInstance().getWallet(userId);
        }));

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        log.info("🚀 MiniApp сервер запущен на порту {}", port);
        log.info("   URL: http://localhost:{}", port);

        // Load persisted sessions and auto-resume unexpectedly stopped ones
        TradingSessionManager mgr = TradingSessionManager.getInstance();
        mgr.loadSessions();
        long chatId = AppConfig.getInstance().getDefaultChatId();
        mgr.autoResumeSessions(chatId);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            log.info("MiniApp сервер остановлен");
        }
    }

    // ---- Handlers ----

    private Map<String, Object> startTradingFromRequest(HttpExchange exchange) throws Exception {
        return startTradingFromRequest(exchange, null);
    }
    private Map<String, Object> startTradingFromRequest(HttpExchange exchange, Long ownerId) throws Exception {
        Map<String, Object> body = parseBody(exchange);
        String type = (String) body.getOrDefault("type", "tester");
        String coinName = (String) body.getOrDefault("coin", "bitcoin");
        double tradingSum = toDouble(body.get("tradingSum"), 100.0);
        double startAssets = toDouble(body.get("startAssets"), 150.0);
        double buyGap = toDouble(body.get("buyGap"), 3.5);
        double sellWithProfitGap = toDouble(body.get("sellWithProfitGap"), 2.0);
        double sellWithLossGap = toDouble(body.get("sellWithLossGap"), 8.0);
        int updateTimeout = toInt(body.get("updateTimeout"), 30);
        int chartRefreshInterval = toInt(body.get("chartRefreshInterval"), 60);
        long chatId = toLong(body.get("chatId"), AppConfig.getInstance().getDefaultChatId());

        TradingSessionManager mgr = TradingSessionManager.getInstance();

        Object result;
        switch (type.toLowerCase()) {
            case "binance":
            case "binance_real":
                result = mgr.startBinanceTrading(coinName, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chartRefreshInterval, chatId);
                break;
            case "binance_test":
                result = mgr.startBinanceTestTrading(coinName, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chartRefreshInterval, chatId);
                break;
            default: // tester
                result = mgr.startTesterTrading(coinName, startAssets, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap, updateTimeout, chartRefreshInterval, chatId);
        }
        if (result instanceof TradingSessionManager.SessionInfo) {
            TradingSessionManager.SessionInfo sess = (TradingSessionManager.SessionInfo) result;
            if (ownerId != null && ownerId != 0L) sess.ownerId = ownerId;
            TradingSessionManager.getInstance().saveSessions();
            return sess.toMap();
        }
        return (Map<String, Object>) result;
    }

    private Map<String, Object> startBacktestFromRequest(HttpExchange exchange) throws Exception {
        Map<String, Object> body = parseBody(exchange);
        String coinName = (String) body.getOrDefault("coin", "bitcoin");
        double tradingSum = toDouble(body.get("tradingSum"), 100.0);
        double buyGap = toDouble(body.get("buyGap"), 3.5);
        double sellWithProfitGap = toDouble(body.get("sellWithProfitGap"), 2.0);
        double sellWithLossGap = toDouble(body.get("sellWithLossGap"), 8.0);
        String chartType = (String) body.getOrDefault("chartType", "1d");
        TradingSessionManager.SessionInfo info = TradingSessionManager.getInstance()
                .startBacktest(coinName, tradingSum, buyGap, sellWithProfitGap, sellWithLossGap, chartType);
        return info.toMap();
    }

    @SuppressWarnings("unchecked")
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
                    m.put("id", c.getId());
                    m.put("name", c.getName());
                    m.put("symbol", c.getSymbol() != null ? c.getSymbol().toUpperCase() : "");
                    m.put("rank", c.getMarket_cap_rank());
                    return m;
                })
                .collect(Collectors.toList());
    }

    private Map<String, Object> getChartData(String coinId, String interval) {
        try {
            Coin coin = CoinsList.getCoinByName(coinId);
            String geckoInterval;
            int days;
            switch (interval) {
                case "7d": days = 7; geckoInterval = "daily"; break;
                case "30d": days = 30; geckoInterval = "daily"; break;
                case "90d": days = 90; geckoInterval = "daily"; break;
                case "1y": days = 365; geckoInterval = "daily"; break;
                default: days = 1; geckoInterval = ""; break; // 5-min
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
        if (prices != null) {
            for (List<Number> point : prices) {
                result.add(new double[]{point.get(0).doubleValue(), point.get(1).doubleValue()});
            }
        }
        return result;
    }

    // ---- Utils ----

    @FunctionalInterface
    interface JsonSupplier { Object get() throws Exception; }

    private void handleJson(HttpExchange exchange, JsonSupplier supplier) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }
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

    private String getPathParam(String path, String prefix) {
        if (path == null || !path.startsWith(prefix)) return "";
        return path.substring(prefix.length()).replaceAll("/.*", "");
    }

    private Map<String, Object> errorMap(String msg) {
        return Map.of("error", msg != null ? msg : "Неизвестная ошибка");
    }

    /** Extract bearer token from Authorization header or query param, resolve userId */
    private Long resolveUser(com.sun.net.httpserver.HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String token = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            token = auth.substring(7).trim();
        } else {
            token = getQueryParam(exchange.getRequestURI().getQuery(), "token");
        }
        return UserProfileManager.getInstance().resolveSession(token);
    }

    private Long requireUser(com.sun.net.httpserver.HttpExchange exchange) {
        Long userId = resolveUser(exchange);
        if (userId == null || userId == 0L) return null;
        return userId;
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

    // ---- Static File Handler ----

    private static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.equals("/index.html")) {
                path = "/static/index.html";
            } else {
                path = "/static" + path;
            }
            addCors(exchange);
            try (InputStream is = MiniAppServer.class.getResourceAsStream(path)) {
                if (is == null) {
                    byte[] msg = "404 Not Found".getBytes();
                    exchange.sendResponseHeaders(404, msg.length);
                    exchange.getResponseBody().write(msg);
                    exchange.getResponseBody().close();
                    return;
                }
                byte[] bytes = is.readAllBytes();
                String ct = getContentType(path);
                exchange.getResponseHeaders().set("Content-Type", ct);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            }
        }

        private void addCors(HttpExchange e) {
            e.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        }

        private String getContentType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=UTF-8";
            if (path.endsWith(".css")) return "text/css; charset=UTF-8";
            if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".jpg")) return "image/jpeg";
            if (path.endsWith(".svg")) return "image/svg+xml";
            if (path.endsWith(".ico")) return "image/x-icon";
            return "application/octet-stream";
        }
    }
}
