package ton.dariushkmetsyak.Api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import ton.dariushkmetsyak.Config.RuntimeConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public class TelegramMiniAppApiServer {
    private final TradingSessionManager manager;
    private HttpServer server;

    public TelegramMiniAppApiServer(TradingSessionManager manager) {
        this.manager = manager;
    }

    public void start() throws IOException {
        int port = RuntimeConfig.getInt("API_PORT", 8080);
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        server.createContext("/health", exchange -> writeJson(exchange, 200, "{\"ok\":true}"));
        server.createContext("/api/status", exchange -> writeJson(exchange, 200, manager.getStatusJson()));
        server.createContext("/api/trade/stop", exchange -> writeJson(exchange, 200, manager.stopTrading()));
        server.createContext("/api/trade/start-test", new StartTestHandler(manager));
        server.createContext("/miniapp", this::serveMiniApp);

        server.start();
        System.out.println("Mini App API server started on port " + port);
    }

    private void serveMiniApp(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, "{\"ok\":false,\"message\":\"Method not allowed\"}");
            return;
        }

        Path html = Path.of("src/main/resources/miniapp/index.html");
        if (!Files.exists(html)) {
            exchange.sendResponseHeaders(404, 0);
            exchange.getResponseBody().write("Mini App not found".getBytes(StandardCharsets.UTF_8));
            exchange.close();
            return;
        }

        byte[] bytes = Files.readAllBytes(html);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static class StartTestHandler implements HttpHandler {
        private final TradingSessionManager manager;

        private StartTestHandler(TradingSessionManager manager) {
            this.manager = manager;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            String coin = extract(body, "coin", "Bitcoin");
            double tradingSum = extractDouble(body, "tradingSum", 50);
            double buyGap = extractDouble(body, "buyGap", 3);
            double profitGap = extractDouble(body, "profitGap", 2);
            double lossGap = extractDouble(body, "lossGap", 3);
            int timeout = (int) extractDouble(body, "updateTimeout", 15);
            long chatId = (long) extractDouble(body, "chatId", 0);

            String result = manager.startTestTrading(coin, tradingSum, buyGap, profitGap, lossGap, timeout, chatId);
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private String extract(String source, String key, String defaultValue) {
            String needle = "\"" + key + "\"";
            int keyIdx = source.indexOf(needle);
            if (keyIdx < 0) return defaultValue;
            int colon = source.indexOf(':', keyIdx);
            if (colon < 0) return defaultValue;
            int firstQuote = source.indexOf('"', colon + 1);
            int secondQuote = source.indexOf('"', firstQuote + 1);
            if (firstQuote < 0 || secondQuote < 0) return defaultValue;
            return source.substring(firstQuote + 1, secondQuote);
        }

        private double extractDouble(String source, String key, double defaultValue) {
            String needle = "\"" + key + "\"";
            int keyIdx = source.indexOf(needle);
            if (keyIdx < 0) return defaultValue;
            int colon = source.indexOf(':', keyIdx);
            if (colon < 0) return defaultValue;
            int end = colon + 1;
            while (end < source.length() && " -0123456789.".indexOf(source.charAt(end)) >= 0) {
                end++;
            }
            String raw = source.substring(colon + 1, end).trim();
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }
}
