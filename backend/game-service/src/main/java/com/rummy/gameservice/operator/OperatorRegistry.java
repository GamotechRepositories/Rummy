package com.rummy.gameservice.operator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Operators (partner platforms) and the players they launch. Without MongoDB (tests) everything is
 * kept in memory.
 */
@Service
public class OperatorRegistry {

    public static final Pattern OPERATOR_ID = Pattern.compile("[a-z0-9][a-z0-9-]{1,31}");
    public static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9_.@:-]{1,64}");
    private static final String PLAYER_PREFIX = "P_";
    private static final Duration OPERATOR_CACHE_TTL = Duration.ofSeconds(30);
    private static final int MAX_CACHED_PLAYERS = 200_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record PlayerRef(String playerId, String operatorId, String externalId) {
    }

    private record CachedOperator(OperatorDocument operator, Instant loadedAt) {
    }

    private final MongoTemplate mongo;
    private final Map<String, OperatorDocument> memoryOperators = new ConcurrentHashMap<>();
    private final Map<String, PlayerRef> memoryPlayers = new ConcurrentHashMap<>();
    private final Map<String, CachedOperator> operatorCache = new ConcurrentHashMap<>();
    /** Player mappings never change, so they can be cached for the life of the process. */
    private final Map<String, PlayerRef> playerCache = new ConcurrentHashMap<>();

    @Autowired
    public OperatorRegistry(@Autowired(required = false) MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public static OperatorRegistry inMemory() {
        return new OperatorRegistry(null);
    }

    public Optional<OperatorDocument> find(String operatorId) {
        if (operatorId == null) {
            return Optional.empty();
        }
        CachedOperator cached = operatorCache.get(operatorId);
        if (cached != null && cached.loadedAt().plus(OPERATOR_CACHE_TTL).isAfter(Instant.now())) {
            return Optional.ofNullable(cached.operator());
        }
        OperatorDocument doc = mongo == null ? memoryOperators.get(operatorId) : mongo.findById(operatorId, OperatorDocument.class);
        operatorCache.put(operatorId, new CachedOperator(doc, Instant.now()));
        return Optional.ofNullable(doc);
    }

    /** An enabled operator, or empty. */
    public Optional<OperatorDocument> findEnabled(String operatorId) {
        return find(operatorId).filter(OperatorDocument::isEnabled);
    }

    public List<OperatorDocument> list() {
        List<OperatorDocument> all = mongo == null ? new ArrayList<>(memoryOperators.values()) : mongo.findAll(OperatorDocument.class);
        all.sort(Comparator.comparing(OperatorDocument::getId));
        return all;
    }

    /** Creates an operator with a fresh secret (returned on the document; shown to the caller once). */
    public OperatorDocument create(String operatorId, String name, String walletUrl, String cashierUrl) {
        if (operatorId == null || !OPERATOR_ID.matcher(operatorId).matches()) {
            throw new IllegalArgumentException("operator id must be 2-32 chars: lowercase letters, digits, '-'");
        }
        OperatorDocument doc = new OperatorDocument();
        doc.setId(operatorId);
        doc.setName(name != null && !name.isBlank() ? name.trim() : operatorId);
        doc.setWalletUrl(requireUrl(walletUrl, "walletUrl"));
        doc.setCashierUrl(optionalUrl(cashierUrl, "cashierUrl"));
        doc.setSecret(newSecret());
        doc.setEnabled(true);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(doc.getCreatedAt());
        if (mongo == null) {
            if (memoryOperators.putIfAbsent(operatorId, doc) != null) {
                throw new IllegalArgumentException("operator " + operatorId + " already exists");
            }
        } else {
            try {
                mongo.insert(doc);
            } catch (DuplicateKeyException e) {
                throw new IllegalArgumentException("operator " + operatorId + " already exists");
            }
        }
        operatorCache.remove(operatorId);
        return doc;
    }

    /** Changes the given fields (null = unchanged). */
    public Optional<OperatorDocument> update(String operatorId, String name, String walletUrl, String cashierUrl, Boolean enabled) {
        String newName = name != null && !name.isBlank() ? name.trim() : null;
        String newWalletUrl = walletUrl != null ? requireUrl(walletUrl, "walletUrl") : null;
        String newCashierUrl = cashierUrl != null ? optionalUrl(cashierUrl, "cashierUrl") : null;
        Update update = new Update().set("updatedAt", Instant.now());
        if (newName != null) update.set("name", newName);
        if (newWalletUrl != null) update.set("walletUrl", newWalletUrl);
        if (cashierUrl != null) update.set("cashierUrl", newCashierUrl);
        if (enabled != null) update.set("enabled", enabled);
        return modify(operatorId, update, doc -> {
            if (newName != null) doc.setName(newName);
            if (newWalletUrl != null) doc.setWalletUrl(newWalletUrl);
            if (cashierUrl != null) doc.setCashierUrl(newCashierUrl);
            if (enabled != null) doc.setEnabled(enabled);
        });
    }

    /** Issues a new secret; the old one stops working at once. */
    public Optional<OperatorDocument> rotateSecret(String operatorId) {
        String secret = newSecret();
        return modify(operatorId, new Update().set("secret", secret).set("updatedAt", Instant.now()),
                doc -> doc.setSecret(secret));
    }

    /** Creates or replaces an operator as given (local development operator only). */
    public void save(OperatorDocument doc) {
        if (mongo == null) {
            memoryOperators.put(doc.getId(), doc);
        } else {
            mongo.save(doc);
        }
        operatorCache.remove(doc.getId());
    }

    private Optional<OperatorDocument> modify(String operatorId, Update update, java.util.function.Consumer<OperatorDocument> inMemory) {
        operatorCache.remove(operatorId);
        if (mongo == null) {
            OperatorDocument doc = memoryOperators.get(operatorId);
            if (doc == null) {
                return Optional.empty();
            }
            inMemory.accept(doc);
            doc.setUpdatedAt(Instant.now());
            return Optional.of(doc);
        }
        return Optional.ofNullable(mongo.findAndModify(Query.query(Criteria.where("_id").is(operatorId)), update,
                FindAndModifyOptions.options().returnNew(true), OperatorDocument.class));
    }

    /** Our player id for an operator's player: stable, and reveals neither the operator nor their id. */
    public static String playerIdFor(String operatorId, String externalId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((operatorId + ":" + externalId).getBytes(StandardCharsets.UTF_8));
            return PLAYER_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 22);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Records that the operator launched this player; returns the player's mapping. */
    public PlayerRef registerPlayer(String operatorId, String externalId) {
        if (externalId == null || !EXTERNAL_ID.matcher(externalId).matches()) {
            throw new IllegalArgumentException("playerId must be 1-64 chars: letters, digits, '_', '.', '@', ':', '-'");
        }
        PlayerRef ref = new PlayerRef(playerIdFor(operatorId, externalId), operatorId, externalId);
        if (mongo == null) {
            memoryPlayers.putIfAbsent(ref.playerId(), ref);
        } else {
            mongo.upsert(Query.query(Criteria.where("_id").is(ref.playerId())),
                    new Update().setOnInsert("operatorId", operatorId).setOnInsert("externalId", externalId)
                            .setOnInsert("createdAt", Instant.now()).set("lastLaunchAt", Instant.now()),
                    OperatorPlayerDocument.class);
        }
        cachePlayer(ref);
        return ref;
    }

    /** The operator behind one of our player ids; empty for bots, the treasury and non-operator players. */
    public Optional<PlayerRef> player(String playerId) {
        if (playerId == null || !playerId.startsWith(PLAYER_PREFIX)) {
            return Optional.empty();
        }
        PlayerRef cached = playerCache.get(playerId);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (mongo == null) {
            return Optional.ofNullable(memoryPlayers.get(playerId));
        }
        OperatorPlayerDocument doc = mongo.findById(playerId, OperatorPlayerDocument.class);
        if (doc == null) {
            return Optional.empty();
        }
        PlayerRef ref = new PlayerRef(doc.getPlayerId(), doc.getOperatorId(), doc.getExternalId());
        cachePlayer(ref);
        return Optional.of(ref);
    }

    private void cachePlayer(PlayerRef ref) {
        if (playerCache.size() >= MAX_CACHED_PLAYERS) {
            playerCache.clear();
        }
        playerCache.put(ref.playerId(), ref);
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String requireUrl(String url, String field) {
        String value = optionalUrl(url, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String optionalUrl(String url, String field) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        try {
            URI uri = URI.create(trimmed);
            if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException(field + " must be an http(s) URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " must be an http(s) URL");
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
