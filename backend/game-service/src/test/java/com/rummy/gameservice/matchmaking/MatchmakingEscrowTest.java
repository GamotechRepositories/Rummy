package com.rummy.gameservice.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.lifecycle.NodeDrainState;
import com.rummy.gameservice.routing.PlayerPresenceService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.wallet.InsufficientBalanceException;
import com.rummy.gameservice.wallet.StakeEscrowService;
import com.rummy.gameservice.wallet.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Matchmaking stake escrow")
class MatchmakingEscrowTest {

    private TableManager tableManager;
    private WalletService walletService;
    private InMemoryMatchmakingStore store;
    private MatchmakingService matchmakingService;
    private StakeEscrowService escrows;

    @BeforeEach
    void setUp() {
        tableManager = new TableManager(new ObjectMapper(), null);
        walletService = new WalletService();
        store = new InMemoryMatchmakingStore();
        TableRoutingRegistry routing = new TableRoutingRegistry(null, "srv-escrow");
        matchmakingService = new MatchmakingService(tableManager, routing, new PlayerPresenceService(null), store,
                walletService, 100, 2000, 80, 60, 350);
        escrows = new StakeEscrowService(null, walletService, new ClusterNodeService(routing, null, 45));
        matchmakingService.setEscrows(escrows);
        tableManager.setEscrows(escrows);
    }

    @AfterEach
    void tearDown() {
        matchmakingService.shutdown();
        tableManager.shutdown();
    }

    @Test
    @DisplayName("Stake is debited when the player joins the queue")
    void stakeEscrowedOnEnqueue() {
        matchmakingService.enqueue(new MatchmakingRequest("USR_E1", "One", "INDIAN_POINTS", 100, 2, false));

        assertThat(balance("USR_E1")).isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("Player without enough balance is not queued")
    void insufficientBalanceIsNotQueued() {
        assertThatThrownBy(() -> matchmakingService.enqueue(
                new MatchmakingRequest("USR_POOR", "Poor", "INDIAN_POINTS", 5000, 2, false)))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(store.countQueued()).isZero();
        assertThat(balance("USR_POOR")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("Cancelling a queued search refunds the stake exactly once")
    void cancelRefundsOnce() {
        MatchmakingTicket ticket = matchmakingService.enqueue(
                new MatchmakingRequest("USR_E2", "Two", "INDIAN_POINTS", 100, 2, false));

        matchmakingService.cancelTicket(ticket.getTicketId());
        matchmakingService.cancelTicket(ticket.getTicketId());

        assertThat(balance("USR_E2")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("Searching again refunds the superseded ticket")
    void supersededTicketRefunded() {
        matchmakingService.enqueue(new MatchmakingRequest("USR_E3", "Three", "INDIAN_POINTS", 100, 2, false));
        matchmakingService.enqueue(new MatchmakingRequest("USR_E3", "Three", "INDIAN_POINTS", 100, 2, false));

        assertThat(balance("USR_E3")).isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("Leaving the lobby before the deal refunds the stake")
    void leavingLobbyRefunds() {
        MatchmakingTicket ticket = matchmakingService.enqueue(
                new MatchmakingRequest("USR_E4", "Four", "INDIAN_POINTS", 100, 2, true));
        assertThat(balance("USR_E4")).isEqualByComparingTo("900");

        matchmakingService.onWaitingHumanLeft("USR_E4", ticket.getMatchedTableId());

        assertThat(balance("USR_E4")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("A stake already refunded by another path is not paid back again")
    void refundGatedByLedger() {
        MatchmakingTicket ticket = matchmakingService.enqueue(
                new MatchmakingRequest("USR_E8", "Eight", "INDIAN_POINTS", 100, 2, false));
        assertThat(escrows.claimRefund("STAKE_" + ticket.getTicketId())).isTrue();

        matchmakingService.cancelTicket(ticket.getTicketId());

        assertThat(balance("USR_E8")).isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("Seating a player in a lobby assigns their stake to that table's game")
    void lobbyStakeAssignedToGame() {
        MatchmakingTicket ticket = matchmakingService.enqueue(
                new MatchmakingRequest("USR_E9", "Nine", "INDIAN_POINTS", 100, 2, true));
        String gameId = tableManager.getTable(ticket.getMatchedTableId()).orElseThrow().getState().getGameId();

        assertThat(escrows.refundGame(gameId, "test")).isEqualTo(1);
        assertThat(balance("USR_E9")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("A draining node refuses new searches without touching the wallet")
    void drainingNodeRefusesSearch() {
        NodeDrainState drainState = mock(NodeDrainState.class);
        when(drainState.isDraining()).thenReturn(true);
        matchmakingService.setDrainState(drainState);

        assertThatThrownBy(() -> matchmakingService.enqueue(
                new MatchmakingRequest("USR_E5", "Five", "INDIAN_POINTS", 100, 2, false)))
                .isInstanceOf(NodeDrainingException.class);

        assertThat(balance("USR_E5")).isEqualByComparingTo("1000");
        assertThat(store.countQueued()).isZero();
    }

    @Test
    @DisplayName("Shutting down refunds queued searches and undealt lobbies")
    void shutdownRefundsQueuedAndLobbies() {
        matchmakingService.enqueue(new MatchmakingRequest("USR_E6", "Six", "INDIAN_POINTS", 100, 2, false));
        matchmakingService.enqueue(new MatchmakingRequest("USR_E7", "Seven", "INDIAN_POINTS", 100, 6, true));
        assertThat(balance("USR_E6")).isEqualByComparingTo("900");
        assertThat(balance("USR_E7")).isEqualByComparingTo("900");

        matchmakingService.releaseLocalWorkOnShutdown();
        matchmakingService.releaseLocalWorkOnShutdown();

        assertThat(balance("USR_E6")).isEqualByComparingTo("1000");
        assertThat(balance("USR_E7")).isEqualByComparingTo("1000");
        assertThat(store.countQueued()).isZero();
    }

    private BigDecimal balance(String playerId) {
        return walletService.getOrCreateWallet(playerId).getFreePlayBalance();
    }
}
