package com.rummy.engine;

import com.rummy.engine.command.*;
import com.rummy.engine.model.*;
import com.rummy.engine.rules.PointsRummyRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

class TurnOrderAndTimeRulesTest {

    private final PointsRummyRules rules = new PointsRummyRules();
    private final Instant now = Instant.now();

    private static RandomGenerator tossLandsOn(int index) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0L;
            }

            @Override
            public int nextInt(int bound) {
                return index;
            }
        };
    }

    private GameState start(GameEngine engine, int players) {
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(), Deck.createStandard13CardDeck());
        for (int i = 1; i <= players; i++) {
            engine.process(state, new JoinCommand("j" + i, "G1", "P" + i, "Player" + i, i - 1, false, now), rules);
            engine.process(state, new ReadyCommand("r" + i, "G1", "P" + i, now), rules);
        }
        engine.process(state, new StartGameCommand("s", "G1", "P1", now), rules);
        return state;
    }

    private static void putOnOpenPile(GameState state, CardInstance card) {
        state.clearDiscardPile();
        state.addToDiscardPile(card);
    }

    @Test
    @DisplayName("The toss picks the first player; the dealer sits just before them")
    void tossPicksFirstPlayer() {
        GameState state = start(new GameEngine(tossLandsOn(2), 0), 3);

        assertThat(state.getTurnState().getCurrentPlayerId()).isEqualTo("P3");
        assertThat(state.getDealerSeatIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("Only the first player may take a joker that is the deal's first open card")
    void openingJokerOnlyForFirstPlayer() {
        GameEngine engine = new GameEngine(tossLandsOn(0), 0);
        GameState state = start(engine, 3);
        CardInstance joker = new CardInstance("D9_JOKER_1", Card.printedJoker(), 9);
        putOnOpenPile(state, joker);

        assertThat(PlayerGameView.from(state, "P1").topDiscardPickable()).isTrue();
        assertThat(engine.process(state, new DrawCommand("d1", "G1", "P1", DrawSource.DISCARD_PILE, now), rules).isSuccess()).isTrue();
        String discard = state.requirePlayer("P1").getHandSnapshot().stream()
                .filter(c -> !c.getInstanceId().equals(joker.getInstanceId())).findFirst().orElseThrow().getInstanceId();
        engine.process(state, new DiscardCommand("x1", "G1", "P1", discard, now), rules);

        CardInstance laterJoker = new CardInstance("D9_JOKER_2", Card.printedJoker(), 9);
        state.addToDiscardPile(laterJoker);
        assertThat(PlayerGameView.from(state, "P2").topDiscardPickable()).isFalse();
        assertThat(engine.process(state, new DrawCommand("d2", "G1", "P2", DrawSource.DISCARD_PILE, now), rules).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("The card just taken from the open pile cannot be the finish card")
    void cannotFinishWithPickedOpenCard() {
        GameEngine engine = new GameEngine(tossLandsOn(0), 0);
        GameState state = start(engine, 2);
        engine.process(state, new DrawCommand("d1", "G1", "P1", DrawSource.DISCARD_PILE, now), rules);
        String picked = state.getTurnState().getDrawnCardInstanceId();
        List<CardInstance> rest = state.requirePlayer("P1").getHandSnapshot().stream()
                .filter(c -> !c.getInstanceId().equals(picked)).toList();

        EngineResult result = engine.process(state, new DeclareCommand("x", "G1", "P1", picked,
                List.of(com.rummy.engine.rules.CardGroup.of(rest)), now), rules);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).contains("just picked");
        assertThat(state.requirePlayer("P1").getStatus()).isEqualTo(PlayerStatus.ACTIVE);
    }

    @Test
    @DisplayName("Running out of turn time first spends the extra-time bank, then counts as a missed turn")
    void extraTimeBeforeMissedTurn() {
        GameEngine engine = new GameEngine(tossLandsOn(0), 30);
        GameState state = start(engine, 2);
        Instant timeout = now.plusSeconds(40);

        EngineResult first = engine.process(state, new TimeoutCommand("t1", "G1", "P1", timeout), rules);

        assertThat(first.isSuccess()).isTrue();
        assertThat(state.getTurnState().getCurrentPlayerId()).isEqualTo("P1");
        assertThat(state.getTurnState().getTurnDeadline()).isEqualTo(timeout.plusSeconds(30));
        assertThat(PlayerGameView.from(state, "P2").inExtraTime()).isTrue();
        assertThat(state.requirePlayer("P1").getConsecutiveMissedTurns()).isZero();

        engine.process(state, new TimeoutCommand("t2", "G1", "P1", timeout.plusSeconds(30)), rules);

        assertThat(state.getTurnState().getCurrentPlayerId()).isEqualTo("P2");
        assertThat(state.getTurnState().isExtraTime()).isFalse();
        assertThat(state.requirePlayer("P1").getConsecutiveMissedTurns()).isEqualTo(1);
        assertThat(state.requirePlayer("P1").getExtraTimeSeconds()).isZero();
    }
}
