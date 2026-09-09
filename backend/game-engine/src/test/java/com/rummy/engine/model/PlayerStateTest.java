package com.rummy.engine.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlayerStateTest {

    @Test
    @DisplayName("Should successfully add and remove cards from player hand")
    void testHandManagement() {
        PlayerState player = new PlayerState("P1", "Alice", 0, false);

        CardInstance c1 = new CardInstance("C1", Card.of(Suit.HEARTS, Rank.TEN), 1);
        CardInstance c2 = new CardInstance("C2", Card.of(Suit.SPADES, Rank.ACE), 1);

        player.addCard(c1);
        player.addCard(c2);

        assertThat(player.getHandSize()).isEqualTo(2);
        assertThat(player.hasCard("C1")).isTrue();
        assertThat(player.hasCard("C2")).isTrue();
        assertThat(player.hasCard("C3")).isFalse();

        // Adding duplicate instance must fail
        assertThatThrownBy(() -> player.addCard(c1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Card instance already in hand");

        // Remove card
        CardInstance removed = player.removeCard("C1");
        assertThat(removed).isEqualTo(c1);
        assertThat(player.getHandSize()).isEqualTo(1);
        assertThat(player.hasCard("C1")).isFalse();

        // Removing non-existent card must throw
        assertThatThrownBy(() -> player.removeCard("C1"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("Should correctly track missed turns and turn completion")
    void testMissedTurnTracking() {
        PlayerState player = new PlayerState("P1", "Alice", 0, false);

        assertThat(player.getConsecutiveMissedTurns()).isEqualTo(0);
        assertThat(player.hasTakenFirstTurn()).isFalse();

        player.incrementMissedTurns();
        player.incrementMissedTurns();
        assertThat(player.getConsecutiveMissedTurns()).isEqualTo(2);

        player.recordTurnCompleted();
        assertThat(player.getConsecutiveMissedTurns()).isEqualTo(0);
        assertThat(player.getTurnsCompleted()).isEqualTo(1);
        assertThat(player.hasTakenFirstTurn()).isTrue();
    }

    @Test
    @DisplayName("Should handle drop status and clear hand")
    void testDrop() {
        PlayerState player = new PlayerState("P1", "Alice", 0, false);
        player.addCard(new CardInstance("C1", Card.of(Suit.HEARTS, Rank.TEN), 1));
        player.setStatus(PlayerStatus.ACTIVE);

        player.markDropped(20);

        assertThat(player.getStatus()).isEqualTo(PlayerStatus.DROPPED);
        assertThat(player.isHasDropped()).isTrue();
        assertThat(player.getScore()).isEqualTo(20);
        assertThat(player.getCumulativeScore()).isEqualTo(20);
        assertThat(player.getHandSize()).isEqualTo(0);
    }
}
