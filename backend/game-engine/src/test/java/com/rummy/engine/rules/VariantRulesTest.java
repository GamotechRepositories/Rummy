package com.rummy.engine.rules;

import com.rummy.engine.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("All 10 Rummy Game Variants Rules Verification")
class VariantRulesTest {

    @Test
    @DisplayName("Variant 01 & 02: Points Rummy & 13 Card Rummy")
    void testPointsRummy() {
        PointsRummyRules rules = new PointsRummyRules();
        assertThat(rules.getCardsPerPlayer()).isEqualTo(13);
        assertThat(rules.getDeckCount()).isEqualTo(2);
        assertThat(rules.getFirstDropPenalty()).isEqualTo(20);
        assertThat(rules.getMiddleDropPenalty()).isEqualTo(40);
        assertThat(rules.getMaximumPenalty()).isEqualTo(80);
    }

    @Test
    @DisplayName("Variant 03 & 04: Pool 101 & Pool 201")
    void testPoolRummy() {
        Pool101Rules pool101 = new Pool101Rules();
        assertThat(pool101.getEliminationThreshold()).isEqualTo(101);
        assertThat(pool101.getFirstDropPenalty()).isEqualTo(20);

        Pool201Rules pool201 = new Pool201Rules();
        assertThat(pool201.getEliminationThreshold()).isEqualTo(201);
        assertThat(pool201.getFirstDropPenalty()).isEqualTo(25);
    }

    @Test
    @DisplayName("Variant 05: Deals Rummy")
    void testDealsRummy() {
        DealsRummyRules deals = new DealsRummyRules(3, 20, 40, 40, 80, 80);
        assertThat(deals.getTotalDeals()).isEqualTo(3);
        assertThat(deals.getCardsPerPlayer()).isEqualTo(13);
    }

    @Test
    @DisplayName("Variant 06: 21-Card Rummy with Tunnela and Strict 3 Pure Sequence Scoring")
    void testTwentyOneCardRummy() {
        TwentyOneCardRummyRules rules = new TwentyOneCardRummyRules();
        assertThat(rules.getCardsPerPlayer()).isEqualTo(21);
        assertThat(rules.getDeckCount()).isEqualTo(3);
        assertThat(rules.getMinimumSequences()).isEqualTo(3);
        assertThat(rules.getRequiredPureSequences()).isEqualTo(3);
        assertThat(rules.getFirstDropPenalty()).isEqualTo(30);
        assertThat(rules.getMiddleDropPenalty()).isEqualTo(60);
        assertThat(rules.getMaximumPenalty()).isEqualTo(120);

        // Verify Tunnela (3 identical cards from different decks) is classified as Pure Sequence
        CardInstance k1 = new CardInstance("D1_S_K", Card.of(Suit.SPADES, Rank.KING), 1);
        CardInstance k2 = new CardInstance("D2_S_K", Card.of(Suit.SPADES, Rank.KING), 2);
        CardInstance k3 = new CardInstance("D3_S_K", Card.of(Suit.SPADES, Rank.KING), 3);
        CardGroup tunnelaGroup = CardGroup.of(k1, k2, k3);

        assertThat(TwentyOneCardRummyRules.isTunnela(tunnelaGroup)).isTrue();
        assertThat(TwentyOneCardRummyRules.classifyGroup(tunnelaGroup, null)).isEqualTo(GroupType.PURE_SEQUENCE);

        // Verify printed jokers tunnela
        CardInstance pj1 = new CardInstance("PJ_1", Card.printedJoker(), 1);
        CardInstance pj2 = new CardInstance("PJ_2", Card.printedJoker(), 2);
        CardInstance pj3 = new CardInstance("PJ_3", Card.printedJoker(), 3);
        CardGroup pjTunnela = CardGroup.of(pj1, pj2, pj3);
        assertThat(TwentyOneCardRummyRules.isTunnela(pjTunnela)).isTrue();

        // Hand points: only 1 pure sequence -> sets and other cards are NOT exempt (must pay points)
        CardInstance h1 = new CardInstance("D1_H_A", Card.of(Suit.HEARTS, Rank.ACE), 1);
        CardInstance h2 = new CardInstance("D1_H_2", Card.of(Suit.HEARTS, Rank.TWO), 1);
        CardInstance h3 = new CardInstance("D1_H_3", Card.of(Suit.HEARTS, Rank.THREE), 1);
        CardGroup pureSeq1 = CardGroup.of(h1, h2, h3);

        CardInstance setC1 = new CardInstance("D1_C_K", Card.of(Suit.CLUBS, Rank.KING), 1);
        CardInstance setC2 = new CardInstance("D1_D_K", Card.of(Suit.DIAMONDS, Rank.KING), 1);
        CardInstance setC3 = new CardInstance("D1_H_K", Card.of(Suit.HEARTS, Rank.KING), 1);
        CardGroup kingSet = CardGroup.of(setC1, setC2, setC3);

        // When only 1 pure sequence present in 21-card rummy, kingSet points (3 x 10 = 30) MUST be counted!
        int scoreWithOnePure = rules.calculateLosingScore(List.of(pureSeq1, kingSet), null);
        assertThat(scoreWithOnePure).isEqualTo(30);

        // When 3 pure sequences are present (e.g. 2 runs + 1 tunnela), valid sets ARE exempt (0 points)
        CardInstance s4 = new CardInstance("D1_S_4", Card.of(Suit.SPADES, Rank.FOUR), 1);
        CardInstance s5 = new CardInstance("D1_S_5", Card.of(Suit.SPADES, Rank.FIVE), 1);
        CardInstance s6 = new CardInstance("D1_S_6", Card.of(Suit.SPADES, Rank.SIX), 1);
        CardGroup pureSeq2 = CardGroup.of(s4, s5, s6);

        int scoreWithThreePure = rules.calculateLosingScore(List.of(pureSeq1, pureSeq2, tunnelaGroup, kingSet), null);
        assertThat(scoreWithThreePure).isEqualTo(0);
    }

    @Test
    @DisplayName("Variant 07: Gin Rummy")
    void testGinRummy() {
        GinRummyRules rules = new GinRummyRules();
        assertThat(rules.getCardsPerPlayer()).isEqualTo(10);
        assertThat(rules.getMinPlayers()).isEqualTo(2);
        assertThat(rules.getMaxPlayers()).isEqualTo(2);
        assertThat(rules.getDeckCount()).isEqualTo(1);

        // Test deadwood calculation: 3-card pure run has 0 deadwood, stray King has 10 deadwood
        CardInstance h4 = CardInstance.of("c1", Card.of(Suit.HEARTS, Rank.FOUR), 1);
        CardInstance h5 = CardInstance.of("c2", Card.of(Suit.HEARTS, Rank.FIVE), 1);
        CardInstance h6 = CardInstance.of("c3", Card.of(Suit.HEARTS, Rank.SIX), 1);
        CardInstance strayKing = CardInstance.of("c4", Card.of(Suit.SPADES, Rank.KING), 1);

        CardGroup runGroup = CardGroup.of(List.of(h4, h5, h6));
        CardGroup deadwoodGroup = CardGroup.of(List.of(strayKing));

        int deadwood = rules.calculateDeadwood(List.of(runGroup, deadwoodGroup));
        assertThat(deadwood).isEqualTo(10);
        assertThat(rules.validateDeclaration(List.of(runGroup, deadwoodGroup), null).isValid()).isTrue();
    }

    @Test
    @DisplayName("Variant 08: 500 Rummy")
    void testRummy500() {
        Rummy500Rules rules = new Rummy500Rules();
        assertThat(rules.getRulesetId()).isEqualTo("RUMMY_500");
        assertThat(Rummy500Rules.TARGET_SCORE).isEqualTo(500);

        Card ace = Card.of(Suit.SPADES, Rank.ACE);
        assertThat(rules.getCardPoints(ace)).isEqualTo(15);
    }

    @Test
    @DisplayName("Variant 09: Kalooki")
    void testKalooki() {
        KalookiRules rules = new KalookiRules();
        assertThat(rules.getCardsPerPlayer()).isEqualTo(13);
        assertThat(rules.getDeckCount()).isEqualTo(2);
        assertThat(rules.getPrintedJokersPerDeck()).isEqualTo(2);
    }

    @Test
    @DisplayName("Variant 10: Canasta")
    void testCanasta() {
        CanastaRules rules = new CanastaRules();
        assertThat(rules.getCardsPerPlayer()).isEqualTo(11);
        assertThat(CanastaRules.TARGET_SCORE).isEqualTo(5000);
        assertThat(CanastaRules.NATURAL_CANASTA_BONUS).isEqualTo(500);
    }

    @Test
    @DisplayName("RulesetRegistry resolves all supported variants")
    void testRulesetRegistry() {
        assertThat(RulesetRegistry.getRegisteredRulesetCount()).isGreaterThanOrEqualTo(9);
        assertThat(RulesetRegistry.requireRuleset("POINTS_13")).isInstanceOf(PointsRummyRules.class);
        assertThat(RulesetRegistry.requireRuleset("POOL_101")).isInstanceOf(Pool101Rules.class);
        assertThat(RulesetRegistry.requireRuleset("POOL_201")).isInstanceOf(Pool201Rules.class);
        assertThat(RulesetRegistry.requireRuleset("DEALS_RUMMY")).isInstanceOf(DealsRummyRules.class);
        assertThat(RulesetRegistry.requireRuleset("RUMMY_21")).isInstanceOf(TwentyOneCardRummyRules.class);
        assertThat(RulesetRegistry.requireRuleset("GIN_RUMMY")).isInstanceOf(GinRummyRules.class);
        assertThat(RulesetRegistry.requireRuleset("RUMMY_500")).isInstanceOf(Rummy500Rules.class);
        assertThat(RulesetRegistry.requireRuleset("KALOOKI")).isInstanceOf(KalookiRules.class);
        assertThat(RulesetRegistry.requireRuleset("CANASTA")).isInstanceOf(CanastaRules.class);
    }
}
