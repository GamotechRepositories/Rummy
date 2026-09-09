package com.rummy.engine.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a physical, uniquely identifiable instance of a card in a game deal.
 * Even when two 52-card decks have identical cards (e.g. two 7♥ cards),
 * each physical card instance has a distinct instanceId and deckNumber.
 * This guarantees zero duplication and full auditability across hands, closed deck, and discard pile.
 */
public final class CardInstance implements Serializable {

    private final String instanceId;
    private final Card card;
    private final int deckNumber;

    public CardInstance(String instanceId, Card card, int deckNumber) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
        this.card = Objects.requireNonNull(card, "card must not be null");
        if (deckNumber < 1) {
            throw new IllegalArgumentException("deckNumber must be >= 1");
        }
        this.deckNumber = deckNumber;
    }

    public static CardInstance of(String instanceId, Card card, int deckNumber) {
        return new CardInstance(instanceId, card, deckNumber);
    }

    public String getInstanceId() {
        return instanceId;
    }

    public Card getCard() {
        return card;
    }

    public int getDeckNumber() {
        return deckNumber;
    }

    public Suit getSuit() {
        return card.suit();
    }

    public Rank getRank() {
        return card.rank();
    }

    public boolean isPrintedJoker() {
        return card.isPrintedJoker();
    }

    public String code() {
        return card.code();
    }

    public String display() {
        return card.display();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CardInstance that = (CardInstance) o;
        return Objects.equals(instanceId, that.instanceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(instanceId);
    }

    @Override
    public String toString() {
        return card.display() + "[" + instanceId + "]";
    }
}
