package com.rummy.engine.model;

import java.security.SecureRandom;
import java.util.*;

/**
 * Server-authoritative Deck manager supporting multi-pack decks, printed jokers,
 * and cryptographically secure (CSPRNG) Fisher-Yates shuffling.
 * 
 * Standard 13-card Indian Rummy configuration:
 * 2 standard 52-card decks + 2 printed jokers = 106 cards total.
 */
public final class Deck {

    private final List<CardInstance> cards;
    private final SecureRandom secureRandom;

    public Deck(List<CardInstance> cards, SecureRandom secureRandom) {
        this.cards = new ArrayList<>(Objects.requireNonNull(cards, "cards must not be null"));
        this.secureRandom = secureRandom != null ? secureRandom : new SecureRandom();
    }

    /**
     * Factory for standard 2-pack Indian 13-Card Rummy deck (106 cards).
     * 2 decks x 52 standard cards + 1 printed joker per deck = 106 cards.
     */
    public static Deck createStandard13CardDeck() {
        return createMultiPackDeck(2, 1, new SecureRandom());
    }

    /**
     * Factory for standard 2-pack Indian 13-Card Rummy deck with custom SecureRandom.
     */
    public static Deck createStandard13CardDeck(SecureRandom secureRandom) {
        return createMultiPackDeck(2, 1, secureRandom);
    }

    /**
     * Factory for multi-pack decks with configurable decks and printed jokers per deck.
     *
     * @param deckCount        number of 52-card standard packs (e.g. 2 for 13-card, 3 for 21-card)
     * @param jokersPerDeck    printed jokers added per pack (typically 1)
     * @param secureRandom     cryptographically secure random generator
     */
    public static Deck createMultiPackDeck(int deckCount, int jokersPerDeck, SecureRandom secureRandom) {
        if (deckCount < 1) {
            throw new IllegalArgumentException("deckCount must be at least 1");
        }
        if (jokersPerDeck < 0) {
            throw new IllegalArgumentException("jokersPerDeck cannot be negative");
        }

        List<CardInstance> cardList = new ArrayList<>(deckCount * (52 + jokersPerDeck));

        for (int d = 1; d <= deckCount; d++) {
            // 52 standard cards
            for (Suit suit : Suit.values()) {
                for (Rank rank : Rank.values()) {
                    String id = String.format("D%d_%s_%s", d, suit.name().substring(0, 1), rank.getSymbol());
                    cardList.add(new CardInstance(id, Card.of(suit, rank), d));
                }
            }
            // Printed jokers for this deck
            for (int j = 1; j <= jokersPerDeck; j++) {
                String id = String.format("D%d_JOKER_%d", d, j);
                cardList.add(new CardInstance(id, Card.printedJoker(), d));
            }
        }

        return new Deck(cardList, secureRandom);
    }

    /**
     * Performs an in-place Fisher-Yates shuffle using CSPRNG (SecureRandom).
     * Never uses Math.random() or non-cryptographic RNGs.
     */
    public void shuffle() {
        int n = cards.size();
        for (int i = n - 1; i > 0; i--) {
            int j = secureRandom.nextInt(i + 1);
            CardInstance temp = cards.get(i);
            cards.set(i, cards.get(j));
            cards.set(j, temp);
        }
    }

    /**
     * Draw the top card from the deck.
     *
     * @return the drawn CardInstance
     * @throws NoSuchElementException if the deck is empty
     */
    public CardInstance draw() {
        if (cards.isEmpty()) {
            throw new NoSuchElementException("Cannot draw from an empty deck");
        }
        return cards.remove(cards.size() - 1);
    }

    /**
     * Draw multiple cards consecutively (e.g. dealing 13 cards to a player).
     *
     * @param count number of cards to draw
     * @return list of drawn cards in order
     */
    public List<CardInstance> drawBatch(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative");
        }
        if (count > cards.size()) {
            throw new IllegalStateException(
                    String.format("Requested %d cards but deck only has %d remaining", count, cards.size()));
        }
        List<CardInstance> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(draw());
        }
        return batch;
    }

    /**
     * Recycle discard pile into the closed deck when closed deck is exhausted.
     * Per ruleset specification R8:
     * - The top card of the discard pile remains in the discard pile.
     * - The remaining discard cards are shuffled and become the new closed deck.
     */
    public void recycleDiscardPile(List<CardInstance> discardCards) {
        Objects.requireNonNull(discardCards, "discardCards must not be null");
        this.cards.clear();
        this.cards.addAll(discardCards);
        shuffle();
    }

    /**
     * Number of cards remaining in the deck.
     */
    public int remaining() {
        return cards.size();
    }

    /**
     * Whether the deck is empty.
     */
    public boolean isEmpty() {
        return cards.isEmpty();
    }

    /**
     * Unmodifiable view of remaining cards (for state snapshots and auditing).
     */
    public List<CardInstance> getCardsSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(cards));
    }

    @Override
    public String toString() {
        return "Deck[remaining=" + cards.size() + "]";
    }
}
