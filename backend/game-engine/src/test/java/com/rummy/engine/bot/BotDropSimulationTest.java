package com.rummy.engine.bot;

import com.rummy.engine.GameEngine;
import com.rummy.engine.command.*;
import com.rummy.engine.model.*;
import com.rummy.engine.rules.PointsRummyRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Six-handed points deals between bots that never drop: whenever a bot would have dropped, it plays on and
 * the deal's outcome for it is recorded. None of those hands may go on to win, and playing them out must
 * cost more than the middle-drop penalty. Seeded, so the result is repeatable.
 */
class BotDropSimulationTest {

    private static final int GAMES = 400;
    private static final int PLAYERS = 6;
    private static final int MAX_ACTIONS = 1200;

    private final GameEngine engine = new GameEngine(() -> 0L, 0);
    private final PointsRummyRules rules = new PointsRummyRules();

    @Test
    @DisplayName("Hands the bot drops would never have won and would lose more than the drop penalty")
    void droppedHandsWouldHaveLost() throws Exception {
        int completed = 0;
        int wouldDrop = 0;
        int wouldDropWins = 0;
        long wouldDropNet = 0;

        for (int g = 0; g < GAMES; g++) {
            String gameId = "SIM_" + g;
            Map<String, BotPlayerAgent> bots = new LinkedHashMap<>();
            for (int p = 0; p < PLAYERS; p++) {
                String id = "P" + p;
                bots.put(id, new BotPlayerAgent(id, id, BotDifficulty.HARD, new Random(g * 31L + p)));
            }
            SecureRandom shuffle = SecureRandom.getInstance("SHA1PRNG");
            shuffle.setSeed(g);
            GameState state = new GameState(gameId, "SIM_T", "POINTS_13", "1.0.0", List.of(),
                    Deck.createStandard13CardDeck(shuffle));
            Instant now = Instant.now();
            int seat = 0;
            for (String id : bots.keySet()) {
                engine.process(state, new JoinCommand("j" + id, gameId, id, id, seat++, true, now), rules);
            }
            for (String id : bots.keySet()) {
                engine.process(state, new ReadyCommand("r" + id, gameId, id, now), rules);
            }
            engine.process(state, new StartGameCommand("start", gameId, "P0", now), rules);

            Set<String> flagged = new HashSet<>();
            int actions = 0;
            while (state.getStatus() == GameStatus.IN_PROGRESS && actions < MAX_ACTIONS) {
                String activeId = state.getTurnState().getCurrentPlayerId();
                GameCommand cmd = bots.get(activeId).decideAction(PlayerGameView.from(state, activeId), rules);
                if (cmd instanceof DropCommand) {
                    flagged.add(activeId);
                    cmd = new DrawCommand(cmd.commandId(), gameId, activeId, DrawSource.CLOSED_DECK, cmd.timestamp());
                }
                assertThat(engine.process(state, cmd, rules).isSuccess()).isTrue();
                actions++;
            }
            if (state.getStatus() == GameStatus.SHOWDOWN) {
                engine.process(state, new ShowdownTimeoutCommand("to_" + gameId, gameId, now), rules);
            }
            if (state.getStatus() != GameStatus.COMPLETED) {
                continue;
            }
            completed++;
            String winner = state.getWinnerPlayerId();
            int pot = bots.keySet().stream().filter(id -> !id.equals(winner))
                    .mapToInt(id -> state.requirePlayer(id).getScore()).sum();
            for (String id : flagged) {
                wouldDrop++;
                if (id.equals(winner)) {
                    wouldDropWins++;
                    wouldDropNet += pot;
                } else {
                    wouldDropNet -= state.requirePlayer(id).getScore();
                }
            }
        }

        double avgNet = (double) wouldDropNet / Math.max(1, wouldDrop);
        System.out.printf("[BotDropSimulation] games=%d wouldDrop=%d wins=%d avgNetPlayingOn=%.1f dropCost=-%d%n",
                completed, wouldDrop, wouldDropWins, avgNet, rules.getMiddleDropPenalty());

        assertThat(completed).isGreaterThan(GAMES / 2);
        assertThat(wouldDrop).isGreaterThanOrEqualTo(5);
        assertThat(wouldDropWins).isZero();
        assertThat(avgNet).isLessThan(-rules.getMiddleDropPenalty());
    }
}
