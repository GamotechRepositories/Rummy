package com.rummy.gameservice.operator;

import com.google.gson.JsonObject;
import com.rummy.gameservice.security.JwtService;
import com.rummy.gameservice.security.PlayerNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

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
 * Operator launch: the operator asks for a launch URL for one of its players, the player's browser opens
 * it, and the game exchanges the one-time code in it for a session token. Codes are short-lived and
 * single use, and the operator's own player id never reaches the browser.
 */
@Service
public class OperatorLaunchService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Launch(String launchUrl, String playerId, long expiresInSeconds) {
    }

    public record Session(String token, String playerId, String displayName, String operatorId, long expiresInSeconds) {
    }

    private final OperatorRegistry registry;
    private final JwtService jwtService;
    private final MongoTemplate mongo;
    private final String gameUrl;
    private final Map<String, String> gameUrlsByCode;
    private final Duration codeTtl;
    private final Duration sessionTtl;
    private final Map<String, LaunchCodeDocument> memory = new ConcurrentHashMap<>();
    private final Map<String, String> playerSessionTokens = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private OrengProviderService orengProvider;

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

    /** Uses up a launch code and opens a session; empty if the code is unknown, used, expired or the operator is disabled. */
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
            if (orengProvider != null && orengProvider.isConfigured()) {
                Optional<JsonObject> profileOpt = orengProvider.getPlayerProfile(code.trim());
                if (profileOpt.isPresent()) {
                    JsonObject profile = profileOpt.get();
                    String extId = profile.has("playerId") ? profile.get("playerId").getAsString() :
                            (profile.has("id") ? profile.get("id").getAsString() : "P_" + Math.abs(code.hashCode()));
                    String name = profile.has("playerUsername") ? profile.get("playerUsername").getAsString() :
                            (profile.has("username") ? profile.get("username").getAsString() : "Player_" + extId);
                    String opId = profile.has("operatorId") ? profile.get("operatorId").getAsString() : "oreng";
                    String gameCode = profile.has("gameCode") && !profile.get("gameCode").isJsonNull()
                            ? normalizeGameCode(profile.get("gameCode").getAsString())
                            : null;

                    OperatorRegistry.PlayerRef player = registry.registerPlayer(opId, extId);
                    String rawSessionToken = code.trim();
                    String token = jwtService.generateToken(player.playerId(), PlayerNames.sanitize(name), opId, gameCode, sessionTtl);
                    saveProviderSessionToken(player.playerId(), opId, gameCode, rawSessionToken);
                    return Optional.of(new Session(token, player.playerId(), name, opId, sessionTtl.toSeconds()));
                }
            }
            return Optional.empty();
        }
        String token = jwtService.generateToken(doc.getPlayerId(), doc.getDisplayName(), doc.getOperatorId(), doc.getGameCode(), sessionTtl);
        return Optional.of(new Session(token, doc.getPlayerId(), doc.getDisplayName(), doc.getOperatorId(), sessionTtl.toSeconds()));
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

    private static String normalizeGameCode(String gameCode) {
        if (gameCode == null || gameCode.isBlank()) {
            return null;
        }
        return gameCode.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "http://localhost:5173";
        }
        String trimmed = raw.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static Map<String, String> parseGameUrls(String raw) {
        if (raw == null || raw.isBlank()) {
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

    public void saveProviderSessionToken(String playerId, String operatorId, String gameCode, String sessionToken) {
        if (playerId == null || playerId.isBlank() || sessionToken == null || sessionToken.isBlank()) {
            return;
        }
        String trimmed = sessionToken.trim();
        playerSessionTokens.put(playerId, trimmed);
        if (mongo != null) {
            Instant now = Instant.now();
            // Keep provider session tokens well beyond JWT session TTL so pending outbox credits can finish.
            Instant expireAt = now.plus(sessionTtl).plus(Duration.ofDays(30));
            Update update = new Update()
                    .set("operatorId", operatorId)
                    .set("gameCode", gameCode)
                    .set("sessionToken", trimmed)
                    .set("updatedAt", now)
                    .set("expireAt", expireAt);
            mongo.upsert(Query.query(Criteria.where("_id").is(playerId)), update, ProviderSessionDocument.class);
        }
    }

    public Optional<String> getSessionTokenForPlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return Optional.empty();
        }
        String cached = playerSessionTokens.get(playerId);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (mongo != null) {
            ProviderSessionDocument doc = mongo.findById(playerId, ProviderSessionDocument.class);
            if (doc != null && doc.getSessionToken() != null && !doc.getSessionToken().isBlank()
                    && (doc.getExpireAt() == null || !doc.getExpireAt().isBefore(Instant.now()))) {
                playerSessionTokens.put(playerId, doc.getSessionToken());
                return Optional.of(doc.getSessionToken());
            }
        }
        return Optional.empty();
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
