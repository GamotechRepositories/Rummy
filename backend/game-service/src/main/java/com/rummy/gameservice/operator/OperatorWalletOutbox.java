package com.rummy.gameservice.operator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import com.google.gson.JsonObject;

/**
 * Delivers operator wallet calls that must not be lost: credits (winnings, refunds) that failed, and
 * rollbacks of debits whose outcome is unknown. Each entry is retried with backoff until the operator
 * accepts it; every node works the queue, a lease stops two nodes sending the same entry at once.
 * Retries reuse the original transaction id, so the operator applies each call once.
 */
@Service
public class OperatorWalletOutbox {

    private static final Logger log = LoggerFactory.getLogger(OperatorWalletOutbox.class);

    public enum Kind { CREDIT, ROLLBACK, PROVIDER_CREDIT }

    private static final String PENDING = "PENDING";
    private static final String DONE = "DONE";
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final int ALERT_AFTER_ATTEMPTS = 5;

    private final MongoTemplate mongo;
    private final OperatorWalletClient client;
    private final Map<String, OperatorOutboxDocument> memory = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, BigDecimal> balanceListener = (playerId, balance) -> { };
    private OrengProviderService orengProvider;
    private OperatorLaunchService operatorLaunches;

    @Autowired
    public OperatorWalletOutbox(@Autowired(required = false) MongoTemplate mongo, OperatorWalletClient client) {
        this.mongo = mongo;
        this.client = Objects.requireNonNull(client);
    }

    /** Package-private constructor for unit tests exercising provider-only outbox paths without HTTP client creation. */
    OperatorWalletOutbox(MongoTemplate mongo) {
        this.mongo = mongo;
        this.client = null;
    }

    @Autowired(required = false)
    public void setOrengProvider(OrengProviderService orengProvider) {
        this.orengProvider = orengProvider;
    }

    @Autowired(required = false)
    public void setOperatorLaunches(OperatorLaunchService operatorLaunches) {
        this.operatorLaunches = operatorLaunches;
    }

    /** Told the player's new operator balance after a queued credit lands. */
    public void setBalanceListener(BiConsumer<String, BigDecimal> listener) {
        this.balanceListener = listener != null ? listener : (playerId, balance) -> { };
    }

    /** Queues a call; a second enqueue of the same call is ignored. */
    public void enqueue(Kind kind, OperatorRegistry.PlayerRef player, String transactionId, BigDecimal amount,
                        String gameId, String type, String description, String reason) {
        insertEntry(kind, player, null, transactionId, amount, gameId, type, description, reason);
    }

    /** Queues a failed Oreng/provider wallet credit with its originating {@code sessionToken} for retry. */
    public void enqueueProviderCredit(OperatorRegistry.PlayerRef player, String providerSessionToken,
                                      String transactionId, BigDecimal amount, String gameId,
                                      String type, String description, String reason) {
        insertEntry(Kind.PROVIDER_CREDIT, player, providerSessionToken, transactionId, amount, gameId, type, description, reason);
    }

    private void insertEntry(Kind kind, OperatorRegistry.PlayerRef player, String providerSessionToken,
                             String transactionId, BigDecimal amount, String gameId, String type,
                             String description, String reason) {
        Instant now = Instant.now();
        OperatorOutboxDocument doc = new OperatorOutboxDocument();
        doc.setId(kind.name() + ":" + transactionId);
        doc.setKind(kind.name());
        doc.setTransactionId(transactionId);
        doc.setPlayerId(player.playerId());
        doc.setOperatorId(player.operatorId());
        doc.setExternalId(player.externalId());
        doc.setAmount(amount);
        doc.setGameId(gameId);
        doc.setType(type);
        doc.setDescription(description);
        doc.setProviderSessionToken(providerSessionToken);
        doc.setStatus(PENDING);
        doc.setNextAttemptAt(now.plusSeconds(2));
        doc.setLastError(reason);
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        if (mongo == null) {
            memory.putIfAbsent(doc.getId(), doc);
        } else {
            try {
                mongo.insert(doc);
            } catch (DuplicateKeyException ignored) {
                // Already queued.
            }
        }
        log.warn("[OperatorWallet] Queued {} {} for {} ({} {}): {}", kind, transactionId, player.operatorId(),
                amount, OperatorWalletClient.CURRENCY, reason);
    }

    public boolean isPending(Kind kind, String transactionId) {
        String id = kind.name() + ":" + transactionId;
        OperatorOutboxDocument doc = mongo == null ? memory.get(id) : mongo.findById(id, OperatorOutboxDocument.class);
        return doc != null && PENDING.equals(doc.getStatus());
    }

    @Scheduled(fixedDelayString = "${rummy.operator.outbox-interval-ms:5000}",
            initialDelayString = "${rummy.operator.outbox-interval-ms:5000}")
    public int deliverDue() {
        return deliverDue(Instant.now());
    }

    int deliverDue(Instant now) {
        int delivered = 0;
        try {
            for (OperatorOutboxDocument doc : leaseDue(now, 100)) {
                if (deliver(doc)) {
                    delivered++;
                }
            }
        } catch (Exception e) {
            log.error("[OperatorWallet] Outbox scan failed: {}", e.getMessage(), e);
        }
        return delivered;
    }

    private boolean deliver(OperatorOutboxDocument doc) {
        if (Kind.PROVIDER_CREDIT.name().equals(doc.getKind())) {
            return deliverProviderCredit(doc);
        }
        if (client == null) {
            handleFailure(doc, "OperatorWalletClient not configured");
            return false;
        }
        OperatorRegistry.PlayerRef player = new OperatorRegistry.PlayerRef(doc.getPlayerId(), doc.getOperatorId(), doc.getExternalId());
        String error;
        try {
            OperatorWalletClient.Reply reply = Kind.ROLLBACK.name().equals(doc.getKind())
                    ? client.rollback(player, doc.getTransactionId(), doc.getAmount(), doc.getGameId())
                    : client.credit(player, doc.getTransactionId(), doc.getAmount(), doc.getGameId(), doc.getType(), doc.getDescription());
            if (reply.ok()) {
                markDone(doc);
                if (reply.balance() != null) {
                    balanceListener.accept(doc.getPlayerId(), reply.balance());
                }
                log.info("[OperatorWallet] Delivered queued {} {} to {}", doc.getKind(), doc.getTransactionId(), doc.getOperatorId());
                return true;
            }
            error = "operator answered " + reply.status();
        } catch (Exception e) {
            error = e.getMessage();
        }
        handleFailure(doc, error);
        return false;
    }

    private boolean deliverProviderCredit(OperatorOutboxDocument doc) {
        String error;
        if (orengProvider == null || !orengProvider.isConfigured()) {
            error = "Oreng provider SDK not configured";
        } else {
            String token = doc.getProviderSessionToken();
            if ((token == null || token.isBlank()) && operatorLaunches != null) {
                token = operatorLaunches.getSessionTokenForPlayer(doc.getPlayerId()).orElse(null);
            }
            if (token == null || token.isBlank()) {
                error = "Missing provider sessionToken for player " + doc.getPlayerId();
            } else {
                try {
                    Optional<JsonObject> resp = orengProvider.credit(
                            token, doc.getAmount(), doc.getTransactionId(), doc.getGameId(), "");
                    markDone(doc);
                    resp.flatMap(OrengProviderService::extractBalance)
                            .ifPresent(bal -> balanceListener.accept(doc.getPlayerId(), bal));
                    log.info("[OperatorWallet] Delivered queued PROVIDER_CREDIT {} to {}",
                            doc.getTransactionId(), doc.getOperatorId());
                    return true;
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
        }
        handleFailure(doc, error);
        return false;
    }

    private void handleFailure(OperatorOutboxDocument doc, String error) {
        int attempts = doc.getAttempts();
        long backoff = Math.min(MAX_BACKOFF.toSeconds(), 5L << Math.min(attempts, 10));
        reschedule(doc, Instant.now().plusSeconds(backoff), error);
        if (attempts >= ALERT_AFTER_ATTEMPTS) {
            log.error("[OperatorWallet] RECONCILE REQUIRED: {} {} of {} {} to {} player {} still failing after {} attempts: {}",
                    doc.getKind(), doc.getTransactionId(), doc.getAmount(), OperatorWalletClient.CURRENCY,
                    doc.getOperatorId(), doc.getExternalId(), attempts, error);
        }
    }

    /** Due entries, each leased to this node so no other node sends it meanwhile. */
    private List<OperatorOutboxDocument> leaseDue(Instant now, int limit) {
        List<OperatorOutboxDocument> leased = new ArrayList<>();
        if (mongo == null) {
            memory.values().stream()
                    .filter(d -> PENDING.equals(d.getStatus()) && !d.getNextAttemptAt().isAfter(now))
                    .sorted(Comparator.comparing(OperatorOutboxDocument::getNextAttemptAt))
                    .limit(limit)
                    .forEach(d -> {
                        d.setAttempts(d.getAttempts() + 1);
                        d.setNextAttemptAt(now.plus(LEASE));
                        leased.add(d);
                    });
            return leased;
        }
        for (int i = 0; i < limit; i++) {
            OperatorOutboxDocument doc = mongo.findAndModify(
                    Query.query(Criteria.where("status").is(PENDING).and("nextAttemptAt").lte(now))
                            .with(Sort.by("nextAttemptAt")),
                    new Update().inc("attempts", 1).set("nextAttemptAt", now.plus(LEASE)).set("updatedAt", now),
                    FindAndModifyOptions.options().returnNew(true),
                    OperatorOutboxDocument.class);
            if (doc == null) {
                break;
            }
            leased.add(doc);
        }
        return leased;
    }

    private void markDone(OperatorOutboxDocument doc) {
        Instant now = Instant.now();
        if (mongo == null) {
            doc.setStatus(DONE);
            doc.setUpdatedAt(now);
            return;
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(doc.getId())),
                new Update().set("status", DONE).set("updatedAt", now).set("expireAt", now.plus(RETENTION)).unset("lastError"),
                OperatorOutboxDocument.class);
    }

    private void reschedule(OperatorOutboxDocument doc, Instant next, String error) {
        if (mongo == null) {
            doc.setNextAttemptAt(next);
            doc.setLastError(error);
            return;
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(doc.getId()).and("status").is(PENDING)),
                new Update().set("nextAttemptAt", next).set("lastError", error).set("updatedAt", Instant.now()),
                OperatorOutboxDocument.class);
    }
}
