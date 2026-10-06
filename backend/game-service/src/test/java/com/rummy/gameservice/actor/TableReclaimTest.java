package com.rummy.gameservice.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.engine.command.DropCommand;
import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.command.ReadyCommand;
import com.rummy.engine.command.StartGameCommand;
import com.rummy.engine.model.GameStatus;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.wallet.StakeEscrowService;
import com.rummy.gameservice.wallet.TestFunding;
import com.rummy.gameservice.wallet.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Idle table reclamation")
class TableReclaimTest {

    private TableRoutingRegistry routing;
    private TableManager manager;

    @BeforeEach
    void setUp() {
        routing = new TableRoutingRegistry(null, "node-a");
        manager = new TableManager(new ObjectMapper().registerModule(new JavaTimeModule()),
                null, null, null, null, routing, 3, 180, 600);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    @DisplayName("A finished match is kept for the grace period, then removed with its routing")
    void finishedMatchReclaimedAfterGrace() {
        TableActor actor = finishedPointsTable("TBL_DONE");
        routing.registerTableOwnership("TBL_DONE");
        routing.registerPlayerTable("P1", "TBL_DONE");
        routing.registerPlayerTable("P2", "TBL_OTHER");
        Instant now = Instant.now();

        assertThat(manager.reclaimIdleTables(now.plusSeconds(60))).isZero();
        assertThat(manager.reclaimIdleTables(now.plusSeconds(181))).isEqualTo(1);

        assertThat(manager.getTable("TBL_DONE")).isEmpty();
        assertThat(routing.getServerForTable("TBL_DONE")).isEmpty();
        assertThat(routing.getTableForPlayer("P1")).isEmpty();
        assertThat(routing.getTableForPlayer("P2")).contains("TBL_OTHER");
        assertThat(actor.processCommand(new ReadyCommand("x", "g", "P1", now), "late").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("A live deal is never reclaimed, however old")
    void liveDealNeverReclaimed() {
        TableActor actor = seatedPointsTable("TBL_LIVE");
        actor.processCommand(new StartGameCommand("s", actor.getState().getGameId(), "P1", Instant.now()), "start");
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);

        assertThat(manager.reclaimIdleTables(Instant.now().plus(Duration.ofDays(1)))).isZero();
        assertThat(manager.getTable("TBL_LIVE")).isPresent();
    }

    @Test
    @DisplayName("An empty waiting room is dropped only after nobody has been connected for the idle window")
    void abandonedWaitingRoomReclaimed() {
        TableActor actor = manager.getOrCreateTable("TBL_EMPTY", null);
        WebSocketSession session = openSession();
        actor.registerSession("P1", session);
        Instant now = Instant.now();

        assertThat(manager.reclaimIdleTables(now.plusSeconds(3600))).isZero();

        actor.unregisterSession("P1", session);
        assertThat(manager.reclaimIdleTables(now.plusSeconds(300))).isZero();
        assertThat(manager.reclaimIdleTables(now.plusSeconds(601))).isEqualTo(1);
    }

    @Test
    @DisplayName("An open matchmaking lobby is left to matchmaking")
    void openLobbyNotReclaimed() {
        TableActor actor = manager.getOrCreateTable("TBL_MM_lobby", null);
        actor.setExpectedPlayers(6);
        actor.armDealHold(Instant.now().plusSeconds(15));

        assertThat(manager.reclaimIdleTables(Instant.now().plus(Duration.ofDays(1)))).isZero();
    }

    @Test
    @DisplayName("A matched table nobody joined is dropped and its escrowed stakes refunded")
    void noShowTableRefunded() {
        WalletService wallet = new WalletService();
        TestFunding.fund(wallet, "P1");
        StakeEscrowService escrows = new StakeEscrowService(null, wallet, new ClusterNodeService(routing, null, 45));
        manager.setEscrows(escrows);
        TableActor actor = manager.getOrCreateTable("TBL_MM_noshow", null);
        actor.setExpectedPlayers(2);
        actor.setStakeTier(100);
        escrows.open("STAKE_NS", "P1", 100, "TBL_MM_noshow", actor.getState().getGameId());
        wallet.debit("P1", BigDecimal.valueOf(100), "GAME_ENTRY_STAKE", "STAKE_NS", null, "test", Map.of());

        assertThat(manager.reclaimIdleTables(Instant.now().plusSeconds(601))).isEqualTo(1);

        assertThat(wallet.getOrCreateWallet("P1").getBalance()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("A stale socket closing after a reconnect does not detach the new socket")
    void staleCloseKeepsNewSession() {
        TableActor actor = seatedPointsTable("TBL_RECONNECT");
        WebSocketSession oldSession = openSession();
        WebSocketSession newSession = openSession();
        actor.registerSession("P1", oldSession);
        actor.registerSession("P1", newSession);

        actor.unregisterSession("P1", oldSession);

        assertThat(manager.reclaimIdleTables(Instant.now().plusSeconds(3600))).isZero();
        actor.unregisterSession("P1", newSession);
        assertThat(manager.reclaimIdleTables(Instant.now().plusSeconds(3600))).isEqualTo(1);
    }

    @Test
    @DisplayName("Node refuses new tables beyond its limit")
    void tableCapacity() {
        manager.getOrCreateTable("T1", null);
        manager.getOrCreateTable("T2", null);
        assertThat(manager.hasCapacityForNewTable()).isTrue();
        manager.getOrCreateTable("T3", null);
        assertThat(manager.hasCapacityForNewTable()).isFalse();
    }

    private TableActor seatedPointsTable(String tableId) {
        TableActor actor = manager.getOrCreateTable(tableId, null);
        String gameId = actor.getState().getGameId();
        Instant now = Instant.now();
        actor.processCommand(new JoinCommand("j1", gameId, "P1", "One", 0, false, now), "j1");
        actor.processCommand(new JoinCommand("j2", gameId, "P2", "Two", 1, false, now), "j2");
        actor.processCommand(new ReadyCommand("r1", gameId, "P1", now), "r1");
        actor.processCommand(new ReadyCommand("r2", gameId, "P2", now), "r2");
        return actor;
    }

    private TableActor finishedPointsTable(String tableId) {
        TableActor actor = seatedPointsTable(tableId);
        String gameId = actor.getState().getGameId();
        actor.processCommand(new StartGameCommand("s", gameId, "P1", Instant.now()), "start");
        TurnTestSupport.dropOnTurn(actor, "d", "P2");
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        return actor;
    }

    private static WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        return session;
    }
}
