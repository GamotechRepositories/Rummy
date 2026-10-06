package com.rummy.gameservice.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.rummy.engine.command.DiscardCommand;
import com.rummy.engine.command.DrawCommand;
import com.rummy.engine.command.DropCommand;
import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.command.ReadyCommand;
import com.rummy.engine.command.StartGameCommand;
import com.rummy.engine.model.Card;
import com.rummy.engine.command.DrawSource;
import com.rummy.engine.model.GameStatus;
import com.rummy.engine.model.PlayerState;
import com.rummy.engine.model.TurnPhase;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.persistence.document.StakeEscrowDocument;
import com.rummy.gameservice.persistence.document.TableSnapshotDocument;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.wallet.StakeEscrowService;
import com.rummy.gameservice.wallet.TestFunding;
import com.rummy.gameservice.wallet.WalletService;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against a local MongoDB (skipped when none is listening on localhost:27017). */
@DisplayName("Crash restore: a surviving node resumes a crashed node's match (MongoDB)")
class CrashRestoreMongoTest {

    private static final String TABLE = "TBL_MM_restore";
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    private MongoClient client;
    private MongoTemplate mongo;
    private String dbName;
    private WalletService wallet;
    private Node a;
    private Node b;

    /** One game-service process. */
    private final class Node {
        final ClusterNodeService cluster;
        final TableRoutingRegistry routing;
        final TableManager tables;
        final StakeEscrowService escrows;
        final TableSnapshotStore store;
        final TableRecoveryService recovery;

        Node(String id) {
            routing = new TableRoutingRegistry(null, id);
            cluster = new ClusterNodeService(routing, mongo, 45);
            tables = new TableManager(json, null, null, null, wallet, routing, 100, 180, 600);
            escrows = new StakeEscrowService(mongo, wallet, cluster);
            store = new TableSnapshotStore(mongo, cluster, true, 300);
            escrows.setSnapshots(store);
            tables.setEscrows(escrows);
            recovery = new TableRecoveryService(tables, store, cluster, routing, json, 0);
            recovery.setEscrows(escrows);
            cluster.heartbeat();
        }
    }

    @BeforeEach
    void setUp() {
        client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=1500&connectTimeoutMS=1500");
        try {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
        } catch (Exception e) {
            client.close();
            assumeTrue(false, "local MongoDB not available");
        }
        dbName = "rummy_restore_test_" + UUID.randomUUID().toString().substring(0, 8);
        mongo = new MongoTemplate(client, dbName);
        wallet = new WalletService();
        TestFunding.fund(wallet, "P1", "P2");
        a = new Node("node-a");
        b = new Node("node-b");
    }

    @AfterEach
    void tearDown() {
        if (mongo != null) {
            a.tables.shutdown();
            b.tables.shutdown();
            client.getDatabase(dbName).drop();
            client.close();
        }
    }

    @Test
    @DisplayName("The match continues on the survivor with the same cards and stakes, and pays out once")
    void crashedMatchResumesAndSettles() throws Exception {
        TableActor original = paidMatchOnA();
        String gameId = original.getState().getGameId();
        String onTurn = original.getState().getTurnState().getCurrentPlayerId();
        original.processCommand(new DrawCommand("d1", gameId, onTurn, DrawSource.CLOSED_DECK, Instant.now()), "draw");
        a.recovery.flushOnce();
        crash("node-a");

        TableRecoveryService.RestoreResult result = b.recovery.tryRestore(TABLE);

        assertThat(result.outcome()).isEqualTo(TableRecoveryService.Outcome.RESTORED);
        TableActor resumed = b.tables.getTable(TABLE).orElseThrow();
        assertThat(resumed.getState().requirePlayer(onTurn).getHandSnapshot())
                .isEqualTo(original.getState().requirePlayer(onTurn).getHandSnapshot());
        assertThat(resumed.getState().getTurnState().getPhase()).isEqualTo(TurnPhase.AWAITING_DISCARD);
        assertThat(escrow("STAKE_P1").getHolderNode()).isEqualTo("node-b");
        assertThat(snapshot().getOwnerNode()).isEqualTo("node-b");
        assertThat(b.routing.getTableForPlayer("P1")).contains(TABLE);

        // The survivor finishes the match: the player on turn discards, the other drops.
        Card cut = resumed.getState().getCutJoker().getCard();
        String discard = resumed.getState().requirePlayer(onTurn).getHandSnapshot().stream()
                .filter(c -> !c.isPrintedJoker() && !c.getCard().isWildJoker(cut)).findFirst().orElseThrow().getInstanceId();
        resumed.processCommand(new DiscardCommand("x1", gameId, onTurn, discard, Instant.now()), "discard");
        String other = "P1".equals(onTurn) ? "P2" : "P1";
        resumed.processCommand(new DropCommand("drop", gameId, other, Instant.now()), "drop");

        assertThat(resumed.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(resumed.getLastSettlement()).isNotNull();
        assertThat(escrow("STAKE_P1").getStatus()).isEqualTo("SETTLED");
        assertThat(balance(other)).isLessThan(new BigDecimal("1000"));
        assertThat(balance(onTurn)).isGreaterThan(new BigDecimal("1000"));
        assertThat(balance(other).add(balance(onTurn))).as("paid out once").isLessThanOrEqualTo(new BigDecimal("2000"));

        b.recovery.flushOnce();
        assertThat(mongo.findById(TABLE, TableSnapshotDocument.class)).as("finished match forgets its snapshot").isNull();
    }

    @Test
    @DisplayName("A node wrongly presumed dead drops its copy and cannot pay out the resumed match")
    void zombieNodeFenced() throws Exception {
        TableActor original = paidMatchOnA();
        a.recovery.flushOnce();
        crash("node-a");
        assertThat(b.recovery.tryRestore(TABLE).outcome()).isEqualTo(TableRecoveryService.Outcome.RESTORED);

        // Node A wakes up and keeps playing its stale copy.
        String gameId = original.getState().getGameId();
        String onTurn = original.getState().getTurnState().getCurrentPlayerId();
        original.processCommand(new DrawCommand("z1", gameId, onTurn, DrawSource.CLOSED_DECK, Instant.now()), "zombie");
        a.recovery.flushOnce();

        assertThat(a.tables.getTable(TABLE)).as("stale copy dropped").isEmpty();
        assertThat(snapshot().getOwnerNode()).isEqualTo("node-b");
        assertThat(a.escrows.beginSettlement(gameId)).isFalse();
        assertThat(a.escrows.refundGame(gameId, "zombie close")).as("stakes now belong to node B").isZero();
        assertThat(escrow("STAKE_P1").getStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("Stakes of a crashed match are refunded only after the restore window closes")
    void janitorWaitsForRestoreWindow() throws Exception {
        TableActor original = paidMatchOnA();
        a.recovery.flushOnce();
        crash("node-a");
        ageEscrows();

        assertThat(b.escrows.refundOrphans()).as("match still restorable").isZero();

        mongo.updateFirst(Query.query(Criteria.where("_id").is(TABLE)),
                Update.update("savedAt", Instant.now().minusSeconds(600)), TableSnapshotDocument.class);
        assertThat(b.escrows.refundOrphans()).isEqualTo(2);
        assertThat(balance("P1")).isEqualByComparingTo("1000");
        assertThat(b.recovery.tryRestore(TABLE).outcome()).isEqualTo(TableRecoveryService.Outcome.NONE);
        assertThat(b.tables.getTable(TABLE)).isEmpty();
        assertThat(original.getState().getGameId()).isNotNull();
    }

    @Test
    @DisplayName("A match whose stakes were partly refunded is not resumed; the rest are refunded")
    void unsafeRestoreRefunds() throws Exception {
        paidMatchOnA();
        a.recovery.flushOnce();
        crash("node-a");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("STAKE_P2")),
                Update.update("status", "ORPHAN_REFUNDED"), StakeEscrowDocument.class);

        assertThat(b.recovery.tryRestore(TABLE).outcome()).isEqualTo(TableRecoveryService.Outcome.NONE);

        assertThat(b.tables.getTable(TABLE)).isEmpty();
        assertThat(escrow("STAKE_P1").getStatus()).isEqualTo("ORPHAN_REFUNDED");
        assertThat(balance("P1")).isEqualByComparingTo("1000");
        assertThat(mongo.findById(TABLE, TableSnapshotDocument.class)).isNull();
    }

    @Test
    @DisplayName("While the host still looks alive the player is asked to retry, and the player lookup finds the table")
    void ownerStillAlive() throws Exception {
        paidMatchOnA();
        a.recovery.flushOnce();

        assertThat(b.recovery.tryRestore(TABLE).outcome()).isEqualTo(TableRecoveryService.Outcome.OWNER_ALIVE);
        assertThat(b.recovery.tryRestoreForPlayer("P2").outcome()).isEqualTo(TableRecoveryService.Outcome.OWNER_ALIVE);

        crash("node-a");
        assertThat(b.recovery.tryRestoreForPlayer("P2").outcome()).isEqualTo(TableRecoveryService.Outcome.RESTORED);
    }

    @Test
    @DisplayName("A stopping node hands its match over; another node resumes it at once, with no refund")
    void deployHandoff() throws Exception {
        TableActor original = paidMatchOnA();
        String gameId = original.getState().getGameId();
        String onTurn = original.getState().getTurnState().getCurrentPlayerId();
        original.processCommand(new DrawCommand("d1", gameId, onTurn, DrawSource.CLOSED_DECK, Instant.now()), "draw");

        assertThat(a.recovery.handOffLiveTables("test")).isEqualTo(1);
        a.recovery.flushOnce();

        assertThat(a.tables.getTable(TABLE)).isEmpty();
        assertThat(snapshot().isHandedOff()).isTrue();
        assertThat(escrow("STAKE_P1").getStatus()).isEqualTo("OPEN");
        assertThat(balance("P1")).isEqualByComparingTo("900");

        // Node A is still alive (draining), yet the released match is taken over immediately.
        assertThat(b.recovery.tryRestore(TABLE).outcome()).isEqualTo(TableRecoveryService.Outcome.RESTORED);
        TableActor resumed = b.tables.getTable(TABLE).orElseThrow();
        assertThat(resumed.getState().requirePlayer(onTurn).getHandSnapshot())
                .isEqualTo(original.getState().requirePlayer(onTurn).getHandSnapshot());
        assertThat(resumed.getState().getTurnState().getPhase()).isEqualTo(TurnPhase.AWAITING_DISCARD);
        assertThat(escrow("STAKE_P1").getHolderNode()).isEqualTo("node-b");
        assertThat(snapshot().getOwnerNode()).isEqualTo("node-b");
        assertThat(snapshot().isHandedOff()).isFalse();
    }

    /** Two humans, 100 chips each escrowed by node A, cards dealt. */
    private TableActor paidMatchOnA() {
        TableActor actor = a.tables.getOrCreateTable(TABLE, null);
        actor.setExpectedPlayers(2);
        actor.setStakeTier(100);
        String gameId = actor.getState().getGameId();
        for (String p : new String[]{"P1", "P2"}) {
            a.escrows.open("STAKE_" + p, p, 100, TABLE, gameId);
            wallet.debit(p, BigDecimal.valueOf(100), "GAME_ENTRY_STAKE", "STAKE_" + p, gameId, "test", Map.of());
        }
        Instant now = Instant.now();
        actor.processCommand(new JoinCommand("j1", gameId, "P1", "One", 0, false, now), "j1");
        actor.processCommand(new JoinCommand("j2", gameId, "P2", "Two", 1, false, now), "j2");
        actor.processCommand(new ReadyCommand("r1", gameId, "P1", now), "r1");
        actor.processCommand(new ReadyCommand("r2", gameId, "P2", now), "r2");
        actor.processCommand(new StartGameCommand("s", gameId, "P1", now), "start");
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(actor.getState().getPlayers()).extracting(PlayerState::getPlayerId).containsExactly("P1", "P2");
        return actor;
    }

    private void crash(String nodeId) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(nodeId)),
                Update.update("heartbeatAt", Instant.now().minusSeconds(600)), "cluster_nodes");
        b.cluster.heartbeat();
    }

    private void ageEscrows() {
        mongo.updateMulti(new Query(), Update.update("updatedAt", Instant.now().minusSeconds(600)), StakeEscrowDocument.class);
    }

    private StakeEscrowDocument escrow(String id) {
        return mongo.findById(id, StakeEscrowDocument.class);
    }

    private TableSnapshotDocument snapshot() {
        return mongo.findById(TABLE, TableSnapshotDocument.class);
    }

    private BigDecimal balance(String playerId) {
        return wallet.getOrCreateWallet(playerId).getBalance();
    }
}
