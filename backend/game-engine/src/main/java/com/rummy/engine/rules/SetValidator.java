package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.Rank;
import com.rummy.engine.model.Suit;

import java.util.*;

/**
 * Server-authoritative Set validator for Indian Rummy.
 * A set contains 3 or 4 cards of the exact same rank from different suits.
 * Jokers may substitute for missing cards. Duplicate suits among natural cards are strictly invalid.
 */
public final class SetValidator {

    private SetValidator() {}

    /**
     * Determines if a card group is a valid 3-card or 4-card set.
     *
     * @param group    the group of card instances
     * @param cutJoker the cut wild joker for the table
     * @return GroupType.SET if valid, or GroupType.INVALID
     */
    public static GroupType validate(CardGroup group, Card cutJoker) {
        if (group == null || group.size() < 3 || group.size() > 4) {
            return GroupType.INVALID;
        }

        List<CardInstance> cards = group.getCards();
        List<CardInstance> naturals = new ArrayList<>();
        int jokerCount = 0;

        for (CardInstance ci : cards) {
            if (ci.isPrintedJoker() || ci.getCard().isWildJoker(cutJoker)) {
                jokerCount++;
            } else {
                naturals.add(ci);
            }
        }

        // All jokers (3 or 4 jokers) is a valid set in Indian Rummy
        if (naturals.isEmpty()) {
            return GroupType.SET;
        }

        // All natural cards must share the exact same rank
        Rank expectedRank = naturals.get(0).getRank();
        Set<Suit> seenSuits = new HashSet<>();

        for (CardInstance natural : naturals) {
            if (natural.getRank() != expectedRank) {
                return GroupType.INVALID; // Mixed ranks not allowed
            }
            if (!seenSuits.add(natural.getSuit())) {
                return GroupType.INVALID; // Duplicate suit in a set is strictly invalid!
            }
        }

        // Since a standard deck has 4 suits, total cards in a set cannot exceed 4
        if (cards.size() > 4) {
            return GroupType.INVALID;
        }

        return GroupType.SET;
    }
}
