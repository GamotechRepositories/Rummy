package com.rummy.engine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeckTest {

    @Test
    @DisplayName("Standard 13-card Indian Rummy deck must contain exactly 106 cards")
    void testStandardDeckCardCount() {
        Deck deck = Deck.createStandard13CardDeck();

        assertThat(deck.remaining()).isEqualTo(106);
        assertThat(deck.isEmpty()).isFalse();

        List<CardInstance> snapshot = deck.getCardsSnapshot();
        assertThat(snapshot).hasSize(106);

        // Check printed jokers count (exactly 2)
        long jokerCount = snapshot.stream().filter(CardInstance::isPrintedJoker).count();
        assertThat(jokerCount).isEqualTo(2);

        // Check standard cards count (104)
        long standardCount = snapshot.stream().filter(c -> !c.isPrintedJoker()).count();
        assertThat(standardCount).isEqualTo(104);
    }

    @Test
    @DisplayName("All card instances must have strictly unique instance IDs")
    void testCardInstanceUniqueness() {
        Deck deck = Deck.createStandard13CardDeck();
        List<CardInstance> snapshot = deck.getCardsSnapshot();

        Set<String> instanceIds = new HashSet<>();
        for (CardInstance card : snapshot) {
            boolean isNew = instanceIds.add(card.getInstanceId());
            assertThat(isNew).as("Duplicate card instance ID found: %s", card.getInstanceId()).isTrue();
        }
        assertThat(instanceIds).hasSize(106);
    }

    @Test
    @DisplayName("Cards must be evenly partitioned across deck 1 and deck 2")
    void testDeckPartitioning() {
        Deck deck = Deck.createStandard13CardDeck();
        List<CardInstance> snapshot = deck.getCardsSnapshot();

        long deck1Count = snapshot.stream().filter(c -> c.getDeckNumber() == 1).count();
        long deck2Count = snapshot.stream().filter(c -> c.getDeckNumber() == 2).count();

        assertThat(deck1Count).isEqualTo(53);
        assertThat(deck2Count).isEqualTo(53);
    }

    @Test
    @DisplayName("Shuffle must preserve total cards and card composition while changing order")
    void testShuffleIntegrity() {
        Deck deck = Deck.createStandard13CardDeck();
        List<String> initialOrder = deck.getCardsSnapshot().stream().map(CardInstance::getInstanceId).toList();

        deck.shuffle();
        List<String> shuffledOrder = deck.getCardsSnapshot().stream().map(CardInstance::getInstanceId).toList();

        assertThat(shuffledOrder).hasSize(106);
        assertThat(new HashSet<>(shuffledOrder)).isEqualTo(new HashSet<>(initialOrder));
        // Order should be different after shuffle
        assertThat(shuffledOrder).isNotEqualTo(initialOrder);
    }

    @Test
    @DisplayName("Draw and drawBatch must remove cards sequentially from deck")
    void testDrawOperations() {
        Deck deck = Deck.createStandard13CardDeck();

        CardInstance drawnOne = deck.draw();
        assertThat(drawnOne).isNotNull();
        assertThat(deck.remaining()).isEqualTo(105);

        // Draw 13 cards (e.g. for player hand)
        List<CardInstance> hand = deck.drawBatch(13);
        assertThat(hand).hasSize(13);
        assertThat(deck.remaining()).isEqualTo(92);

        // Drawing more than available must throw
        assertThatThrownBy(() -> deck.drawBatch(93))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Requested 93 cards but deck only has 92 remaining");
    }

    @Test
    @DisplayName("Drawing until empty and then drawing must throw NoSuchElementException")
    void testDrawUntilEmpty() {
        Deck deck = Deck.createStandard13CardDeck();
        deck.drawBatch(106);

        assertThat(deck.isEmpty()).isTrue();
        assertThat(deck.remaining()).isEqualTo(0);

        assertThatThrownBy(deck::draw)
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Cannot draw from an empty deck");
    }

    @Test
    @DisplayName("Recycle discard pile must replenish deck with discard cards and shuffle")
    void testRecycleDiscardPile() {
        Deck deck = Deck.createStandard13CardDeck();
        List<CardInstance> dealtCards = deck.drawBatch(80);

        // Discard pile has 80 cards
        deck.recycleDiscardPile(dealtCards);

        assertThat(deck.remaining()).isEqualTo(80);
        assertThat(deck.isEmpty()).isFalse();
    }
}
