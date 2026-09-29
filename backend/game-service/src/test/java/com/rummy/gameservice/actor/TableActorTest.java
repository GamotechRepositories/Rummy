package com.rummy.gameservice.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.engine.EngineResult;
import com.rummy.engine.GameEngine;
import com.rummy.engine.command.*;
import com.rummy.engine.model.*;
import com.rummy.engine.rules.DealsRummyRules;
import com.rummy.engine.rules.PointsRummyRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TableActorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ScheduledExecutorService scheduler;
    private TableActor tableActor;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        Deck deck = Deck.createStandard13CardDeck();
        GameState initialState = new GameState("G_TEST", "T_TEST", "POINTS_13", "1.0.0", List.of(), deck);
        tableActor = new TableActor("T_TEST", initialState, new PointsRummyRules(), new GameEngine(), objectMapper, scheduler);
    }

    @AfterEach
    void tearDown() {
        tableActor.destroy();
        scheduler.shutdownNow();
    }

    @Test
    @DisplayName("TableActor should sequentially process Join, Ready, Start, and broadcast to registered sessions")
    void testTableActorWorkflow() throws Exception {
        WebSocketSession sessionAlice = mock(WebSocketSession.class);
        when(sessionAlice.isOpen()).thenReturn(true);

        WebSocketSession sessionBob = mock(WebSocketSession.class);
        when(sessionBob.isOpen()).thenReturn(true);

        Instant now = Instant.now();

        // 1. Join Alice & Bob
        tableActor.processCommand(new JoinCommand("c1", "G_TEST", "ALICE", "Alice", 0, false, now), "req-1");
        tableActor.processCommand(new JoinCommand("c2", "G_TEST", "BOB", "Bob", 1, false, now), "req-2");

        tableActor.registerSession("ALICE", sessionAlice);
        tableActor.registerSession("BOB", sessionBob);

        // 2. Ready up
        tableActor.processCommand(new ReadyCommand("c3", "G_TEST", "ALICE", now), "req-3");
        tableActor.processCommand(new ReadyCommand("c4", "G_TEST", "BOB", now), "req-4");

        // 3. Start Game
        EngineResult startResult = tableActor.processCommand(new StartGameCommand("c5", "G_TEST", "ALICE", now), "req-5");

        assertThat(startResult.isSuccess()).isTrue();
        assertThat(tableActor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);

        // Verify that messages were sent to Alice and Bob sessions
        ArgumentCaptor<TextMessage> msgCaptor = ArgumentCaptor.forClass(TextMessage.class);
        verify(sessionAlice, atLeastOnce()).sendMessage(msgCaptor.capture());
        verify(sessionBob, atLeastOnce()).sendMessage(any(TextMessage.class));

        List<TextMessage> sentToAlice = msgCaptor.getAllValues();
        assertThat(sentToAlice).isNotEmpty();

        // At least one message should be GAME_VIEW and another GAME_EVENT
        boolean hasGameView = sentToAlice.stream().anyMatch(m -> m.getPayload().contains("GAME_VIEW"));
        boolean hasGameEvent = sentToAlice.stream().anyMatch(m -> m.getPayload().contains("GAME_EVENT"));

        assertThat(hasGameView).isTrue();
        assertThat(hasGameEvent).isTrue();
    }

    @Test
    @DisplayName("Pool Rummy: Deal finish under threshold records deal history and does not eliminate player")
    void testPoolRummyMultiDealAndElimination() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState poolState = new GameState("G_POOL", "T_POOL", "POOL_101", "1.0.0", List.of(), deck);
        TableActor poolActor = new TableActor("T_POOL", poolState, new com.rummy.engine.rules.Pool101Rules(), new GameEngine(), objectMapper, scheduler);

        Instant now = Instant.now();
        poolActor.processCommand(new JoinCommand("c1", "G_POOL", "P1", "Player 1", 0, false, now), "req-1");
        poolActor.processCommand(new JoinCommand("c2", "G_POOL", "P2", "Player 2", 1, false, now), "req-2");
        poolActor.processCommand(new ReadyCommand("c3", "G_POOL", "P1", now), "req-3");
        poolActor.processCommand(new ReadyCommand("c4", "G_POOL", "P2", now), "req-4");
        poolActor.processCommand(new StartGameCommand("c5", "G_POOL", "P1", now), "req-5");

        assertThat(poolActor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);

        // Player 2 drops in Deal 1 (first drop = 20 pts)
        poolActor.processCommand(new DropCommand("c6", "G_POOL", "P2", now), "req-6");

        // Deal 1 is COMPLETED because P2 dropped, but P2's score is 20 < 101, so neither is ELIMINATED
        assertThat(poolActor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(poolActor.getState().getPlayer("P2").orElseThrow().getCumulativeScore()).isEqualTo(20);
        assertThat(poolActor.getState().getPlayer("P2").orElseThrow().getStatus()).isEqualTo(PlayerStatus.DROPPED);
        assertThat(poolActor.getDealHistory()).hasSize(1);
        assertThat(poolActor.getDealHistory().get(0).dealNumber()).isEqualTo(1);
        assertThat(poolActor.getDealHistory().get(0).winnerPlayerId()).isEqualTo("P1");

        // Test Re-join: If player P2 is eliminated with 105 points while P1 has 0 points
        PlayerState p2 = poolActor.getState().getPlayer("P2").orElseThrow();
        p2.setCumulativeScore(105);
        p2.markEliminated();

        // Attempt rejoin for P2: max active is P1 with 0 points (<= 79)
        boolean rejoinded = poolActor.handleRejoin("P2", "req-rejoin");
        assertThat(rejoinded).isTrue();
        assertThat(p2.getStatus()).isEqualTo(PlayerStatus.READY);
        assertThat(p2.getCumulativeScore()).isEqualTo(1); // 0 + 1 = 1

        poolActor.destroy();
    }

    @Test
    @DisplayName("Deals Rummy: 2 Deals allocates 160 chips, transfers chips on deal finish, and crowns tournament winner after all deals")
    void testDealsRummyMultiDealChipsAndProgression() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState dealsState = new GameState("G_DEALS", "T_DEALS", "DEALS_2", "1.0.0", List.of(), deck);
        TableActor dealsActor = new TableActor("T_DEALS", dealsState, new DealsRummyRules(2), new GameEngine(), objectMapper, scheduler);

        Instant now = Instant.now();
        dealsActor.processCommand(new JoinCommand("c1", "G_DEALS", "P1", "Player 1", 0, false, now), "req-1");
        dealsActor.processCommand(new JoinCommand("c2", "G_DEALS", "P2", "Player 2", 1, false, now), "req-2");
        dealsActor.processCommand(new ReadyCommand("c3", "G_DEALS", "P1", now), "req-3");
        dealsActor.processCommand(new ReadyCommand("c4", "G_DEALS", "P2", now), "req-4");

        // Start Deal 1
        dealsActor.processCommand(new StartGameCommand("c5", "G_DEALS", "P1", now), "req-5");
        assertThat(dealsActor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(dealsActor.getState().getDealNumber()).isEqualTo(1);

        // Initial chips: 2 deals * 80 = 160 chips per player
        PlayerState p1 = dealsActor.getState().getPlayer("P1").orElseThrow();
        PlayerState p2 = dealsActor.getState().getPlayer("P2").orElseThrow();
        assertThat(p1.getChipBalance()).isEqualTo(160);
        assertThat(p2.getChipBalance()).isEqualTo(160);

        // Player 2 drops in Deal 1 (first drop = 20 pts penalty)
        dealsActor.processCommand(new DropCommand("c6", "G_DEALS", "P2", now), "req-6");

        // Deal 1 COMPLETED
        assertThat(dealsActor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        // P2 lost 20 chips -> 140
        assertThat(p2.getChipBalance()).isEqualTo(140);
        // P1 won 20 chips -> 180
        assertThat(p1.getChipBalance()).isEqualTo(180);
        assertThat(dealsActor.getDealHistory()).hasSize(1);
        assertThat(dealsActor.getDealHistory().get(0).dealNumber()).isEqualTo(1);
        assertThat(dealsActor.getDealHistory().get(0).winnerPlayerId()).isEqualTo("P1");
        // Tournament is NOT yet finished because deal 1 < totalDeals 2
        assertThat(dealsActor.getTournamentWinnerId()).isNull();

        // Start Deal 2
        dealsActor.startNextDeal();
        assertThat(dealsActor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(dealsActor.getState().getDealNumber()).isEqualTo(2);

        // Chip balances are preserved across deals
        assertThat(p1.getChipBalance()).isEqualTo(180);
        assertThat(p2.getChipBalance()).isEqualTo(140);

        // In Deal 2, P1 drops (first drop = 20 pts penalty)
        dealsActor.processCommand(new DropCommand("c7", "G_DEALS", "P1", now), "req-7");

        // Deal 2 finishes: P1 loses 20 chips -> 160, P2 gains 20 chips -> 160
        assertThat(dealsActor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(p1.getChipBalance()).isEqualTo(160);
        assertThat(p2.getChipBalance()).isEqualTo(160);
        assertThat(dealsActor.getDealHistory()).hasSize(2);

        // All 2 deals completed! Tournament winner must be selected
        assertThat(dealsActor.getTournamentWinnerId()).isNotNull();

        dealsActor.destroy();
    }
}
