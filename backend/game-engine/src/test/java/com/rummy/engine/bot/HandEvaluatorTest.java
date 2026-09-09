package com.rummy.engine.bot;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.Rank;
import com.rummy.engine.model.Suit;
import com.rummy.engine.rules.PointsRummyRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HandEvaluatorTest {

    private final PointsRummyRules rules = new PointsRummyRules();

    private static CardInstance card(Suit suit, Rank rank, String id) {
        return new CardInstance(id, Card.of(suit, rank), 1);
    }

    @Test
    @DisplayName("should identify whether a card improves the hand")
    void testDoesCardImproveHand() {
        // Hand contains 4♥ 5♥ (needs 3♥ or 6♥ to complete pure sequence)
        List<CardInstance> hand = List.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2"),
                card(Suit.SPADES, Rank.KING, "3")
        );

        CardInstance helpfulCard = card(Suit.HEARTS, Rank.SIX, "4");
        CardInstance unhelpfulCard = card(Suit.CLUBS, Rank.TWO, "5");

        assertThat(HandEvaluator.doesCardImproveHand(helpfulCard, hand, null)).isTrue();
        assertThat(HandEvaluator.doesCardImproveHand(unhelpfulCard, hand, null)).isFalse();
    }

    @Test
    @DisplayName("should find winning declaration in a 14-card winning hand")
    void testFindWinningDeclaration() {
        // 14 cards:
        // Pure Sequence: 4♥ 5♥ 6♥ 7♥ (4 cards)
        // Impure Sequence: 9♣ 10♣ J♣ (3 cards)
        // Set: K♠ K♥ K♦ (3 cards)
        // Set: 3♠ 3♥ 3♦ (3 cards)
        // Finish card: 2♠ (1 card)
        List<CardInstance> hand14 = List.of(
                card(Suit.HEARTS, Rank.FOUR, "1"),
                card(Suit.HEARTS, Rank.FIVE, "2"),
                card(Suit.HEARTS, Rank.SIX, "3"),
                card(Suit.HEARTS, Rank.SEVEN, "4"),

                card(Suit.CLUBS, Rank.NINE, "5"),
                card(Suit.CLUBS, Rank.TEN, "6"),
                card(Suit.CLUBS, Rank.JACK, "7"),

                card(Suit.SPADES, Rank.KING, "8"),
                card(Suit.HEARTS, Rank.KING, "9"),
                card(Suit.DIAMONDS, Rank.KING, "10"),

                card(Suit.SPADES, Rank.THREE, "11"),
                card(Suit.HEARTS, Rank.THREE, "12"),
                card(Suit.DIAMONDS, Rank.THREE, "13"),

                card(Suit.SPADES, Rank.TWO, "FINISH")
        );

        Optional<HandEvaluator.EvaluationResult> result = HandEvaluator.findWinningDeclaration(hand14, null, rules);

        assertThat(result).isPresent();
        assertThat(result.get().isWinningDeclaration()).isTrue();
        assertThat(result.get().finishCard().getInstanceId()).isEqualTo("FINISH");
        assertThat(result.get().meldedGroups()).hasSize(4);
    }
}
