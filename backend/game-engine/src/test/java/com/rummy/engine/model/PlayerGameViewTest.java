package com.rummy.engine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerGameViewTest {

    @Test
    @DisplayName("PlayerGameView must only expose viewer cards and hide opponent cards")
    void testZeroKnowledgeProjection() {
        PlayerState p1 = new PlayerState("P1", "Alice", 0, false);
        PlayerState p2 = new PlayerState("P2", "Bob", 1, true);

        CardInstance c1 = new CardInstance("C1", Card.of(Suit.HEARTS, Rank.ACE), 1);
        CardInstance c2 = new CardInstance("C2", Card.of(Suit.SPADES, Rank.KING), 1);
        CardInstance secretCard = new CardInstance("SECRET", Card.of(Suit.CLUBS, Rank.SEVEN), 1);

        p1.addCard(c1);
        p1.addCard(c2);
        p2.addCard(secretCard);

        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(p1, p2), deck);
        state.setStatus(GameStatus.IN_PROGRESS);

        // Set turn to P1
        state.setTurnState(TurnState.startTurn(1, "P1", Instant.now(), 30));

        // Create view for P1
        PlayerGameView viewP1 = PlayerGameView.from(state, "P1");

        // P1 sees own 2 cards
        assertThat(viewP1.viewerPlayerId()).isEqualTo("P1");
        assertThat(viewP1.hand()).containsExactly(c1, c2);
        assertThat(viewP1.isMyTurn()).isTrue();

        // P1 sees P2 in opponents list with cardCount 1, but NO card instances during IN_PROGRESS
        assertThat(viewP1.opponents()).hasSize(1);
        PlayerGameView.OpponentView opp = viewP1.opponents().get(0);
        assertThat(opp.playerId()).isEqualTo("P2");
        assertThat(opp.displayName()).isEqualTo("Bob");
        assertThat(opp.cardCount()).isEqualTo(1);
        assertThat(opp.isBot()).isTrue();
        assertThat(opp.hand()).isEmpty();
        assertThat(viewP1.winningGroups()).isEmpty();

        // Create view for P2
        PlayerGameView viewP2 = PlayerGameView.from(state, "P2");
        assertThat(viewP2.viewerPlayerId()).isEqualTo("P2");
        assertThat(viewP2.hand()).containsExactly(secretCard);
        assertThat(viewP2.isMyTurn()).isFalse();

        // P2 sees P1 has 2 cards, without knowing what P1 has
        assertThat(viewP2.opponents().get(0).cardCount()).isEqualTo(2);
        assertThat(viewP2.opponents().get(0).hand()).isEmpty();
    }

    @Test
    @DisplayName("PlayerGameView must reveal all opponents hands and winning groups when game is COMPLETED")
    void testShowdownCardRevealWhenCompleted() {
        PlayerState p1 = new PlayerState("P1", "Alice", 0, false);
        PlayerState p2 = new PlayerState("P2", "Bob", 1, true);

        CardInstance c1 = new CardInstance("C1", Card.of(Suit.HEARTS, Rank.ACE), 1);
        CardInstance secretCard = new CardInstance("SECRET", Card.of(Suit.CLUBS, Rank.SEVEN), 1);

        p1.addCard(c1);
        p2.addCard(secretCard);

        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(p1, p2), deck);
        state.setStatus(GameStatus.COMPLETED);
        state.setWinnerPlayerId("P1");
        state.setWinningGroups(List.of(com.rummy.engine.rules.CardGroup.of(c1)));

        PlayerGameView viewP1 = PlayerGameView.from(state, "P1");

        // When COMPLETED, P1 can now see P2's showdown hand
        assertThat(viewP1.opponents()).hasSize(1);
        assertThat(viewP1.opponents().get(0).hand()).containsExactly(secretCard);
        assertThat(viewP1.winningGroups()).hasSize(1);
        assertThat(viewP1.winningGroups().get(0).getCards()).containsExactly(c1);

        // P2 can also see P1's hand and winning groups
        PlayerGameView viewP2 = PlayerGameView.from(state, "P2");
        assertThat(viewP2.opponents().get(0).hand()).containsExactly(c1);
        assertThat(viewP2.winningGroups()).hasSize(1);
    }
}
