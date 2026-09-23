package com.rummy.gameservice.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.routing.PlayerPresenceService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MatchmakingServiceTest {

    private TableManager tableManager;
    private TableRoutingRegistry routingRegistry;
    private PlayerPresenceService presenceService;
    private MatchmakingService matchmakingService;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        tableManager = new TableManager(mapper, null);
        routingRegistry = new TableRoutingRegistry(null, "srv-test-node");
        presenceService = new PlayerPresenceService(null);
        matchmakingService = new MatchmakingService(tableManager, routingRegistry, presenceService, 100, 2000);
    }

    @AfterEach
    void tearDown() {
        matchmakingService.shutdown();
        tableManager.shutdown();
    }

    @Test
    @DisplayName("Should match two real human players in queue into the same table")
    void shouldMatchTwoPlayers() {
        MatchmakingRequest req1 = new MatchmakingRequest("USR_1", "Player One", "INDIAN_POINTS", 100, 2, false);
        MatchmakingRequest req2 = new MatchmakingRequest("USR_2", "Player Two", "INDIAN_POINTS", 100, 2, false);

        MatchmakingTicket t1 = matchmakingService.enqueue(req1);
        MatchmakingTicket t2 = matchmakingService.enqueue(req2);

        assertThat(t1.getStatus()).isEqualTo(MatchmakingTicket.Status.QUEUED);
        assertThat(t2.getStatus()).isEqualTo(MatchmakingTicket.Status.QUEUED);

        // Process queue directly
        matchmakingService.processQueues();

        assertThat(t1.getStatus()).isEqualTo(MatchmakingTicket.Status.MATCHED);
        assertThat(t2.getStatus()).isEqualTo(MatchmakingTicket.Status.MATCHED);
        assertThat(t1.getMatchedTableId()).isNotNull();
        assertThat(t1.getMatchedTableId()).isEqualTo(t2.getMatchedTableId());

        // Verify routing registered table
        assertThat(routingRegistry.getTableForPlayer("USR_1")).isPresent().contains(t1.getMatchedTableId());
        assertThat(routingRegistry.getTableForPlayer("USR_2")).isPresent().contains(t1.getMatchedTableId());
    }

    @Test
    @DisplayName("Should fallback to AI bot when timeout expires with allowAiFallback=true")
    void shouldFallbackToAiOnTimeout() throws InterruptedException {
        MatchmakingRequest req = new MatchmakingRequest("USR_SOLO", "Solo Human", "INDIAN_POINTS", 100, 2, true);
        MatchmakingTicket ticket = matchmakingService.enqueue(req);

        // Wait for AI fallback timeout (100ms)
        Thread.sleep(150);

        matchmakingService.processQueues();

        assertThat(ticket.getStatus()).isEqualTo(MatchmakingTicket.Status.MATCHED);
        assertThat(ticket.getMatchedTableId()).isNotNull();

        // Verify table was created and has bot
        var table = tableManager.getTable(ticket.getMatchedTableId());
        assertThat(table).isPresent();
        assertThat(table.get().getState().getPlayers()).hasSize(1); // 1 bot added
        assertThat(table.get().getState().getPlayers().get(0).isBot()).isTrue();
    }

    @Test
    @DisplayName("6-player table with one human fills the other five seats with bots")
    void shouldFillSixSeatTableWithFiveBots() throws InterruptedException {
        MatchmakingRequest req = new MatchmakingRequest("USR_SOLO6", "Solo Human", "INDIAN_POINTS", 100, 6, true);
        MatchmakingTicket ticket = matchmakingService.enqueue(req);

        Thread.sleep(150);
        matchmakingService.processQueues();

        assertThat(ticket.getStatus()).isEqualTo(MatchmakingTicket.Status.MATCHED);
        var table = tableManager.getTable(ticket.getMatchedTableId());
        assertThat(table).isPresent();
        assertThat(table.get().getState().getPlayers()).hasSize(5);
        assertThat(table.get().getState().getPlayers()).allMatch(p -> p.isBot());
    }

    @Test
    @DisplayName("6-player table with two humans fills the other four seats with bots")
    void shouldFillSixSeatTableWhenTwoHumansAreWaiting() throws InterruptedException {
        MatchmakingRequest req1 = new MatchmakingRequest("USR_A", "Player A", "INDIAN_POINTS", 100, 6, true);
        MatchmakingRequest req2 = new MatchmakingRequest("USR_B", "Player B", "INDIAN_POINTS", 100, 6, true);
        MatchmakingTicket t1 = matchmakingService.enqueue(req1);
        MatchmakingTicket t2 = matchmakingService.enqueue(req2);

        Thread.sleep(150);
        matchmakingService.processQueues();

        assertThat(t1.getStatus()).isEqualTo(MatchmakingTicket.Status.MATCHED);
        assertThat(t2.getStatus()).isEqualTo(MatchmakingTicket.Status.MATCHED);
        assertThat(t1.getMatchedTableId()).isEqualTo(t2.getMatchedTableId());

        var table = tableManager.getTable(t1.getMatchedTableId());
        assertThat(table).isPresent();
        assertThat(table.get().getState().getPlayers()).hasSize(4);
        assertThat(table.get().getState().getPlayers()).allMatch(p -> p.isBot());
    }

    @Test
    @DisplayName("Should cancel ticket successfully")
    void shouldCancelTicket() {
        MatchmakingRequest req = new MatchmakingRequest("USR_CANCEL", "Canceller", "INDIAN_POINTS", 100, 2, false);
        MatchmakingTicket ticket = matchmakingService.enqueue(req);

        boolean cancelled = matchmakingService.cancelTicket(ticket.getTicketId());
        assertThat(cancelled).isTrue();
        assertThat(ticket.getStatus()).isEqualTo(MatchmakingTicket.Status.CANCELLED);
    }
}
