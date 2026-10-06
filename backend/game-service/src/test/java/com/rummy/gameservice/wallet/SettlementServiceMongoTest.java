package com.rummy.gameservice.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.persistence.document.SettlementJobDocument;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against a local MongoDB (skipped when none is listening on localhost:27017). */
@DisplayName("Async settlement: payouts are queued, retried, and taken over from dead nodes (MongoDB)")
class SettlementServiceMongoTest {

    private static final String GAME = "GAME_settle";
    private static final String TABLE = "TBL_settle";

    private MongoClient client;
    private MongoTemplate mongo;
    private String dbName;
    private FlakyWallet wallet;
    private Node a;
    private Node b;

    /** Wallet whose next {@code failures} payouts throw, as if the wallet database were unreachable. */
    private static final class FlakyWallet extends WalletService {
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public GameSettlementResult settleMatch(String gameId, String tableId, String rulesetId, BigDecimal stakeTier,
                                                String winnerPlayerId, Map<String, Integer> finalScores,
                                                List<String> allPlayerIds, Map<String, Integer> rejoinCounts) {
            calls.incrementAndGet();
            if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("wallet database unreachable");
            }
            return super.settleMatch(gameId, tableId, rulesetId, stakeTier, winnerPlayerId, finalScores, allPlayerIds, rejoinCounts);
        }
    }

    private final class Node {
        final ClusterNodeService cluster;
        final StakeEscrowService escrows;
        final SettlementJobStore jobs;
        final SettlementService settlements;

        Node(String id) {
            cluster = new ClusterNodeService(new TableRoutingRegistry(null, id), mongo, 45);
            escrows = new StakeEscrowService(mongo, wallet, cluster);
            jobs = new SettlementJobStore(mongo, cluster);
            escrows.setSettlementJobs(jobs);
            settlements = new SettlementService(wallet, jobs, cluster, new ObjectMapper(), 4);
            settlements.setEscrows(escrows);
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
        dbName = "rummy_settle_test_" + UUID.randomUUID().toString().substring(0, 8);
        mongo = new MongoTemplate(client, dbName);
        wallet = new FlakyWallet();
        a = new Node("node-a");
        b = new Node("node-b");
        for (String p : new String[]{"P1", "P2"}) {
            a.escrows.open("STAKE_" + p, p, 100, TABLE, GAME);
            wallet.debit(p, BigDecimal.valueOf(100), "GAME_ENTRY_STAKE", "STAKE_" + p, GAME, "test", Map.of());
        }
    }

    @AfterEach
    void tearDown() {
        if (mongo != null) {
            a.settlements.shutdown();
            b.settlements.shutdown();
            client.getDatabase(dbName).drop();
            client.close();
        }
    }

    @Test
    @DisplayName("The payout runs off the caller's thread and the table is told the result")
    void paysInBackground() throws Exception {
        CompletableFuture<GameSettlementResult> told = new CompletableFuture<>();

        a.settlements.submit(job(), listener(told));

        GameSettlementResult result = told.get(10, TimeUnit.SECONDS);
        assertThat(result.winnerPlayerId()).isEqualTo("P1");
        assertThat(escrow("STAKE_P1").getStatus()).isEqualTo("SETTLED");
        assertThat(jobDoc().getStatus()).isEqualTo("DONE");
        assertPaidOnce();
    }

    @Test
    @DisplayName("A failed payout keeps its stakes locked, is not refunded by the janitor, and is retried")
    void failedPayoutIsRetried() throws Exception {
        wallet.failures.set(1);

        a.settlements.submit(job(), listener(new CompletableFuture<>()));
        await(() -> "RETRY".equals(jobDoc().getStatus()));

        assertThat(escrow("STAKE_P1").getStatus()).isEqualTo("CLOSING");
        ageEscrows();
        assertThat(a.escrows.refundOrphans()).as("queued payout is not refunded").isZero();
        assertThat(balance("P1")).isEqualByComparingTo("900");

        makeDue();
        assertThat(a.settlements.retryDue()).isEqualTo(1);
        await(() -> "DONE".equals(jobDoc().getStatus()));

        assertThat(escrow("STAKE_P2").getStatus()).isEqualTo("SETTLED");
        assertPaidOnce();
    }

    @Test
    @DisplayName("A payout left behind by a dead node is finished by a survivor, exactly once")
    void deadNodePayoutTakenOver() throws Exception {
        wallet.failures.set(1);
        a.settlements.submit(job(), listener(new CompletableFuture<>()));
        await(() -> "RETRY".equals(jobDoc().getStatus()));
        makeDue();

        assertThat(b.settlements.retryDue()).as("holder still alive").isZero();

        mongo.updateFirst(Query.query(Criteria.where("_id").is("node-a")),
                Update.update("heartbeatAt", Instant.now().minusSeconds(600)), "cluster_nodes");
        b.cluster.heartbeat();
        assertThat(b.settlements.retryDue()).isEqualTo(1);
        await(() -> "DONE".equals(jobDoc().getStatus()));

        assertThat(jobDoc().getHolderNode()).isEqualTo("node-b");
        assertThat(escrow("STAKE_P1").getHolderNode()).isEqualTo("node-b");
        assertThat(escrow("STAKE_P1").getStatus()).isEqualTo("SETTLED");
        assertPaidOnce();

        makeDue();
        assertThat(b.settlements.retryDue()).as("finished payouts are not run again").isZero();
    }

    @Test
    @DisplayName("A match whose stakes were already refunded is not paid out")
    void refundedMatchCancelled() throws Exception {
        a.escrows.refundGame(GAME, "cancelled");
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>();

        a.settlements.submit(job(), new SettlementService.Listener() {
            @Override
            public void settled(GameSettlementResult result) {
                cancelled.complete(false);
            }

            @Override
            public void cancelled() {
                cancelled.complete(true);
            }
        });

        assertThat(cancelled.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(jobDoc().getStatus()).isEqualTo("CANCELLED");
        assertThat(balance("P1")).isEqualByComparingTo("1000");
        assertThat(balance("P2")).isEqualByComparingTo("1000");
    }

    private static SettlementService.Job job() {
        return new SettlementService.Job(GAME, TABLE, "POINTS_13", 100, "P1",
                Map.of("P1", 0, "P2", 80), List.of("P1", "P2"), Map.of());
    }

    private static SettlementService.Listener listener(CompletableFuture<GameSettlementResult> told) {
        return new SettlementService.Listener() {
            @Override
            public void settled(GameSettlementResult result) {
                told.complete(result);
            }

            @Override
            public void cancelled() {
                told.completeExceptionally(new AssertionError("payout cancelled"));
            }
        };
    }

    private void assertPaidOnce() {
        assertThat(balance("P2")).isEqualByComparingTo("900");
        assertThat(balance("P1")).isGreaterThan(new BigDecimal("1000"));
        assertThat(balance("P1").add(balance("P2"))).isLessThanOrEqualTo(new BigDecimal("2000"));
    }

    private void makeDue() {
        mongo.updateMulti(new Query(), Update.update("nextAttemptAt", Instant.now().minusSeconds(1)), SettlementJobDocument.class);
    }

    private void ageEscrows() {
        mongo.updateMulti(new Query(), Update.update("updatedAt", Instant.now().minusSeconds(600)), StakeEscrowDocument.class);
    }

    private static void await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!Boolean.TRUE.equals(condition.get())) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within 10s");
            }
            Thread.sleep(25);
        }
    }

    private SettlementJobDocument jobDoc() {
        SettlementJobDocument d = mongo.findById(GAME, SettlementJobDocument.class);
        return d != null ? d : new SettlementJobDocument();
    }

    private StakeEscrowDocument escrow(String id) {
        return mongo.findById(id, StakeEscrowDocument.class);
    }

    private BigDecimal balance(String playerId) {
        return wallet.getOrCreateWallet(playerId).getFreePlayBalance();
    }
}
