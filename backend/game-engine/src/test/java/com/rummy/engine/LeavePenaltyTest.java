package com.rummy.engine;

import com.rummy.engine.command.*;
import com.rummy.engine.model.*;
import com.rummy.engine.rules.Pool101Rules;
import com.rummy.engine.rules.PointsRummyRules;
import com.rummy.engine.rules.RummyRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Leaving mid-hand: a drop before drawing, the full penalty after")
class LeavePenaltyTest {

    private final GameEngine engine = new GameEngine();
    private final Instant now = Instant.now();

    private GameState startThreePlayers(RummyRules rules) {
        GameState state = new GameState("G1", "T1", rules.getRulesetId(), "1.0.0", List.of(), Deck.createStandard13CardDeck());
        for (int i = 1; i <= 3; i++) {
            engine.process(state, new JoinCommand("j" + i, "G1", "P" + i, "Player" + i, i - 1, false, now), rules);
            engine.process(state, new ReadyCommand("r" + i, "G1", "P" + i, now), rules);
        }
        assertThat(engine.process(state, new StartGameCommand("s", "G1", "P1", now), rules).isSuccess()).isTrue();
        return state;
    }

    private String waitingPlayer(GameState state) {
        String current = state.getTurnState().getCurrentPlayerId();
        return state.getPlayers().stream().map(PlayerState::getPlayerId).filter(id -> !id.equals(current)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Points: leaving before ever drawing costs the first-drop penalty")
    void leaveBeforeFirstTurnIsFirstDrop() {
        PointsRummyRules rules = new PointsRummyRules();
        GameState state = startThreePlayers(rules);
        String leaver = waitingPlayer(state);

        assertThat(engine.process(state, new DropCommand("d", "G1", leaver, now, true), rules).isSuccess()).isTrue();

        assertThat(state.requirePlayer(leaver).getStatus()).isEqualTo(PlayerStatus.DROPPED);
        assertThat(state.requirePlayer(leaver).getScore()).isEqualTo(rules.getFirstDropPenalty());
    }

    @Test
    @DisplayName("Points: leaving after drawing costs the full penalty and passes the turn")
    void leaveAfterDrawIsFullPenalty() {
        PointsRummyRules rules = new PointsRummyRules();
        GameState state = startThreePlayers(rules);
        String current = state.getTurnState().getCurrentPlayerId();
        engine.process(state, new DrawCommand("dr", "G1", current, DrawSource.CLOSED_DECK, now), rules);

        assertThat(engine.process(state, new DropCommand("d", "G1", current, now), rules).isSuccess()).isFalse();
        assertThat(engine.process(state, new DropCommand("d2", "G1", current, now, true), rules).isSuccess()).isTrue();

        assertThat(state.requirePlayer(current).getScore()).isEqualTo(rules.getMaximumPenalty());
        assertThat(state.getTurnState().getCurrentPlayerId()).isNotEqualTo(current);
    }

    @Test
    @DisplayName("Pool: leaving still costs the full penalty")
    void poolLeaveIsFullPenalty() {
        Pool101Rules rules = new Pool101Rules();
        GameState state = startThreePlayers(rules);
        String leaver = waitingPlayer(state);

        engine.process(state, new DropCommand("d", "G1", leaver, now, true), rules);

        assertThat(state.requirePlayer(leaver).getScore()).isEqualTo(rules.getMaximumPenalty());
    }
}
