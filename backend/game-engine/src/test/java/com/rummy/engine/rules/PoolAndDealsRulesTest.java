package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.Rank;
import com.rummy.engine.model.Suit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PoolAndDealsRulesTest {

    private final Card cutJoker = Card.of(Suit.HEARTS, Rank.FIVE);

    @Test
    @DisplayName("Pool101 rules constants and penalties")
    void testPool101Rules() {
        Pool101Rules rules = new Pool101Rules();

        assertThat(rules.getRulesetId()).isEqualTo("POOL_101");
        assertThat(rules.getEliminationThreshold()).isEqualTo(101);
        assertThat(rules.getFirstDropPenalty()).isEqualTo(20);
        assertThat(rules.getMiddleDropPenalty()).isEqualTo(40);
        assertThat(rules.getWrongDeclarationPenalty()).isEqualTo(80);
        assertThat(rules.getMaximumPenalty()).isEqualTo(80);
    }

    @Test
    @DisplayName("Pool201 rules constants and penalties")
    void testPool201Rules() {
        Pool201Rules rules = new Pool201Rules();

        assertThat(rules.getRulesetId()).isEqualTo("POOL_201");
        assertThat(rules.getEliminationThreshold()).isEqualTo(201);
        assertThat(rules.getFirstDropPenalty()).isEqualTo(25);
        assertThat(rules.getMiddleDropPenalty()).isEqualTo(50);
        assertThat(rules.getWrongDeclarationPenalty()).isEqualTo(80);
        assertThat(rules.getMaximumPenalty()).isEqualTo(80);
    }

    @Test
    @DisplayName("Deals Rummy rules constants")
    void testDealsRummyRules() {
        DealsRummyRules rules = new DealsRummyRules(3, 20, 40, 40, 80, 80);

        assertThat(rules.getRulesetId()).isEqualTo("DEALS_RUMMY");
        assertThat(rules.getTotalDeals()).isEqualTo(3);
        assertThat(rules.getFirstDropPenalty()).isEqualTo(20);
        assertThat(rules.getMiddleDropPenalty()).isEqualTo(40);
        assertThat(rules.getWrongDeclarationPenalty()).isEqualTo(80);
    }

    @Test
    @DisplayName("Pool rules validate declarations accurately")
    void testPoolDeclarationValidation() {
        Pool101Rules rules = new Pool101Rules();

        // 1 Pure (4 cards), 1 Pure (3 cards), 1 Set (3 cards), 1 Set (3 cards) = 13 cards
        CardGroup pure1 = new CardGroup(List.of(
                com.rummy.engine.model.CardInstance.of("c1", Card.of(Suit.SPADES, Rank.FOUR), 1),
                com.rummy.engine.model.CardInstance.of("c2", Card.of(Suit.SPADES, Rank.FIVE), 1),
                com.rummy.engine.model.CardInstance.of("c3", Card.of(Suit.SPADES, Rank.SIX), 1),
                com.rummy.engine.model.CardInstance.of("c4", Card.of(Suit.SPADES, Rank.SEVEN), 1)
        ));
        CardGroup pure2 = new CardGroup(List.of(
                com.rummy.engine.model.CardInstance.of("c5", Card.of(Suit.DIAMONDS, Rank.EIGHT), 1),
                com.rummy.engine.model.CardInstance.of("c6", Card.of(Suit.DIAMONDS, Rank.NINE), 1),
                com.rummy.engine.model.CardInstance.of("c7", Card.of(Suit.DIAMONDS, Rank.TEN), 1)
        ));
        CardGroup set1 = new CardGroup(List.of(
                com.rummy.engine.model.CardInstance.of("c8", Card.of(Suit.CLUBS, Rank.KING), 1),
                com.rummy.engine.model.CardInstance.of("c9", Card.of(Suit.DIAMONDS, Rank.KING), 1),
                com.rummy.engine.model.CardInstance.of("c10", Card.of(Suit.HEARTS, Rank.KING), 1)
        ));
        CardGroup set2 = new CardGroup(List.of(
                com.rummy.engine.model.CardInstance.of("c11", Card.of(Suit.SPADES, Rank.ACE), 1),
                com.rummy.engine.model.CardInstance.of("c12", Card.of(Suit.HEARTS, Rank.ACE), 1),
                com.rummy.engine.model.CardInstance.of("c13", Card.of(Suit.CLUBS, Rank.ACE), 1)
        ));

        DeclarationResult res = rules.validateDeclaration(List.of(pure1, pure2, set1, set2), cutJoker);
        assertThat(res.isValid()).isTrue();
    }
}
