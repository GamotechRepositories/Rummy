package com.rummy.gameservice.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.engine.EngineResult;
import com.rummy.engine.GameEngine;
import com.rummy.engine.command.*;
import com.rummy.engine.model.*;
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
}
