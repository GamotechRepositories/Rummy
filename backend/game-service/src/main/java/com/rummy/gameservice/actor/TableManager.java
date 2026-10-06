package com.rummy.gameservice.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.engine.GameEngine;
import com.rummy.engine.model.Deck;
import com.rummy.engine.model.GameState;
import com.rummy.engine.model.GameStateSnapshots;
import com.rummy.engine.rules.PointsRummyRules;
import com.rummy.engine.rules.RulesetRegistry;
import com.rummy.engine.rules.RummyRules;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.wallet.SettlementService;
import com.rummy.gameservice.wallet.StakeEscrowService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Registry and lifecycle manager for in-memory TableActors.
 */
@Service
public class TableManager {

    private static final Logger log = LoggerFactory.getLogger(TableManager.class);

    private final GameEngine engine = new GameEngine();
    private final ObjectMapper objectMapper;
    private final com.rummy.gameservice.persistence.GamePersistenceService persistenceService;
    private final com.rummy.gameservice.kafka.GameEventProducer eventProducer;
    private final com.rummy.gameservice.session.PlayerSessionService sessionService;
    private final com.rummy.gameservice.wallet.WalletService walletService;
    private final TableRoutingRegistry routingRegistry;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
    private final Map<String, TableActor> tables = new ConcurrentHashMap<>();
    private StakeEscrowService escrows;
    private SettlementService settlements;

    private final int maxTables;
    private final Duration finishedGrace;
    private final Duration abandonedAfter;

    @Autowired
    public TableManager(ObjectMapper objectMapper,
                        com.rummy.gameservice.persistence.GamePersistenceService persistenceService,
                        @Autowired(required = false) com.rummy.gameservice.kafka.GameEventProducer eventProducer,
                        @Autowired(required = false) @Lazy com.rummy.gameservice.session.PlayerSessionService sessionService,
                        @Autowired(required = false) com.rummy.gameservice.wallet.WalletService walletService,
                        @Autowired(required = false) TableRoutingRegistry routingRegistry,
                        @Value("${rummy.tables.max-per-node:20000}") int maxTables,
                        @Value("${rummy.tables.finished-grace-seconds:180}") long finishedGraceSeconds,
                        @Value("${rummy.tables.abandoned-after-seconds:600}") long abandonedAfterSeconds) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.persistenceService = persistenceService;
        this.eventProducer = eventProducer;
        this.sessionService = sessionService;
        this.walletService = walletService;
        this.routingRegistry = routingRegistry;
        this.maxTables = maxTables;
        this.finishedGrace = Duration.ofSeconds(finishedGraceSeconds);
        this.abandonedAfter = Duration.ofSeconds(abandonedAfterSeconds);
        this.settlements = walletService != null ? SettlementService.inline(walletService) : null;
    }

    public TableManager(ObjectMapper objectMapper,
                        com.rummy.gameservice.persistence.GamePersistenceService persistenceService,
                        com.rummy.gameservice.kafka.GameEventProducer eventProducer,
                        com.rummy.gameservice.session.PlayerSessionService sessionService,
                        com.rummy.gameservice.wallet.WalletService walletService) {
        this(objectMapper, persistenceService, eventProducer, sessionService, walletService, null, 20000, 180, 600);
    }

    public TableManager(ObjectMapper objectMapper,
                        com.rummy.gameservice.persistence.GamePersistenceService persistenceService) {
        this(objectMapper, persistenceService, null, null, null);
    }

    public TableManager(ObjectMapper objectMapper) {
        this(objectMapper, null, null, null, null);
    }

    public TableActor getOrCreateTable(String tableId, RummyRules rules) {
        return tables.computeIfAbsent(tableId, id -> {
            RummyRules activeRules = rules != null ? rules : new PointsRummyRules();
            String gameId = "G_" + UUID.randomUUID().toString().substring(0, 8);
            Deck deck = Deck.createMultiPackDeck(activeRules.getDeckCount(), activeRules.getPrintedJokersPerDeck(), new SecureRandom());
            GameState initialState = new GameState(gameId, id, activeRules.getRulesetId(),
                    activeRules.getRulesetVersion(), List.of(), deck);

            TableActor actor = new TableActor(id, initialState, activeRules, engine, objectMapper, scheduler,
                    persistenceService, eventProducer, sessionService, walletService);
            actor.setEscrows(escrows);
            actor.setSettlements(settlements);
            return actor;
        });
    }

    @Autowired(required = false)
    public void setEscrows(StakeEscrowService escrows) {
        this.escrows = escrows;
        if (settlements != null) {
            settlements.setEscrows(escrows);
        }
    }

    /** Payouts run on this queue; without it (tests) they run inline on the table's thread. */
    @Autowired(required = false)
    public void setSettlements(SettlementService settlements) {
        this.settlements = settlements;
    }

    /**
     * Rebuilds a match that was running on a crashed node and starts its clocks. If the table is
     * already hosted here (a concurrent restore won), the existing actor is returned unchanged.
     */
    public TableActor restoreTable(TableSnapshot snapshot, Duration minNextDealDelay) {
        RummyRules rules = RulesetRegistry.requireRuleset(snapshot.game().rulesetId());
        GameState state = GameStateSnapshots.restore(snapshot.game());
        TableActor actor = new TableActor(snapshot.tableId(), state, rules, engine, objectMapper, scheduler,
                persistenceService, eventProducer, sessionService, walletService);
        actor.setEscrows(escrows);
        actor.setSettlements(settlements);
        actor.applySnapshot(snapshot);
        TableActor existing = tables.putIfAbsent(snapshot.tableId(), actor);
        if (existing != null) {
            return existing;
        }
        actor.resumeAfterRestore(minNextDealDelay);
        return actor;
    }

    /**
     * Drops a table whose match another node has taken over (this node was presumed dead). Its stakes
     * now belong to that node, so nothing is refunded here.
     */
    public void abandonTable(String tableId) {
        TableActor actor = tables.remove(tableId);
        if (actor != null) {
            actor.destroy();
            log.warn("[TableManager] Abandoned table {}: its match continues on another node", tableId);
        }
    }

    public Collection<TableActor> allTables() {
        return Collections.unmodifiableCollection(tables.values());
    }

    public Optional<TableActor> getTable(String tableId) {
        return Optional.ofNullable(tables.get(tableId));
    }

    /** False once this node hosts its table limit; new tables should go to another node. */
    public boolean hasCapacityForNewTable() {
        return tables.size() < maxTables;
    }

    public void removeTable(String tableId) {
        TableActor actor = tables.remove(tableId);
        if (actor != null) {
            actor.destroy();
            refundUnsettledStakes(actor, "table closed");
        }
    }

    public int activeTableCount() {
        return tables.size();
    }

    public long liveTableCount() {
        return tables.values().stream().filter(TableActor::isLive).count();
    }

    /** Refunds and closes every started match that is still unfinished. Returns how many were aborted. */
    public int abortLiveMatches(String reason) {
        int aborted = 0;
        for (TableActor actor : tables.values()) {
            if (actor.isLive() && actor.getState().getStatus() != com.rummy.engine.model.GameStatus.WAITING_FOR_PLAYERS) {
                actor.abortMatch(reason);
                aborted++;
            }
        }
        return aborted;
    }

    @Scheduled(fixedDelayString = "${rummy.tables.reclaim-interval-ms:30000}",
            initialDelayString = "${rummy.tables.reclaim-interval-ms:30000}")
    public void reclaimIdleTables() {
        reclaimIdleTables(Instant.now());
    }

    /** Drops finished and abandoned tables (and their routing entries). Returns how many were removed. */
    public int reclaimIdleTables(Instant now) {
        int removed = 0;
        for (TableActor actor : tables.values()) {
            Optional<String> reason = actor.reclaimReason(now, finishedGrace, abandonedAfter);
            if (reason.isPresent() && closeTable(actor, reason.get())) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[TableManager] Reclaimed {} idle table(s); {} remain", removed, tables.size());
        }
        return removed;
    }

    private boolean closeTable(TableActor actor, String reason) {
        String tableId = actor.getTableId();
        if (!tables.remove(tableId, actor)) {
            return false;
        }
        List<String> humans = actor.humanPlayerIds();
        actor.destroy();
        refundUnsettledStakes(actor, reason);
        if (routingRegistry != null) {
            routingRegistry.unregisterTable(tableId);
            for (String playerId : humans) {
                if (routingRegistry.getTableForPlayer(playerId).map(tableId::equals).orElse(false)) {
                    routingRegistry.unregisterPlayer(playerId);
                }
            }
        }
        log.debug("[TableManager] Closed table {} ({})", tableId, reason);
        return true;
    }

    /** A table that goes away without paying out (e.g. never dealt) returns whatever is still escrowed. */
    private void refundUnsettledStakes(TableActor actor, String reason) {
        if (escrows == null) {
            return;
        }
        try {
            int refunded = escrows.refundGame(actor.getState().getGameId(), reason);
            if (refunded > 0) {
                log.info("[TableManager] Refunded {} unsettled stake(s) for table {} ({})", refunded, actor.getTableId(), reason);
            }
        } catch (Exception e) {
            log.error("[TableManager] RECONCILE REQUIRED: refunding stakes of table {} failed: {}", actor.getTableId(), e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        for (TableActor actor : tables.values()) {
            actor.destroy();
        }
        tables.clear();
        scheduler.shutdownNow();
    }
}
