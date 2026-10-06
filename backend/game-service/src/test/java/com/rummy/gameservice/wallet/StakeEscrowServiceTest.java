package com.rummy.gameservice.wallet;

import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Stake escrow ledger")
class StakeEscrowServiceTest {

    private WalletService wallet;
    private StakeEscrowService escrows;

    @BeforeEach
    void setUp() {
        wallet = new WalletService();
        TestFunding.fund(wallet, "P1", "P2", "P3", "P9");
        escrows = new StakeEscrowService(null, wallet,
                new ClusterNodeService(new TableRoutingRegistry(null, "node-a"), null, 45));
    }

    @Test
    @DisplayName("Only one refund path can claim a stake")
    void claimRefundOnce() {
        stake("STAKE_1", "P1", "G1");

        assertThat(escrows.claimRefund("STAKE_1")).isTrue();
        assertThat(escrows.claimRefund("STAKE_1")).isFalse();
        assertThat(escrows.status("STAKE_1")).contains(StakeEscrowService.Status.REFUNDED);
    }

    @Test
    @DisplayName("A failed refund can be retried after reopening")
    void reopenAllowsRetry() {
        stake("STAKE_2", "P1", "G1");
        assertThat(escrows.claimRefund("STAKE_2")).isTrue();

        escrows.reopen("STAKE_2");

        assertThat(escrows.claimRefund("STAKE_2")).isTrue();
    }

    @Test
    @DisplayName("Cancelling a game refunds each open stake once and leaves settled games alone")
    void refundGameOnce() {
        stake("STAKE_A", "P1", "G_ABORT");
        stake("STAKE_B", "P2", "G_ABORT");
        stake("STAKE_C", "P3", "G_DONE");
        assertThat(escrows.beginSettlement("G_DONE")).isTrue();
        escrows.completeSettlement("G_DONE");

        assertThat(escrows.refundGame("G_ABORT", "test")).isEqualTo(2);
        assertThat(escrows.refundGame("G_ABORT", "test")).isZero();
        assertThat(escrows.refundGame("G_DONE", "test")).isZero();

        assertThat(balance("P1")).isEqualByComparingTo("1000");
        assertThat(balance("P2")).isEqualByComparingTo("1000");
        assertThat(balance("P3")).isEqualByComparingTo("900");
        assertThat(escrows.status("STAKE_C")).contains(StakeEscrowService.Status.SETTLED);
    }

    @Test
    @DisplayName("A refund claimed before settlement keeps that stake out of the payout lock")
    void settlementSkipsRefundedStake() {
        stake("STAKE_X", "P1", "G2");
        stake("STAKE_Y", "P2", "G2");
        escrows.claimRefund("STAKE_X");

        assertThat(escrows.beginSettlement("G2")).isTrue();
        escrows.completeSettlement("G2");

        assertThat(escrows.status("STAKE_X")).contains(StakeEscrowService.Status.REFUNDED);
        assertThat(escrows.status("STAKE_Y")).contains(StakeEscrowService.Status.SETTLED);
    }

    @Test
    @DisplayName("A stake whose debit failed is forgotten")
    void discardFailedDebit() {
        escrows.open("STAKE_FAIL", "P1", 100, null, null);

        escrows.discard("STAKE_FAIL");

        assertThat(escrows.status("STAKE_FAIL")).isEmpty();
    }

    @Test
    @DisplayName("Refunds never pay back a stake that was never debited")
    void noRefundWithoutDebit() {
        escrows.open("STAKE_GHOST", "P9", 100, "T1", "G_GHOST");

        escrows.refundGame("G_GHOST", "test");

        assertThat(balance("P9")).isEqualByComparingTo("1000");
    }

    private void stake(String escrowId, String playerId, String gameId) {
        escrows.open(escrowId, playerId, 100, "T_" + gameId, gameId);
        wallet.debit(playerId, BigDecimal.valueOf(100), "GAME_ENTRY_STAKE", escrowId, null, "test", Map.of());
    }

    private BigDecimal balance(String playerId) {
        return wallet.getOrCreateWallet(playerId).getBalance();
    }
}
