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

/**
 * Manages user profiles stored in profiles/<telegramUserId>.json.
 * Provides Telegram WebApp initData verification and Binance API key storage.
 */
public class UserProfileManager {
    private static final Logger log = LoggerFactory.getLogger(UserProfileManager.class);
    private static final String PROFILES_DIR = "profiles";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static UserProfileManager instance;

    // In-memory session tokens: token → telegramUserId
    private final ConcurrentHashMap<String, Long> sessionTokens = new ConcurrentHashMap<>();
    // Token expiry: token → expiresAt millis
    private final ConcurrentHashMap<String, Long> tokenExpiry = new ConcurrentHashMap<>();
    private static final long TOKEN_TTL_MS = 7L * 24 * 3600 * 1000; // 7 days

    public static class UserProfile {
        public long telegramUserId;
        public String telegramUsername;
        public String telegramFirstName;
        public String telegramPhotoUrl;
        // Binance keys stored as plain text (server-local, no external exposure)
        public String binanceApiKey = "";
        public String binanceSecretKey = "";   // for HMAC accounts
        public String binancePrivKeyPath = ""; // for Ed25519 accounts
        public String binanceTestApiKey = "";
        public String binanceTestSecretKey = "";
        public String binanceTestPrivKeyPath = "";
        public long createdAt;
        public long updatedAt;

        public UserProfile() {}
        public UserProfile(long uid, String username, String firstName) {
            this.telegramUserId = uid;
            this.telegramUsername = username;
            this.telegramFirstName = firstName;
            this.createdAt = this.updatedAt = System.currentTimeMillis();
        }

        public Map<String, Object> toPublicMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("telegramUserId", telegramUserId);
            m.put("telegramUsername", telegramUsername);
            m.put("telegramFirstName", telegramFirstName);
            m.put("telegramPhotoUrl", telegramPhotoUrl);
            m.put("hasBinanceKeys", binanceApiKey != null && !binanceApiKey.isBlank());
            m.put("hasBinanceTestKeys", binanceTestApiKey != null && !binanceTestApiKey.isBlank());
            m.put("createdAt", createdAt);
            m.put("updatedAt", updatedAt);
            return m;
        }
    }

    private UserProfileManager() {
        try { Files.createDirectories(Paths.get(PROFILES_DIR)); } catch (IOException ignored) {}
    }

    public static synchronized UserProfileManager getInstance() {
        if (instance == null) instance = new UserProfileManager();
        return instance;
    }

    // ---- Telegram Auth ----

    /**
     * Verify Telegram WebApp initData using HMAC-SHA256.
     * Returns parsed user map if valid, null if invalid.
     * Bot token is read from AppConfig.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyInitData(String initData, String botToken) {
        if (initData == null || initData.isBlank()) return null;
        try {
            // Parse query string
            Map<String, String> params = new LinkedHashMap<>();
            String hash = null;
            for (String part : initData.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2) {
                    String key = java.net.URLDecoder.decode(kv[0], "UTF-8");
                    String val = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    if ("hash".equals(key)) hash = val;
                    else params.put(key, val);
                }
            }
            if (hash == null) return null;

            // Build data-check-string: sorted key=value pairs joined by \n
            StringBuilder sb = new StringBuilder();
            params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> { if (sb.length() > 0) sb.append('\n'); sb.append(e.getKey()).append('=').append(e.getValue()); });

            // Compute HMAC-SHA256
            Mac mac = Mac.getInstance("HmacSHA256");
            // Secret key = HMAC-SHA256("WebAppData", botToken)
            SecretKeySpec webAppData = new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(webAppData);
            byte[] secretKey = mac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));

            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] computedHash = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
            String computedHex = bytesToHex(computedHash);

            if (!MessageDigest.isEqual(computedHex.getBytes(), hash.getBytes())) {
                log.warn("Telegram initData verification failed");
                return null;
            }

            // Parse user JSON
            String userJson = params.get("user");
            if (userJson == null) return null;
            Map<String, Object> user = mapper.readValue(userJson, Map.class);
            user.put("auth_date", params.get("auth_date"));
            return user;
        } catch (Exception e) {
            log.error("Error verifying initData", e);
            return null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Create session token for verified user, return token string */
    public String createSession(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "") + Long.toHexString(userId);
        long expires = System.currentTimeMillis() + TOKEN_TTL_MS;
        sessionTokens.put(token, userId);
        tokenExpiry.put(token, expires);
        // Cleanup expired tokens
        tokenExpiry.entrySet().removeIf(e -> e.getValue() < System.currentTimeMillis());
        sessionTokens.keySet().retainAll(tokenExpiry.keySet());
        return token;
    }

    /** Resolve userId from token, return null if invalid/expired */
    public Long resolveSession(String token) {
        if (token == null || token.isBlank()) return null;
        Long expires = tokenExpiry.get(token);
        if (expires == null || expires < System.currentTimeMillis()) {
            sessionTokens.remove(token);
            tokenExpiry.remove(token);
            return null;
        }
        return sessionTokens.get(token);
    }

    // ---- Profile CRUD ----

    private String profilePath(long userId) {
        return Paths.get(PROFILES_DIR, userId + ".json").toString();
    }

    public UserProfile loadProfile(long userId) {
        try {
            File f = new File(profilePath(userId));
            if (!f.exists()) return null;
            return mapper.readValue(f, UserProfile.class);
        } catch (Exception e) {
            log.error("Failed to load profile for {}", userId, e);
            return null;
        }
    }

    public void saveProfile(UserProfile profile) {
        try {
            profile.updatedAt = System.currentTimeMillis();
            mapper.writerWithDefaultPrettyPrinter()
                .writeValue(new File(profilePath(profile.telegramUserId)), profile);
        } catch (Exception e) {
            log.error("Failed to save profile for {}", profile.telegramUserId, e);
        }
    }

    public UserProfile getOrCreateProfile(long userId, String username, String firstName) {
        UserProfile p = loadProfile(userId);
        if (p == null) {
            p = new UserProfile(userId, username, firstName);
            saveProfile(p);
        }
        return p;
    }

    /** Update profile fields from login data */
    public UserProfile updateFromTelegram(Map<String, Object> tgUser) {
        long uid = toLong(tgUser.get("id"));
        String username = (String) tgUser.getOrDefault("username", "");
        String firstName = (String) tgUser.getOrDefault("first_name", "");
        String photoUrl  = (String) tgUser.getOrDefault("photo_url", "");
        UserProfile p = getOrCreateProfile(uid, username, firstName);
        p.telegramUsername  = username;
        p.telegramFirstName = firstName;
        if (photoUrl != null && !photoUrl.isBlank()) p.telegramPhotoUrl = photoUrl;
        saveProfile(p);
        return p;
    }

    // ---- Binance keys ----

    public Map<String, Object> updateBinanceKeys(long userId, Map<String, Object> body, boolean testnet) {
        UserProfile p = loadProfile(userId);
        if (p == null) return Map.of("error", "Профиль не найден");
        String apiKey   = (String) body.getOrDefault("apiKey", "");
        String privPath = (String) body.getOrDefault("privKeyPath", "");
        if (testnet) {
            if (!apiKey.isBlank()) p.binanceTestApiKey = apiKey;
            if (!privPath.isBlank()) p.binanceTestPrivKeyPath = privPath;
        } else {
            if (!apiKey.isBlank()) p.binanceApiKey = apiKey;
            if (!privPath.isBlank()) p.binancePrivKeyPath = privPath;
        }
        saveProfile(p);
        return Map.of("ok", true, "testnet", testnet);
    }

    /** Return Binance keys suitable for AccountBuilder (masks secret in response) */
    public Map<String, Object> getBinanceKeysStatus(long userId) {
        UserProfile p = loadProfile(userId);
        if (p == null) return Map.of("error", "Профиль не найден");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mainnet", Map.of(
            "hasApiKey",   p.binanceApiKey != null && !p.binanceApiKey.isBlank(),
            "hasPrivKey",  p.binancePrivKeyPath != null && !p.binancePrivKeyPath.isBlank(),
            "apiKeyHint",  maskKey(p.binanceApiKey),
            "privKeyPath", p.binancePrivKeyPath != null ? p.binancePrivKeyPath : ""
        ));
        m.put("testnet", Map.of(
            "hasApiKey",   p.binanceTestApiKey != null && !p.binanceTestApiKey.isBlank(),
            "hasPrivKey",  p.binanceTestPrivKeyPath != null && !p.binanceTestPrivKeyPath.isBlank(),
            "apiKeyHint",  maskKey(p.binanceTestApiKey),
            "privKeyPath", p.binanceTestPrivKeyPath != null ? p.binanceTestPrivKeyPath : ""
        ));
        return m;
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "—";
        return key.substring(0, 4) + "••••" + key.substring(key.length() - 4);
    }

    /** Build trade history summary from all sessions for this user */
    public Map<String, Object> getTradingReport(long userId) {
        var mgr = TradingSessionManager.getInstance();
        var all = mgr.getAllSessions();

        Map<String, Object> report = new LinkedHashMap<>();
        int totalSessions = all.size();
        long buyCount = 0, sellCount = 0;
        Map<String, Integer> sessionsByType = new LinkedHashMap<>();
        Map<String, Double> profitByType = new LinkedHashMap<>();

        for (Map<String, Object> s : all) {
            String type = (String) s.getOrDefault("type", "UNKNOWN");
            sessionsByType.merge(type, 1, Integer::sum);
            // Count BUY/SELL events
            @SuppressWarnings("unchecked")
            var events = (java.util.List<Map<String, Object>>) s.get("events");
            if (events != null) {
                for (var e : events) {
                    String etype = (String) e.get("type");
                    if ("BUY".equals(etype)) buyCount++;
                    if ("SELL".equals(etype)) sellCount++;
                }
            }
        }

        report.put("totalSessions", totalSessions);
        report.put("buyCount", buyCount);
        report.put("sellCount", sellCount);
        report.put("sessionsByType", sessionsByType);
        report.put("sessions", all);
        return report;
    }

    private long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0L; }
    }
}
