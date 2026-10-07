package com.rummy.gameservice.actor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.engine.GameEngine;
import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.command.ReadyCommand;
import com.rummy.engine.command.StartGameCommand;
import com.rummy.engine.model.Deck;
import com.rummy.engine.model.GameState;
import com.rummy.engine.model.GameStatus;
import com.rummy.engine.model.PlayerState;
import com.rummy.engine.model.PlayerStatus;
import com.rummy.engine.rules.Pool101Rules;
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

/** Pool Rummy between deals: leaving, rejoining and splitting the prize. */
class PoolBreakTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ScheduledExecutorService scheduler;
    private TableActor actor;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        if (actor != null) {
            actor.destroy();
        }
        scheduler.shutdownNow();
    }

    private void startPool(int players) {
        GameState state = new GameState("G_PB", "T_PB", "POOL_101", "1.0.0", List.of(), Deck.createStandard13CardDeck());
        actor = new TableActor("T_PB", state, new Pool101Rules(), new GameEngine(() -> 0L, 0), objectMapper, scheduler);
        Instant now = Instant.now();
        for (int i = 1; i <= players; i++) {
            actor.processCommand(new JoinCommand("j" + i, "G_PB", "P" + i, "Player " + i, i - 1, false, now), "join-" + i);
            actor.processCommand(new ReadyCommand("r" + i, "G_PB", "P" + i, now), "ready-" + i);
        }
        actor.processCommand(new StartGameCommand("s", "G_PB", "P1", now), "start");
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
    }

    private PlayerState player(String id) {
        return actor.getState().requirePlayer(id);
    }

    @Test
    @DisplayName("Last opponent leaves in the break: the player who stayed wins at once")
    void leaveInBreakEndsMatch() {
        startPool(2);
        TurnTestSupport.dropOnTurn(actor, "d2", "P2");
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(actor.getTournamentWinnerId()).isNull();

        actor.handleVoluntaryLeave("P2", "leave");

        assertThat(actor.getTournamentWinnerId()).isEqualTo("P1");
    }

    @Test
    @DisplayName("Players leaving one by one in the break: match ends only when one is left, and never crowns a leaver")
    void leaversNeverWin() {
        startPool(3);
        player("P1").setCumulativeScore(60);
        TurnTestSupport.dropOnTurn(actor, "d2", "P2");
        TurnTestSupport.dropOnTurn(actor, "d3", "P3");
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);

        actor.handleVoluntaryLeave("P2", "leave-2");
        assertThat(actor.getTournamentWinnerId()).isNull();

        actor.handleVoluntaryLeave("P3", "leave-3");
        assertThat(actor.getTournamentWinnerId()).isEqualTo("P1");
    }

    @Test
    @DisplayName("Rejoin is closed while a deal is being played")
    void noRejoinMidDeal() {
        startPool(3);
        player("P3").setCumulativeScore(105);
        player("P3").markEliminated();

        assertThat(actor.handleRejoin("P3", "rj")).isFalse();
        assertThat(player("P3").getStatus()).isEqualTo(PlayerStatus.ELIMINATED);
    }

    @Test
    @DisplayName("Rejoin works in the break, but not for a player who left")
    void rejoinInBreakOnlyForThoseWhoStayed() {
        startPool(4);
        player("P3").setCumulativeScore(100);
        player("P4").setCumulativeScore(100);
        TurnTestSupport.dropOnTurn(actor, "d3", "P3");
        TurnTestSupport.dropOnTurn(actor, "d4", "P4");
        TurnTestSupport.dropOnTurn(actor, "d2", "P2");
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(player("P3").getStatus()).isEqualTo(PlayerStatus.ELIMINATED);
        actor.handleVoluntaryLeave("P4", "leave");

        assertThat(actor.handleRejoin("P4", "rj4")).isFalse();
        assertThat(actor.handleRejoin("P3", "rj3")).isTrue();
        assertThat(player("P3").getCumulativeScore()).isEqualTo(21);
    }

    /** 4 players at ₹25: P4 is knocked out, P2 and P3 dropped once, so drops left are 5/4/4 and the prize is ₹85. */
    private void fourPlayerBreak() {
        startPool(4);
        actor.setStakeTier(25);
        player("P4").setCumulativeScore(100);
        TurnTestSupport.dropOnTurn(actor, "d4", "P4");
        TurnTestSupport.dropOnTurn(actor, "d2", "P2");
        TurnTestSupport.dropOnTurn(actor, "d3", "P3");
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(player("P4").getStatus()).isEqualTo(PlayerStatus.ELIMINATED);
        assertThat(actor.getTournamentWinnerId()).isNull();
    }

    @Test
    @DisplayName("Split: everyone accepts, the match ends and the largest share is the winner")
    void splitAccepted() throws Exception {
        fourPlayerBreak();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        actor.registerSession("P2", session);

        assertThat(actor.handleSplitRequest("P2", "split")).isTrue();
        JsonNode view = lastViewWithSplitRequest(session);
        assertThat(view.path("split").path("requestedBy").asText()).isEqualTo("P2");
        assertThat(view.path("split").path("payouts").path("P1").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(view.path("split").path("payouts").path("P2").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(view.path("prizePool").decimalValue()).isEqualByComparingTo("85.00");

        assertThat(actor.handleRejoin("P4", "rj")).isFalse();

        assertThat(actor.handleSplitResponse("P1", true, "a1")).isTrue();
        assertThat(actor.getTournamentWinnerId()).isNull();
        assertThat(actor.handleSplitResponse("P3", true, "a3")).isTrue();
        assertThat(actor.getTournamentWinnerId()).isEqualTo("P1");
    }

    @Test
    @DisplayName("Split: one refusal keeps the match going, and it cannot be asked again in the same break")
    void splitDeclined() {
        fourPlayerBreak();
        assertThat(actor.handleSplitRequest("P1", "split")).isTrue();
        assertThat(actor.handleSplitResponse("P3", false, "no")).isTrue();

        assertThat(actor.getTournamentWinnerId()).isNull();
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(actor.handleSplitRequest("P2", "again")).isFalse();
    }

    @Test
    @DisplayName("Split: not on a 2-player table, and not by a player who is out")
    void splitNotAllowed() {
        fourPlayerBreak();
        assertThat(actor.handleSplitRequest("P4", "out")).isFalse();
        actor.destroy();

        startPool(2);
        actor.setStakeTier(25);
        TurnTestSupport.dropOnTurn(actor, "d2", "P2");
        assertThat(actor.handleSplitRequest("P1", "two")).isFalse();
    }

    private JsonNode lastViewWithSplitRequest(WebSocketSession session) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(session, atLeast(0)).sendMessage(captor.capture());
            for (TextMessage m : captor.getAllValues()) {
                JsonNode msg = objectMapper.readTree(m.getPayload());
                JsonNode payload = msg.path("payload");
                if ("GAME_VIEW".equals(msg.path("type").asText()) && payload.path("split").hasNonNull("requestedBy")) {
                    return payload;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No view with the split request was sent");
    }
}
