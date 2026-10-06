package com.rummy.engine.rules;

import com.rummy.engine.bot.HandEvaluator;
import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.Rank;
import com.rummy.engine.model.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Losers are scored on their best possible arrangement")
class BestHandPointsTest {

    /** Nines are wild in every hand below. */
    private static final Card CUT = Card.of(Suit.CLUBS, Rank.NINE);
    private int seq;

    private CardInstance c(Suit suit, Rank rank) {
        return new CardInstance("C" + (seq++), Card.of(suit, rank), 1);
    }

    @Test
    @DisplayName("A five-card pure sequence is one meld, not four cards plus a loose one")
    void longSequenceCountsWhole() {
        List<CardInstance> hand = List.of(
                c(Suit.SPADES, Rank.THREE), c(Suit.SPADES, Rank.FOUR), c(Suit.SPADES, Rank.FIVE),
                c(Suit.SPADES, Rank.SIX), c(Suit.SPADES, Rank.SEVEN),
                c(Suit.HEARTS, Rank.TEN), c(Suit.HEARTS, Rank.JACK), c(Suit.HEARTS, Rank.QUEEN),
                c(Suit.DIAMONDS, Rank.KING), c(Suit.CLUBS, Rank.KING), c(Suit.HEARTS, Rank.KING),
                c(Suit.DIAMONDS, Rank.TWO), c(Suit.CLUBS, Rank.FIVE));

        assertThat(ScoreCalculator.bestHandPoints(hand, CUT, 80)).isEqualTo(7);

        HandEvaluator.EvaluationResult quick = HandEvaluator.evaluateDeadwood(hand, CUT);
        List<CardGroup> quickGroups = new ArrayList<>(quick.meldedGroups());
        quickGroups.add(CardGroup.of(quick.deadwoodCards()));
        assertThat(ScoreCalculator.calculateHandPoints(quickGroups, CUT, 80)).isGreaterThan(7);
    }

    @Test
    @DisplayName("No pure sequence: every card counts, capped at 80")
    void noPureSequenceIsCapped() {
        List<CardInstance> hand = List.of(
                c(Suit.SPADES, Rank.ACE), c(Suit.SPADES, Rank.QUEEN), c(Suit.SPADES, Rank.EIGHT),
                c(Suit.HEARTS, Rank.KING), c(Suit.HEARTS, Rank.JACK), c(Suit.HEARTS, Rank.SEVEN),
                c(Suit.DIAMONDS, Rank.ACE), c(Suit.DIAMONDS, Rank.QUEEN), c(Suit.DIAMONDS, Rank.EIGHT),
                c(Suit.CLUBS, Rank.KING), c(Suit.CLUBS, Rank.JACK), c(Suit.CLUBS, Rank.SEVEN),
                c(Suit.SPADES, Rank.SIX));

        assertThat(ScoreCalculator.bestHandPoints(hand, CUT, 80)).isEqualTo(80);
    }

    @Test
    @DisplayName("Pure sequence without a second sequence: sets still count")
    void pureWithoutSecondSequence() {
        List<CardInstance> hand = List.of(
                c(Suit.SPADES, Rank.THREE), c(Suit.SPADES, Rank.FOUR), c(Suit.SPADES, Rank.FIVE),
                c(Suit.HEARTS, Rank.FOUR), c(Suit.DIAMONDS, Rank.FOUR), c(Suit.CLUBS, Rank.FOUR),
                c(Suit.HEARTS, Rank.SIX), c(Suit.DIAMONDS, Rank.SIX), c(Suit.CLUBS, Rank.SIX),
                c(Suit.DIAMONDS, Rank.EIGHT), c(Suit.CLUBS, Rank.TEN), c(Suit.HEARTS, Rank.JACK),
                c(Suit.SPADES, Rank.KING));

        assertThat(ScoreCalculator.bestHandPoints(hand, CUT, 80)).isEqualTo(68);
    }

    @Test
    @DisplayName("A wild joker completing a second sequence leaves only loose cards counting")
    void wildJokerMakesSecondSequence() {
        List<CardInstance> hand = List.of(
                c(Suit.SPADES, Rank.THREE), c(Suit.SPADES, Rank.FOUR), c(Suit.SPADES, Rank.FIVE),
                c(Suit.HEARTS, Rank.SEVEN), c(Suit.HEARTS, Rank.EIGHT), c(Suit.DIAMONDS, Rank.NINE),
                c(Suit.HEARTS, Rank.KING), c(Suit.DIAMONDS, Rank.KING), c(Suit.CLUBS, Rank.KING),
                c(Suit.DIAMONDS, Rank.QUEEN), c(Suit.CLUBS, Rank.FIVE), c(Suit.HEARTS, Rank.TWO),
                c(Suit.SPADES, Rank.TEN));

        assertThat(ScoreCalculator.bestHandPoints(hand, CUT, 80)).isEqualTo(27);
    }
}
