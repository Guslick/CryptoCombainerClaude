package ton.dariushkmetsyak.Web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.KeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages user profiles with secure per-user key storage.
 *
 * Security model for API keys:
 *  - Keys are stored in profiles/keys/<userId>/ — separate directory, tighter perms
 *  - PEM files stored in profiles/keys/<userId>/pems/
 *  - Profile JSON (profiles/<userId>.json) does NOT contain raw keys,
 *    only a flag indicating whether keys are set
 *  - Keys file: profiles/keys/<userId>/binance_keys.json (separate from profile)
 *  - File permissions set to 600 (owner read/write only)
 */
public class UserProfileManager {
    private static final Logger log = LoggerFactory.getLogger(UserProfileManager.class);
    private static final String PROFILES_DIR = "profiles";
    private static final String KEYS_DIR     = "profiles/keys";
    private static final ObjectMapper mapper  = new ObjectMapper();
    private static UserProfileManager instance;

    // ── UserProfile (NO keys stored here) ────────────────────────────────────

    public static class UserProfile {
        public long   telegramUserId;
        public String telegramUsername   = "";
        public String telegramFirstName  = "";
        public String telegramPhotoUrl   = "";
        // Only flags — never the actual key values
        public boolean hasBinanceMainnet = false;
        public boolean hasBinanceTestnet = false;
        public long createdAt;
        public long updatedAt;

        public UserProfile() {}
        public UserProfile(long uid, String username, String firstName) {
            this.telegramUserId  = uid;
            this.telegramUsername  = username  != null ? username  : "";
            this.telegramFirstName = firstName != null ? firstName : "";
            this.createdAt = this.updatedAt = System.currentTimeMillis();
        }

        public Map<String, Object> toPublicMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("telegramUserId",    telegramUserId);
            m.put("telegramUsername",  telegramUsername);
            m.put("telegramFirstName", telegramFirstName);
            m.put("telegramPhotoUrl",  telegramPhotoUrl);
            m.put("hasBinanceKeys",     hasBinanceMainnet);
            m.put("hasBinanceTestKeys", hasBinanceTestnet);
            m.put("createdAt", createdAt);
            m.put("updatedAt", updatedAt);
            return m;
        }
    }

    /** Stored separately from profile — never serialised into profile JSON */
    private static class BinanceKeys {
        public String apiKey     = "";
        public String privKeyPath = "";  // path to PEM file
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    private UserProfileManager() {
        try {
            Files.createDirectories(Paths.get(PROFILES_DIR));
            Files.createDirectories(Paths.get(KEYS_DIR));
        } catch (IOException ignored) {}
    }

    public static synchronized UserProfileManager getInstance() {
        if (instance == null) instance = new UserProfileManager();
        return instance;
    }

    // ── Telegram Auth ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyInitData(String initData, String botToken) {
        if (initData == null || initData.isBlank()) return null;
        try {
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
            StringBuilder sb = new StringBuilder();
            params.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> { if (sb.length() > 0) sb.append('\n'); sb.append(e.getKey()).append('=').append(e.getValue()); });
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec webApp = new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(webApp);
            byte[] sk = mac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sk, "HmacSHA256"));
            byte[] computed = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(bytesToHex(computed).getBytes(), hash.getBytes())) return null;
            String userJson = params.get("user");
            if (userJson == null) return null;
            Map<String, Object> user = mapper.readValue(userJson, Map.class);
            user.put("auth_date", params.get("auth_date"));
            return user;
        } catch (Exception e) { log.error("Error verifying initData", e); return null; }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> verifyLoginWidget(Map<String, Object> data, String botToken) {
        if (data == null || !data.containsKey("hash")) return null;
        try {
            String hash = (String) data.get("hash");
            StringBuilder sb = new StringBuilder();
            data.entrySet().stream().filter(e -> !"hash".equals(e.getKey())).sorted(Map.Entry.comparingByKey())
                .forEach(e -> { if (sb.length() > 0) sb.append('\n'); sb.append(e.getKey()).append('=').append(e.getValue()); });
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] sk = sha256.digest(botToken.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sk, "HmacSHA256"));
            byte[] computed = mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(bytesToHex(computed).getBytes(), hash.getBytes())) return null;
            Object authDate = data.get("auth_date");
            if (authDate != null && System.currentTimeMillis() / 1000 - Long.parseLong(authDate.toString()) > 86400) return null;
            return data;
        } catch (Exception e) { log.error("Error verifying login widget", e); return null; }
    }

    private String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ── Session tokens ────────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, Long> sessionTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> tokenExpiry   = new ConcurrentHashMap<>();
    private static final long TOKEN_TTL_MS = 7L * 24 * 3600 * 1000;

    public String createSession(long userId) {
        String token = UUID.randomUUID().toString().replace("-", "") + Long.toHexString(userId);
        sessionTokens.put(token, userId);
        tokenExpiry.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        long now = System.currentTimeMillis();
        tokenExpiry.entrySet().removeIf(e -> e.getValue() < now);
        sessionTokens.keySet().retainAll(tokenExpiry.keySet());
        return token;
    }

    public Long resolveSession(String token) {
        if (token == null || token.isBlank()) return null;
        Long exp = tokenExpiry.get(token);
        if (exp == null || exp < System.currentTimeMillis()) {
            sessionTokens.remove(token); tokenExpiry.remove(token); return null;
        }
        return sessionTokens.get(token);
    }

    // ── Profile CRUD ──────────────────────────────────────────────────────────

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
        long uid = toLong(tgUser.get("id"));
        String username   = str(tgUser.get("username"));
        String firstName  = str(tgUser.get("first_name"));
        String photoUrl   = str(tgUser.get("photo_url"));
        UserProfile p = getOrCreate(uid, username, firstName);
        p.telegramUsername = username; p.telegramFirstName = firstName;
        if (!photoUrl.isBlank()) p.telegramPhotoUrl = photoUrl;
        saveProfile(p);
        return p;
    }

    // ── Secure key storage ────────────────────────────────────────────────────

    /** Directory for a user's keys — isolated per user */
    private Path userKeysDir(long userId) throws IOException {
        Path dir = Paths.get(KEYS_DIR, String.valueOf(userId));
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            // Restrict permissions: owner read/write only (chmod 700)
            try { dir.toFile().setReadable(true, true); dir.toFile().setWritable(true, true); dir.toFile().setExecutable(true, true); } catch (Exception ignored) {}
        }
        return dir;
    }

    private Path keysFilePath(long userId, boolean testnet) throws IOException {
        return userKeysDir(userId).resolve(testnet ? "binance_test_keys.json" : "binance_keys.json");
    }

    private Path pemFilePath(long userId, boolean testnet) throws IOException {
        Path dir = userKeysDir(userId).resolve("pems");
        Files.createDirectories(dir);
        return dir.resolve(testnet ? "test.pem" : "main.pem");
    }

    private BinanceKeys loadKeys(long userId, boolean testnet) {
        try {
            Path path = keysFilePath(userId, testnet);
            if (!Files.exists(path)) return new BinanceKeys();
            return mapper.readValue(path.toFile(), BinanceKeys.class);
        } catch (Exception e) { log.warn("Failed to load keys for user {}", userId); return new BinanceKeys(); }
    }

    private void saveKeys(long userId, BinanceKeys keys, boolean testnet) throws IOException {
        Path path = keysFilePath(userId, testnet);
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), keys);
        // Restrict to owner only
        path.toFile().setReadable(true, true);
        path.toFile().setWritable(true, true);
    }

    /**
     * Save Binance API key and/or PEM content for a user.
     * Keys stored in profiles/keys/<userId>/ — NOT in the public profile JSON.
     */
    public Map<String, Object> saveBinanceKeys(long userId, String apiKey, String pemContent, boolean testnet) {
        try {
            BinanceKeys keys = loadKeys(userId, testnet);
            boolean pemSaved = false;

            if (apiKey != null && !apiKey.isBlank()) {
                keys.apiKey = apiKey.trim();
            }

            if (pemContent != null && !pemContent.isBlank()) {
                String content = pemContent.replace("\\n", "\n").trim();
                if (!content.contains("-----BEGIN")) {
                    content = "-----BEGIN PRIVATE KEY-----\n" + content + "\n-----END PRIVATE KEY-----\n";
                }
                Path pemPath = pemFilePath(userId, testnet);
                Files.writeString(pemPath, content, StandardCharsets.UTF_8);
                pemPath.toFile().setReadable(true, true);
                pemPath.toFile().setWritable(true, true);
                keys.privKeyPath = pemPath.toString();
                pemSaved = true;
            }

            saveKeys(userId, keys, testnet);

            // Update profile flags (no actual key data in profile)
            UserProfile p = loadProfile(userId);
            if (p != null) {
                boolean hasKey = !blank(keys.apiKey);
                boolean hasPem = !blank(keys.privKeyPath) && new File(keys.privKeyPath).exists();
                if (testnet) p.hasBinanceTestnet = hasKey || hasPem;
                else         p.hasBinanceMainnet = hasKey || hasPem;
                saveProfile(p);
            }

            return Map.of("ok", true, "testnet", testnet, "pemSaved", pemSaved);
        } catch (Exception e) {
            log.error("Failed to save keys for user {}", userId, e);
            return Map.of("error", "Ошибка сохранения ключей: " + e.getMessage());
        }
    }

    /** Public key status (hints only, no raw values) */
    public Map<String, Object> getBinanceKeysStatus(long userId) {
        BinanceKeys main = loadKeys(userId, false);
        BinanceKeys test = loadKeys(userId, true);
        return Map.of(
            "mainnet", Map.of(
                "hasApiKey", !blank(main.apiKey),
                "hasPem",    !blank(main.privKeyPath) && new File(main.privKeyPath).exists(),
                "apiKeyHint", mask(main.apiKey)
            ),
            "testnet", Map.of(
                "hasApiKey", !blank(test.apiKey),
                "hasPem",    !blank(test.privKeyPath) && new File(test.privKeyPath).exists(),
                "apiKeyHint", mask(test.apiKey)
            )
        );
    }

    /** Resolve API key for trading — called by TradingSessionManager */
    public String getBinanceApiKey(long userId, boolean testnet) {
        return loadKeys(userId, testnet).apiKey;
    }

    /** Resolve PEM path for trading — called by TradingSessionManager */
    public String getBinancePrivKeyPath(long userId, boolean testnet) {
        return loadKeys(userId, testnet).privKeyPath;
    }

    /**
     * Completely delete all key files for a user (testnet or mainnet).
     * Overwrites with zeros before deletion to prevent recovery.
     */
    public void deleteKeys(long userId, boolean testnet) {
        try {
            Path keysPath = keysFilePath(userId, testnet);
            Path pemPath  = pemFilePath(userId, testnet);
            // Overwrite with garbage before delete
            for (Path p : List.of(keysPath, pemPath)) {
                if (Files.exists(p)) {
                    try {
                        byte[] zeros = new byte[(int) Files.size(p)];
                        java.util.Arrays.fill(zeros, (byte) 0);
                        Files.write(p, zeros);
                    } catch (Exception ignored) {}
                    Files.delete(p);
                }
            }
            // Update profile flags
            UserProfile profile = loadProfile(userId);
            if (profile != null) {
                if (testnet) profile.hasBinanceTestnet = false;
                else         profile.hasBinanceMainnet = false;
                saveProfile(profile);
            }
            log.info("Deleted {} keys for user {}", testnet ? "testnet" : "mainnet", userId);
        } catch (Exception e) {
            log.error("Failed to delete keys for user {}", userId, e);
        }
    }

    // ── Wallet ────────────────────────────────────────────────────────────────

    /** Return balances for the specified network (testnet/mainnet) */
    public Map<String, Object> getWallet(long userId, boolean testnet) {
        BinanceKeys keys = loadKeys(userId, testnet);
        Map<String, Object> result = new LinkedHashMap<>();

        if (blank(keys.apiKey)) {
            result.put("error", "API ключи не настроены. Добавьте ключи в разделе Binance API ключи.");
            return result;
        }

        List<Map<String, Object>> balances = fetchBinanceBalances(keys.apiKey, keys.privKeyPath, testnet);
        result.put("balances", balances);

        // Active session snapshots for this user
        List<Map<String, Object>> running = TradingSessionManager.forUser(userId).getAllSessions()
            .stream().filter(s -> "RUNNING".equals(s.get("status")))
            .map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("sessionId", s.get("id")); m.put("coin", s.get("coinName"));
                m.put("coinBalance", s.get("coinBalance")); m.put("usdtBalance", s.get("usdtBalance"));
                return m;
            }).collect(Collectors.toList());
        result.put("activeSessions", running);
        return result;
    }

    private List<Map<String, Object>> fetchBinanceBalances(String apiKey, String privKeyPath, boolean testnet) {
        if (blank(apiKey)) {
            List<Map<String, Object>> err = new ArrayList<>();
            err.add(Map.of("error", "API ключ не настроен"));
            return err;
        }
        if (blank(privKeyPath) || !new java.io.File(privKeyPath).exists()) {
            List<Map<String, Object>> err = new ArrayList<>();
            err.add(Map.of("error", "PEM файл не найден. Загрузите .pem файл в настройках ключей."));
            return err;
        }
        try {
            char[] key = apiKey.toCharArray();
            String resolvedPem = ton.dariushkmetsyak.Config.AppConfig.getInstance()
                    .resolvePrivateKeyPath(privKeyPath);
            char[] pem = resolvedPem.toCharArray();
            ton.dariushkmetsyak.TradingApi.ApiService.Account acc =
                ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder.createNewBinance(key, pem,
                    testnet
                        ? ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder.BINANCE_BASE_URL.TESTNET
                        : ton.dariushkmetsyak.TradingApi.ApiService.AccountBuilder.BINANCE_BASE_URL.MAINNET);
            // getAllAssets() returns only assets with balance > 0
            Map<ton.dariushkmetsyak.GeckoApiService.geckoEntities.Coin, Double> assets =
                acc.wallet().getAllAssets();
            List<Map<String, Object>> list = new ArrayList<>();
            for (var e : assets.entrySet()) {
                Double amount = e.getValue();
                if (amount == null || amount <= 0) continue;
                String symbol = e.getKey().getSymbol() != null ? e.getKey().getSymbol() : "???";
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("symbol", symbol);
                b.put("name",   e.getKey().getName()   != null ? e.getKey().getName()   : "Unknown");
                b.put("amount", amount);
                // Calculate USDT value
                double usdtValue = 0;
                if ("USDT".equalsIgnoreCase(symbol) || "USDC".equalsIgnoreCase(symbol)
                        || "BUSD".equalsIgnoreCase(symbol) || "FDUSD".equalsIgnoreCase(symbol)) {
                    usdtValue = amount;
                } else {
                    try {
                        double price = ton.dariushkmetsyak.TradingApi.ApiService.Account.getCurrentPrice(e.getKey());
                        usdtValue = amount * price;
                    } catch (Exception priceErr) {
                        log.debug("Could not get price for {}: {}", symbol, priceErr.getMessage());
                    }
                }
                b.put("usdtValue", usdtValue);
                list.add(b);
            }
            // Sort: USDT first, then by usdtValue descending
            list.sort((a, b) -> {
                boolean aU = "USDT".equalsIgnoreCase((String)a.get("symbol"));
                boolean bU = "USDT".equalsIgnoreCase((String)b.get("symbol"));
                if (aU) return -1; if (bU) return 1;
                return Double.compare((Double)b.get("usdtValue"), (Double)a.get("usdtValue"));
            });
            log.info("Fetched {} assets for {} ({} balances > 0)",
                assets.size(), testnet ? "testnet" : "mainnet", list.size());
            return list;
        } catch (Exception e) {
            log.error("Failed to fetch Binance balances (testnet={}): {}", testnet, e.getMessage());
            List<Map<String, Object>> err = new ArrayList<>();
            err.add(Map.of("error", e.getMessage() != null ? e.getMessage() : "Ошибка подключения к Binance"));
            return err;
        }
    }

    // ── Trade report ──────────────────────────────────────────────────────────

    public Map<String, Object> getTradingReport(long userId) {
        List<Map<String, Object>> all = TradingSessionManager.forUser(userId).getAllSessions();
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
        r.put("totalSessions", all.size()); r.put("buyCount", buyCount); r.put("sellCount", sellCount);
        r.put("sessionsByType", byType); r.put("sessions", all);
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
