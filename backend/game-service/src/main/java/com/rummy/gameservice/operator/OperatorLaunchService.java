package com.rummy.gameservice.operator;

import com.rummy.gameservice.security.JwtService;
import com.rummy.gameservice.security.PlayerNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
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
    private final Duration codeTtl;
    private final Duration sessionTtl;
    private final Map<String, LaunchCodeDocument> memory = new ConcurrentHashMap<>();

    @Autowired
    public OperatorLaunchService(OperatorRegistry registry,
                                 JwtService jwtService,
                                 @Autowired(required = false) MongoTemplate mongo,
                                 @Value("${rummy.operator.game-url:http://localhost:5173}") String gameUrl,
                                 @Value("${rummy.operator.launch-code-ttl-seconds:120}") long codeTtlSeconds,
                                 @Value("${rummy.operator.session-ttl-hours:12}") long sessionTtlHours) {
        this.registry = Objects.requireNonNull(registry);
        this.jwtService = Objects.requireNonNull(jwtService);
        this.mongo = mongo;
        this.gameUrl = gameUrl.endsWith("/") ? gameUrl.substring(0, gameUrl.length() - 1) : gameUrl;
        this.codeTtl = Duration.ofSeconds(codeTtlSeconds);
        this.sessionTtl = Duration.ofHours(sessionTtlHours);
    }

    /** Registers the player if new and returns a one-time launch URL for them. */
    public Launch launch(String operatorId, String externalId, String displayName) {
        OperatorRegistry.PlayerRef player = registry.registerPlayer(operatorId, externalId);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        LaunchCodeDocument doc = new LaunchCodeDocument();
        doc.setCodeHash(hash(code));
        doc.setPlayerId(player.playerId());
        doc.setOperatorId(operatorId);
        doc.setExternalId(externalId);
        doc.setDisplayName(PlayerNames.sanitize(displayName));
        doc.setExpireAt(Instant.now().plus(codeTtl));
        if (mongo == null) {
            memory.put(doc.getCodeHash(), doc);
        } else {
            mongo.insert(doc);
        }
        return new Launch(gameUrl + "/?launch=" + code, player.playerId(), codeTtl.toSeconds());
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
            return Optional.empty();
        }
        String token = jwtService.generateToken(doc.getPlayerId(), doc.getDisplayName(), doc.getOperatorId(), sessionTtl);
        return Optional.of(new Session(token, doc.getPlayerId(), doc.getDisplayName(), doc.getOperatorId(), sessionTtl.toSeconds()));
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
