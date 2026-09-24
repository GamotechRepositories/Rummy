package com.rummy.gameservice.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.model.GameStatus;
import com.rummy.engine.model.PlayerState;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.routing.PlayerPresenceService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        matchmakingService = new MatchmakingService(
                tableManager, routingRegistry, presenceService, 100, 2000, 80, 60, 350);
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
    @DisplayName("Lobby seats the human immediately and adds one bot after the human-only window")
    void shouldFallbackToAiOnTimeout() throws InterruptedException {
        MatchmakingRequest req = new MatchmakingRequest("USR_SOLO", "Solo Human", "INDIAN_POINTS", 100, 2, true);
        MatchmakingTicket ticket = matchmakingService.enqueue(req);

        assertThat(ticket.getStatus()).isEqualTo(MatchmakingTicket.Status.MATCHED);
        assertThat(ticket.getMatchedTableId()).isNotNull();

        TableActor table = tableManager.getTable(ticket.getMatchedTableId()).orElseThrow();
        assertThat(table.getState().getPlayers()).isEmpty();

        waitUntil(() -> table.getState().getPlayers().size() == 1, 400);
        assertThat(table.getState().getPlayers()).hasSize(1);
        assertThat(table.getState().getPlayers().get(0).isBot()).isTrue();

        seatHuman(table, "USR_SOLO", "Solo Human");
        waitUntil(() -> table.getState().getStatus() == GameStatus.IN_PROGRESS, 800);
        assertThat(table.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("6-player lobby does not fill every bot seat at once")
    void shouldFillSixSeatTableWithFiveBots() throws InterruptedException {
        MatchmakingRequest req = new MatchmakingRequest("USR_SOLO6", "Solo Human", "INDIAN_POINTS", 100, 6, true);
        MatchmakingTicket ticket = matchmakingService.enqueue(req);

        assertThat(ticket.getStatus()).isEqualTo(MatchmakingTicket.Status.MATCHED);
        TableActor table = tableManager.getTable(ticket.getMatchedTableId()).orElseThrow();
        assertThat(table.getState().getPlayers()).isEmpty();

        waitUntil(() -> table.getState().getPlayers().size() >= 1, 400);
        assertThat(table.getState().getPlayers().size()).isBetween(1, 2);
        assertThat(table.getState().getPlayers()).allMatch(PlayerState::isBot);
        assertThat(table.getState().getStatus()).isEqualTo(GameStatus.WAITING_FOR_PLAYERS);
    }

    @Test
    @DisplayName("A second human shares the open lobby table before the deal")
    void shouldFillSixSeatTableWhenTwoHumansAreWaiting() {
        MatchmakingRequest req1 = new MatchmakingRequest("USR_A", "Player A", "INDIAN_POINTS", 100, 6, true);
        MatchmakingRequest req2 = new MatchmakingRequest("USR_B", "Player B", "INDIAN_POINTS", 100, 6, true);
        MatchmakingTicket t1 = matchmakingService.enqueue(req1);
        MatchmakingTicket t2 = matchmakingService.enqueue(req2);

        assertThat(t1.getStatus()).isEqualTo(MatchmakingTicket.Status.MATCHED);
        assertThat(t2.getStatus()).isEqualTo(MatchmakingTicket.Status.MATCHED);
        assertThat(t1.getMatchedTableId()).isEqualTo(t2.getMatchedTableId());
        assertThat(tableManager.getTable(t1.getMatchedTableId()).orElseThrow().getState().getPlayers()).isEmpty();
    }

    @Test
    @DisplayName("An arriving human replaces the newest bot when the table is full")
    void shouldEvictBotForArrivingHuman() throws InterruptedException {
        MatchmakingTicket first = matchmakingService.enqueue(
                new MatchmakingRequest("USR_1", "Player One", "INDIAN_POINTS", 100, 2, true));
        TableActor table = tableManager.getTable(first.getMatchedTableId()).orElseThrow();
        waitUntil(() -> table.countBots() == 1, 400);

        MatchmakingTicket second = matchmakingService.enqueue(
                new MatchmakingRequest("USR_2", "Player Two", "INDIAN_POINTS", 100, 2, true));

        assertThat(second.getMatchedTableId()).isEqualTo(first.getMatchedTableId());
        assertThat(table.countBots()).isZero();
    }

    @Test
    @DisplayName("A player who arrives after the deal gets a different table")
    void shouldNotJoinAfterDeal() throws InterruptedException {
        MatchmakingTicket first = matchmakingService.enqueue(
                new MatchmakingRequest("USR_EARLY", "Early", "INDIAN_POINTS", 100, 2, true));
        TableActor table = tableManager.getTable(first.getMatchedTableId()).orElseThrow();
        waitUntil(() -> table.countBots() == 1, 400);
        seatHuman(table, "USR_EARLY", "Early");
        waitUntil(() -> table.getState().getStatus() == GameStatus.IN_PROGRESS, 800);

        MatchmakingTicket late = matchmakingService.enqueue(
                new MatchmakingRequest("USR_LATE", "Late", "INDIAN_POINTS", 100, 2, true));
        assertThat(late.getMatchedTableId()).isNotEqualTo(first.getMatchedTableId());
    }

    private static void seatHuman(TableActor table, String playerId, String name) {
        Set<Integer> taken = table.getState().getPlayers().stream()
                .map(PlayerState::getSeatIndex)
                .collect(Collectors.toSet());
        int seat = 0;
        while (taken.contains(seat)) seat++;
        table.processCommand(new JoinCommand(
                UUID.randomUUID().toString(),
                table.getState().getGameId(),
                playerId,
                name,
                seat,
                false,
                Instant.now()
        ), "TEST_JOIN");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
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
