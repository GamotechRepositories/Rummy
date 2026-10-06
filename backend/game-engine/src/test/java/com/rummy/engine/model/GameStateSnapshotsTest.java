package com.rummy.engine.model;

import com.rummy.engine.EngineResult;
import com.rummy.engine.GameEngine;
import com.rummy.engine.command.*;
import com.rummy.engine.model.snapshot.GameStateSnapshot;
import com.rummy.engine.rules.PointsRummyRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Game state snapshots")
class GameStateSnapshotsTest {

    private final GameEngine engine = new GameEngine(() -> 0L, 0);
    private final PointsRummyRules rules = new PointsRummyRules();

    @Test
    @DisplayName("A mid-turn state restores exactly, including deck order and turn deadline")
    void roundTripMidGame() {
        GameState original = startedGame();
        play(original, new DrawCommand("d1", "G1", "P1", DrawSource.CLOSED_DECK, Instant.now()));

        GameStateSnapshot snap = GameStateSnapshots.capture(original);
        GameState restored = GameStateSnapshots.restore(snap);

        assertThat(GameStateSnapshots.capture(restored)).isEqualTo(snap);
        assertThat(restored.getDeck().getCardsSnapshot()).isEqualTo(original.getDeck().getCardsSnapshot());
        assertThat(restored.requirePlayer("P1").getHandSnapshot()).isEqualTo(original.requirePlayer("P1").getHandSnapshot());
        assertThat(restored.getTurnState().getTurnDeadline()).isEqualTo(original.getTurnState().getTurnDeadline());
        assertThat(restored.getTurnState().getPhase()).isEqualTo(TurnPhase.AWAITING_DISCARD);
        assertThat(restored.getSequence()).isEqualTo(original.getSequence());
    }

    @Test
    @DisplayName("A restored game continues exactly like the original would have")
    void restoredGamePlaysOnIdentically() {
        GameState original = startedGame();
        GameState restored = GameStateSnapshots.restore(GameStateSnapshots.capture(original));

        for (GameState s : List.of(original, restored)) {
            play(s, new DrawCommand("d1", "G1", "P1", DrawSource.CLOSED_DECK, Instant.now()));
            Card cut = s.getCutJoker().getCard();
            String discard = s.requirePlayer("P1").getHandSnapshot().stream()
                    .filter(c -> !c.isPrintedJoker() && !c.getCard().isWildJoker(cut))
                    .findFirst().orElseThrow().getInstanceId();
            play(s, new DiscardCommand("x1", "G1", "P1", discard, Instant.now()));
            play(s, new DrawCommand("d2", "G1", "P2", DrawSource.DISCARD_PILE, Instant.now()));
        }

        assertThat(restored.requirePlayer("P2").getHandSnapshot()).isEqualTo(original.requirePlayer("P2").getHandSnapshot());
        assertThat(restored.getDeck().getCardsSnapshot()).isEqualTo(original.getDeck().getCardsSnapshot());
        assertThat(restored.getTurnState().getCurrentPlayerId()).isEqualTo("P2");
    }

    @Test
    @DisplayName("Dropped players keep their showdown hand and penalty")
    void droppedPlayerRestored() {
        GameState original = startedGame();
        play(original, new DropCommand("drop", "G1", "P1", Instant.now()));

        GameState restored = GameStateSnapshots.restore(GameStateSnapshots.capture(original));

        PlayerState p1 = restored.requirePlayer("P1");
        assertThat(p1.isHasDropped()).isTrue();
        assertThat(p1.getShowdownHand()).hasSize(13);
        assertThat(p1.getScore()).isEqualTo(original.requirePlayer("P1").getScore());
        assertThat(restored.getStatus()).isEqualTo(original.getStatus());
        assertThat(restored.getFinishedAt()).isEqualTo(original.getFinishedAt());
    }

    @Test
    @DisplayName("Every card id in a multi-pack deck parses back to the same card")
    void cardIdsRoundTrip() {
        for (CardInstance c : Deck.createMultiPackDeck(3, 2, new java.security.SecureRandom()).getCardsSnapshot()) {
            CardInstance parsed = GameStateSnapshots.card(c.getInstanceId());
            assertThat(parsed.getCard()).isEqualTo(c.getCard());
            assertThat(parsed.getDeckNumber()).isEqualTo(c.getDeckNumber());
        }
        assertThatThrownBy(() -> GameStateSnapshots.card("X_Y")).isInstanceOf(IllegalArgumentException.class);
    }

    private GameState startedGame() {
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(), Deck.createStandard13CardDeck());
        Instant now = Instant.now();
        play(state, new JoinCommand("j1", "G1", "P1", "Alice", 0, false, now));
        play(state, new JoinCommand("j2", "G1", "P2", "Bob", 1, false, now));
        play(state, new ReadyCommand("r1", "G1", "P1", now));
        play(state, new ReadyCommand("r2", "G1", "P2", now));
        play(state, new StartGameCommand("s", "G1", "P1", now));
        assertThat(state.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        return state;
    }

    private void play(GameState state, GameCommand command) {
        EngineResult r = engine.process(state, command, rules);
        assertThat(r.isSuccess()).as(command.getClass().getSimpleName() + ": " + r.errorMessage()).isTrue();
    }
}
