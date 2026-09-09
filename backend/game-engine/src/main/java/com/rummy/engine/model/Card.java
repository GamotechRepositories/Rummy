package com.rummy.engine.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Immutable value object representing a card definition in a Rummy deck.
 * For standard cards, suit and rank are non-null and isPrintedJoker is false.
 * For printed jokers, isPrintedJoker is true, and suit and rank are null.
 */
public record Card(Suit suit, Rank rank, boolean isPrintedJoker) implements Serializable, Comparable<Card> {

    public static final Card PRINTED_JOKER = new Card(null, null, true);

    public Card {
        if (isPrintedJoker) {
            if (suit != null || rank != null) {
                throw new IllegalArgumentException("Printed joker cannot have a suit or rank");
            }
        } else {
            Objects.requireNonNull(suit, "Standard card must have a suit");
            Objects.requireNonNull(rank, "Standard card must have a rank");
        }
    }

    /**
     * Create a standard natural card.
     */
    public static Card of(Suit suit, Rank rank) {
        return new Card(suit, rank, false);
    }

    /**
     * Return a printed joker singleton.
     */
    public static Card printedJoker() {
        return PRINTED_JOKER;
    }

    /**
     * Card code representation (e.g. "7H", "10S", "AD", "JK").
     */
    public String code() {
        if (isPrintedJoker) {
            return "JK";
        }
        return rank.getSymbol() + suit.name().substring(0, 1);
    }

    /**
     * Human-readable display symbol (e.g. "7♥", "10♠", "A♦", "★ Joker").
     */
    public String display() {
        if (isPrintedJoker) {
            return "★ Joker";
        }
        return rank.getSymbol() + suit.getSymbol();
    }

    /**
     * Check if this card matches the rank of a cut wild joker.
     */
    public boolean isWildJoker(Card cutJoker) {
        if (isPrintedJoker) {
            return true;
        }
        if (cutJoker == null) {
            return false;
        }
        if (cutJoker.isPrintedJoker()) {
            // If cut card is a printed joker, typically Aces act as wild jokers
            return this.rank == Rank.ACE;
        }
        return this.rank == cutJoker.rank();
    }

    /**
     * Point value of the card when unmelded (0 for jokers, rank value for naturals).
     */
    public int points(Card cutJoker) {
        if (isPrintedJoker || isWildJoker(cutJoker)) {
            return 0;
        }
        return rank.getDefaultPoints();
    }

    @Override
    public int compareTo(Card other) {
        if (this.isPrintedJoker && other.isPrintedJoker) {
            return 0;
        }
        if (this.isPrintedJoker) {
            return 1;
        }
        if (other.isPrintedJoker) {
            return -1;
        }
        int suitComparison = this.suit.compareTo(other.suit);
        if (suitComparison != 0) {
            return suitComparison;
        }
        return this.rank.compareTo(other.rank);
    }

    @Override
    public String toString() {
        return display();
    }
}
