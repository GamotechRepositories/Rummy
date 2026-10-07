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

    /** Gives a test player ₹1000 to play with. */
    private void fund(String playerId) {
        walletService.credit(playerId, BigDecimal.valueOf(1000), "TEST_FUNDING", "FUND_" + playerId, null, "Test funding", null);
    }

    @Test
    @DisplayName("A new wallet starts empty, in INR: no free money")
    void testInitialWalletCreation() {
        WalletAccountDocument wallet = walletService.getOrCreateWallet("USR_ALICE");
        assertThat(wallet.getPlayerId()).isEqualTo("USR_ALICE");
        assertThat(wallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.getCurrency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("Credit and debit modify balance and produce immutable ledger transactions")
    void testCreditAndDebit() {
        String playerId = "USR_BOB";
        fund(playerId);

        WalletTransactionDocument creditTx = walletService.credit(
                playerId, BigDecimal.valueOf(500), "GAME_WIN", "TX_CREDIT_01", null, "Win", null
        );
        assertThat(creditTx.getBalanceBefore()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(creditTx.getBalanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(1500));

        WalletTransactionDocument debitTx = walletService.debit(
                playerId, BigDecimal.valueOf(300), "GAME_ENTRY", "TX_DEBIT_01", "G100", "Game entry", null
        );
        assertThat(debitTx.getBalanceBefore()).isEqualByComparingTo(BigDecimal.valueOf(1500));
        assertThat(debitTx.getBalanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(1200));

        List<WalletTransactionDocument> history = walletService.getTransactions(playerId);
        assertThat(history).hasSize(3);
    }

    @Test
    @DisplayName("Idempotency: Duplicate transaction keys return existing transaction without double credit/debit")
    void testIdempotencyProtection() {
        String playerId = "USR_CHARLIE";
        fund(playerId);

        walletService.credit(playerId, BigDecimal.valueOf(200), "GAME_WIN", "IDEMPOTENT_KEY_123", null, "Test", null);
        assertThat(walletService.getOrCreateWallet(playerId).getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1200));

        WalletTransactionDocument duplicate = walletService.credit(
                playerId, BigDecimal.valueOf(200), "GAME_WIN", "IDEMPOTENT_KEY_123", null, "Test", null
        );
        assertThat(duplicate.getBalanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(1200));
        assertThat(walletService.getOrCreateWallet(playerId).getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1200));
    }

    @Test
    @DisplayName("Debit fails when balance is insufficient")
    void testInsufficientBalanceDebitFails() {
        String playerId = "USR_DAVE";
        assertThatThrownBy(() -> walletService.debit(playerId, BigDecimal.valueOf(5000), "GAME_ENTRY", "TX_FAIL", null, "Overdraw", null))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("Insufficient balance");
    }

    @Test
    @DisplayName("Points Rummy: Loser pays only for lost points (cap 80), unlost stake refunded, 15% platform rake to treasury")
    void testPointsRummySettlementWithRakeAndRefund() {
        String winner = "USR_PW_WIN";
        String loser = "USR_PW_LOSE";
        fund(winner);
        fund(loser);

        BigDecimal stakeTier = BigDecimal.valueOf(100);

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
        assertThat(walletService.getOrCreateWallet(loser).getBalance()).isEqualByComparingTo(BigDecimal.valueOf(950.00));

        // Winner had 900 + 100 (stake return) + 42.50 (net prize) = 1042.50 (Net win: +42.50)
        assertThat(walletService.getOrCreateWallet(winner).getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1042.50));

        assertThat(walletService.getPlatformTreasuryBalance()).isEqualByComparingTo(BigDecimal.valueOf(7.50));
    }

    @Test
    @DisplayName("Pool Rummy: Fixed entry fee, total pot 200, 15% rake (30), winner takes 170")
    void testPoolRummySettlementWith15PercentRake() {
        String winner = "USR_POOL_W";
        String loser = "USR_POOL_L";
        fund(winner);
        fund(loser);

        BigDecimal stakeTier = BigDecimal.valueOf(100);

        walletService.debit(winner, stakeTier, "GAME_ENTRY_STAKE", "STAKE_MATCH_2_" + winner, "MATCH_2", "Entry", null);
        walletService.debit(loser, stakeTier, "GAME_ENTRY_STAKE", "STAKE_MATCH_2_" + loser, "MATCH_2", "Entry", null);

        GameSettlementResult result = walletService.settleMatch(
                "MATCH_2", "TBL_2", "POOL_101", stakeTier, winner,
                java.util.Map.of(winner, 0, loser, 80),
                List.of(winner, loser)
        );

        assertThat(result).isNotNull();
        assertThat(result.totalGrossPot()).isEqualByComparingTo(BigDecimal.valueOf(200.00));
        assertThat(result.platformRakeAmount()).isEqualByComparingTo(BigDecimal.valueOf(30.00)); // 15% of 200
        assertThat(result.netWinnerPrize()).isEqualByComparingTo(BigDecimal.valueOf(170.00));

        // Winner had 900 + 170 = 1070
        assertThat(walletService.getOrCreateWallet(winner).getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1070.00));
    }

    @Test
    @DisplayName("Pool Rummy with Rejoin: Rejoin fees are added to gross pot, increasing prize and rake")
    void testPoolRummySettlementWithRejoinFee() {
        String winner = "USR_POOL_W2";
        String loser = "USR_POOL_L2";
        String rejoiner = "USR_POOL_RJ";
        fund(winner);
        fund(loser);
        fund(rejoiner);

        BigDecimal stakeTier = BigDecimal.valueOf(100);

        walletService.debit(winner, stakeTier, "GAME_ENTRY_STAKE", "STAKE_MATCH_3_" + winner, "MATCH_3", "Entry", null);
        walletService.debit(loser, stakeTier, "GAME_ENTRY_STAKE", "STAKE_MATCH_3_" + loser, "MATCH_3", "Entry", null);
        walletService.debit(rejoiner, stakeTier, "GAME_ENTRY_STAKE", "STAKE_MATCH_3_" + rejoiner, "MATCH_3", "Entry", null);
        walletService.debit(rejoiner, stakeTier, "REJOIN_FEE", "REJOIN_MATCH_3_" + rejoiner, "MATCH_3", "Rejoin", null);

        // Gross pot = 100 + 100 + (100 + 100) = 400; rake 60; net prize 340
        GameSettlementResult result = walletService.settleMatch(
                "MATCH_3", "TBL_3", "POOL_101", stakeTier, winner,
                java.util.Map.of(winner, 0, loser, 80, rejoiner, 60),
                List.of(winner, loser, rejoiner),
                java.util.Map.of(rejoiner, 1)
        );

        assertThat(result).isNotNull();
        assertThat(result.totalGrossPot()).isEqualByComparingTo(BigDecimal.valueOf(400.00));
        assertThat(result.platformRakeAmount()).isEqualByComparingTo(BigDecimal.valueOf(60.00));
        assertThat(result.netWinnerPrize()).isEqualByComparingTo(BigDecimal.valueOf(340.00));

        assertThat(result.playerDetails().get(rejoiner).lossAmount()).isEqualByComparingTo(BigDecimal.valueOf(200.00));
        assertThat(result.playerDetails().get(rejoiner).netWalletDelta()).isEqualByComparingTo(BigDecimal.valueOf(-200.00));

        // Winner wallet: 1000 - 100 + 340 = 1240
        assertThat(walletService.getOrCreateWallet(winner).getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1240.00));
    }

    @Test
    @DisplayName("Pool split: each sharing player is paid their share; the eliminated player pays the entry")
    void testPoolSplitSettlement() {
        List<String> players = List.of("USR_SP_A", "USR_SP_B", "USR_SP_C", "USR_SP_D");
        BigDecimal stake = BigDecimal.valueOf(25);
        for (String p : players) {
            fund(p);
            walletService.debit(p, stake, "GAME_ENTRY_STAKE", "STAKE_SPLIT_" + p, "MATCH_SPLIT", "Entry", null);
        }
        java.util.LinkedHashMap<String, Integer> drops = new java.util.LinkedHashMap<>();
        drops.put("USR_SP_A", 5);
        drops.put("USR_SP_B", 4);
        drops.put("USR_SP_C", 4);

        // Gross 100, rake 15, prize 85: A gets one extra drop (25) + 20, B and C get 20 each
        GameSettlementResult result = walletService.settleMatch(
                "MATCH_SPLIT", "TBL_SPLIT", "POOL_101", stake, "USR_SP_A",
                java.util.Map.of("USR_SP_A", 0, "USR_SP_B", 20, "USR_SP_C", 20, "USR_SP_D", 120),
                players, java.util.Map.of(), drops);

        assertThat(result.netWinnerPrize()).isEqualByComparingTo("85.00");
        assertThat(result.splitPayouts()).containsOnlyKeys("USR_SP_A", "USR_SP_B", "USR_SP_C");
        assertThat(result.splitPayouts().get("USR_SP_A")).isEqualByComparingTo("45.00");
        assertThat(result.splitPayouts().get("USR_SP_B")).isEqualByComparingTo("20.00");
        assertThat(walletService.getOrCreateWallet("USR_SP_A").getBalance()).isEqualByComparingTo("1020.00");
        assertThat(walletService.getOrCreateWallet("USR_SP_B").getBalance()).isEqualByComparingTo("995.00");
        assertThat(walletService.getOrCreateWallet("USR_SP_D").getBalance()).isEqualByComparingTo("975.00");
        assertThat(result.playerDetails().get("USR_SP_D").isWinner()).isFalse();
        assertThat(walletService.getPlatformTreasuryBalance()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("21-Card Rummy: 120-pt cap, First Drop 30 pts, 75% stake refund, 15% platform rake")
    void testTwentyOneCardRummySettlement() {
        String winner = "USR_21_WIN";
        String loser = "USR_21_LOSE";
        fund(winner);
        fund(loser);

        BigDecimal stakeTier = BigDecimal.valueOf(120);

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

        assertThat(walletService.getOrCreateWallet(loser).getBalance()).isEqualByComparingTo(BigDecimal.valueOf(970.00));
        assertThat(walletService.getOrCreateWallet(winner).getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1025.50));
    }

    @Test
    @DisplayName("Concurrent debits never overdraw the wallet")
    void testConcurrentDebitsDoNotOverdraw() throws Exception {
        String playerId = "USR_RACE";
        fund(playerId);

        int threads = 50;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger succeeded = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "RACE_" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    walletService.debit(playerId, BigDecimal.valueOf(100), "GAME_ENTRY", key, null, "race", null);
                    succeeded.incrementAndGet();
                } catch (InsufficientBalanceException ignored) {
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(walletService.getOrCreateWallet(playerId).getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Deals Rummy: Fixed entry fee tournament, 15% platform rake, winner takes net pool")
    void testDealsRummySettlement() {
        String winner = "USR_DEALS_W";
        String loser = "USR_DEALS_L";
        fund(winner);
        fund(loser);

        BigDecimal stakeTier = BigDecimal.valueOf(50);

        walletService.debit(winner, stakeTier, "GAME_ENTRY_STAKE", "STAKE_DEALS_" + winner, "MDEALS", "Entry", null);
        walletService.debit(loser, stakeTier, "GAME_ENTRY_STAKE", "STAKE_DEALS_" + loser, "MDEALS", "Entry", null);

        GameSettlementResult result = walletService.settleMatch(
                "MDEALS", "TBL_DEALS", "DEALS_RUMMY", stakeTier, winner,
                java.util.Map.of(winner, 0, loser, 80),
                List.of(winner, loser)
        );

        assertThat(result).isNotNull();
        assertThat(result.totalGrossPot()).isEqualByComparingTo(BigDecimal.valueOf(100.00));
        assertThat(result.platformRakeAmount()).isEqualByComparingTo(BigDecimal.valueOf(15.00)); // 15% of 100
        assertThat(result.netWinnerPrize()).isEqualByComparingTo(BigDecimal.valueOf(85.00));

        // Winner had 950 + 85 = 1035
        assertThat(walletService.getOrCreateWallet(winner).getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1035.00));
    }
}
