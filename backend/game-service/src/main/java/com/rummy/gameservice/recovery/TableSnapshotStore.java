package com.rummy.gameservice.recovery;

import com.mongodb.bulk.BulkWriteError;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.persistence.document.TableSnapshotDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.BulkOperationException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MongoDB storage for {@link TableSnapshotDocument}s. Every write is conditional on this node still
 * owning the record, so a node that was presumed dead cannot overwrite a match another node resumed.
 * Without MongoDB the store is disabled and crashed matches are refunded instead of restored.
 */
@Component
public class TableSnapshotStore {

    private static final int DUPLICATE_KEY = 11000;
    /** Kept a while past the restore window so late reconnects learn the game is gone; then MongoDB deletes it. */
    private static final Duration RETENTION = Duration.ofHours(6);

    private final MongoTemplate mongo;
    private final ClusterNodeService cluster;
    private final Duration restoreWindow;

    @Autowired
    public TableSnapshotStore(@Autowired(required = false) MongoTemplate mongo,
                              ClusterNodeService cluster,
                              @Value("${rummy.recovery.enabled:true}") boolean enabled,
                              @Value("${rummy.recovery.restore-window-seconds:300}") long restoreWindowSeconds) {
        this.mongo = enabled ? mongo : null;
        this.cluster = cluster;
        this.restoreWindow = Duration.ofSeconds(restoreWindowSeconds);
    }

    public record Entry(String tableId, String gameId, List<String> humans, String payload) {
    }

    public boolean enabled() {
        return mongo != null;
    }

    /** True while a crashed match may still be resumed; afterwards its stakes are refunded instead. */
    public boolean withinRestoreWindow(TableSnapshotDocument doc) {
        return doc.getSavedAt() != null && doc.getSavedAt().isAfter(Instant.now().minus(restoreWindow));
    }

    /** Writes snapshots owned by this node. Returns the tables another node has taken over. */
    public Set<String> saveAll(List<Entry> entries) {
        return write(entries, false);
    }

    /** Writes final snapshots of matches this node stopped for a deploy, releasing them to any node. */
    public Set<String> handOff(List<Entry> entries) {
        return write(entries, true);
    }

    private Set<String> write(List<Entry> entries, boolean handedOff) {
        if (mongo == null || entries.isEmpty()) {
            return Set.of();
        }
        Instant now = Instant.now();
        BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, TableSnapshotDocument.class);
        for (Entry e : entries) {
            bulk.upsert(ownedBySelf(e.tableId()), new Update()
                    .set("gameId", e.gameId())
                    .set("humans", e.humans())
                    .set("savedAt", now)
                    .set("expireAt", now.plus(RETENTION))
                    .set("payload", e.payload())
                    .set("handedOff", handedOff));
        }
        try {
            bulk.execute();
            return Set.of();
        } catch (BulkOperationException ex) {
            Set<String> lost = new HashSet<>();
            for (BulkWriteError error : ex.getErrors()) {
                if (error.getCode() != DUPLICATE_KEY) {
                    throw ex;
                }
                lost.add(entries.get(error.getIndex()).tableId());
            }
            return lost;
        }
    }

    public void deleteOwned(Collection<String> tableIds) {
        if (mongo == null || tableIds.isEmpty()) {
            return;
        }
        mongo.remove(Query.query(Criteria.where("_id").in(tableIds).and("ownerNode").is(cluster.nodeId())),
                TableSnapshotDocument.class);
    }

    public void delete(String tableId, String owner) {
        if (mongo == null) {
            return;
        }
        mongo.remove(Query.query(Criteria.where("_id").is(tableId).and("ownerNode").is(owner)), TableSnapshotDocument.class);
    }

    public Optional<TableSnapshotDocument> find(String tableId) {
        return mongo == null ? Optional.empty() : Optional.ofNullable(mongo.findById(tableId, TableSnapshotDocument.class));
    }

    public Optional<TableSnapshotDocument> findByPlayer(String playerId) {
        if (mongo == null) {
            return Optional.empty();
        }
        Query q = Query.query(Criteria.where("humans").is(playerId)).with(Sort.by(Sort.Direction.DESC, "savedAt")).limit(1);
        return Optional.ofNullable(mongo.findOne(q, TableSnapshotDocument.class));
    }

    /** Moves ownership from a dead (or handing-off) node to this one. Exactly one claimant succeeds. */
    public boolean claim(String tableId, String previousOwner) {
        if (mongo == null) {
            return false;
        }
        Query q = Query.query(Criteria.where("_id").is(tableId).and("ownerNode").is(previousOwner));
        return mongo.updateFirst(q, new Update().set("ownerNode", cluster.nodeId()).set("handedOff", false),
                TableSnapshotDocument.class).getModifiedCount() == 1;
    }

    /** Of {@code gameIds}, those whose crashed match can still be restored (their stakes must not be refunded yet). */
    public Set<String> restorableGameIds(Collection<String> gameIds) {
        if (mongo == null || gameIds.isEmpty()) {
            return Set.of();
        }
        Query q = Query.query(Criteria.where("gameId").in(gameIds).and("savedAt").gt(Instant.now().minus(restoreWindow)));
        q.fields().include("gameId");
        Set<String> ids = new HashSet<>();
        for (TableSnapshotDocument d : mongo.find(q, TableSnapshotDocument.class)) {
            ids.add(d.getGameId());
        }
        return ids;
    }

    private Query ownedBySelf(String tableId) {
        return Query.query(Criteria.where("_id").is(tableId).and("ownerNode").is(cluster.nodeId()));
    }
}
