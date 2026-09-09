package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;

import java.io.Serializable;
import java.util.*;

/**
 * Represents an arrangement of cards grouped together by a player.
 */
public final class CardGroup implements Serializable {

    private final List<CardInstance> cards;

    public CardGroup(List<CardInstance> cards) {
        this.cards = new ArrayList<>(Objects.requireNonNull(cards, "cards must not be null"));
    }

    public static CardGroup of(CardInstance... cards) {
        return new CardGroup(Arrays.asList(cards));
    }

    public static CardGroup of(List<CardInstance> cards) {
        return new CardGroup(cards);
    }

    public List<CardInstance> getCards() {
        return Collections.unmodifiableList(cards);
    }

    public int size() {
        return cards.size();
    }

    public boolean isEmpty() {
        return cards.isEmpty();
    }

    /**
     * Calculates the penalty points of all cards in this group.
     * Printed jokers and cut wild jokers contribute 0 points.
     */
    public int calculatePoints(Card cutJoker) {
        int total = 0;
        for (CardInstance card : cards) {
            total += card.getCard().points(cutJoker);
        }
        return total;
    }

    @Override
    public String toString() {
        return "CardGroup" + cards;
    }
}
