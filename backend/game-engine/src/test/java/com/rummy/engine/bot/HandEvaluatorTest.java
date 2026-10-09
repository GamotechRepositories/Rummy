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

    @Test
    @DisplayName("should calculate connector score for consecutive runs, gaps, and pairs")
    void testCalculateConnectorScore() {
        CardInstance eightSpades = card(Suit.SPADES, Rank.EIGHT, "8S");
        CardInstance nineSpades = card(Suit.SPADES, Rank.NINE, "9S");
        CardInstance tenSpades = card(Suit.SPADES, Rank.TEN, "10S");
        CardInstance eightHearts = card(Suit.HEARTS, Rank.EIGHT, "8H");
        CardInstance twoDiamonds = card(Suit.DIAMONDS, Rank.TWO, "2D");

        // Consecutive run: 8♠ with 9♠ -> 20 (4 outs * 5)
        assertThat(HandEvaluator.calculateConnectorScore(eightSpades, List.of(eightSpades, nineSpades), null)).isEqualTo(20);

        // One-gap run: 8♠ with 10♠ -> 10
        assertThat(HandEvaluator.calculateConnectorScore(eightSpades, List.of(eightSpades, tenSpades), null)).isEqualTo(10);

        // Same rank pair: 8♠ with 8♥ -> 14 (4 outs * 3.5)
        assertThat(HandEvaluator.calculateConnectorScore(eightSpades, List.of(eightSpades, eightHearts), null)).isEqualTo(14);

        // Isolated card: 8♠ with 2♦ -> 0
        assertThat(HandEvaluator.calculateConnectorScore(eightSpades, List.of(eightSpades, twoDiamonds), null)).isEqualTo(0);
    }

    @Test
    @DisplayName("should calculate opponent danger score correctly")
    void testCalculateOpponentDangerScore() {
        Card pickedCard = Card.of(Suit.SPADES, Rank.EIGHT);
        java.util.Set<Card> opponentPicks = java.util.Set.of(pickedCard);

        // Adjacent card (7♠ or 9♠) -> danger 40
        assertThat(HandEvaluator.calculateOpponentDangerScore(card(Suit.SPADES, Rank.SEVEN, "7S"), opponentPicks)).isEqualTo(40);
        assertThat(HandEvaluator.calculateOpponentDangerScore(card(Suit.SPADES, Rank.NINE, "9S"), opponentPicks)).isEqualTo(40);

        // Gap card (6♠ or 10♠) -> danger 20
        assertThat(HandEvaluator.calculateOpponentDangerScore(card(Suit.SPADES, Rank.SIX, "6S"), opponentPicks)).isEqualTo(20);
        assertThat(HandEvaluator.calculateOpponentDangerScore(card(Suit.SPADES, Rank.TEN, "10S"), opponentPicks)).isEqualTo(20);

        // Same rank card (8♥) -> danger 25
        assertThat(HandEvaluator.calculateOpponentDangerScore(card(Suit.HEARTS, Rank.EIGHT, "8H"), opponentPicks)).isEqualTo(25);

        // Unrelated card (2♦) -> danger 0
        assertThat(HandEvaluator.calculateOpponentDangerScore(card(Suit.DIAMONDS, Rank.TWO, "2D"), opponentPicks)).isEqualTo(0);
    }

    @Test
    @DisplayName("should penalize connectors when needed cards are dead in discard history (Card Counting)")
    void testDeadCardAwareness() {
        CardInstance sevenSpades = card(Suit.SPADES, Rank.SEVEN, "7S");
        CardInstance nineSpades = card(Suit.SPADES, Rank.NINE, "9S");

        // Open 7♠ and 9♠ needs 8♠
        assertThat(HandEvaluator.calculateConnectorScore(sevenSpades, List.of(sevenSpades, nineSpades), null, List.of(), 2)).isEqualTo(10);

        // If both 8♠ are in discard history (dead cards)
        List<CardInstance> discardHistory = List.of(
                card(Suit.SPADES, Rank.EIGHT, "8S_D1"),
                card(Suit.SPADES, Rank.EIGHT, "8S_D2")
        );
        // Connector score should drop to 0 because 8♠ is completely dead
        assertThat(HandEvaluator.calculateConnectorScore(sevenSpades, List.of(sevenSpades, nineSpades), null, discardHistory, 2)).isEqualTo(0);
    }

    @Test
    @DisplayName("should assign 2.5x danger multiplier to cards picked by immediate downstream player")
    void testDownstreamNextPlayerDefense() {
        Card pickedCard = Card.of(Suit.SPADES, Rank.EIGHT);
        java.util.Map<String, java.util.Set<Card>> picksByPlayer = java.util.Map.of("P_NEXT", java.util.Set.of(pickedCard));

        CardInstance adjacentCard = card(Suit.SPADES, Rank.NINE, "9S");

        // If picked by next downstream player: 40 * 2.5 = 100
        int downstreamDanger = HandEvaluator.calculateOpponentDangerScore(adjacentCard, picksByPlayer, "P_NEXT");
        assertThat(downstreamDanger).isEqualTo(100);

        // If picked by a distant player (not next downstream): 40 * 1.0 = 40
        int regularDanger = HandEvaluator.calculateOpponentDangerScore(adjacentCard, picksByPlayer, "P_OTHER");
        assertThat(regularDanger).isEqualTo(40);
    }

    @Test
    @DisplayName("should accurately detect Pure Sequences and when card forms a Pure Sequence")
    void testPureSequenceHelpers() {
        List<CardInstance> handWithPure = List.of(
                card(Suit.HEARTS, Rank.FOUR, "4H"),
                card(Suit.HEARTS, Rank.FIVE, "5H"),
                card(Suit.HEARTS, Rank.SIX, "6H"),
                card(Suit.CLUBS, Rank.NINE, "9C")
        );
        assertThat(HandEvaluator.hasPureSequence(handWithPure, null)).isTrue();

        List<CardInstance> handWithoutPure = List.of(
                card(Suit.HEARTS, Rank.FOUR, "4H"),
                card(Suit.HEARTS, Rank.FIVE, "5H"),
                card(Suit.CLUBS, Rank.NINE, "9C")
        );
        assertThat(HandEvaluator.hasPureSequence(handWithoutPure, null)).isFalse();

        // 6♥ forms pure sequence with 4♥ and 5♥
        CardInstance sixHearts = card(Suit.HEARTS, Rank.SIX, "6H");
        assertThat(HandEvaluator.doesCardFormPureSequence(sixHearts, handWithoutPure)).isTrue();

        // 6♦ does not form pure sequence
        CardInstance sixDiamonds = card(Suit.DIAMONDS, Rank.SIX, "6D");
        assertThat(HandEvaluator.doesCardFormPureSequence(sixDiamonds, handWithoutPure)).isFalse();
    }

    @Test
    @DisplayName("should drop a hopeless hand from turn 6 against four or more opponents")
    void testMiddleDropAtCrowdedTable() {
        assertThat(HandEvaluator.shouldTakeMiddleDrop(hopelessHand(), null, rules, 0, 0, 5, 5)).isFalse();
        assertThat(HandEvaluator.shouldTakeMiddleDrop(hopelessHand(), null, rules, 0, 0, 6, 5)).isTrue();
        assertThat(HandEvaluator.shouldTakeMiddleDrop(hopelessHand(), null, rules, 0, 0, 6, 4)).isTrue();
    }

    @Test
    @DisplayName("should keep playing a hopeless hand short-handed until turn 8")
    void testMiddleDropShortHanded() {
        assertThat(HandEvaluator.shouldTakeMiddleDrop(hopelessHand(), null, rules, 0, 0, 7, 3)).isFalse();
        assertThat(HandEvaluator.shouldTakeMiddleDrop(hopelessHand(), null, rules, 0, 0, 8, 3)).isTrue();
        assertThat(HandEvaluator.shouldTakeMiddleDrop(hopelessHand(), null, rules, 0, 0, 7, 1)).isFalse();
        assertThat(HandEvaluator.shouldTakeMiddleDrop(hopelessHand(), null, rules, 0, 0, 8, 1)).isTrue();
    }

    @Test
    @DisplayName("should keep playing with a pure sequence or any joker")
    void testNoMiddleDropWithPureSequenceOrJoker() {
        List<CardInstance> withPure = new java.util.ArrayList<>(hopelessHand());
        withPure.set(4, card(Suit.SPADES, Rank.QUEEN, "Q"));
        withPure.set(8, card(Suit.SPADES, Rank.JACK, "J"));
        assertThat(HandEvaluator.shouldTakeMiddleDrop(withPure, null, rules, 0, 0, 10, 5)).isFalse();

        List<CardInstance> withJoker = new java.util.ArrayList<>(hopelessHand());
        withJoker.set(1, new CardInstance("J1", Card.printedJoker(), 1));
        assertThat(HandEvaluator.shouldTakeMiddleDrop(withJoker, null, rules, 0, 0, 10, 5)).isFalse();
    }

    @Test
    @DisplayName("should never take a pool drop that eliminates the bot")
    void testPoolMiddleDrop() {
        // Pool 101 at 65: dropping (40) reaches 105
        assertThat(HandEvaluator.shouldTakeMiddleDrop(hopelessHand(), null, rules, 65, 101, 10, 5)).isFalse();
        // Pool 201 at 140: dropping (40) stays at 180
        assertThat(HandEvaluator.shouldTakeMiddleDrop(hopelessHand(), null, rules, 140, 201, 8, 1)).isTrue();
    }

    private static List<CardInstance> hopelessHand() {
        return List.of(
                card(Suit.SPADES, Rank.KING, "1"),
                card(Suit.HEARTS, Rank.KING, "2"),
                card(Suit.DIAMONDS, Rank.QUEEN, "3"),
                card(Suit.CLUBS, Rank.JACK, "4"),
                card(Suit.SPADES, Rank.TEN, "5"),
                card(Suit.HEARTS, Rank.NINE, "6"),
                card(Suit.DIAMONDS, Rank.EIGHT, "7"),
                card(Suit.CLUBS, Rank.SEVEN, "8"),
                card(Suit.SPADES, Rank.FOUR, "9"),
                card(Suit.HEARTS, Rank.THREE, "10"),
                card(Suit.DIAMONDS, Rank.TWO, "11"),
                card(Suit.CLUBS, Rank.FIVE, "12"),
                card(Suit.SPADES, Rank.ACE, "13")
        );
    }
}
