package com.rummy.gameservice.wallet;

import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.persistence.document.SettlementJobDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable queue of payouts ({@link SettlementJobDocument}). Without MongoDB the queue lives in memory
 * (single process, no crash survival).
 */
@Component
public class SettlementJobStore {

    public enum Status { PENDING, RETRY, DONE, CANCELLED }

    private static final Duration RETENTION = Duration.ofDays(7);
    private static final List<String> OPEN = List.of(Status.PENDING.name(), Status.RETRY.name());

    private final MongoTemplate mongo;
    private final ClusterNodeService cluster;
    private final Map<String, SettlementJobDocument> memory = new ConcurrentHashMap<>();

    @Autowired
    public SettlementJobStore(@Autowired(required = false) MongoTemplate mongo,
                              @Autowired(required = false) ClusterNodeService cluster) {
        this.mongo = mongo;
        this.cluster = cluster;
    }

    /** In-memory store (tests, no MongoDB). */
    static SettlementJobStore inMemory() {
        return new SettlementJobStore(null, null);
    }

    String nodeId() {
        return cluster != null ? cluster.nodeId() : "local";
    }

    /** Records a new payout, first attempt due after {@code firstRetryAfter}. A retried insert is ignored. */
    void insert(String gameId, String payload, Duration firstRetryAfter) {
        Instant now = Instant.now();
        SettlementJobDocument doc = new SettlementJobDocument();
        doc.setGameId(gameId);
        doc.setStatus(Status.PENDING.name());
        doc.setHolderNode(nodeId());
        doc.setNextAttemptAt(now.plus(firstRetryAfter));
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        doc.setPayload(payload);
        if (mongo == null) {
            memory.putIfAbsent(gameId, doc);
            return;
        }
        try {
            mongo.insert(doc);
        } catch (DuplicateKeyException ignored) {
            // Same match submitted twice; the first record stands.
        }
    }

    void markFinal(String gameId, Status status) {
        Instant now = Instant.now();
        if (mongo == null) {
            memory.computeIfPresent(gameId, (k, d) -> {
                d.setStatus(status.name());
                d.setUpdatedAt(now);
                return d;
            });
            return;
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(gameId)),
                new Update().set("status", status.name()).set("updatedAt", now).set("expireAt", now.plus(RETENTION)),
                SettlementJobDocument.class);
    }

    void markRetry(String gameId, int attempts, Instant nextAttemptAt) {
        if (mongo == null) {
            memory.computeIfPresent(gameId, (k, d) -> {
                d.setStatus(Status.RETRY.name());
                d.setAttempts(attempts);
                d.setNextAttemptAt(nextAttemptAt);
                return d;
            });
            return;
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(gameId).and("status").in(OPEN)),
                new Update().set("status", Status.RETRY.name()).set("attempts", attempts)
                        .set("nextAttemptAt", nextAttemptAt).set("updatedAt", Instant.now()),
                SettlementJobDocument.class);
    }

    /** Unfinished payouts whose next attempt is due, oldest first. */
    List<SettlementJobDocument> due(int limit) {
        Instant now = Instant.now();
        if (mongo == null) {
            return memory.values().stream()
                    .filter(d -> OPEN.contains(d.getStatus()) && !d.getNextAttemptAt().isAfter(now))
                    .sorted(Comparator.comparing(SettlementJobDocument::getNextAttemptAt))
                    .limit(limit)
                    .toList();
        }
        Query q = Query.query(Criteria.where("status").in(OPEN).and("nextAttemptAt").lte(now))
                .with(Sort.by("nextAttemptAt")).limit(limit);
        return mongo.find(q, SettlementJobDocument.class);
    }

    /** Takes a job over from a dead holder. Exactly one claimant succeeds. */
    boolean claim(String gameId, String deadHolder) {
        if (mongo == null) {
            return true;
        }
        Query q = Query.query(Criteria.where("_id").is(gameId).and("holderNode").is(deadHolder).and("status").in(OPEN));
        return mongo.updateFirst(q, new Update().set("holderNode", nodeId()).set("updatedAt", Instant.now()),
                SettlementJobDocument.class).getModifiedCount() == 1;
    }

    /** Of {@code gameIds}, those still waiting to be paid out (their stakes must not be refunded). */
    public Set<String> pendingGameIds(Collection<String> gameIds) {
        if (gameIds.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        if (mongo == null) {
            for (String id : gameIds) {
                SettlementJobDocument d = memory.get(id);
                if (d != null && OPEN.contains(d.getStatus())) {
                    ids.add(id);
                }
            }
            return ids;
        }
        Query q = Query.query(Criteria.where("_id").in(gameIds).and("status").in(OPEN));
        q.fields().include("_id");
        for (SettlementJobDocument d : mongo.find(q, SettlementJobDocument.class)) {
            ids.add(d.getGameId());
        }
        return ids;
    }

    public java.util.Optional<SettlementJobDocument> find(String gameId) {
        return java.util.Optional.ofNullable(mongo == null ? memory.get(gameId) : mongo.findById(gameId, SettlementJobDocument.class));
    }
}
