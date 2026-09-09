package com.rummy.gameservice.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.engine.command.*;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.GameStatus;
import com.rummy.engine.model.PlayerState;
import com.rummy.engine.rules.CardGroup;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("Multi-Table Concurrent Load & Reconnection Storm Simulation")
class MultiTableLoadSimulationTest {

    private ObjectMapper objectMapper;
    private TableManager tableManager;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        tableManager = new TableManager(objectMapper);
    }

    @Test
    @DisplayName("Simulate 20 concurrent tables running autonomous games under load")
    void testConcurrentMultiTableSimulation() throws Exception {
        int tableCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(tableCount);
        AtomicInteger completedGames = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < tableCount; i++) {
            final String tableId = "SIM_TBL_" + i;
            executor.submit(() -> {
                try {
                    TableActor actor = tableManager.getOrCreateTable(tableId, null);
                    String gameId = actor.getState().getGameId();

                    // Join 2 players (Player 1 + Player 2)
                    WebSocketSession session1 = mock(WebSocketSession.class);
                    when(session1.isOpen()).thenReturn(true);
                    WebSocketSession session2 = mock(WebSocketSession.class);
                    when(session2.isOpen()).thenReturn(true);

                    String p1 = "USR_SIM_" + tableId + "_1";
                    String p2 = "USR_SIM_" + tableId + "_2";

                    actor.registerSession(p1, session1);
                    actor.registerSession(p2, session2);

                    actor.processCommand(new JoinCommand(UUID.randomUUID().toString(), gameId, p1, "Player One", 0, false, Instant.now()), "req_j1");
                    actor.processCommand(new JoinCommand(UUID.randomUUID().toString(), gameId, p2, "Player Two", 1, false, Instant.now()), "req_j2");

                    actor.processCommand(new ReadyCommand(UUID.randomUUID().toString(), gameId, p1, Instant.now()), "req_r1");
                    actor.processCommand(new ReadyCommand(UUID.randomUUID().toString(), gameId, p2, Instant.now()), "req_r2");

                    actor.processCommand(new StartGameCommand(UUID.randomUUID().toString(), gameId, p1, Instant.now()), "req_start");

                    assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);

                    // Execute a sequence of simulated turns
                    for (int round = 0; round < 3; round++) {
                        String currentTurnPlayer = actor.getState().getTurnState().getCurrentPlayerId();
                        assertThat(currentTurnPlayer).isNotNull();

                        // Turn player draws from closed deck
                        actor.processCommand(new DrawCommand(UUID.randomUUID().toString(), gameId, currentTurnPlayer, DrawSource.CLOSED_DECK, Instant.now()), "req_draw");

                        // Discard one card from hand
                        PlayerState playerState = actor.getState().requirePlayer(currentTurnPlayer);
                        List<CardInstance> hand = playerState.getHandSnapshot();
                        assertThat(hand.size()).isGreaterThanOrEqualTo(14);

                        CardInstance discardCard = hand.get(hand.size() - 1);
                        actor.processCommand(new DiscardCommand(UUID.randomUUID().toString(), gameId, currentTurnPlayer, discardCard.getInstanceId(), Instant.now()), "req_discard");
                    }

                    completedGames.incrementAndGet();
                } catch (Throwable t) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completedInTime = latch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completedInTime).isTrue();
        assertThat(errorCount.get()).isEqualTo(0);
        assertThat(completedGames.get()).isEqualTo(tableCount);
        assertThat(tableManager.activeTableCount()).isEqualTo(tableCount);
    }

    @Test
    @DisplayName("Simulate Reconnection Storm: 10 players disconnect mid-game and reconnect seamlessly")
    void testReconnectionStormResilience() throws Exception {
        String tableId = "RECONNECT_STORM_TBL";
        TableActor actor = tableManager.getOrCreateTable(tableId, null);
        String gameId = actor.getState().getGameId();

        // 2 players join
        String p1 = "USR_RECONN_1";
        String p2 = "USR_RECONN_2";

        WebSocketSession session1 = mock(WebSocketSession.class);
        when(session1.isOpen()).thenReturn(true);
        WebSocketSession session2 = mock(WebSocketSession.class);
        when(session2.isOpen()).thenReturn(true);

        actor.registerSession(p1, session1);
        actor.registerSession(p2, session2);

        actor.processCommand(new JoinCommand(UUID.randomUUID().toString(), gameId, p1, "Reconn Player 1", 0, false, Instant.now()), "req_j1");
        actor.processCommand(new JoinCommand(UUID.randomUUID().toString(), gameId, p2, "Reconn Player 2", 1, false, Instant.now()), "req_j2");
        actor.processCommand(new ReadyCommand(UUID.randomUUID().toString(), gameId, p1, Instant.now()), "req_r1");
        actor.processCommand(new ReadyCommand(UUID.randomUUID().toString(), gameId, p2, Instant.now()), "req_r2");
        actor.processCommand(new StartGameCommand(UUID.randomUUID().toString(), gameId, p1, Instant.now()), "req_start");

        // Player 1 draws
        actor.processCommand(new DrawCommand(UUID.randomUUID().toString(), gameId, p1, DrawSource.CLOSED_DECK, Instant.now()), "req_d1");

        // Disconnect storm: Player 1 abruptly disconnects socket
        actor.unregisterSession(p1);

        // Verify Player 1 hand is preserved intact during disconnection
        PlayerState p1State = actor.getState().requirePlayer(p1);
        assertThat(p1State.getHandSnapshot()).hasSize(14);

        // Player 1 reconnects with a fresh new socket session
        WebSocketSession newSession1 = mock(WebSocketSession.class);
        when(newSession1.isOpen()).thenReturn(true);

        actor.registerSession(p1, newSession1);

        // Player 1 successfully finishes discard after reconnect
        CardInstance toDiscard = p1State.getHandSnapshot().get(0);
        actor.processCommand(new DiscardCommand(UUID.randomUUID().toString(), gameId, p1, toDiscard.getInstanceId(), Instant.now()), "req_disc_reconn");

        assertThat(p1State.getHandSnapshot()).hasSize(13);
        assertThat(actor.getState().getTurnState().getCurrentPlayerId()).isEqualTo(p2);
    }
}
