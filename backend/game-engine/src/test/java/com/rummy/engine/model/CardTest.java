package com.rummy.engine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTest {

    @Test
    @DisplayName("Should correctly create standard card and evaluate properties")
    void testStandardCardProperties() {
        Card card = Card.of(Suit.HEARTS, Rank.SEVEN);

        assertThat(card.suit()).isEqualTo(Suit.HEARTS);
        assertThat(card.rank()).isEqualTo(Rank.SEVEN);
        assertThat(card.isPrintedJoker()).isFalse();
        assertThat(card.code()).isEqualTo("7H");
        assertThat(card.display()).isEqualTo("7♥");
        assertThat(card.suit().isRed()).isTrue();
        assertThat(card.suit().isBlack()).isFalse();
    }

    @Test
    @DisplayName("Should correctly evaluate point values for unmelded cards")
    void testCardPoints() {
        Card ace = Card.of(Suit.SPADES, Rank.ACE);
        Card king = Card.of(Suit.DIAMONDS, Rank.KING);
        Card ten = Card.of(Suit.CLUBS, Rank.TEN);
        Card five = Card.of(Suit.HEARTS, Rank.FIVE);
        Card printedJoker = Card.printedJoker();

        // Cut card is 8 of Spades
        Card cutJoker = Card.of(Suit.SPADES, Rank.EIGHT);

        assertThat(ace.points(cutJoker)).isEqualTo(10);
        assertThat(king.points(cutJoker)).isEqualTo(10);
        assertThat(ten.points(cutJoker)).isEqualTo(10);
        assertThat(five.points(cutJoker)).isEqualTo(5);
        assertThat(printedJoker.points(cutJoker)).isEqualTo(0);

        // Card matching the cut joker rank has 0 points
        Card wildJoker = Card.of(Suit.HEARTS, Rank.EIGHT);
        assertThat(wildJoker.isWildJoker(cutJoker)).isTrue();
        assertThat(wildJoker.points(cutJoker)).isEqualTo(0);
    }

    @Test
    @DisplayName("When cut card is printed joker, Aces act as wild jokers with 0 points")
    void testCutJokerIsPrintedJoker() {
        Card cutJoker = Card.printedJoker();
        Card aceHearts = Card.of(Suit.HEARTS, Rank.ACE);
        Card sevenSpades = Card.of(Suit.SPADES, Rank.SEVEN);

        assertThat(aceHearts.isWildJoker(cutJoker)).isTrue();
        assertThat(aceHearts.points(cutJoker)).isEqualTo(0);
        assertThat(sevenSpades.isWildJoker(cutJoker)).isFalse();
        assertThat(sevenSpades.points(cutJoker)).isEqualTo(7);
    }

    @Test
    @DisplayName("Printed joker validation rules")
    void testPrintedJokerValidation() {
        Card joker = Card.printedJoker();
        assertThat(joker.isPrintedJoker()).isTrue();
        assertThat(joker.suit()).isNull();
        assertThat(joker.rank()).isNull();
        assertThat(joker.code()).isEqualTo("JK");
        assertThat(joker.display()).isEqualTo("★ Joker");

        // Attempting to create an invalid printed joker with suit or rank must fail
        assertThatThrownBy(() -> new Card(Suit.HEARTS, Rank.ACE, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Printed joker cannot have a suit or rank");

        // Attempting to create standard card with null suit or rank must fail
        assertThatThrownBy(() -> new Card(null, Rank.ACE, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Card(Suit.HEARTS, null, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Card comparison and ordering")
    void testCardOrdering() {
        Card fourHearts = Card.of(Suit.HEARTS, Rank.FOUR);
        Card fiveHearts = Card.of(Suit.HEARTS, Rank.FIVE);
        Card fourDiamonds = Card.of(Suit.DIAMONDS, Rank.FOUR);
        Card joker = Card.printedJoker();

        assertThat(fourHearts).isLessThan(fiveHearts);
        assertThat(fourHearts).isLessThan(fourDiamonds);
        assertThat(fourHearts).isLessThan(joker);
    }
}
