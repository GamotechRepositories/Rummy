package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.Rank;
import com.rummy.engine.model.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SetValidatorTest {

    private static CardInstance card(Suit suit, Rank rank, String id) {
        return new CardInstance(id, Card.of(suit, rank), 1);
    }

    private static CardInstance joker(String id) {
        return new CardInstance(id, Card.printedJoker(), 1);
    }

    @Test
    @DisplayName("Valid 3-card and 4-card sets with distinct suits")
    void testValidSets() {
        // 7♠ 7♥ 7♦
        CardGroup set3 = CardGroup.of(
                card(Suit.SPADES, Rank.SEVEN, "1"),
                card(Suit.HEARTS, Rank.SEVEN, "2"),
                card(Suit.DIAMONDS, Rank.SEVEN, "3")
        );
        assertThat(SetValidator.validate(set3, null)).isEqualTo(GroupType.SET);

        // K♠ K♥ K♦ K♣ (4 suits)
        CardGroup set4 = CardGroup.of(
                card(Suit.SPADES, Rank.KING, "1"),
                card(Suit.HEARTS, Rank.KING, "2"),
                card(Suit.DIAMONDS, Rank.KING, "3"),
                card(Suit.CLUBS, Rank.KING, "4")
        );
        assertThat(SetValidator.validate(set4, null)).isEqualTo(GroupType.SET);
    }

    @Test
    @DisplayName("Valid sets with joker substitution")
    void testSetsWithJoker() {
        // 7♠ 7♥ [Printed Joker]
        CardGroup setWithJoker = CardGroup.of(
                card(Suit.SPADES, Rank.SEVEN, "1"),
                card(Suit.HEARTS, Rank.SEVEN, "2"),
                joker("J1")
        );
        assertThat(SetValidator.validate(setWithJoker, null)).isEqualTo(GroupType.SET);

        // Wild joker is 3♦. Set is 8♠ 8♥ [3♦] [Joker]
        Card cutJoker = Card.of(Suit.DIAMONDS, Rank.THREE);
        CardGroup setWithWildJoker = CardGroup.of(
                card(Suit.SPADES, Rank.EIGHT, "1"),
                card(Suit.HEARTS, Rank.EIGHT, "2"),
                card(Suit.DIAMONDS, Rank.THREE, "3"), // Wild joker
                joker("J1")
        );
        assertThat(SetValidator.validate(setWithWildJoker, cutJoker)).isEqualTo(GroupType.SET);
    }

    @Test
    @DisplayName("Invalid sets (duplicate suits, mixed ranks, > 4 cards, < 3 cards)")
    void testInvalidSets() {
        // Duplicate suit (7♠ 7♠ 7♥) is strictly invalid even with 2 decks!
        CardGroup duplicateSuit = CardGroup.of(
                card(Suit.SPADES, Rank.SEVEN, "D1_7S"),
                card(Suit.SPADES, Rank.SEVEN, "D2_7S"),
                card(Suit.HEARTS, Rank.SEVEN, "D1_7H")
        );
        assertThat(SetValidator.validate(duplicateSuit, null)).isEqualTo(GroupType.INVALID);

        // Mixed ranks (7♠ 8♥ 7♦)
        CardGroup mixedRanks = CardGroup.of(
                card(Suit.SPADES, Rank.SEVEN, "1"),
                card(Suit.HEARTS, Rank.EIGHT, "2"),
                card(Suit.DIAMONDS, Rank.SEVEN, "3")
        );
        assertThat(SetValidator.validate(mixedRanks, null)).isEqualTo(GroupType.INVALID);

        // Too short (2 cards)
        CardGroup tooShort = CardGroup.of(
                card(Suit.SPADES, Rank.SEVEN, "1"),
                card(Suit.HEARTS, Rank.SEVEN, "2")
        );
        assertThat(SetValidator.validate(tooShort, null)).isEqualTo(GroupType.INVALID);

        // More than 4 cards (5 cards cannot form a set since only 4 suits exist)
        CardGroup tooLong = CardGroup.of(
                card(Suit.SPADES, Rank.SEVEN, "1"),
                card(Suit.HEARTS, Rank.SEVEN, "2"),
                card(Suit.DIAMONDS, Rank.SEVEN, "3"),
                card(Suit.CLUBS, Rank.SEVEN, "4"),
                joker("J1")
        );
        assertThat(SetValidator.validate(tooLong, null)).isEqualTo(GroupType.INVALID);
    }
}
