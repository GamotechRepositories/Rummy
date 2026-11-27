package com.rummy.gameservice.operator;

import com.rummy.gameservice.security.JwtService;
import com.rummy.gameservice.security.PlayerNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles player launch and session binding:
 * <ul>
 *   <li>Operations launch (`?token=&partner_code=&gid=&game_code=&username=&user_id=&currency=&lang=&extsess=&close_url=`):
 *       verifies token + fetches initial authoritative balance via 2051 {@code POST /api/v1/wallet/balance} and
 *       stores the Operations session for subsequent 2052 wallet transfers.</li>
 *   <li>Mock/direct operator launch (`?launch=`): single-use code exchange for local testing and direct B2B operators.</li>
 * </ul>
 */
@Service
public class OperatorLaunchService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Launch(String launchUrl, String playerId, long expiresInSeconds) {}

    public record Session(
            String token,
            String playerId,
            String displayName,
            String operatorId,
            long expiresInSeconds,
            BigDecimal balance,
            String partnerCode,
            String gid,
            String gameCode,
            String currency,
            String lang,
            String closeUrl
    ) {
        public Session(String token, String playerId, String displayName, String operatorId, long expiresInSeconds) {
            this(token, playerId, displayName, operatorId, expiresInSeconds,
                    null, operatorId, null, null, "INR", "en", null);
        }
    }

    public record OperationsLaunchRequest(
            String token,
            String partnerCode,
            String gid,
            String gameCode,
            String username,
            String userId,
            String currency,
            String lang,
            String extsess,
            String closeUrl
    ) {}

    public record OperationsRedeemResult(
            boolean ok,
            Session session,
            String error,
            String errorCode,
            String message,
            String closeUrl
    ) {
        public static OperationsRedeemResult success(Session session) {
            return new OperationsRedeemResult(true, session, null, null, null, session.closeUrl());
        }

        public static OperationsRedeemResult failure(String error, String errorCode, String message, String closeUrl) {
            return new OperationsRedeemResult(false, null, error, errorCode, message, closeUrl);
        }
    }

    private final OperatorRegistry registry;
    private final JwtService jwtService;
    private final MongoTemplate mongo;
    private final String gameUrl;
    private final Map<String, String> gameUrlsByCode;
    private final Duration codeTtl;
    private final Duration sessionTtl;
    private final Map<String, LaunchCodeDocument> memory = new ConcurrentHashMap<>();
    private final Map<String, ProviderSessionDocument> providerSessions = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private OperationsWalletClient operationsClient;

    public OperatorLaunchService(OperatorRegistry registry,
                                 JwtService jwtService,
                                 MongoTemplate mongo,
                                 String gameUrl,
                                 long codeTtlSeconds,
                                 long sessionTtlHours) {
        this(registry, jwtService, mongo, gameUrl, "", codeTtlSeconds, sessionTtlHours);
    }

    @Autowired
    public OperatorLaunchService(OperatorRegistry registry,
                                 JwtService jwtService,
                                 @Autowired(required = false) MongoTemplate mongo,
                                 @Value("${rummy.operator.game-url:http://localhost:5173}") String gameUrl,
                                 @Value("${rummy.operator.game-urls:}") String gameUrlsRaw,
                                 @Value("${rummy.operator.launch-code-ttl-seconds:120}") long codeTtlSeconds,
                                 @Value("${rummy.operator.session-ttl-hours:12}") long sessionTtlHours) {
        this.registry = Objects.requireNonNull(registry);
        this.jwtService = Objects.requireNonNull(jwtService);
        this.mongo = mongo;
        this.gameUrl = normalizeBaseUrl(gameUrl);
        this.gameUrlsByCode = parseGameUrls(gameUrlsRaw);
        this.codeTtl = Duration.ofSeconds(codeTtlSeconds);
        this.sessionTtl = Duration.ofHours(sessionTtlHours);
    }

    @Autowired(required = false)
    public void setOperationsClient(OperationsWalletClient operationsClient) {
        this.operationsClient = operationsClient;
    }

    /** Registers the player if new and returns a one-time launch URL for them. */
    public Launch launch(String operatorId, String externalId, String displayName) {
        return launch(operatorId, externalId, displayName, null);
    }

    /** Registers the player if new and returns a one-time launch URL routed by optional {@code gameCode}. */
    public Launch launch(String operatorId, String externalId, String displayName, String gameCode) {
        OperatorRegistry.PlayerRef player = registry.registerPlayer(operatorId, externalId);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String normalizedGameCode = normalizeGameCode(gameCode);

        LaunchCodeDocument doc = new LaunchCodeDocument();
        doc.setCodeHash(hash(code));
        doc.setPlayerId(player.playerId());
        doc.setOperatorId(operatorId);
        doc.setExternalId(externalId);
        doc.setDisplayName(PlayerNames.sanitize(displayName));
        doc.setGameCode(normalizedGameCode);
        doc.setExpireAt(Instant.now().plus(codeTtl));
        if (mongo == null) {
            memory.put(doc.getCodeHash(), doc);
        } else {
            mongo.insert(doc);
        }
        String targetUrl = resolveGameUrl(normalizedGameCode);
        return new Launch(targetUrl + "/?launch=" + code, player.playerId(), codeTtl.toSeconds());
    }

    /** Uses up a single-use launch code and opens a session; empty if unknown, used, expired, or operator disabled. */
    public Optional<Session> redeem(String code) {
        if (code == null || code.isBlank() || code.length() > 100) {
            return Optional.empty();
        }
        String codeHash = hash(code.trim());
        LaunchCodeDocument doc = mongo == null
                ? memory.remove(codeHash)
                : mongo.findAndRemove(Query.query(Criteria.where("_id").is(codeHash)), LaunchCodeDocument.class);
        if (doc == null || doc.getExpireAt().isBefore(Instant.now())
                || registry.findEnabled(doc.getOperatorId()).isEmpty()) {
            return Optional.empty();
        }
        String token = jwtService.generateToken(
                doc.getPlayerId(), doc.getDisplayName(), doc.getOperatorId(), doc.getGameCode(), sessionTtl);
        return Optional.of(new Session(
                token, doc.getPlayerId(), doc.getDisplayName(), doc.getOperatorId(), sessionTtl.toSeconds(),
                null, doc.getOperatorId(), null, doc.getGameCode(), "INR", "en", null));
    }

    private static final int MAX_TOKEN_LENGTH = 512;
    private static final int MAX_PARTNER_CODE_LENGTH = 64;
    private static final int MAX_ID_LENGTH = 128;
    private static final int MAX_EXTSESS_LENGTH = 256;
    private static final int MAX_CLOSE_URL_LENGTH = 1024;

    /**
     * Validates an Operations launch session by calling 2051 {@code POST /api/v1/wallet/balance}
     * server-to-server, persists the launch context (`partner_code`, `gid`, `user_id`, `close_url`, etc.),
     * and mints a Rummy JWT for the browser client.
     */
    public OperationsRedeemResult redeemOperations(OperationsLaunchRequest req) {
        if (req == null || isBlank(req.token())) {
            return OperationsRedeemResult.failure(
                    "INVALID_LAUNCH", null, "Missing session token from launch URL.", sanitizeCloseUrl(req != null ? req.closeUrl() : null));
        }
        String closeUrl = sanitizeCloseUrl(req.closeUrl());
        if (isBlank(req.partnerCode())) {
            return OperationsRedeemResult.failure(
                    "INVALID_LAUNCH", null, "Missing partner_code from launch URL.", closeUrl);
        }

        String rawToken = req.token().trim();
        String partnerCode = req.partnerCode().trim().toUpperCase(Locale.ROOT);
        if (rawToken.length() > MAX_TOKEN_LENGTH
                || partnerCode.length() > MAX_PARTNER_CODE_LENGTH
                || !partnerCode.matches("^[A-Z0-9_-]{1,64}$")
                || (!isBlank(req.gid()) && req.gid().trim().length() > MAX_PARTNER_CODE_LENGTH)
                || (!isBlank(req.userId()) && req.userId().trim().length() > MAX_ID_LENGTH)
                || (!isBlank(req.username()) && req.username().trim().length() > MAX_ID_LENGTH)
                || (!isBlank(req.extsess()) && req.extsess().trim().length() > MAX_EXTSESS_LENGTH)) {
            return OperationsRedeemResult.failure(
                    "INVALID_LAUNCH", null, "Invalid launch parameters.", closeUrl);
        }
        if (operationsClient == null || !operationsClient.isConfigured()) {
            return OperationsRedeemResult.failure(
                    "OPERATIONS_NOT_CONFIGURED", null,
                    "Operations service is not configured on this game server.", closeUrl);
        }

        String gid = !isBlank(req.gid()) ? req.gid().trim() : null;
        String gameCode = normalizeGameCode(req.gameCode());
        String username = !isBlank(req.username()) ? req.username().trim() : null;
        String userId = !isBlank(req.userId()) ? req.userId().trim() : null;
        String currency = normalizeCurrency(req.currency());
        String lang = normalizeLang(req.lang());
        String extsess = !isBlank(req.extsess()) ? req.extsess().trim() : null;

        OperationsWalletClient.Reply balReply;
        try {
            balReply = operationsClient.getBalance(rawToken, partnerCode);
        } catch (OperatorWalletException e) {
            String err = e.isSessionInvalid() ? "SESSION_INVALID" : "OPERATIONS_UNAVAILABLE";
            return OperationsRedeemResult.failure(err, e.getErrorCode(), e.getMessage(), closeUrl);
        }

        if (!balReply.ok()) {
            String err = balReply.sessionInvalid() ? "SESSION_INVALID" : "INVALID_LAUNCH";
            String msg = balReply.sessionInvalid()
                    ? "Your game session has expired or is invalid. Please return to the lobby and launch again."
                    : (balReply.rawMessage() != null ? balReply.rawMessage() : "Unable to verify session with Operations.");
            return OperationsRedeemResult.failure(err, balReply.errorCode(), msg, closeUrl);
        }

        String externalId = resolveSafeExternalId(partnerCode, userId, username, rawToken);
        OperatorRegistry.PlayerRef player = registry.registerPlayer(partnerCode, externalId);
        String displayName = PlayerNames.sanitize(username != null ? username : userId);

        saveProviderSession(player.playerId(), partnerCode, gid, gameCode, rawToken, username, userId,
                currency, lang, extsess, closeUrl);

        String jwt = jwtService.generateToken(player.playerId(), displayName, partnerCode, gameCode, sessionTtl);
        return OperationsRedeemResult.success(new Session(
                jwt, player.playerId(), displayName, partnerCode, sessionTtl.toSeconds(),
                balReply.balance(), partnerCode, gid, gameCode, currency, lang, closeUrl));
    }

    public void saveProviderSessionToken(String playerId, String operatorId, String gameCode, String sessionToken) {
        saveProviderSession(playerId, operatorId, null, gameCode, sessionToken, null, null,
                "INR", "en", null, null);
    }

    public void saveProviderSession(String playerId,
                                    String partnerCode,
                                    String gid,
                                    String gameCode,
                                    String sessionToken,
                                    String username,
                                    String userId,
                                    String currency,
                                    String lang,
                                    String extsess,
                                    String closeUrl) {
        if (isBlank(playerId) || isBlank(sessionToken)) {
            return;
        }
        Instant now = Instant.now();
        // Retain beyond JWT TTL so late wins / refunds (4.3) and outbox retries always succeed.
        Instant expireAt = now.plus(sessionTtl).plus(Duration.ofDays(30));

        ProviderSessionDocument doc = new ProviderSessionDocument();
        doc.setPlayerId(playerId);
        doc.setOperatorId(partnerCode);
        doc.setPartnerCode(partnerCode);
        doc.setGid(gid);
        doc.setGameCode(gameCode);
        doc.setSessionToken(sessionToken.trim());
        doc.setUsername(username);
        doc.setUserId(userId);
        doc.setCurrency(currency != null ? currency : "INR");
        doc.setLang(lang != null ? lang : "en");
        doc.setExtsess(extsess);
        doc.setCloseUrl(closeUrl);
        doc.setUpdatedAt(now);
        doc.setExpireAt(expireAt);

        providerSessions.put(playerId, doc);
        if (mongo != null) {
            Update update = new Update()
                    .set("operatorId", partnerCode)
                    .set("partnerCode", partnerCode)
                    .set("gid", gid)
                    .set("gameCode", gameCode)
                    .set("sessionToken", sessionToken.trim())
                    .set("username", username)
                    .set("userId", userId)
                    .set("currency", doc.getCurrency())
                    .set("lang", doc.getLang())
                    .set("extsess", extsess)
                    .set("closeUrl", closeUrl)
                    .set("updatedAt", now)
                    .set("expireAt", expireAt);
            mongo.upsert(Query.query(Criteria.where("_id").is(playerId)), update, ProviderSessionDocument.class);
        }
    }

    public Optional<ProviderSessionDocument> getProviderSession(String playerId) {
        if (isBlank(playerId)) {
            return Optional.empty();
        }
        ProviderSessionDocument cached = providerSessions.get(playerId);
        if (cached != null && !isBlank(cached.getSessionToken())
                && (cached.getExpireAt() == null || !cached.getExpireAt().isBefore(Instant.now()))) {
            return Optional.of(cached);
        }
        if (mongo != null) {
            ProviderSessionDocument doc = mongo.findById(playerId, ProviderSessionDocument.class);
            if (doc != null && !isBlank(doc.getSessionToken())
                    && (doc.getExpireAt() == null || !doc.getExpireAt().isBefore(Instant.now()))) {
                providerSessions.put(playerId, doc);
                return Optional.of(doc);
            }
        }
        return Optional.empty();
    }

    public Optional<String> getSessionTokenForPlayer(String playerId) {
        return getProviderSession(playerId).map(ProviderSessionDocument::getSessionToken);
    }

    private String resolveGameUrl(String normalizedGameCode) {
        if (normalizedGameCode != null) {
            String mapped = gameUrlsByCode.get(normalizedGameCode);
            if (mapped != null) {
                return mapped;
            }
        }
        return gameUrl;
    }

    private static String resolveSafeExternalId(String partnerCode, String userId, String username, String token) {
        if (!isBlank(userId) && OperatorRegistry.EXTERNAL_ID.matcher(userId).matches()) {
            return userId;
        }
        String basis = !isBlank(userId) ? userId : (!isBlank(username) ? username : token);
        return "U_" + hash(partnerCode + ":" + basis).substring(0, 24);
    }

    static String sanitizeCloseUrl(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_CLOSE_URL_LENGTH || trimmed.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
            if (("https".equals(scheme) || "http".equals(scheme)) && uri.getHost() != null) {
                return trimmed;
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private static String normalizeCurrency(String raw) {
        if (isBlank(raw)) {
            return "INR";
        }
        String cleaned = raw.trim().toUpperCase(Locale.ROOT);
        return cleaned.length() == 3 ? cleaned : "INR";
    }

    private static String normalizeLang(String raw) {
        if (isBlank(raw)) {
            return "en";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeGameCode(String gameCode) {
        if (isBlank(gameCode)) {
            return null;
        }
        return gameCode.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeBaseUrl(String raw) {
        if (isBlank(raw)) {
            return "http://localhost:5173";
        }
        String trimmed = raw.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static Map<String, String> parseGameUrls(String raw) {
        if (isBlank(raw)) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String entry : raw.split("[;,]")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq >= trimmed.length() - 1) continue;
            String key = normalizeGameCode(trimmed.substring(0, eq));
            String url = normalizeBaseUrl(trimmed.substring(eq + 1));
            if (key != null) {
                map.put(key, url);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
