package com.rummy.engine;

import com.rummy.engine.command.*;
import com.rummy.engine.model.*;
import com.rummy.engine.rules.CardGroup;
import com.rummy.engine.rules.PointsRummyRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DropAndDeclareRulesTest {

    private final GameEngine engine = new GameEngine(() -> 0L, 0);
    private final PointsRummyRules rules = new PointsRummyRules();
    private final Instant now = Instant.now();

    private GameState startThreePlayers() {
        GameState state = new GameState("G1", "T1", "POINTS_13", "1.0.0", List.of(), Deck.createStandard13CardDeck());
        for (int i = 1; i <= 3; i++) {
            engine.process(state, new JoinCommand("j" + i, "G1", "P" + i, "Player" + i, i - 1, false, now), rules);
            engine.process(state, new ReadyCommand("r" + i, "G1", "P" + i, now), rules);
        }
        engine.process(state, new StartGameCommand("s", "G1", "P1", now), rules);
        return state;
    }

    @Test
    @DisplayName("Drop is only allowed on your own turn, before drawing")
    void dropOnlyOnOwnTurn() {
        GameState state = startThreePlayers();
        String current = state.getTurnState().getCurrentPlayerId();
        String other = state.getPlayers().stream().map(PlayerState::getPlayerId)
                .filter(id -> !id.equals(current)).findFirst().orElseThrow();

        assertThat(engine.process(state, new DropCommand("d1", "G1", other, now), rules).isSuccess()).isFalse();
        assertThat(state.requirePlayer(other).getStatus()).isEqualTo(PlayerStatus.ACTIVE);

        assertThat(engine.process(state, new DropCommand("d2", "G1", current, now), rules).isSuccess()).isTrue();
        assertThat(state.requirePlayer(current).getScore()).isEqualTo(rules.getFirstDropPenalty());
    }

    @Test
    @DisplayName("After a missed turn the view no longer offers the first-drop penalty")
    void missedTurnEndsFirstDrop() {
        GameState state = startThreePlayers();
        String current = state.getTurnState().getCurrentPlayerId();
        assertThat(PlayerGameView.from(state, current).firstDropAvailable()).isTrue();

        engine.process(state, new TimeoutCommand("t", "G1", current, now), rules);

        PlayerGameView view = PlayerGameView.from(state, current);
        assertThat(view.hasTakenFirstTurn()).isFalse();
        assertThat(view.firstDropAvailable()).isFalse();
    }

    @Test
    @DisplayName("Declared groups must be exactly the hand without the finish card")
    void declareGroupsMustMatchHand() {
        GameState state = startThreePlayers();
        String current = state.getTurnState().getCurrentPlayerId();
        engine.process(state, new DrawCommand("dr", "G1", current, DrawSource.CLOSED_DECK, now), rules);
        List<CardInstance> hand = state.requirePlayer(current).getHandSnapshot();
        String finish = hand.get(0).getInstanceId();

        EngineResult withFinish = engine.process(state,
                new DeclareCommand("x1", "G1", current, finish, List.of(CardGroup.of(hand.subList(0, 13))), now), rules);
        EngineResult missingCard = engine.process(state,
                new DeclareCommand("x2", "G1", current, finish, List.of(CardGroup.of(hand.subList(1, 13))), now), rules);

        assertThat(withFinish.isSuccess()).isFalse();
        assertThat(missingCard.isSuccess()).isFalse();
        assertThat(state.requirePlayer(current).getStatus()).isEqualTo(PlayerStatus.ACTIVE);
        assertThat(state.requirePlayer(current).getHandSize()).isEqualTo(14);
    }
}
