package com.rummy.gameservice.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.engine.command.DropCommand;
import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.command.ReadyCommand;
import com.rummy.engine.command.StartGameCommand;
import com.rummy.engine.model.GameStatus;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.handler.GameWebSocketHandler;
import com.rummy.gameservice.matchmaking.MatchmakingService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.wallet.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Graceful node drain")
class GracefulDrainLifecycleTest {

    private WalletService wallet;
    private TableManager manager;
    private GameWebSocketHandler socketHandler;
    private MatchmakingService matchmaking;
    private ApplicationEventPublisher events;
    private NodeDrainState drainState;
    private GracefulDrainLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        wallet = new WalletService();
        manager = new TableManager(new ObjectMapper().registerModule(new JavaTimeModule()),
                null, null, null, wallet, new TableRoutingRegistry(null, "node-a"), 100, 180, 600);
        socketHandler = mock(GameWebSocketHandler.class);
        matchmaking = mock(MatchmakingService.class);
        events = mock(ApplicationEventPublisher.class);
        drainState = new NodeDrainState();
        lifecycle = new GracefulDrainLifecycle(drainState, manager, socketHandler, matchmaking, events, 1, 50);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    @DisplayName("A match still running at the timeout is cancelled and every entry refunded")
    void timeoutAbortsAndRefunds() throws Exception {
        TableActor actor = seatedPointsTable("TBL_LIVE", 100);
        WebSocketSession p1Socket = mock(WebSocketSession.class);
        when(p1Socket.isOpen()).thenReturn(true);
        actor.registerSession("P1", p1Socket);
        actor.processCommand(new StartGameCommand("s", actor.getState().getGameId(), "P1", Instant.now()), "start");
        assertThat(manager.liveTableCount()).isEqualTo(1);

        assertThat(lifecycle.drainAndWait()).isEqualTo(1);

        assertThat(balance("P1")).isEqualByComparingTo("1100");
        assertThat(balance("P2")).isEqualByComparingTo("1100");
        assertThat(manager.liveTableCount()).isZero();
        assertThat(drainState.isDraining()).isTrue();
        verify(matchmaking).releaseLocalWorkOnShutdown();
        verify(socketHandler, atLeastOnce()).closeSessionsWithoutTable();
        verify(events).publishEvent(readiness(ReadinessState.REFUSING_TRAFFIC));
        verify(p1Socket).sendMessage(argThat(m -> m instanceof TextMessage t
                && t.getPayload().contains("\"TABLE_CLOSED\"")));
    }

    @Test
    @DisplayName("Aborting twice refunds only once")
    void abortRefundsOnce() {
        TableActor actor = seatedPointsTable("TBL_TWICE", 100);
        actor.processCommand(new StartGameCommand("s", actor.getState().getGameId(), "P1", Instant.now()), "start");

        manager.abortLiveMatches("test");
        manager.abortLiveMatches("test");
        actor.abortMatch("test");

        assertThat(balance("P1")).isEqualByComparingTo("1100");
    }

    @Test
    @DisplayName("Finished and waiting tables do not hold up shutdown")
    void nothingLiveDrainsImmediately() {
        TableActor done = seatedPointsTable("TBL_DONE", 0);
        String gameId = done.getState().getGameId();
        done.processCommand(new StartGameCommand("s", gameId, "P1", Instant.now()), "start");
        done.processCommand(new DropCommand("d", gameId, "P2", Instant.now()), "drop");
        assertThat(done.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        manager.getOrCreateTable("TBL_WAITING", null);

        long started = System.nanoTime();
        assertThat(lifecycle.drainAndWait()).isZero();

        assertThat(System.nanoTime() - started).isLessThan(500_000_000L);
    }

    @Test
    @DisplayName("Cancelling a drain puts the node back in rotation")
    void cancelDrain() {
        lifecycle.beginDrain("test");
        assertThat(drainState.isDraining()).isTrue();

        lifecycle.cancelDrain();

        assertThat(drainState.isDraining()).isFalse();
        verify(events).publishEvent(readiness(ReadinessState.ACCEPTING_TRAFFIC));
    }

    private static ApplicationEvent readiness(ReadinessState state) {
        return argThat((ApplicationEvent e) -> e instanceof AvailabilityChangeEvent<?> ace && ace.getState() == state);
    }

    private TableActor seatedPointsTable(String tableId, int stakeTier) {
        TableActor actor = manager.getOrCreateTable(tableId, null);
        actor.setStakeTier(stakeTier);
        String gameId = actor.getState().getGameId();
        Instant now = Instant.now();
        actor.processCommand(new JoinCommand("j1", gameId, "P1", "One", 0, false, now), "j1");
        actor.processCommand(new JoinCommand("j2", gameId, "P2", "Two", 1, false, now), "j2");
        actor.processCommand(new ReadyCommand("r1", gameId, "P1", now), "r1");
        actor.processCommand(new ReadyCommand("r2", gameId, "P2", now), "r2");
        return actor;
    }

    private BigDecimal balance(String playerId) {
        return wallet.getOrCreateWallet(playerId).getFreePlayBalance();
    }
}
