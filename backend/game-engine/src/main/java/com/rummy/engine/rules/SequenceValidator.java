package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.Rank;
import com.rummy.engine.model.Suit;

import java.util.*;

/**
 * Server-authoritative sequence validator for Indian Rummy.
 * Validates Pure Sequences (>=3 consecutive cards, same suit, 0 jokers)
 * and Impure Sequences (>=3 consecutive cards, same suit, joker substitution).
 */
public final class SequenceValidator {

    private SequenceValidator() {}

    /**
     * Determines if a card group is a valid pure or impure sequence.
     *
     * @param group    the group of card instances
     * @param cutJoker the cut wild joker for the table (can be null if no wild joker)
     * @return GroupType.PURE_SEQUENCE, GroupType.IMPURE_SEQUENCE, or GroupType.INVALID
     */
    public static GroupType validate(CardGroup group, Card cutJoker) {
        if (group == null || group.size() < 3) {
            return GroupType.INVALID;
        }

        List<CardInstance> cards = group.getCards();

        // 1. Check if it qualifies as a PURE SEQUENCE (strictly no jokers used as wildcards)
        if (isPureSequence(cards)) {
            return GroupType.PURE_SEQUENCE;
        }

        // 2. Check if it qualifies as an IMPURE SEQUENCE
        if (isImpureSequence(cards, cutJoker)) {
            return GroupType.IMPURE_SEQUENCE;
        }

        return GroupType.INVALID;
    }

    /**
     * Pure sequence requires:
     * - Minimum 3 cards
     * - No printed jokers
     * - Exact same suit across all cards
     * - Consecutive ranks with no duplicates
     * - Either Ace-low (1..13) or Ace-high (2..14). Wrap-around is invalid.
     */
    public static boolean isPureSequence(List<CardInstance> cards) {
        if (cards == null || cards.size() < 3) {
            return false;
        }

        Suit expectedSuit = null;
        List<Integer> ranks = new ArrayList<>(cards.size());

        for (CardInstance instance : cards) {
            if (instance.isPrintedJoker()) {
                return false;
            }
            Card card = instance.getCard();
            if (expectedSuit == null) {
                expectedSuit = card.suit();
            } else if (card.suit() != expectedSuit) {
                return false;
            }
            ranks.add(card.rank().getOrder());
        }

        // Check consecutive in Ace-low (Ace = 1)
        if (checkConsecutiveRanks(ranks)) {
            return true;
        }

        // Check consecutive in Ace-high (Ace = 14) if Ace is present
        boolean hasAce = cards.stream().anyMatch(c -> c.getRank() == Rank.ACE);
        if (hasAce) {
            List<Integer> aceHighRanks = cards.stream()
                    .map(c -> c.getRank().getAceHighOrder())
                    .toList();
            return checkConsecutiveRanks(aceHighRanks);
        }

        return false;
    }

    private static boolean checkConsecutiveRanks(List<Integer> ranks) {
        List<Integer> sorted = new ArrayList<>(ranks);
        Collections.sort(sorted);

        for (int i = 0; i < sorted.size() - 1; i++) {
            if (sorted.get(i + 1) - sorted.get(i) != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Impure sequence requires:
     * - Minimum 3 cards
     * - Natural cards must share the exact same suit
     * - No duplicate natural ranks
     * - Sufficient jokers to fill all gaps
     * - Total span must fit without wrap-around (range 1..13 for Ace-low, 2..14 for Ace-high)
     */
    public static boolean isImpureSequence(List<CardInstance> cards, Card cutJoker) {
        if (cards == null || cards.size() < 3) {
            return false;
        }

        int totalCards = cards.size();
        List<CardInstance> naturals = new ArrayList<>();
        int jokerCount = 0;

        for (CardInstance ci : cards) {
            if (ci.isPrintedJoker() || ci.getCard().isWildJoker(cutJoker)) {
                jokerCount++;
            } else {
                naturals.add(ci);
            }
        }

        // If all cards are jokers, 3+ jokers can represent an impure sequence
        if (naturals.isEmpty()) {
            return totalCards >= 3;
        }

        // If only 1 natural card and the rest are jokers (e.g. 1 natural + 2 jokers = 3 cards), valid impure sequence
        if (naturals.size() == 1) {
            return totalCards >= 3;
        }

        // Verify all natural cards share the same suit
        Suit suit = naturals.get(0).getSuit();
        for (CardInstance natural : naturals) {
            if (natural.getSuit() != suit) {
                return false;
            }
        }

        // Check Ace-low (Ace = 1)
        List<Integer> aceLowRanks = naturals.stream().map(c -> c.getRank().getOrder()).toList();
        if (canFormSequenceWithJokers(aceLowRanks, jokerCount, totalCards, 1, 13)) {
            return true;
        }

        // Check Ace-high (Ace = 14) if Ace is present
        boolean hasAce = naturals.stream().anyMatch(c -> c.getRank() == Rank.ACE);
        if (hasAce) {
            List<Integer> aceHighRanks = naturals.stream().map(c -> c.getRank().getAceHighOrder()).toList();
            if (canFormSequenceWithJokers(aceHighRanks, jokerCount, totalCards, 2, 14)) {
                return true;
            }
        }

        return false;
    }

    private static boolean canFormSequenceWithJokers(List<Integer> naturalRanks,
                                                     int jokerCount,
                                                     int totalCards,
                                                     int minAllowedRank,
                                                     int maxAllowedRank) {
        List<Integer> sorted = new ArrayList<>(naturalRanks);
        Collections.sort(sorted);

        // Check for duplicate natural ranks
        for (int i = 0; i < sorted.size() - 1; i++) {
            if (sorted.get(i).equals(sorted.get(i + 1))) {
                return false; // Duplicate ranks not allowed in a sequence
            }
        }

        // Calculate missing gaps between consecutive natural cards
        int requiredJokers = 0;
        for (int i = 0; i < sorted.size() - 1; i++) {
            int gap = sorted.get(i + 1) - sorted.get(i) - 1;
            requiredJokers += gap;
        }

        if (requiredJokers > jokerCount) {
            return false; // Not enough jokers to bridge internal gaps
        }

        // Check if the total required cards (naturals + bridged gaps + remaining jokers on ends) fits within rank limits
        int minNatural = sorted.get(0);
        int maxNatural = sorted.get(sorted.size() - 1);
        int naturalSpan = maxNatural - minNatural + 1;

        if (naturalSpan > totalCards) {
            return false;
        }

        // Leftover jokers will be placed before minNatural or after maxNatural
        int remainingJokers = jokerCount - requiredJokers;
        int availableRoom = (minNatural - minAllowedRank) + (maxAllowedRank - maxNatural);

        return remainingJokers <= availableRoom;
    }
}
