package com.rummy.gameservice.wallet;

import java.math.BigDecimal;

/** Wallets start empty; tests give their players ₹1000 explicitly. */
public final class TestFunding {

    public static final BigDecimal AMOUNT = BigDecimal.valueOf(1000);

    private TestFunding() {
    }

    public static void fund(WalletService wallet, String... playerIds) {
        for (String playerId : playerIds) {
            wallet.credit(playerId, AMOUNT, "TEST_FUNDING", "FUND_" + playerId, null, "Test funding", null);
        }
    }
}
