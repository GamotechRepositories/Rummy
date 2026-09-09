package com.rummy.gameservice.wallet;

import com.rummy.gameservice.persistence.document.WalletAccountDocument;
import com.rummy.gameservice.persistence.document.WalletTransactionDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WalletService & Double-Entry Ledger Tests")
class WalletServiceTest {

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService();
    }

    @Test
    @DisplayName("Initial wallet provisioning creates 1000 free-play tokens")
    void testInitialWalletCreation() {
        WalletAccountDocument wallet = walletService.getOrCreateWallet("USR_ALICE");
        assertThat(wallet.getPlayerId()).isEqualTo("USR_ALICE");
        assertThat(wallet.getFreePlayBalance()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(wallet.isRealMoneyEnabled()).isFalse(); // Compliance gate
    }

    @Test
    @DisplayName("Credit and debit modify balance and produce immutable ledger transactions")
    void testCreditAndDebit() {
        String playerId = "USR_BOB";
        walletService.getOrCreateWallet(playerId);

        // Credit 500
        WalletTransactionDocument creditTx = walletService.credit(
                playerId, BigDecimal.valueOf(500), "PROMOTIONAL_CREDIT", "TX_CREDIT_01", null, "Bonus", null
        );
        assertThat(creditTx.getBalanceBefore()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(creditTx.getBalanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(1500));

        // Debit 300
        WalletTransactionDocument debitTx = walletService.debit(
                playerId, BigDecimal.valueOf(300), "GAME_ENTRY", "TX_DEBIT_01", "G100", "Game entry", null
        );
        assertThat(debitTx.getBalanceBefore()).isEqualByComparingTo(BigDecimal.valueOf(1500));
        assertThat(debitTx.getBalanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(1200));

        List<WalletTransactionDocument> history = walletService.getTransactions(playerId);
        assertThat(history).hasSize(2);
    }

    @Test
    @DisplayName("Idempotency: Duplicate transaction keys return existing transaction without double credit/debit")
    void testIdempotencyProtection() {
        String playerId = "USR_CHARLIE";

        walletService.credit(playerId, BigDecimal.valueOf(200), "DEPOSIT", "IDEMPOTENT_KEY_123", null, "Test", null);
        assertThat(walletService.getOrCreateWallet(playerId).getFreePlayBalance()).isEqualByComparingTo(BigDecimal.valueOf(1200));

        // Repeat with identical idempotency key
        WalletTransactionDocument duplicate = walletService.credit(
                playerId, BigDecimal.valueOf(200), "DEPOSIT", "IDEMPOTENT_KEY_123", null, "Test", null
        );
        assertThat(duplicate.getBalanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(1200));
        assertThat(walletService.getOrCreateWallet(playerId).getFreePlayBalance()).isEqualByComparingTo(BigDecimal.valueOf(1200));
    }

    @Test
    @DisplayName("Debit fails when balance is insufficient")
    void testInsufficientBalanceDebitFails() {
        String playerId = "USR_DAVE";
        assertThatThrownBy(() -> walletService.debit(playerId, BigDecimal.valueOf(5000), "GAME_ENTRY", "TX_FAIL", null, "Overdraw", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient free-play token balance");
    }

    @Test
    @DisplayName("Game settlement debits loser and credits winner pot")
    void testGameSettlement() {
        String winner = "USR_WINNER";
        String loser = "USR_LOSER";

        walletService.getOrCreateWallet(winner); // 1000
        walletService.getOrCreateWallet(loser);  // 1000

        walletService.settleGame("GAME_SETTLE_1", winner, List.of(loser), BigDecimal.valueOf(250));

        assertThat(walletService.getOrCreateWallet(loser).getFreePlayBalance()).isEqualByComparingTo(BigDecimal.valueOf(750));
        assertThat(walletService.getOrCreateWallet(winner).getFreePlayBalance()).isEqualByComparingTo(BigDecimal.valueOf(1250));
    }
}
