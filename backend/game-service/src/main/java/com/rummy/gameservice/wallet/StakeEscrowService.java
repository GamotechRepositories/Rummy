package com.rummy.gameservice.wallet;

import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.persistence.document.StakeEscrowDocument;
import com.rummy.gameservice.recovery.TableSnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Durable record of every stake held in escrow, so no exit path — including a crashed node — can
 * keep a player's money or pay it back twice.
 *
 * <p>Lifecycle: {@code OPEN} (money taken, outcome pending) moves to exactly one final state:
 * {@code SETTLED} via {@code CLOSING} (the match paid out), {@code REFUNDED} (cancelled, left the
 * lobby, server drain) or {@code ORPHAN_REFUNDED} (its holder node died). Every transition is a
 * conditional update, so concurrent refund paths race safely and only one wins.
 *
 * <p>Settlement fence: a node that was presumed dead (heartbeat lost) may still be running its match.
 * {@link #beginSettlement} refuses to pay out a game whose stakes were already refunded as orphans.
 */
@Service
public class StakeEscrowService {

    private static final Logger log = LoggerFactory.getLogger(StakeEscrowService.class);

    public enum Status { OPEN, CLOSING, SETTLED, REFUNDED, ORPHAN_REFUNDED }

    private static final Duration RETENTION = Duration.ofDays(30);
    /** Recently written escrows are never reclaimed, even if their node looks dead. */
    private static final Duration ORPHAN_GRACE = Duration.ofSeconds(60);
    private static final String REJOIN_FEE_PREFIX = "REJOIN_FEE_";

    private final MongoTemplate mongo;
    private final WalletService wallet;
    private final ClusterNodeService cluster;
    private final Map<String, StakeEscrowDocument> memory = new ConcurrentHashMap<>();
    private TableSnapshotStore snapshots;
    private SettlementJobStore settlementJobs;

    @Autowired
    public StakeEscrowService(@Autowired(required = false) MongoTemplate mongo,
                              WalletService wallet,
                              ClusterNodeService cluster) {
        this.mongo = mongo;
        this.wallet = Objects.requireNonNull(wallet);
        this.cluster = Objects.requireNonNull(cluster);
    }

    @Autowired(required = false)
    public void setSnapshots(TableSnapshotStore snapshots) {
        this.snapshots = snapshots;
    }

    @Autowired(required = false)
    public void setSettlementJobs(SettlementJobStore settlementJobs) {
        this.settlementJobs = settlementJobs;
    }

    /**
     * Records a stake, held by this node, before it is debited. If the debit then fails, call
     * {@link #discard}; if the process dies in between, refunds find no debit and pay nothing.
     */
    public void open(String escrowId, String playerId, long amount, String tableId, String gameId) {
        StakeEscrowDocument doc = new StakeEscrowDocument(escrowId, playerId, amount, Status.OPEN.name(),
                cluster.nodeId(), tableId, gameId);
        if (mongo == null) {
            memory.putIfAbsent(escrowId, doc);
            return;
        }
        try {
            mongo.insert(doc);
        } catch (DuplicateKeyException ignored) {
            // Retried open for the same debit.
        }
    }

    /** The debit for an opened escrow failed; forget it. */
    public void discard(String escrowId) {
        if (mongo == null) {
            memory.computeIfPresent(escrowId, (k, d) -> Status.OPEN.name().equals(d.getStatus()) ? null : d);
            return;
        }
        mongo.remove(byIdAndStatus(escrowId, Status.OPEN), StakeEscrowDocument.class);
    }

    /** This node now hosts the table the stake was paid for. */
    public void assignToTable(String escrowId, String tableId, String gameId) {
        Instant now = Instant.now();
        if (mongo == null) {
            synchronized (memory) {
                StakeEscrowDocument d = memory.get(escrowId);
                if (d != null && Status.OPEN.name().equals(d.getStatus())) {
                    d.setHolderNode(cluster.nodeId());
                    d.setTableId(tableId);
                    d.setGameId(gameId);
                    d.setUpdatedAt(now);
                }
            }
            return;
        }
        mongo.updateFirst(byIdAndStatus(escrowId, Status.OPEN),
                new Update().set("holderNode", cluster.nodeId()).set("tableId", tableId)
                        .set("gameId", gameId).set("updatedAt", now),
                StakeEscrowDocument.class);
    }

    /**
     * Claims an open escrow for a refund. Exactly one caller gets {@code true}; it must then credit
     * the player, or call {@link #reopen} if the credit fails. Stakes escrowed before this ledger
     * existed have no record and are allowed through (their refund key is still idempotent).
     */
    public boolean claimRefund(String escrowId) {
        if (transition(escrowId, Status.OPEN, Status.REFUNDED)) {
            return true;
        }
        return status(escrowId).isEmpty();
    }

    /** Undo a claim whose credit failed, so another path can retry the refund. */
    public void reopen(String escrowId) {
        if (mongo == null) {
            synchronized (memory) {
                StakeEscrowDocument d = memory.get(escrowId);
                if (d != null && Status.REFUNDED.name().equals(d.getStatus())) {
                    d.setStatus(Status.OPEN.name());
                    d.setClosedAt(null);
                    d.setExpireAt(null);
                }
            }
            return;
        }
        mongo.updateFirst(byIdAndStatus(escrowId, Status.REFUNDED),
                new Update().set("status", Status.OPEN.name()).set("updatedAt", Instant.now())
                        .unset("closedAt").unset("expireAt"),
                StakeEscrowDocument.class);
    }

    /**
     * Takes over the open stakes of a match being restored from {@code deadOwner}. Returns false if the
     * match cannot safely continue: some stake was already paid out, refunded as an orphan, or is held
     * by another node, or a seated player's entry was refunded. The caller must then
     * {@link #refundCrashedGame} instead of resuming.
     */
    public boolean adoptGame(String gameId, String deadOwner, Collection<String> seatedHumans) {
        if (mongo == null) {
            return true;
        }
        mongo.updateMulti(byGameAndStatus(gameId, Status.OPEN).addCriteria(Criteria.where("holderNode").is(deadOwner)),
                new Update().set("holderNode", cluster.nodeId()).set("updatedAt", Instant.now()),
                StakeEscrowDocument.class);
        for (StakeEscrowDocument d : mongo.find(Query.query(Criteria.where("gameId").is(gameId)), StakeEscrowDocument.class)) {
            Status status = Status.valueOf(d.getStatus());
            boolean entryRefunded = status == Status.REFUNDED && !d.getId().startsWith(REJOIN_FEE_PREFIX)
                    && seatedHumans.contains(d.getPlayerId());
            boolean heldElsewhere = status == Status.OPEN && !cluster.nodeId().equals(d.getHolderNode());
            if (status == Status.CLOSING || status == Status.SETTLED || status == Status.ORPHAN_REFUNDED
                    || entryRefunded || heldElsewhere) {
                log.warn("[Escrow] Game {} cannot be restored: escrow {} is {} (holder {})",
                        gameId, d.getId(), status, d.getHolderNode());
                return false;
            }
        }
        return true;
    }

    /** Takes over the stakes of a finished match whose payout this node inherited from {@code deadHolder}. */
    public void adoptForSettlement(String gameId, String deadHolder) {
        if (mongo == null) {
            return;
        }
        mongo.updateMulti(Query.query(Criteria.where("gameId").is(gameId)
                        .and("status").in(Status.OPEN.name(), Status.CLOSING.name())
                        .and("holderNode").is(deadHolder)),
                new Update().set("holderNode", cluster.nodeId()).set("updatedAt", Instant.now()),
                StakeEscrowDocument.class);
    }

    /** Refunds the open stakes this node holds for a match that died with its server. */
    public int refundCrashedGame(String gameId) {
        List<StakeEscrowDocument> open = mongo == null
                ? memory.values().stream().filter(d -> gameId.equals(d.getGameId()) && Status.OPEN.name().equals(d.getStatus())).toList()
                : mongo.find(heldBySelf(byGameAndStatus(gameId, Status.OPEN)), StakeEscrowDocument.class);
        int refunded = 0;
        for (StakeEscrowDocument d : open) {
            if (transition(d.getId(), Status.OPEN, Status.ORPHAN_REFUNDED)
                    && creditBack(d, "CRASH_REFUND_", "GAME_CRASH_REFUND", "Game cancelled: server restarted")) {
                refunded++;
            }
        }
        return refunded;
    }

    /**
     * Locks the game's open stakes for payout. Returns false if the caller must not pay out: some stake
     * was already refunded as an orphan (this node was presumed dead; the rest are then refunded too),
     * or another node holds the game's stakes because it resumed the match after this node looked dead.
     */
    public boolean beginSettlement(String gameId) {
        List<StakeEscrowDocument> closing = new ArrayList<>();
        boolean orphaned;
        if (mongo != null && heldByOtherNode(gameId, Status.OPEN, Status.CLOSING)) {
            log.error("[Escrow] Game {} is held by another node; this copy of the match must not pay out", gameId);
            return false;
        }
        if (mongo == null) {
            synchronized (memory) {
                for (StakeEscrowDocument d : memory.values()) {
                    if (gameId.equals(d.getGameId()) && Status.OPEN.name().equals(d.getStatus())) {
                        d.setStatus(Status.CLOSING.name());
                        closing.add(d);
                    }
                }
                orphaned = memory.values().stream().anyMatch(d -> gameId.equals(d.getGameId())
                        && Status.ORPHAN_REFUNDED.name().equals(d.getStatus()));
            }
        } else {
            mongo.updateMulti(heldBySelf(byGameAndStatus(gameId, Status.OPEN)),
                    new Update().set("status", Status.CLOSING.name()).set("updatedAt", Instant.now()),
                    StakeEscrowDocument.class);
            if (heldByOtherNode(gameId, Status.OPEN)) {
                // Adopted by a restoring node between the check above and the lock.
                mongo.updateMulti(heldBySelf(byGameAndStatus(gameId, Status.CLOSING)),
                        new Update().set("status", Status.OPEN.name()).set("updatedAt", Instant.now()),
                        StakeEscrowDocument.class);
                log.error("[Escrow] Game {} was taken over by another node during settlement; not paying out", gameId);
                return false;
            }
            orphaned = mongo.exists(byGameAndStatus(gameId, Status.ORPHAN_REFUNDED), StakeEscrowDocument.class);
            if (orphaned) {
                closing = mongo.find(byGameAndStatus(gameId, Status.CLOSING), StakeEscrowDocument.class);
            }
        }
        if (!orphaned) {
            if (fullyRefunded(gameId)) {
                log.error("[Escrow] Game {} has no stakes left to pay out (all refunded); cancelling payout", gameId);
                return false;
            }
            return true;
        }
        log.error("[Escrow] Game {} was refunded as orphaned while still running; cancelling payout", gameId);
        for (StakeEscrowDocument d : closing) {
            if (transition(d.getId(), Status.CLOSING, Status.ORPHAN_REFUNDED)) {
                creditBack(d, "CRASH_REFUND_", "GAME_CRASH_REFUND", "Game cancelled: server connection lost");
            }
        }
        return false;
    }

    public void completeSettlement(String gameId) {
        Instant now = Instant.now();
        if (mongo == null) {
            synchronized (memory) {
                memory.values().stream()
                        .filter(d -> gameId.equals(d.getGameId()) && Status.CLOSING.name().equals(d.getStatus()))
                        .forEach(d -> d.setStatus(Status.SETTLED.name()));
            }
            return;
        }
        mongo.updateMulti(byGameAndStatus(gameId, Status.CLOSING),
                new Update().set("status", Status.SETTLED.name()).set("updatedAt", now)
                        .set("closedAt", now).set("expireAt", now.plus(RETENTION)),
                StakeEscrowDocument.class);
    }

    /**
     * Refunds every open stake this node holds for a cancelled game. Stakes adopted by a node that
     * resumed the match are left alone. Returns how many were paid back.
     */
    public int refundGame(String gameId, String reason) {
        List<StakeEscrowDocument> open;
        if (mongo == null) {
            open = memory.values().stream()
                    .filter(d -> gameId.equals(d.getGameId()) && Status.OPEN.name().equals(d.getStatus()))
                    .toList();
        } else {
            open = mongo.find(heldBySelf(byGameAndStatus(gameId, Status.OPEN)), StakeEscrowDocument.class);
        }
        int refunded = 0;
        for (StakeEscrowDocument d : open) {
            if (!transition(d.getId(), Status.OPEN, Status.REFUNDED)) {
                continue;
            }
            if (creditBack(d, "ABORT_REFUND_", "GAME_ABORT_REFUND", "Game cancelled: " + reason)) {
                refunded++;
            } else {
                reopen(d.getId());
            }
        }
        return refunded;
    }

    /** Refunds stakes whose holder node has died. Safe to run on every node at once. */
    @Scheduled(fixedDelayString = "${rummy.escrow.orphan-scan-interval-ms:30000}",
            initialDelayString = "${rummy.escrow.orphan-scan-initial-delay-ms:60000}")
    public int refundOrphans() {
        if (mongo == null) {
            return 0;
        }
        try {
            Set<String> alive = cluster.aliveNodes();
            Instant cutoff = Instant.now().minus(ORPHAN_GRACE);
            Query orphans = Query.query(Criteria.where("status").is(Status.OPEN.name())
                    .and("holderNode").ne(null).nin(alive)
                    .and("updatedAt").lt(cutoff));
            List<StakeEscrowDocument> candidates = mongo.find(orphans.limit(500), StakeEscrowDocument.class);
            Set<String> protectedGames = protectedGames(candidates);
            int refunded = 0;
            for (StakeEscrowDocument d : candidates) {
                if (d.getGameId() != null && protectedGames.contains(d.getGameId())) {
                    // Its match can still be resumed, or its payout is queued; a surviving node finishes it.
                    continue;
                }
                Query claim = byIdAndStatus(d.getId(), Status.OPEN).addCriteria(Criteria.where("holderNode").is(d.getHolderNode()));
                if (mongo.updateFirst(claim, terminal(Status.ORPHAN_REFUNDED), StakeEscrowDocument.class).getModifiedCount() != 1) {
                    continue;
                }
                if (creditBack(d, "CRASH_REFUND_", "GAME_CRASH_REFUND", "Game cancelled: server restarted")) {
                    refunded++;
                } else {
                    mongo.updateFirst(byIdAndStatus(d.getId(), Status.ORPHAN_REFUNDED),
                            new Update().set("status", Status.OPEN.name()).unset("closedAt").unset("expireAt"),
                            StakeEscrowDocument.class);
                }
            }
            Query stuck = Query.query(Criteria.where("status").is(Status.CLOSING.name())
                    .and("holderNode").nin(alive).and("updatedAt").lt(cutoff));
            List<StakeEscrowDocument> stuckEscrows = mongo.find(stuck.limit(100), StakeEscrowDocument.class);
            Set<String> queuedPayouts = settlementJobs == null ? Set.of() : settlementJobs.pendingGameIds(gameIdsOf(stuckEscrows));
            for (StakeEscrowDocument d : stuckEscrows) {
                if (queuedPayouts.contains(d.getGameId())) {
                    continue;
                }
                log.error("[Escrow] RECONCILE REQUIRED: escrow {} (game {}) was mid-settlement when node {} died",
                        d.getId(), d.getGameId(), d.getHolderNode());
            }
            if (refunded > 0) {
                log.warn("[Escrow] Refunded {} stake(s) held by dead nodes", refunded);
            }
            return refunded;
        } catch (Exception e) {
            log.error("[Escrow] Orphan scan failed: {}", e.getMessage(), e);
            return 0;
        }
    }

    /** The game had stakes, but none is locked or paid any more: paying out would spend refunded money. */
    private boolean fullyRefunded(String gameId) {
        List<String> payable = List.of(Status.CLOSING.name(), Status.SETTLED.name());
        if (mongo == null) {
            synchronized (memory) {
                List<StakeEscrowDocument> stakes = memory.values().stream()
                        .filter(d -> gameId.equals(d.getGameId()) && !d.getId().startsWith(REJOIN_FEE_PREFIX)).toList();
                return !stakes.isEmpty() && stakes.stream().noneMatch(d -> payable.contains(d.getStatus()));
            }
        }
        Criteria stakes = Criteria.where("gameId").is(gameId).and("_id").not().regex("^" + REJOIN_FEE_PREFIX);
        return mongo.exists(Query.query(stakes), StakeEscrowDocument.class)
                && !mongo.exists(Query.query(Criteria.where("gameId").is(gameId)
                .and("_id").not().regex("^" + REJOIN_FEE_PREFIX).and("status").in(payable)), StakeEscrowDocument.class);
    }

    private Set<String> protectedGames(List<StakeEscrowDocument> candidates) {
        Set<String> gameIds = gameIdsOf(candidates);
        Set<String> result = new java.util.HashSet<>();
        if (snapshots != null) {
            result.addAll(snapshots.restorableGameIds(gameIds));
        }
        if (settlementJobs != null) {
            result.addAll(settlementJobs.pendingGameIds(gameIds));
        }
        return result;
    }

    private static Set<String> gameIdsOf(List<StakeEscrowDocument> escrows) {
        return escrows.stream().map(StakeEscrowDocument::getGameId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public Optional<Status> status(String escrowId) {
        StakeEscrowDocument d = mongo == null ? memory.get(escrowId) : mongo.findById(escrowId, StakeEscrowDocument.class);
        return Optional.ofNullable(d).map(doc -> Status.valueOf(doc.getStatus()));
    }

    private boolean creditBack(StakeEscrowDocument d, String keyPrefix, String type, String description) {
        try {
            if (!wallet.hasTransaction(d.getId())) {
                return true;
            }
            wallet.credit(d.getPlayerId(), BigDecimal.valueOf(d.getAmount()), type, keyPrefix + d.getId(),
                    d.getGameId(), description, Map.of("escrowId", d.getId(),
                            "tableId", d.getTableId() != null ? d.getTableId() : ""));
            return true;
        } catch (Exception e) {
            log.error("[Escrow] RECONCILE REQUIRED: refund of {} to {} failed: {}", d.getId(), d.getPlayerId(), e.getMessage());
            return false;
        }
    }

    private boolean transition(String escrowId, Status from, Status to) {
        boolean terminal = to == Status.SETTLED || to == Status.REFUNDED || to == Status.ORPHAN_REFUNDED;
        if (mongo == null) {
            synchronized (memory) {
                StakeEscrowDocument d = memory.get(escrowId);
                if (d == null || !from.name().equals(d.getStatus())) {
                    return false;
                }
                d.setStatus(to.name());
                if (terminal) {
                    d.setClosedAt(Instant.now());
                }
                return true;
            }
        }
        Update update = terminal ? terminal(to) : new Update().set("status", to.name()).set("updatedAt", Instant.now());
        return mongo.updateFirst(byIdAndStatus(escrowId, from), update, StakeEscrowDocument.class).getModifiedCount() == 1;
    }

    private static Update terminal(Status to) {
        Instant now = Instant.now();
        return new Update().set("status", to.name()).set("updatedAt", now)
                .set("closedAt", now).set("expireAt", now.plus(RETENTION));
    }

    private static Query byIdAndStatus(String id, Status status) {
        return Query.query(Criteria.where("_id").is(id).and("status").is(status.name()));
    }

    private static Query byGameAndStatus(String gameId, Status status) {
        return Query.query(Criteria.where("gameId").is(gameId).and("status").is(status.name()));
    }

    private Query heldBySelf(Query query) {
        return query.addCriteria(Criteria.where("holderNode").is(cluster.nodeId()));
    }

    private boolean heldByOtherNode(String gameId, Status... statuses) {
        List<String> names = java.util.Arrays.stream(statuses).map(Status::name).toList();
        return mongo.exists(Query.query(Criteria.where("gameId").is(gameId).and("status").in(names)
                .and("holderNode").ne(cluster.nodeId())), StakeEscrowDocument.class);
    }
}
