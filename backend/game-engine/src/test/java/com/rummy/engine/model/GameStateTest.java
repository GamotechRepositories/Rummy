package com.rummy.engine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameStateTest {

    @Test
    @DisplayName("Should seat players and query by ID and seat correctly")
    void testPlayerQuerying() {
        PlayerState p1 = new PlayerState("P1", "Alice", 0, false);
        PlayerState p2 = new PlayerState("P2", "Bob", 1, true);
        Deck deck = Deck.createStandard13CardDeck();

        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(p1, p2), deck);

        assertThat(state.getPlayer("P1")).contains(p1);
        assertThat(state.getPlayer("P2")).contains(p2);
        assertThat(state.getPlayer("P3")).isEmpty();

        assertThat(state.getPlayerBySeat(0)).contains(p1);
        assertThat(state.getPlayerBySeat(1)).contains(p2);
        assertThat(state.getPlayerBySeat(2)).isEmpty();

        assertThat(state.requirePlayer("P1")).isEqualTo(p1);
        assertThatThrownBy(() -> state.requirePlayer("P999"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("Should cycle clockwise between active players and skip dropped players")
    void testNextActivePlayerCycling() {
        PlayerState p1 = new PlayerState("P1", "Alice", 0, false);
        PlayerState p2 = new PlayerState("P2", "Bob", 1, false);
        PlayerState p3 = new PlayerState("P3", "Charlie", 2, false);
        PlayerState p4 = new PlayerState("P4", "Dave", 3, false);

        p1.setStatus(PlayerStatus.ACTIVE);
        p2.setStatus(PlayerStatus.DROPPED); // P2 dropped!
        p3.setStatus(PlayerStatus.ACTIVE);
        p4.setStatus(PlayerStatus.ACTIVE);

        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(p1, p2, p3, p4), deck);

        // Next after P1 (seat 0) should be P3 (seat 2), skipping dropped P2 (seat 1)
        Optional<PlayerState> nextFromP1 = state.nextActivePlayer("P1");
        assertThat(nextFromP1).isPresent();
        assertThat(nextFromP1.get().getPlayerId()).isEqualTo("P3");

        // Next after P3 should be P4
        Optional<PlayerState> nextFromP3 = state.nextActivePlayer("P3");
        assertThat(nextFromP3).isPresent();
        assertThat(nextFromP3.get().getPlayerId()).isEqualTo("P4");

        // Next after P4 (seat 3) wraps around to P1 (seat 0)
        Optional<PlayerState> nextFromP4 = state.nextActivePlayer("P4");
        assertThat(nextFromP4).isPresent();
        assertThat(nextFromP4.get().getPlayerId()).isEqualTo("P1");
    }

    @Test
    @DisplayName("Should manage open discard pile correctly")
    void testDiscardPileManagement() {
        PlayerState p1 = new PlayerState("P1", "Alice", 0, false);
        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(p1), deck);

        assertThat(state.topDiscard()).isNull();

        CardInstance card1 = new CardInstance("C1", Card.of(Suit.HEARTS, Rank.FOUR), 1);
        CardInstance card2 = new CardInstance("C2", Card.of(Suit.SPADES, Rank.KING), 1);

        state.addToDiscardPile(card1);
        assertThat(state.topDiscard()).isEqualTo(card1);

        state.addToDiscardPile(card2);
        assertThat(state.topDiscard()).isEqualTo(card2);
        assertThat(state.getDiscardPile()).hasSize(2);

        CardInstance taken = state.takeTopDiscard();
        assertThat(taken).isEqualTo(card2);
        assertThat(state.topDiscard()).isEqualTo(card1);
        assertThat(state.getDiscardPile()).hasSize(1);
    }

    @Test
    @DisplayName("Sequence increments monotonically")
    void testSequenceIncrements() {
        PlayerState p1 = new PlayerState("P1", "Alice", 0, false);
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(p1), Deck.createStandard13CardDeck());

        assertThat(state.getSequence()).isEqualTo(0L);
        assertThat(state.nextSequence()).isEqualTo(1L);
        assertThat(state.nextSequence()).isEqualTo(2L);
        assertThat(state.getSequence()).isEqualTo(2L);
    }
}
