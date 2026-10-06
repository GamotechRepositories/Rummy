package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-authoritative penalty score calculator for Indian 13-Card / Points Rummy.
 * 
 * Rules:
 * 1. Valid Declarer = 0 penalty points.
 * 2. Invalid / Wrong Declaration = 80 penalty points.
 * 3. First Drop = 20 penalty points.
 * 4. Middle Drop / Auto Drop = 40 penalty points.
 * 5. Loser calculation:
 *    - If player has NO pure sequence: all ungrouped cards count (cap 80).
 *    - If player has 1 pure sequence but < 2 sequences: pure sequence is exempt, rest count (cap 80).
 *    - If player has >= 2 sequences (incl. >= 1 pure): all valid sequences & sets are exempt, only invalid cards count (cap 80).
 *    - Printed and wild jokers always count 0 points.
 *    - Maximum penalty is capped at 80 points.
 */
public final class ScoreCalculator {

    public static final int DEFAULT_FIRST_DROP = 20;
    public static final int DEFAULT_MIDDLE_DROP = 40;
    public static final int DEFAULT_MAX_PENALTY = 80;

    private ScoreCalculator() {}

    /**
     * Calculates the loser penalty points for a grouped hand.
     *
     * @param groups     the player's hand arranged in groups
     * @param cutJoker   the table cut wild joker
     * @param maxPenalty maximum cap (typically 80)
     * @return calculated penalty score between 0 and maxPenalty
     */
    public static int calculateHandPoints(List<CardGroup> groups, Card cutJoker, int maxPenalty) {
        if (groups == null || groups.isEmpty()) {
            return 0;
        }

        int pureSequenceCount = 0;
        int impureSequenceCount = 0;
        List<CardGroup> pureSequences = new ArrayList<>();
        List<CardGroup> validMelds = new ArrayList<>();
        List<CardGroup> invalidGroups = new ArrayList<>();

        for (CardGroup group : groups) {
            GroupType type = DeclarationValidator.classifyGroup(group, cutJoker);
            switch (type) {
                case PURE_SEQUENCE -> {
                    pureSequenceCount++;
                    pureSequences.add(group);
                    validMelds.add(group);
                }
                case IMPURE_SEQUENCE -> {
                    impureSequenceCount++;
                    validMelds.add(group);
                }
                case SET -> validMelds.add(group);
                case INVALID -> invalidGroups.add(group);
            }
        }

        int totalSequences = pureSequenceCount + impureSequenceCount;

        // CASE 1: No pure sequence -> all cards count (except jokers), capped at maxPenalty
        if (pureSequenceCount == 0) {
            int total = 0;
            for (CardGroup group : groups) {
                total += group.calculatePoints(cutJoker);
            }
            return Math.min(total, maxPenalty);
        }

        // CASE 2: At least 1 pure sequence, but less than 2 sequences
        // Pure sequences are exempt; all other cards (even if in a set) count.
        if (totalSequences < 2) {
            int total = 0;
            for (CardGroup group : groups) {
                if (!pureSequences.contains(group)) {
                    total += group.calculatePoints(cutJoker);
                }
            }
            return Math.min(total, maxPenalty);
        }

        // CASE 3: At least 2 sequences (including at least 1 pure sequence)
        // All valid sequences and sets are exempt; only cards in invalid groups count.
        int total = 0;
        for (CardGroup group : invalidGroups) {
            total += group.calculatePoints(cutJoker);
        }
        return Math.min(total, maxPenalty);
    }

    /**
     * Default calculation with standard 80-point maximum loss cap.
     */
    public static int calculateHandPoints(List<CardGroup> groups, Card cutJoker) {
        return calculateHandPoints(groups, cutJoker, DEFAULT_MAX_PENALTY);
    }

    private static final int MAX_SOLVER_CARDS = 16;
    private static final int INFEASIBLE = Integer.MAX_VALUE / 2;
    private static final byte INVALID = 0, SET = 1, IMPURE = 2, PURE = 3;

    /**
     * The lowest penalty this hand can score under the rules above, over every way of arranging it
     * (melds of any length, unmelded cards left ungrouped). Losers are scored with this when someone
     * declares, so nobody pays for an arrangement worse than the best one they hold.
     */
    public static int bestHandPoints(List<CardInstance> hand, Card cutJoker, int maxPenalty) {
        int n = hand == null ? 0 : hand.size();
        if (n == 0) {
            return 0;
        }
        if (n > MAX_SOLVER_CARDS) {
            return calculateHandPoints(List.of(CardGroup.of(hand)), cutJoker, maxPenalty);
        }
        int full = (1 << n) - 1;
        int[] sum = new int[full + 1];
        byte[] type = new byte[full + 1];
        boolean anyPure = false;
        List<CardInstance> cards = new ArrayList<>(n);
        for (int mask = 1; mask <= full; mask++) {
            int low = Integer.numberOfTrailingZeros(mask);
            sum[mask] = sum[mask & (mask - 1)] + hand.get(low).getCard().points(cutJoker);
            if (Integer.bitCount(mask) < 3) {
                continue;
            }
            cards.clear();
            for (int m = mask; m != 0; m &= m - 1) {
                cards.add(hand.get(Integer.numberOfTrailingZeros(m)));
            }
            type[mask] = switch (DeclarationValidator.classifyGroup(CardGroup.of(cards), cutJoker)) {
                case PURE_SEQUENCE -> PURE;
                case IMPURE_SEQUENCE -> IMPURE;
                case SET -> SET;
                default -> INVALID;
            };
            anyPure |= type[mask] == PURE;
        }

        int total = sum[full];
        int best = total;
        if (anyPure) {
            // At least one pure sequence: everything outside the pure sequences counts.
            best = Math.min(best, total - maxPureCovered(full, sum, type, new int[full + 1]));
            // At least two sequences, one of them pure: only ungrouped cards count.
            int[] memo = new int[(full + 1) * 6];
            java.util.Arrays.fill(memo, -1);
            best = Math.min(best, minUngrouped(full, 0, 0, sum, type, memo));
        }
        return Math.min(best, maxPenalty);
    }

    /** Most points that disjoint pure sequences can cover within {@code mask}. */
    private static int maxPureCovered(int mask, int[] sum, byte[] type, int[] memo) {
        if (mask == 0) {
            return 0;
        }
        if (memo[mask] != 0) {
            return memo[mask] - 1;
        }
        int bit = mask & -mask;
        int rest = mask ^ bit;
        int best = maxPureCovered(rest, sum, type, memo);
        for (int s = rest; ; s = (s - 1) & rest) {
            int meld = s | bit;
            if (type[meld] == PURE) {
                best = Math.max(best, sum[meld] + maxPureCovered(mask ^ meld, sum, type, memo));
            }
            if (s == 0) {
                break;
            }
        }
        memo[mask] = best + 1;
        return best;
    }

    /**
     * Fewest points left ungrouped within {@code mask}, given whether a pure sequence is already melded
     * ({@code pure}) and how many sequences are (capped at 2); infeasible unless both requirements are met.
     */
    private static int minUngrouped(int mask, int pure, int sequences, int[] sum, byte[] type, int[] memo) {
        if (mask == 0) {
            return pure == 1 && sequences >= 2 ? 0 : INFEASIBLE;
        }
        int key = mask * 6 + pure * 3 + sequences;
        if (memo[key] >= 0) {
            return memo[key];
        }
        int bit = mask & -mask;
        int rest = mask ^ bit;
        int best = sum[bit] + minUngrouped(rest, pure, sequences, sum, type, memo);
        for (int s = rest; ; s = (s - 1) & rest) {
            int meld = s | bit;
            byte t = type[meld];
            if (t != INVALID) {
                int nextPure = t == PURE ? 1 : pure;
                int nextSequences = t >= IMPURE ? Math.min(2, sequences + 1) : sequences;
                best = Math.min(best, minUngrouped(mask ^ meld, nextPure, nextSequences, sum, type, memo));
            }
            if (s == 0) {
                break;
            }
        }
        best = Math.min(best, INFEASIBLE);
        memo[key] = best;
        return best;
    }
}
