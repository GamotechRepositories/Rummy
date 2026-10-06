package com.rummy.gameservice.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.actor.TableSnapshot;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.persistence.document.TableSnapshotDocument;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.wallet.StakeEscrowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets a match survive the crash of the node hosting it.
 *
 * <p>Every node periodically writes a snapshot of each running match it hosts (only tables that changed
 * since the last write). When a player reconnects to a table whose node has died, a surviving node
 * claims the snapshot, takes over the escrowed stakes and resumes the match from the last snapshot.
 * Matches that cannot be resumed safely, or are not reclaimed within the restore window, are refunded
 * by {@link StakeEscrowService#refundOrphans()} as before.
 */
@Service
public class TableRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TableRecoveryService.class);
    private static final int LOCK_STRIPES = 64;

    public enum Outcome { RESTORED, OWNER_ALIVE, NONE }

    public record RestoreResult(Outcome outcome, String tableId, String owner) {
        static RestoreResult none(String tableId) {
            return new RestoreResult(Outcome.NONE, tableId, null);
        }
    }

    private final TableManager tableManager;
    private final TableSnapshotStore store;
    private final ClusterNodeService cluster;
    private final TableRoutingRegistry routing;
    private final ObjectMapper objectMapper;
    private final Duration minNextDealDelay;
    private StakeEscrowService escrows;

    /** Mutation count at the last snapshot written (or found unrestorable) per hosted table. */
    private final Map<String, Long> flushed = new ConcurrentHashMap<>();
    /** Tables with a snapshot record owned by this node. */
    private final Set<String> saved = ConcurrentHashMap.newKeySet();
    private final Object[] restoreLocks = new Object[LOCK_STRIPES];

    @Autowired
    public TableRecoveryService(TableManager tableManager,
                                TableSnapshotStore store,
                                ClusterNodeService cluster,
                                TableRoutingRegistry routing,
                                ObjectMapper objectMapper,
                                @Value("${rummy.recovery.min-next-deal-delay-seconds:10}") long minNextDealDelaySeconds) {
        this.tableManager = tableManager;
        this.store = store;
        this.cluster = cluster;
        this.routing = routing;
        this.objectMapper = objectMapper;
        this.minNextDealDelay = Duration.ofSeconds(minNextDealDelaySeconds);
        for (int i = 0; i < LOCK_STRIPES; i++) {
            restoreLocks[i] = new Object();
        }
    }

    @Autowired(required = false)
    public void setEscrows(StakeEscrowService escrows) {
        this.escrows = escrows;
    }

    public boolean enabled() {
        return store.enabled();
    }

    /** Writes snapshots of matches that changed and deletes those of matches that ended. */
    @Scheduled(fixedDelayString = "${rummy.recovery.snapshot-interval-ms:1000}",
            initialDelayString = "${rummy.recovery.snapshot-interval-ms:1000}")
    public synchronized void flushSnapshots() {
        if (!store.enabled()) {
            return;
        }
        try {
            flushOnce();
        } catch (Exception e) {
            log.warn("[Recovery] Snapshot flush failed: {}", e.getMessage());
        }
    }

    void flushOnce() throws Exception {
        List<TableSnapshotStore.Entry> writes = new ArrayList<>();
        Map<String, Long> writtenAt = new java.util.HashMap<>();
        List<String> deletes = new ArrayList<>();
        Set<String> hosted = new HashSet<>();

        for (TableActor actor : tableManager.allTables()) {
            String tableId = actor.getTableId();
            hosted.add(tableId);
            long mutations = actor.mutationCount();
            Long last = flushed.get(tableId);
            if (last != null && last == mutations) {
                continue;
            }
            Optional<TableSnapshot> snapshot = actor.captureSnapshot();
            if (snapshot.isPresent()) {
                TableSnapshot snap = snapshot.get();
                writes.add(new TableSnapshotStore.Entry(tableId, snap.gameId(), snap.resumablePlayerIds(),
                        objectMapper.writeValueAsString(snap)));
                writtenAt.put(tableId, mutations);
            } else {
                if (saved.remove(tableId)) {
                    deletes.add(tableId);
                }
                flushed.put(tableId, mutations);
            }
        }
        for (String tableId : List.copyOf(flushed.keySet())) {
            if (!hosted.contains(tableId)) {
                flushed.remove(tableId);
                if (saved.remove(tableId)) {
                    deletes.add(tableId);
                }
            }
        }

        Set<String> lost = store.saveAll(writes);
        for (Map.Entry<String, Long> e : writtenAt.entrySet()) {
            if (lost.contains(e.getKey())) {
                continue;
            }
            saved.add(e.getKey());
            flushed.put(e.getKey(), e.getValue());
        }
        for (String tableId : lost) {
            log.error("[Recovery] Table {} was resumed by another node while this node looked dead; dropping local copy", tableId);
            saved.remove(tableId);
            flushed.remove(tableId);
            tableManager.abandonTable(tableId);
        }
        store.deleteOwned(deletes);
    }

    /**
     * Hands this node's running matches to the other nodes before it stops (deploy, scale-down): each
     * match is frozen, its final snapshot written as released, and its players disconnected so they
     * reconnect to a node that resumes it. Matches whose snapshot cannot be written are cancelled with
     * refunds. Returns how many matches were handed off.
     */
    public synchronized int handOffLiveTables(String abortReason) {
        if (!store.enabled()) {
            return 0;
        }
        List<TableActor> frozen = new ArrayList<>();
        List<TableSnapshotStore.Entry> entries = new ArrayList<>();
        for (TableActor actor : tableManager.allTables()) {
            if (!actor.isLive()) {
                continue;
            }
            Optional<TableSnapshot> snapshot = actor.freezeForHandoff();
            if (snapshot.isEmpty()) {
                continue;
            }
            try {
                TableSnapshot snap = snapshot.get();
                entries.add(new TableSnapshotStore.Entry(actor.getTableId(), snap.gameId(), snap.resumablePlayerIds(),
                        objectMapper.writeValueAsString(snap)));
                frozen.add(actor);
            } catch (Exception e) {
                log.error("[Recovery] Could not serialise table {} for handoff: {}", actor.getTableId(), e.getMessage());
                actor.abortFrozenMatch(abortReason);
            }
        }
        Set<String> lost;
        try {
            lost = store.handOff(entries);
        } catch (Exception e) {
            log.error("[Recovery] Handoff write failed; cancelling {} match(es) with refunds: {}", frozen.size(), e.getMessage());
            frozen.forEach(actor -> actor.abortFrozenMatch(abortReason));
            return 0;
        }
        int handedOff = 0;
        for (TableActor actor : frozen) {
            String tableId = actor.getTableId();
            saved.remove(tableId);
            flushed.remove(tableId);
            if (!lost.contains(tableId)) {
                routing.unregisterTableIfOwnedBy(tableId, cluster.nodeId());
                actor.closeSessionsForHandoff();
                handedOff++;
            }
            tableManager.abandonTable(tableId);
        }
        log.warn("[Recovery] Handed off {} running match(es) to other nodes", handedOff);
        return handedOff;
    }

    /**
     * Resumes {@code tableId} on this node if it was running on a node that has since died.
     * {@link Outcome#OWNER_ALIVE} means its host still looks alive (it may have crashed moments ago and
     * not yet missed enough heartbeats); the caller should ask the player to retry shortly.
     */
    public RestoreResult tryRestore(String tableId) {
        if (!store.enabled() || tableId == null) {
            return RestoreResult.none(tableId);
        }
        synchronized (restoreLocks[Math.floorMod(tableId.hashCode(), LOCK_STRIPES)]) {
            if (tableManager.getTable(tableId).isPresent()) {
                return new RestoreResult(Outcome.RESTORED, tableId, cluster.nodeId());
            }
            try {
                return restoreLocked(tableId);
            } catch (Exception e) {
                log.error("[Recovery] Restoring table {} failed: {}", tableId, e.getMessage(), e);
                return RestoreResult.none(tableId);
            }
        }
    }

    /** Finds and resumes the crashed table a player was seated at, when their routing was lost with the node. */
    public RestoreResult tryRestoreForPlayer(String playerId) {
        if (!store.enabled() || playerId == null) {
            return RestoreResult.none(null);
        }
        Optional<TableSnapshotDocument> doc;
        try {
            doc = store.findByPlayer(playerId);
        } catch (Exception e) {
            log.warn("[Recovery] Snapshot lookup for player {} failed: {}", playerId, e.getMessage());
            return RestoreResult.none(null);
        }
        if (doc.isEmpty() || cluster.nodeId().equals(doc.get().getOwnerNode())) {
            return RestoreResult.none(null);
        }
        String tableId = doc.get().getTableId();
        Optional<String> routedOwner = routing.getServerForTable(tableId);
        if (routedOwner.isPresent() && cluster.isAlive(routedOwner.get())) {
            // The table is healthy; the player's own binding was cleared on purpose (they left).
            return RestoreResult.none(tableId);
        }
        return tryRestore(tableId);
    }

    private RestoreResult restoreLocked(String tableId) throws Exception {
        Optional<TableSnapshotDocument> found = store.find(tableId);
        if (found.isEmpty()) {
            return RestoreResult.none(tableId);
        }
        TableSnapshotDocument doc = found.get();
        String owner = doc.getOwnerNode();
        if (cluster.nodeId().equals(owner)) {
            // Ours, yet not hosted: the match ended or was dropped here; the record is stale.
            store.delete(tableId, owner);
            return RestoreResult.none(tableId);
        }
        if (!doc.isHandedOff() && cluster.isAlive(owner)) {
            return new RestoreResult(Outcome.OWNER_ALIVE, tableId, owner);
        }
        if (!store.withinRestoreWindow(doc)) {
            log.info("[Recovery] Snapshot of table {} is past the restore window; its stakes are refunded instead", tableId);
            store.delete(tableId, owner);
            return RestoreResult.none(tableId);
        }
        if (!store.claim(tableId, owner)) {
            return store.find(tableId)
                    .filter(d -> !cluster.nodeId().equals(d.getOwnerNode()) && cluster.isAlive(d.getOwnerNode()))
                    .map(d -> new RestoreResult(Outcome.OWNER_ALIVE, tableId, d.getOwnerNode()))
                    .orElse(RestoreResult.none(tableId));
        }

        TableSnapshot snap = objectMapper.readValue(doc.getPayload(), TableSnapshot.class);
        Set<String> seated = new HashSet<>(snap.humanPlayerIds());
        if (snap.stakeTier() > 0 && escrows != null && !escrows.adoptGame(snap.gameId(), owner, seated)) {
            int refunded = escrows.refundCrashedGame(snap.gameId());
            log.warn("[Recovery] Table {} could not be resumed safely; refunded {} stake(s)", tableId, refunded);
            store.delete(tableId, cluster.nodeId());
            return RestoreResult.none(tableId);
        }

        TableActor actor = tableManager.restoreTable(snap, minNextDealDelay);
        routing.registerTableOwnership(tableId);
        for (String playerId : snap.resumablePlayerIds()) {
            routing.registerPlayerTable(playerId, tableId);
        }
        saved.add(tableId);
        flushed.remove(tableId);
        log.warn("[Recovery] Resumed table {} (game {}) from {} node {}; snapshot age {}s",
                tableId, actor.getState().getGameId(), doc.isHandedOff() ? "stopping" : "crashed", owner,
                Duration.between(doc.getSavedAt(), java.time.Instant.now()).toSeconds());
        return new RestoreResult(Outcome.RESTORED, tableId, cluster.nodeId());
    }
}
