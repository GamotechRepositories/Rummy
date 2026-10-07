package com.rummy.gameservice.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Pool Rummy prize split, as on Indian platforms (RummyCircle "Split"):
 * <ul>
 *   <li>Allowed at the end of a deal when 2 or 3 players remain on a table that started with more than
 *       3 players, or 2 remain on a table that started with 3. Never on a table that started with 2.</li>
 *   <li>Each remaining player's drops left are equalised to the lowest: every excess drop is paid out at
 *       one entry fee, then the rest of the prize is shared equally.</li>
 *   <li>At least one entry fee must be left to share after equalising, otherwise the prize cannot be split.</li>
 * </ul>
 */
public final class PoolSplit {

    private PoolSplit() {
    }

    /** How many more times the player can drop (at the first-drop penalty) and stay below the elimination score. */
    public static int dropsRemaining(int cumulativeScore, int eliminationThreshold, int firstDropPenalty) {
        if (firstDropPenalty <= 0) {
            return 0;
        }
        return Math.max(0, (eliminationThreshold - 1 - cumulativeScore) / firstDropPenalty);
    }

    public static boolean tableSizeAllows(int startedWith, int remaining) {
        if (startedWith > 3) {
            return remaining == 2 || remaining == 3;
        }
        return startedWith == 3 && remaining == 2;
    }

    /** The prize after the platform fee, rounded exactly as {@link WalletService#settleMatch} does. */
    public static BigDecimal netPrize(BigDecimal grossPot) {
        BigDecimal rake = grossPot.multiply(WalletService.DEFAULT_RAKE_RATE).setScale(2, RoundingMode.HALF_UP);
        return grossPot.subtract(rake).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Each player's share of {@code netPrize}, in the iteration order of {@code dropsLeft}; empty when
     * less than one entry fee would be left to share. Shares always add up to {@code netPrize} exactly.
     */
    public static Optional<Map<String, BigDecimal>> payouts(Map<String, Integer> dropsLeft, BigDecimal entryFee, BigDecimal netPrize) {
        if (dropsLeft == null || dropsLeft.size() < 2 || entryFee == null || entryFee.signum() <= 0
                || netPrize == null || netPrize.signum() <= 0) {
            return Optional.empty();
        }
        int fewest = dropsLeft.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        Map<String, BigDecimal> shares = new LinkedHashMap<>();
        BigDecimal excessTotal = BigDecimal.ZERO;
        for (Map.Entry<String, Integer> e : dropsLeft.entrySet()) {
            BigDecimal excess = entryFee.multiply(BigDecimal.valueOf(e.getValue() - fewest));
            shares.put(e.getKey(), excess);
            excessTotal = excessTotal.add(excess);
        }
        BigDecimal remainder = netPrize.subtract(excessTotal);
        if (remainder.compareTo(entryFee) < 0) {
            return Optional.empty();
        }
        long remainderPaise = remainder.movePointRight(2).setScale(0, RoundingMode.DOWN).longValueExact();
        int n = shares.size();
        long each = remainderPaise / n;
        long leftover = remainderPaise - each * n;
        for (Map.Entry<String, BigDecimal> e : shares.entrySet()) {
            long paise = each + (leftover-- > 0 ? 1 : 0);
            e.setValue(e.getValue().add(BigDecimal.valueOf(paise, 2)).setScale(2, RoundingMode.UNNECESSARY));
        }
        return Optional.of(shares);
    }
}
