package ton.dariushkmetsyak.Web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages user profiles, Telegram auth, Binance key storage (incl. PEM upload),
 * and per-user data isolation.
 */
public class UserProfileManager {
    private static final Logger log = LoggerFactory.getLogger(UserProfileManager.class);
    private static final String PROFILES_DIR = "profiles";
    private static final String PEMS_DIR     = "profiles/pems";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static UserProfileManager instance;

    // token → userId
    private final ConcurrentHashMap<String, Long>   sessionTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>   tokenExpiry   = new ConcurrentHashMap<>();
    private static final long TOKEN_TTL_MS = 7L * 24 * 3600 * 1000;

    // ── UserProfile ──────────────────────────────────────────────────────────

    public static class UserProfile {
        public long   telegramUserId;
        public String telegramUsername   = "";
        public String telegramFirstName  = "";
        public String telegramPhotoUrl   = "";
        // Binance keys — stored as API key string; PEM stored as file reference
        public String binanceApiKey         = "";
        public String binancePrivKeyPath    = "";   // path to pem file in PEMS_DIR
        public String binanceTestApiKey     = "";
        public String binanceTestPrivKeyPath = "";
        public long createdAt;
        public long updatedAt;

        public UserProfile() {}
        public UserProfile(long uid, String username, String firstName) {
            this.telegramUserId  = uid;
            this.telegramUsername  = username != null ? username : "";
            this.telegramFirstName = firstName != null ? firstName : "";
            this.createdAt = this.updatedAt = System.currentTimeMillis();
        }

        public Map<String, Object> toPublicMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("telegramUserId",    telegramUserId);
            m.put("telegramUsername",  telegramUsername);
            m.put("telegramFirstName", telegramFirstName);
            m.put("telegramPhotoUrl",  telegramPhotoUrl);
            m.put("hasBinanceKeys",     binanceApiKey     != null && !binanceApiKey.isBlank());
            m.put("hasBinanceTestKeys", binanceTestApiKey != null && !binanceTestApiKey.isBlank());
            m.put("createdAt", createdAt);
            m.put("updatedAt", updatedAt);
            return m;
        }
    }

    private UserProfileManager() {
        try {
            Files.createDirectories(Paths.get(PROFILES_DIR));
            Files.createDirectories(Paths.get(PEMS_DIR));
        } catch (IOException ignored) {}
    }

    public static synchronized UserProfileManager getInstance() {
        if (instance == null) instance = new UserProfileManager();
        return instance;
    }

    // ── Telegram Auth ────────────────────────────────────────────────────────

    /**
     * Verify Telegram WebApp initData (HMAC-SHA256).
     * Returns parsed user map on success, null on failure.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyInitData(String initData, String botToken) {
        if (initData == null || initData.isBlank() || blank(botToken)) return null;
        try {
            Map<String, String> params = new LinkedHashMap<>();
            String hash = null;
            for (String part : initData.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;
                String key = urlDecodePreservePlus(kv[0]);
                String val = urlDecodePreservePlus(kv[1]);
                if ("hash".equals(key)) hash = val;
                else params.put(key, val);
            }
            if (blank(hash)) return null;

            StringBuilder sb = new StringBuilder();
            params.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> { if (sb.length() > 0) sb.append("\n"); sb.append(e.getKey()).append('=').append(e.getValue()); });

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec webAppData = new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(webAppData);
            byte[] secretKey = mac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] computed = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
            if (!secureHexEquals(bytesToHex(computed), hash)) return null;

            String authDate = params.get("auth_date");
            if (!isFreshAuthDate(authDate, 86400)) return null;

            String userJson = params.get("user");
            if (blank(userJson)) return null;
            Map<String, Object> user = mapper.readValue(userJson, Map.class);
            user.put("auth_date", authDate);
            return user;
        } catch (Exception e) {
            log.warn("Error verifying initData: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Verify Telegram Login Widget callback data (HMAC-SHA256 with SHA256(botToken) as key).
     * Used for browser-based login via widget.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyLoginWidget(Map<String, Object> data, String botToken) {
        if (data == null || !data.containsKey("hash") || blank(botToken)) return null;
        try {
            String hash = str(data.get("hash"));
            if (blank(hash)) return null;
            // Build check string: sorted key=value, excluding hash and null values
            StringBuilder sb = new StringBuilder();
            data.entrySet().stream()
                .filter(e -> !"hash".equals(e.getKey()) && e.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> { if (sb.length() > 0) sb.append("\n"); sb.append(e.getKey()).append('=').append(e.getValue()); });

            // Secret key = SHA256(botToken)
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] secretKey = sha256.digest(botToken.getBytes(StandardCharsets.UTF_8));

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] computed = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
            if (!secureHexEquals(bytesToHex(computed), hash)) {
                log.warn("Login widget verification failed");
                return null;
            }

            if (!isFreshAuthDate(data.get("auth_date"), 86400)) {
                log.warn("Login widget data expired");
                return null;
            }
            return data;
        } catch (Exception e) {
            log.warn("Error verifying login widget: {}", e.getMessage());
            return null;
        }
    }

    private boolean secureHexEquals(String computedHex, String incomingHex) {
        if (computedHex == null || incomingHex == null) return false;
        return MessageDigest.isEqual(
            computedHex.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
            incomingHex.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean isFreshAuthDate(Object authDate, long maxAgeSec) {
        if (authDate == null) return false;
        try {
            long ts = Long.parseLong(authDate.toString());
            long now = System.currentTimeMillis() / 1000;
            return ts <= now + 300 && now - ts <= maxAgeSec;
        } catch (Exception e) {
            return false;
        }
    }

    private String urlDecodePreservePlus(String s) throws java.io.UnsupportedEncodingException {
        if (s == null) return "";
        return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
    }

    private String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public String createSession(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "") + Long.toHexString(userId);
        sessionTokens.put(token, userId);
        tokenExpiry.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        // Cleanup
        long now = System.currentTimeMillis();
        tokenExpiry.entrySet().removeIf(e -> e.getValue() < now);
        sessionTokens.keySet().retainAll(tokenExpiry.keySet());
        return token;
    }

    public Long resolveSession(String token) {
        if (token == null || token.isBlank()) return null;
        Long exp = tokenExpiry.get(token);
        if (exp == null || exp < System.currentTimeMillis()) {
            sessionTokens.remove(token);
            tokenExpiry.remove(token);
            return null;
        }
        return sessionTokens.get(token);
    }

    // ── Profile CRUD ─────────────────────────────────────────────────────────

    private String profilePath(long userId) {
        return Paths.get(PROFILES_DIR, userId + ".json").toString();
    }

    public UserProfile loadProfile(long userId) {
        try {
            File f = new File(profilePath(userId));
            if (!f.exists()) return null;
            return mapper.readValue(f, UserProfile.class);
        } catch (Exception e) { log.error("Load profile {}", userId, e); return null; }
    }

    public void saveProfile(UserProfile p) {
        try {
            p.updatedAt = System.currentTimeMillis();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(profilePath(p.telegramUserId)), p);
        } catch (Exception e) { log.error("Save profile {}", p.telegramUserId, e); }
    }

    public UserProfile getOrCreate(long uid, String username, String firstName) {
        UserProfile p = loadProfile(uid);
        if (p == null) { p = new UserProfile(uid, username, firstName); saveProfile(p); }
        return p;
    }

    public UserProfile updateFromTelegram(Map<String, Object> tgUser) {
        long uid       = toLong(tgUser.get("id"));
        String username   = str(tgUser.get("username"));
        String firstName  = str(tgUser.get("first_name"));
        String photoUrl   = str(tgUser.get("photo_url"));
        UserProfile p = getOrCreate(uid, username, firstName);
        p.telegramUsername  = username;
        p.telegramFirstName = firstName;
        if (!photoUrl.isBlank()) p.telegramPhotoUrl = photoUrl;
        saveProfile(p);
        return p;
    }

    // ── Binance Keys ─────────────────────────────────────────────────────────

    /**
     * Save API key text and (optional) PEM file content for a user.
     * pemContent is the raw PEM string uploaded from browser.
     * If pemContent is provided, it is written to profiles/pems/<userId>_<net>.pem
     * and the path is stored. resolvePrivateKeyPath() can then read it directly.
     */
    public Map<String, Object> saveBinanceKeys(long userId, String apiKey, String pemContent, boolean testnet) {
        UserProfile p = loadProfile(userId);
        if (p == null) return Map.of("error", "Профиль не найден");

        String pemPath = null;
        if (pemContent != null && !pemContent.isBlank()) {
            String suffix = testnet ? "test" : "main";
            pemPath = Paths.get(PEMS_DIR, userId + "_" + suffix + ".pem").toString();
            try {
                // Normalise — ensure PEM headers exist
                String content = pemContent.replace("\\n", "\n").trim();
                if (!content.contains("-----BEGIN")) {
                    content = "-----BEGIN PRIVATE KEY-----\n" + content + "\n-----END PRIVATE KEY-----\n";
                }
                Files.writeString(Paths.get(pemPath), content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("Failed to write PEM for user {}", userId, e);
                return Map.of("error", "Ошибка сохранения PEM: " + e.getMessage());
            }
        }

        if (testnet) {
            if (apiKey  != null && !apiKey.isBlank())  p.binanceTestApiKey      = apiKey;
            if (pemPath != null) p.binanceTestPrivKeyPath = pemPath;
        } else {
            if (apiKey  != null && !apiKey.isBlank())  p.binanceApiKey      = apiKey;
            if (pemPath != null) p.binancePrivKeyPath = pemPath;
        }
        saveProfile(p);
        return Map.of("ok", true, "testnet", testnet, "pemSaved", pemPath != null);
    }

    public Map<String, Object> getBinanceKeysStatus(long userId) {
        UserProfile p = loadProfile(userId);
        if (p == null) return Map.of("error", "Профиль не найден");
        return Map.of(
            "mainnet", Map.of(
                "hasApiKey",  !blank(p.binanceApiKey),
                "hasPem",     !blank(p.binancePrivKeyPath) && new File(p.binancePrivKeyPath).exists(),
                "apiKeyHint", mask(p.binanceApiKey)
            ),
            "testnet", Map.of(
                "hasApiKey",  !blank(p.binanceTestApiKey),
                "hasPem",     !blank(p.binanceTestPrivKeyPath) && new File(p.binanceTestPrivKeyPath).exists(),
                "apiKeyHint", mask(p.binanceTestApiKey)
            )
        );
    }

    // ── Wallet (structured) ───────────────────────────────────────────────────

    /**
     * Fetch live wallet balances from Binance.
     * Returns list of {symbol, name, amount} for all non-zero assets.
     */
    public Map<String, Object> getWallet(long userId) {
        UserProfile p = loadProfile(userId);
        if (p == null) return Map.of("error", "Профиль не найден");

        Map<String, Object> result = new LinkedHashMap<>();

        // Mainnet
        if (!blank(p.binanceApiKey) && !blank(p.binancePrivKeyPath)) {
            result.put("mainnet", fetchBinanceBalances(p.binanceApiKey, p.binancePrivKeyPath, false));
        }
        // Testnet
        if (!blank(p.binanceTestApiKey) && !blank(p.binanceTestPrivKeyPath)) {
            result.put("testnet", fetchBinanceBalances(p.binanceTestApiKey, p.binanceTestPrivKeyPath, true));
        }

        // Active session snapshots
        List<Map<String, Object>> running = TradingSessionManager.getInstance()
            .getSessionsByOwner(userId).stream()
            .filter(s -> "RUNNING".equals(s.get("status")))
            .map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sessionId", s.get("id")); m.put("coin", s.get("coinName"));
                m.put("coinBalance", s.get("coinBalance")); m.put("usdtBalance", s.get("usdtBalance"));
                return m;
            }).collect(Collectors.toList());
        result.put("activeSessions", running);
        return result;
    }

    private Object fetchBinanceBalances(String apiKey, String privKeyPath, boolean testnet) {
        try {
            char[] key  = apiKey.toCharArray();
            char[] pem  = ton.dariushkmetsyak.Config.AppConfig.getInstance()
                .resolvePrivateKeyPath(privKeyPath).toCharArray();
            ton.dariushkmetsyak.TradingApi.ApiService.Account acc =
                ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder.createNewBinance(
                    key, pem,
                    testnet
                        ? ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder.BINANCE_BASE_URL.TESTNET
                        : ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder.BINANCE_BASE_URL.MAINNET);

            Map<ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin, Double> assets =
                acc.wallet().getAllAssets();

            List<Map<String, Object>> balances = new ArrayList<>();
            for (var entry : assets.entrySet()) {
                if (entry.getValue() == null || entry.getValue() == 0) continue;
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("symbol", entry.getKey().getSymbol());
                b.put("name",   entry.getKey().getName());
                b.put("amount", entry.getValue());
                balances.add(b);
            }
            // Sort: USDT first, then by amount desc
            balances.sort((a, b2) -> {
                boolean aUsdt = "USDT".equalsIgnoreCase((String)a.get("symbol"));
                boolean bUsdt = "USDT".equalsIgnoreCase((String)b2.get("symbol"));
                if (aUsdt) return -1; if (bUsdt) return 1;
                return Double.compare((Double)b2.get("amount"), (Double)a.get("amount"));
            });
            return balances;
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    // ── Trade Report ─────────────────────────────────────────────────────────

    public Map<String, Object> getTradingReport(long userId) {
        List<Map<String, Object>> all = TradingSessionManager.getInstance().getSessionsByOwner(userId);
        long buyCount = 0, sellCount = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (var s : all) {
            byType.merge((String) s.getOrDefault("type", "?"), 1, Integer::sum);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evts = (List<Map<String, Object>>) s.get("events");
            if (evts != null) for (var e : evts) {
                if ("BUY".equals(e.get("type")))  buyCount++;
                if ("SELL".equals(e.get("type"))) sellCount++;
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalSessions", all.size());
        r.put("buyCount",  buyCount);
        r.put("sellCount", sellCount);
        r.put("sessionsByType", byType);
        r.put("sessions", all);
        return r;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean blank(String s) { return s == null || s.isBlank(); }
    private String  str(Object v)   { return v == null ? "" : v.toString(); }
    private String  mask(String k)  {
        if (blank(k)) return "—";
        if (k.length() < 8) return "••••";
        return k.substring(0, 4) + "••••" + k.substring(k.length() - 4);
    }
    private long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0L; }
    }
}
