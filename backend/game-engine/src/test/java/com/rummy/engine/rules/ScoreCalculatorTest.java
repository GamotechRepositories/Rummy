package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.Rank;
import com.rummy.engine.model.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreCalculatorTest {

    private static CardInstance card(Suit suit, Rank rank, String id) {
        return new CardInstance(id, Card.of(suit, rank), 1);
    }

    private static CardInstance joker(String id) {
        return new CardInstance(id, Card.printedJoker(), 1);
    }

    @Test
    @DisplayName("Case 1: When player has NO pure sequence, all cards count (capped at 80)")
    void testNoPureSequenceAllCount() {
        // Player has 1 set and rest invalid cards
        CardGroup set = CardGroup.of(
                card(Suit.SPADES, Rank.KING, "1"), // 10
                card(Suit.HEARTS, Rank.KING, "2"), // 10
                card(Suit.DIAMONDS, Rank.KING, "3") // 10
        );
        CardGroup invalidGroup = CardGroup.of(
                card(Suit.CLUBS, Rank.ACE, "4"),  // 10
                card(Suit.CLUBS, Rank.QUEEN, "5"),// 10
                card(Suit.CLUBS, Rank.JACK, "6"), // 10
                card(Suit.CLUBS, Rank.TEN, "7"),  // 10
                card(Suit.HEARTS, Rank.NINE, "8") // 9
        );
        // Total points = 30 + 49 = 79 points
        int points = ScoreCalculator.calculateHandPoints(List.of(set, invalidGroup), null);
        assertThat(points).isEqualTo(79);

        // If another card pushes it over 80 (e.g. + 10)
        CardGroup extra = CardGroup.of(card(Suit.HEARTS, Rank.EIGHT, "9")); // + 8
        int cappedPoints = ScoreCalculator.calculateHandPoints(List.of(set, invalidGroup, extra), null);
        assertThat(cappedPoints).isEqualTo(80); // Capped at 80!
    }

    @Test
    @DisplayName("Case 2: 1 Pure sequence protects itself (0 pts), but other cards (including sets) count")
    void testOnePureSequenceProtectsOnlyitself() {
        // Pure sequence: 4♥ 5♥ 6♥ (protected = 0 points)
        CardGroup pureSeq = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2"),
                card(Suit.HEARTS, Rank.SIX, "3")
        );

        // Valid Set: 8♠ 8♥ 8♦ (3 * 8 = 24 points, not protected because no second sequence!)
        CardGroup set = CardGroup.of(
                card(Suit.SPADES, Rank.EIGHT, "4"),
                card(Suit.HEARTS, Rank.EIGHT, "5"),
                card(Suit.DIAMONDS, Rank.EIGHT, "6")
        );

        // Invalid cards: 2♣ 3♦ (2 + 3 = 5 points)
        CardGroup invalid = CardGroup.of(
                card(Suit.CLUBS, Rank.TWO, "7"),
                card(Suit.DIAMONDS, Rank.THREE, "8")
        );

        // Total = 0 (pure) + 24 (set) + 5 (invalid) = 29 points
        int points = ScoreCalculator.calculateHandPoints(List.of(pureSeq, set, invalid), null);
        assertThat(points).isEqualTo(29);
    }

    @Test
    @DisplayName("Case 3: At least 2 sequences (1 pure) protects all valid sequences & sets")
    void testTwoSequencesProtectsAllValidMelds() {
        // Pure sequence: 4♥ 5♥ 6♥ (0 points)
        CardGroup pureSeq = CardGroup.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2"),
                card(Suit.HEARTS, Rank.SIX, "3")
        );

        // Impure sequence: 9♣ 10♣ [Joker] (0 points)
        CardGroup impureSeq = CardGroup.of(
                card(Suit.CLUBS, Rank.NINE, "4"),
                card(Suit.CLUBS, Rank.TEN, "5"),
                joker("J1")
        );

        // Valid set: K♠ K♥ K♦ (0 points, protected!)
        CardGroup set = CardGroup.of(
                card(Suit.SPADES, Rank.KING, "6"),
                card(Suit.HEARTS, Rank.KING, "7"),
                card(Suit.DIAMONDS, Rank.KING, "8")
        );

        // Invalid ungrouped cards: 3♠ 4♦ (3 + 4 = 7 points)
        CardGroup invalid = CardGroup.of(
                card(Suit.SPADES, Rank.THREE, "9"),
                card(Suit.DIAMONDS, Rank.FOUR, "10")
        );

        int points = ScoreCalculator.calculateHandPoints(List.of(pureSeq, impureSeq, set, invalid), null);
        assertThat(points).isEqualTo(7);
    }

    @Test
    @DisplayName("Points Rummy rules constants and penalties")
    void testPointsRummyRulesPenalties() {
        PointsRummyRules rules = new PointsRummyRules();

        assertThat(rules.getRulesetId()).isEqualTo("POINTS_13");
        assertThat(rules.getRulesetVersion()).isEqualTo("1.0.0");
        assertThat(rules.getCardsPerPlayer()).isEqualTo(13);
        assertThat(rules.getFirstDropPenalty()).isEqualTo(20);
        assertThat(rules.getMiddleDropPenalty()).isEqualTo(40);
        assertThat(rules.getAutoDropPenalty()).isEqualTo(40);
        assertThat(rules.getWrongDeclarationPenalty()).isEqualTo(80);
        assertThat(rules.getMaximumPenalty()).isEqualTo(80);
    }
}
