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

    @Test
    @DisplayName("Points Rummy: Loser pays only for lost points (cap 80), unlost stake refunded, 15% platform rake to treasury")
    void testPointsRummySettlementWithRakeAndRefund() {
        String winner = "USR_PW_WIN";
        String loser = "USR_PW_LOSE";

        walletService.getOrCreateWallet(winner); // 1000
        walletService.getOrCreateWallet(loser);  // 1000

        BigDecimal stakeTier = BigDecimal.valueOf(100);

        // Simulate entry debit at matchmaking start
        walletService.debit(winner, stakeTier, "GAME_ENTRY_STAKE", "STAKE_MATCH_1_" + winner, "MATCH_1", "Entry", null);
        walletService.debit(loser, stakeTier, "GAME_ENTRY_STAKE", "STAKE_MATCH_1_" + loser, "MATCH_1", "Entry", null);

        // Loser dropped at 40 points penalty (out of 80 max) -> loss is 50.00, refund is 50.00
        GameSettlementResult result = walletService.settleMatch(
                "MATCH_1", "TBL_1", "POINTS_13", stakeTier, winner,
                java.util.Map.of(winner, 0, loser, 40),
                List.of(winner, loser)
        );

        assertThat(result).isNotNull();
        assertThat(result.totalGrossPot()).isEqualByComparingTo(BigDecimal.valueOf(50.00));
        assertThat(result.platformRakeAmount()).isEqualByComparingTo(BigDecimal.valueOf(7.50)); // 15% of 50
        assertThat(result.netWinnerPrize()).isEqualByComparingTo(BigDecimal.valueOf(42.50));   // 50 - 7.50

        // Loser had 900 + 50.00 refund = 950.00 (Net loss: -50.00)
        assertThat(walletService.getOrCreateWallet(loser).getFreePlayBalance()).isEqualByComparingTo(BigDecimal.valueOf(950.00));

        // Winner had 900 + 100 (stake return) + 42.50 (net prize) = 1042.50 (Net win: +42.50)
        assertThat(walletService.getOrCreateWallet(winner).getFreePlayBalance()).isEqualByComparingTo(BigDecimal.valueOf(1042.50));

        // Platform Treasury received 7.50 rake
        assertThat(walletService.getPlatformTreasuryBalance()).isEqualByComparingTo(BigDecimal.valueOf(1007.50));
    }

    @Test
    @DisplayName("Pool Rummy: Fixed entry fee, total pot 200, 15% rake (30), winner takes 170")
    void testPoolRummySettlementWith15PercentRake() {
        String winner = "USR_POOL_W";
        String loser = "USR_POOL_L";

        walletService.getOrCreateWallet(winner); // 1000
        walletService.getOrCreateWallet(loser);  // 1000

        BigDecimal stakeTier = BigDecimal.valueOf(100);

        // Matchmaking debits entry
        walletService.debit(winner, stakeTier, "GAME_ENTRY_STAKE", "STAKE_MATCH_2_" + winner, "MATCH_2", "Entry", null);
        walletService.debit(loser, stakeTier, "GAME_ENTRY_STAKE", "STAKE_MATCH_2_" + loser, "MATCH_2", "Entry", null);

        GameSettlementResult result = walletService.settleMatch(
                "MATCH_2", "TBL_2", "POOL_101", stakeTier, winner,
                java.util.Map.of(winner, 0, loser, 80),
                List.of(winner, loser)
        );

        assertThat(result).isNotNull();
        assertThat(result.totalGrossPot()).isEqualByComparingTo(BigDecimal.valueOf(100.00));
        assertThat(result.platformRakeAmount()).isEqualByComparingTo(BigDecimal.valueOf(15.00)); // 15% of 100
        assertThat(result.netWinnerPrize()).isEqualByComparingTo(BigDecimal.valueOf(85.00));

        // Winner had 900 + 85 = 985
        assertThat(walletService.getOrCreateWallet(winner).getFreePlayBalance()).isEqualByComparingTo(BigDecimal.valueOf(985.00));
    }

    @Test
    @DisplayName("21-Card Rummy: 120-pt cap, First Drop 30 pts, 75% stake refund, 15% platform rake")
    void testTwentyOneCardRummySettlement() {
        String winner = "USR_21_WIN";
        String loser = "USR_21_LOSE";

        walletService.getOrCreateWallet(winner); // 1000
        walletService.getOrCreateWallet(loser);  // 1000

        BigDecimal stakeTier = BigDecimal.valueOf(120);

        // Entry stake debited
        walletService.debit(winner, stakeTier, "GAME_ENTRY_STAKE", "STAKE_21_" + winner, "M21", "Entry", null);
        walletService.debit(loser, stakeTier, "GAME_ENTRY_STAKE", "STAKE_21_" + loser, "M21", "Entry", null);

        // Loser First Drop = 30 pts (out of 120) -> loss is 30.00, refund is 90.00
        GameSettlementResult result = walletService.settleMatch(
                "M21", "TBL_21", "RUMMY_21", stakeTier, winner,
                java.util.Map.of(winner, 0, loser, 30),
                List.of(winner, loser)
        );

        assertThat(result).isNotNull();
        assertThat(result.totalGrossPot()).isEqualByComparingTo(BigDecimal.valueOf(30.00));
        assertThat(result.platformRakeAmount()).isEqualByComparingTo(BigDecimal.valueOf(4.50)); // 15% of 30
        assertThat(result.netWinnerPrize()).isEqualByComparingTo(BigDecimal.valueOf(25.50));

        // Loser had 880 + 90.00 refund = 970.00 (Net loss: -30.00)
        assertThat(walletService.getOrCreateWallet(loser).getFreePlayBalance()).isEqualByComparingTo(BigDecimal.valueOf(970.00));

        // Winner had 880 + 120 (stake return) + 25.50 (net prize) = 1025.50 (Net win: +25.50)
        assertThat(walletService.getOrCreateWallet(winner).getFreePlayBalance()).isEqualByComparingTo(BigDecimal.valueOf(1025.50));
    }

    @Test
    @DisplayName("Deals Rummy: Fixed entry fee tournament, 15% platform rake, winner takes net pool")
    void testDealsRummySettlement() {
        String winner = "USR_DEALS_W";
        String loser = "USR_DEALS_L";

        walletService.getOrCreateWallet(winner); // 1000
        walletService.getOrCreateWallet(loser);  // 1000

        BigDecimal stakeTier = BigDecimal.valueOf(50);

        walletService.debit(winner, stakeTier, "GAME_ENTRY_STAKE", "STAKE_DEALS_" + winner, "MDEALS", "Entry", null);
        walletService.debit(loser, stakeTier, "GAME_ENTRY_STAKE", "STAKE_DEALS_" + loser, "MDEALS", "Entry", null);

        GameSettlementResult result = walletService.settleMatch(
                "MDEALS", "TBL_DEALS", "DEALS_RUMMY", stakeTier, winner,
                java.util.Map.of(winner, 0, loser, 80),
                List.of(winner, loser)
        );

        assertThat(result).isNotNull();
        assertThat(result.totalGrossPot()).isEqualByComparingTo(BigDecimal.valueOf(50.00));
        assertThat(result.platformRakeAmount()).isEqualByComparingTo(BigDecimal.valueOf(7.50)); // 15% of 50
        assertThat(result.netWinnerPrize()).isEqualByComparingTo(BigDecimal.valueOf(42.50));
    }
}
