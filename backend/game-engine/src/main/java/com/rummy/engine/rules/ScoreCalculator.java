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
}
