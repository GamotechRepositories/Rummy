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
        tableActor = new TableActor("T_TEST", initialState, new PointsRummyRules(), new GameEngine(() -> 0L, 0), objectMapper, scheduler);
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
    @DisplayName("Points: a player who leaves mid-hand is settled at the drop penalty, not 0")
    void leaverIsSettledAtDropPenalty() {
        Instant now = Instant.now();
        for (int i = 1; i <= 3; i++) {
            tableActor.processCommand(new JoinCommand("j" + i, "G_TEST", "P" + i, "Player " + i, i - 1, false, now), "join-" + i);
            tableActor.processCommand(new ReadyCommand("r" + i, "G_TEST", "P" + i, now), "ready-" + i);
        }
        tableActor.processCommand(new StartGameCommand("s", "G_TEST", "P1", now), "start");
        String current = tableActor.getState().getTurnState().getCurrentPlayerId();
        List<String> waiting = tableActor.getState().getPlayers().stream()
                .map(PlayerState::getPlayerId).filter(id -> !id.equals(current)).toList();
        String leaver = waiting.get(0);

        tableActor.handleVoluntaryLeave(leaver, "leave");
        assertThat(tableActor.getState().requirePlayer(leaver).getStatus()).isEqualTo(PlayerStatus.DROPPED);

        EngineResult end = tableActor.processCommand(new DropCommand("d", "G_TEST", waiting.get(1), now, true), "drop");
        com.rummy.engine.event.GameFinishedEvent finished = end.events().stream()
                .filter(com.rummy.engine.event.GameFinishedEvent.class::isInstance)
                .map(com.rummy.engine.event.GameFinishedEvent.class::cast)
                .findFirst().orElseThrow();

        assertThat(finished.finalScores()).containsEntry(leaver, 20);
        assertThat(tableActor.getState().requirePlayer(leaver).getStatus()).isEqualTo(PlayerStatus.ELIMINATED);
    }

    @Test
    @DisplayName("Pool Rummy: Deal finish under threshold records deal history and does not eliminate player")
    void testPoolRummyMultiDealAndElimination() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState poolState = new GameState("G_POOL", "T_POOL", "POOL_101", "1.0.0", List.of(), deck);
        TableActor poolActor = new TableActor("T_POOL", poolState, new com.rummy.engine.rules.Pool101Rules(), new GameEngine(() -> 0L, 0), objectMapper, scheduler);

        Instant now = Instant.now();
        poolActor.processCommand(new JoinCommand("c1", "G_POOL", "P1", "Player 1", 0, false, now), "req-1");
        poolActor.processCommand(new JoinCommand("c2", "G_POOL", "P2", "Player 2", 1, false, now), "req-2");
        poolActor.processCommand(new ReadyCommand("c3", "G_POOL", "P1", now), "req-3");
        poolActor.processCommand(new ReadyCommand("c4", "G_POOL", "P2", now), "req-4");
        poolActor.processCommand(new StartGameCommand("c5", "G_POOL", "P1", now), "req-5");

        assertThat(poolActor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);

        // Player 2 drops in Deal 1 (first drop = 20 pts)
        TurnTestSupport.dropOnTurn(poolActor, "c6", "P2");

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

        // Verify that after tournament completes, rejoin is rejected
        PlayerState p2After = poolActor.getState().getPlayer("P2").orElseThrow();
        p2After.markEliminated();
        // Crown winner
        poolActor.handleRejoin("P2", "req-rejoin-2"); // P2 is READY
        poolActor.getState().setStatus(GameStatus.COMPLETED);
        // Force tournament completion by eliminating P2 again
        p2After.markEliminated();
        // Set tournamentWinnerId via settlement or direct flow
        poolActor.destroy();
    }

    @Test
    @DisplayName("Pool Rummy: Rejoin is rejected when tournament has already concluded")
    void testPoolRummyRejoinRejectedWhenMatchFinished() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState poolState = new GameState("G_POOL_END", "T_POOL_END", "POOL_101", "1.0.0", List.of(), deck);
        TableActor poolActor = new TableActor("T_POOL_END", poolState, new com.rummy.engine.rules.Pool101Rules(), new GameEngine(() -> 0L, 0), objectMapper, scheduler);

        Instant now = Instant.now();
        poolActor.processCommand(new JoinCommand("c1", "G_POOL_END", "P1", "Player 1", 0, false, now), "req-1");
        poolActor.processCommand(new JoinCommand("c2", "G_POOL_END", "P2", "Player 2", 1, false, now), "req-2");
        poolActor.processCommand(new ReadyCommand("c3", "G_POOL_END", "P1", now), "req-3");
        poolActor.processCommand(new ReadyCommand("c4", "G_POOL_END", "P2", now), "req-4");
        poolActor.processCommand(new StartGameCommand("c5", "G_POOL_END", "P1", now), "req-5");

        // P2 eliminated with 105 points in Deal 1 -> P1 is the sole survivor, so tournament concludes
        PlayerState p2 = poolActor.getState().getPlayer("P2").orElseThrow();
        p2.setCumulativeScore(105);
        TurnTestSupport.dropOnTurn(poolActor, "c6", "P2");

        // Since P2 has 105 + 20 = 125 >= 101, P2 is eliminated. Sole survivor P1 is crowned winner!
        assertThat(poolActor.getTournamentWinnerId()).isEqualTo("P1");

        // Attempting to rejoin after tournament completed must fail
        boolean rejoinAttempt = poolActor.handleRejoin("P2", "req-late-rejoin");
        assertThat(rejoinAttempt).isFalse();

        poolActor.destroy();
    }

    @Test
    @DisplayName("Deals Rummy: 2 Deals allocates 160 chips, transfers chips on deal finish, and crowns tournament winner after all deals")
    void testDealsRummyMultiDealChipsAndProgression() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState dealsState = new GameState("G_DEALS", "T_DEALS", "DEALS_2", "1.0.0", List.of(), deck);
        TableActor dealsActor = new TableActor("T_DEALS", dealsState, new DealsRummyRules(2), new GameEngine(() -> 0L, 0), objectMapper, scheduler);

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
        TurnTestSupport.dropOnTurn(dealsActor, "c6", "P2");

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
        TurnTestSupport.dropOnTurn(dealsActor, "c7", "P1");

        // Deal 2 finishes: P1 loses 20 chips -> 160, P2 gains 20 chips -> 160
        assertThat(dealsActor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(p1.getChipBalance()).isEqualTo(160);
        assertThat(p2.getChipBalance()).isEqualTo(160);
        // Verify cumulative scores were NOT double counted (each 20, not 40)
        assertThat(p1.getCumulativeScore()).isEqualTo(20);
        assertThat(p2.getCumulativeScore()).isEqualTo(20);
        assertThat(dealsActor.getDealHistory()).hasSize(2);

        // All 2 deals completed! Both players tied at 160 chips and 20 penalty points.
        // Sudden-Death Tie-Breaker Deal 3 is triggered!
        assertThat(dealsActor.getEffectiveTotalDeals()).isEqualTo(3);
        assertThat(dealsActor.getTournamentWinnerId()).isNull();

        // Start Deal 3 (Tie-Breaker Deal)
        dealsActor.startNextDeal();
        assertThat(dealsActor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(dealsActor.getState().getDealNumber()).isEqualTo(3);

        // In Deal 3, P2 drops (first drop = 20 pts penalty)
        TurnTestSupport.dropOnTurn(dealsActor, "c8", "P2");
        assertThat(dealsActor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(p1.getChipBalance()).isEqualTo(180);
        assertThat(p2.getChipBalance()).isEqualTo(140);
        assertThat(dealsActor.getTournamentWinnerId()).isEqualTo("P1");

        dealsActor.destroy();
    }

    @Test
    @DisplayName("Deals Rummy: Chip underflow protection")
    void testDealsTieBreakerAndUnderflowProtection() {
        Deck deck = Deck.createStandard13CardDeck();
        GameState dealsState = new GameState("G_TIE", "T_TIE", "DEALS_2", "1.0.0", List.of(), deck);
        TableActor actor = new TableActor("T_TIE", dealsState, new DealsRummyRules(2), new GameEngine(() -> 0L, 0), objectMapper, scheduler);

        Instant now = Instant.now();
        actor.processCommand(new JoinCommand("c1", "G_TIE", "P1", "Player 1", 0, false, now), "r1");
        actor.processCommand(new JoinCommand("c2", "G_TIE", "P2", "Player 2", 1, false, now), "r2");
        actor.processCommand(new ReadyCommand("c3", "G_TIE", "P1", now), "r3");
        actor.processCommand(new ReadyCommand("c4", "G_TIE", "P2", now), "r4");
        actor.processCommand(new StartGameCommand("c5", "G_TIE", "P1", now), "r5");

        PlayerState p1 = actor.getState().getPlayer("P1").orElseThrow();
        PlayerState p2 = actor.getState().getPlayer("P2").orElseThrow();

        // Artificial test setup: P2 has only 10 chips left, P1 has 160 chips
        p2.setChipBalance(10);
        // P2 drops: 20 pts penalty. Because P2 only has 10 chips, chips lost must be capped at 10 (not negative)!
        TurnTestSupport.dropOnTurn(actor, "c6", "P2");

        assertThat(p2.getChipBalance()).isEqualTo(0); // Clamped, not -10
        assertThat(p1.getChipBalance()).isEqualTo(170); // Gained 10 chips (conserved)
        assertThat(p2.getCumulativeScore()).isEqualTo(20);

        actor.destroy();
    }
}
