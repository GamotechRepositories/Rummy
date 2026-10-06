package com.rummy.gameservice.wallet;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.persistence.document.StakeEscrowDocument;
import com.rummy.gameservice.routing.TableRoutingRegistry;
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
@DisplayName("Crash refunds via node heartbeats (MongoDB)")
class CrashRefundMongoTest {

    private MongoClient client;
    private MongoTemplate mongo;
    private String dbName;
    private WalletService wallet;
    private ClusterNodeService survivorNode;
    private ClusterNodeService crashedNode;
    private StakeEscrowService survivor;
    private StakeEscrowService crashed;

    @BeforeEach
    void setUp() {
        client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=1500&connectTimeoutMS=1500");
        try {
            client.getDatabase("admin").runCommand(new Document("ping", 1));
        } catch (Exception e) {
            client.close();
            assumeTrue(false, "local MongoDB not available");
        }
        dbName = "rummy_crash_test_" + UUID.randomUUID().toString().substring(0, 8);
        mongo = new MongoTemplate(client, dbName);
        wallet = new WalletService();
        survivorNode = new ClusterNodeService(new TableRoutingRegistry(null, "node-survivor"), mongo, 45);
        crashedNode = new ClusterNodeService(new TableRoutingRegistry(null, "node-crashed"), mongo, 45);
        survivor = new StakeEscrowService(mongo, wallet, survivorNode);
        crashed = new StakeEscrowService(mongo, wallet, crashedNode);
        survivorNode.heartbeat();
        crashedNode.heartbeat();
    }

    @AfterEach
    void tearDown() {
        if (mongo != null) {
            client.getDatabase(dbName).drop();
            client.close();
        }
    }

    @Test
    @DisplayName("Live nodes are recognised; a node that stopped heartbeating is not")
    void liveness() {
        assertThat(survivorNode.isAlive("node-crashed")).isTrue();

        expireHeartbeat("node-crashed");

        assertThat(survivorNode.isAlive("node-crashed")).isFalse();
        assertThat(survivorNode.aliveNodes()).containsExactly("node-survivor");
    }

    @Test
    @DisplayName("Stakes held by a crashed node are refunded exactly once by a survivor")
    void orphanedStakesRefundedOnce() {
        stakeOnCrashedNode("STAKE_O1", "P1", "G_CRASH");
        stakeOnCrashedNode("STAKE_O2", "P2", "G_CRASH");
        assertThat(balance("P1")).isEqualByComparingTo("900");

        assertThat(survivor.refundOrphans()).as("node still alive").isZero();
        expireHeartbeat("node-crashed");
        ageEscrows();

        assertThat(survivor.refundOrphans()).isEqualTo(2);
        assertThat(survivor.refundOrphans()).isZero();
        assertThat(balance("P1")).isEqualByComparingTo("1000");
        assertThat(balance("P2")).isEqualByComparingTo("1000");
        assertThat(survivor.status("STAKE_O1")).contains(StakeEscrowService.Status.ORPHAN_REFUNDED);
    }

    @Test
    @DisplayName("A node presumed dead cannot pay out a game whose stakes were already refunded")
    void settlementFencedAfterOrphanRefund() {
        stakeOnCrashedNode("STAKE_F1", "P1", "G_FENCE");
        expireHeartbeat("node-crashed");
        ageEscrows();
        survivor.refundOrphans();
        stakeOnCrashedNode("STAKE_F2", "P2", "G_FENCE");

        assertThat(crashed.beginSettlement("G_FENCE")).isFalse();
        assertThat(balance("P1")).isEqualByComparingTo("1000");
        assertThat(balance("P2")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("A node that left cleanly is treated as gone immediately")
    void leaveDeregisters() {
        crashedNode.leave();

        assertThat(survivorNode.isAlive("node-crashed")).isFalse();
    }

    private void stakeOnCrashedNode(String escrowId, String playerId, String gameId) {
        crashed.open(escrowId, playerId, 100, "T_" + gameId, gameId);
        wallet.debit(playerId, BigDecimal.valueOf(100), "GAME_ENTRY_STAKE", escrowId, gameId, "test", Map.of());
    }

    private void expireHeartbeat(String nodeId) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(nodeId)),
                Update.update("heartbeatAt", Instant.now().minusSeconds(600)), "cluster_nodes");
    }

    private void ageEscrows() {
        mongo.updateMulti(new Query(), Update.update("updatedAt", Instant.now().minusSeconds(600)), StakeEscrowDocument.class);
    }

    private BigDecimal balance(String playerId) {
        return wallet.getOrCreateWallet(playerId).getFreePlayBalance();
    }
}
