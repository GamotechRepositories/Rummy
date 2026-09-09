package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.Rank;
import com.rummy.engine.model.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceValidatorTest {

    private static CardInstance card(Suit suit, Rank rank, String id) {
        return new CardInstance(id, Card.of(suit, rank), 1);
    }

    private static CardInstance joker(String id) {
        return new CardInstance(id, Card.printedJoker(), 1);
    }

    @Test
    @DisplayName("Valid pure sequences (Ace-low and Ace-high)")
    void testPureSequences() {
        // 4♥ 5♥ 6♥
        CardGroup normalRun = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2"),
                card(Suit.HEARTS, Rank.SIX, "3")
        );
        assertThat(SequenceValidator.validate(normalRun, null)).isEqualTo(GroupType.PURE_SEQUENCE);

        // A♠ 2♠ 3♠ (Ace-low)
        CardGroup aceLow = CardGroup.of(
                card(Suit.SPADES, Rank.ACE, "1"),
                card(Suit.SPADES, Rank.TWO, "2"),
                card(Suit.SPADES, Rank.THREE, "3")
        );
        assertThat(SequenceValidator.validate(aceLow, null)).isEqualTo(GroupType.PURE_SEQUENCE);

        // 10♦ J♦ Q♦ K♦ A♦ (Ace-high, 5 cards)
        CardGroup aceHigh = CardGroup.of(
                card(Suit.DIAMONDS, Rank.TEN, "1"),
                card(Suit.DIAMONDS, Rank.JACK, "2"),
                card(Suit.DIAMONDS, Rank.QUEEN, "3"),
                card(Suit.DIAMONDS, Rank.KING, "4"),
                card(Suit.DIAMONDS, Rank.ACE, "5")
        );
        assertThat(SequenceValidator.validate(aceHigh, null)).isEqualTo(GroupType.PURE_SEQUENCE);
    }

    @Test
    @DisplayName("Wrap-around sequence (K-A-2) must be strictly invalid")
    void testWrapAroundSequenceIsInvalid() {
        // K♣ A♣ 2♣
        CardGroup wrapAround = CardGroup.of(
                card(Suit.CLUBS, Rank.KING, "1"),
                card(Suit.CLUBS, Rank.ACE, "2"),
                card(Suit.CLUBS, Rank.TWO, "3")
        );
        assertThat(SequenceValidator.validate(wrapAround, null)).isEqualTo(GroupType.INVALID);
    }

    @Test
    @DisplayName("Sequences with printed and wild jokers should be classified as IMPURE_SEQUENCE")
    void testImpureSequences() {
        // 4♥ [Printed Joker] 6♥
        CardGroup withPrintedJoker = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                joker("J1"),
                card(Suit.HEARTS, Rank.SIX, "2")
        );
        assertThat(SequenceValidator.validate(withPrintedJoker, null)).isEqualTo(GroupType.IMPURE_SEQUENCE);

        // Wild joker is 9♣
        Card cutJoker = Card.of(Suit.CLUBS, Rank.NINE);

        // 7♠ 8♠ [9♦ acting as wild joker] 10♠
        CardGroup withWildJoker = CardGroup.of(
                card(Suit.SPADES, Rank.SEVEN, "1"),
                card(Suit.SPADES, Rank.EIGHT, "2"),
                card(Suit.DIAMONDS, Rank.NINE, "3"), // wild joker
                card(Suit.SPADES, Rank.TEN, "4")
        );
        assertThat(SequenceValidator.validate(withWildJoker, cutJoker)).isEqualTo(GroupType.IMPURE_SEQUENCE);
    }

    @Test
    @DisplayName("Invalid sequences (too short, wrong suits, duplicate ranks, insufficient jokers)")
    void testInvalidSequences() {
        // Only 2 cards
        CardGroup tooShort = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2")
        );
        assertThat(SequenceValidator.validate(tooShort, null)).isEqualTo(GroupType.INVALID);

        // Wrong suit (4♥ 5♠ 6♥)
        CardGroup mixedSuits = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.SPADES, Rank.FIVE, "2"),
                card(Suit.HEARTS, Rank.SIX, "3")
        );
        assertThat(SequenceValidator.validate(mixedSuits, null)).isEqualTo(GroupType.INVALID);

        // Duplicate rank (4♥ 4♥ 5♥)
        CardGroup duplicateRank = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FOUR, "2"),
                card(Suit.HEARTS, Rank.FIVE, "3")
        );
        assertThat(SequenceValidator.validate(duplicateRank, null)).isEqualTo(GroupType.INVALID);

        // Gap too large for available jokers (4♥ [Joker] 7♥ - gap of 2 cards requires 2 jokers)
        CardGroup gapTooLarge = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                joker("J1"),
                card(Suit.HEARTS, Rank.SEVEN, "2")
        );
        assertThat(SequenceValidator.validate(gapTooLarge, null)).isEqualTo(GroupType.INVALID);
    }
}
