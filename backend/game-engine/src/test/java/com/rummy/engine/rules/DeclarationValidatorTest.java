package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.Rank;
import com.rummy.engine.model.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeclarationValidatorTest {

    private static CardInstance card(Suit suit, Rank rank, String id) {
        return new CardInstance(id, Card.of(suit, rank), 1);
    }

    private static CardInstance joker(String id) {
        return new CardInstance(id, Card.printedJoker(), 1);
    }

    @Test
    @DisplayName("Valid 13-card declaration with 1 pure sequence, 1 impure sequence, and 2 sets")
    void testValid13CardDeclaration() {
        // Group 1: Pure Sequence (4♥ 5♥ 6♥ 7♥) - 4 cards
        CardGroup g1 = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2"),
                card(Suit.HEARTS, Rank.SIX, "3"),
                card(Suit.HEARTS, Rank.SEVEN, "4")
        );

        // Group 2: Impure Sequence (9♣ 10♣ [Joker]) - 3 cards
        CardGroup g2 = CardGroup.of(
                card(Suit.CLUBS, Rank.NINE, "5"),
                card(Suit.CLUBS, Rank.TEN, "6"),
                joker("J1")
        );

        // Group 3: Set (K♠ K♥ K♦) - 3 cards
        CardGroup g3 = CardGroup.of(
                card(Suit.SPADES, Rank.KING, "7"),
                card(Suit.HEARTS, Rank.KING, "8"),
                card(Suit.DIAMONDS, Rank.KING, "9")
        );

        // Group 4: Set (3♠ 3♥ 3♦) - 3 cards
        CardGroup g4 = CardGroup.of(
                card(Suit.SPADES, Rank.THREE, "10"),
                card(Suit.HEARTS, Rank.THREE, "11"),
                card(Suit.DIAMONDS, Rank.THREE, "12"),
                card(Suit.CLUBS, Rank.THREE, "13")
        );
        // Wait, 4 + 3 + 3 + 4 = 14 cards! Let's make g4 have 3 cards:
        CardGroup g4Three = CardGroup.of(
                card(Suit.SPADES, Rank.THREE, "10"),
                card(Suit.HEARTS, Rank.THREE, "11"),
                card(Suit.DIAMONDS, Rank.THREE, "12")
        );
        // 4 + 3 + 3 + 3 = 13 cards

        DeclarationResult result = DeclarationValidator.validate(List.of(g1, g2, g3, g4Three), null);

        assertThat(result.isValid()).isTrue();
        assertThat(result.pureSequencesCount()).isEqualTo(1);
        assertThat(result.impureSequencesCount()).isEqualTo(1);
        assertThat(result.validSetsCount()).isEqualTo(2);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("Invalid declaration if missing pure sequence")
    void testMissingPureSequence() {
        // Group 1: Impure sequence (4♥ 5♥ [Joker])
        CardGroup g1 = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2"),
                joker("J1")
        );

        // Group 2: Impure sequence (9♣ 10♣ [Joker2])
        CardGroup g2 = CardGroup.of(
                card(Suit.CLUBS, Rank.NINE, "3"),
                card(Suit.CLUBS, Rank.TEN, "4"),
                joker("J2")
        );

        // Group 3: Set (4 cards)
        CardGroup g3 = CardGroup.of(
                card(Suit.SPADES, Rank.KING, "5"),
                card(Suit.HEARTS, Rank.KING, "6"),
                card(Suit.DIAMONDS, Rank.KING, "7"),
                card(Suit.CLUBS, Rank.KING, "8")
        );

        // Group 4: Set (3 cards)
        CardGroup g4 = CardGroup.of(
                card(Suit.SPADES, Rank.THREE, "9"),
                card(Suit.HEARTS, Rank.THREE, "10"),
                card(Suit.DIAMONDS, Rank.THREE, "11")
        );

        // 3 + 3 + 4 + 3 = 13 cards
        DeclarationResult result = DeclarationValidator.validate(List.of(g1, g2, g3, g4), null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.pureSequencesCount()).isEqualTo(0);
        assertThat(result.errors()).anyMatch(err -> err.contains("at least one pure sequence"));
    }

    @Test
    @DisplayName("Invalid declaration if only 1 sequence exists (even if pure)")
    void testOnlyOneSequence() {
        // Pure Sequence (4 cards)
        CardGroup g1 = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2"),
                card(Suit.HEARTS, Rank.SIX, "3"),
                card(Suit.HEARTS, Rank.SEVEN, "4")
        );

        // Three Sets (3 cards each = 9 cards)
        CardGroup g2 = CardGroup.of(
                card(Suit.SPADES, Rank.KING, "5"),
                card(Suit.HEARTS, Rank.KING, "6"),
                card(Suit.DIAMONDS, Rank.KING, "7")
        );
        CardGroup g3 = CardGroup.of(
                card(Suit.SPADES, Rank.THREE, "8"),
                card(Suit.HEARTS, Rank.THREE, "9"),
                card(Suit.DIAMONDS, Rank.THREE, "10")
        );
        CardGroup g4 = CardGroup.of(
                card(Suit.SPADES, Rank.NINE, "11"),
                card(Suit.HEARTS, Rank.NINE, "12"),
                card(Suit.DIAMONDS, Rank.NINE, "13")
        );

        // Total 13 cards, 1 pure sequence, 3 sets
        DeclarationResult result = DeclarationValidator.validate(List.of(g1, g2, g3, g4), null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.pureSequencesCount()).isEqualTo(1);
        assertThat(result.errors()).anyMatch(err -> err.contains("at least 2 sequences"));
    }

    @Test
    @DisplayName("Invalid declaration if card count is not exactly 13")
    void testWrongCardCount() {
        // Only 10 cards declared
        CardGroup g1 = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2"),
                card(Suit.HEARTS, Rank.SIX, "3")
        );
        CardGroup g2 = CardGroup.of(
                card(Suit.CLUBS, Rank.EIGHT, "4"),
                card(Suit.CLUBS, Rank.NINE, "5"),
                card(Suit.CLUBS, Rank.TEN, "6")
        );
        CardGroup g3 = CardGroup.of(
                card(Suit.SPADES, Rank.KING, "7"),
                card(Suit.HEARTS, Rank.KING, "8"),
                card(Suit.DIAMONDS, Rank.KING, "9"),
                card(Suit.CLUBS, Rank.KING, "10")
        );

        DeclarationResult result = DeclarationValidator.validate(List.of(g1, g2, g3), null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).anyMatch(err -> err.contains("must contain exactly 13 cards"));
    }
}
