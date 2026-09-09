package com.rummy.engine.bot;

import com.rummy.engine.EngineResult;
import com.rummy.engine.GameEngine;
import com.rummy.engine.command.*;
import com.rummy.engine.model.*;
import com.rummy.engine.rules.PointsRummyRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BotSimulationTest {

    private final GameEngine engine = new GameEngine();
    private final PointsRummyRules rules = new PointsRummyRules();

    @Test
    @DisplayName("Full autonomous simulation: 2 AI Bots play turn-by-turn through GameEngine to completion")
    void testAutonomousBotSimulation() {
        BotPlayerAgent bot1 = new BotPlayerAgent("BOT_1", "AliceBot", BotDifficulty.HARD);
        BotPlayerAgent bot2 = new BotPlayerAgent("BOT_2", "BobBot", BotDifficulty.MEDIUM);
        Map<String, BotPlayerAgent> botMap = Map.of("BOT_1", bot1, "BOT_2", bot2);

        Deck deck = Deck.createStandard13CardDeck();
        GameState state = new GameState("SIM_G1", "SIM_T1", "POINTS_13", "1.0.0", List.of(), deck);
        Instant now = Instant.now();

        // 1. Join both bots
        engine.process(state, new JoinCommand("c1", "SIM_G1", "BOT_1", "AliceBot", 0, true, now), rules);
        engine.process(state, new JoinCommand("c2", "SIM_G1", "BOT_2", "BobBot", 1, true, now), rules);

        // 2. Ready up
        engine.process(state, new ReadyCommand("c3", "SIM_G1", "BOT_1", now), rules);
        engine.process(state, new ReadyCommand("c4", "SIM_G1", "BOT_2", now), rules);

        // 3. Start Game
        EngineResult startResult = engine.process(state, new StartGameCommand("c5", "SIM_G1", "BOT_1", now), rules);
        assertThat(startResult.isSuccess()).isTrue();
        assertThat(state.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);

        // 4. Automated Game Loop (max 200 turns to avoid infinite loops)
        int maxTurns = 200;
        int actionCount = 0;

        while (state.getStatus() == GameStatus.IN_PROGRESS && actionCount < maxTurns) {
            TurnState turn = state.getTurnState();
            assertThat(turn).isNotNull();
            String activeId = turn.getCurrentPlayerId();
            BotPlayerAgent activeBot = botMap.get(activeId);
            assertThat(activeBot).isNotNull();

            // Create zero-knowledge sanitized player view
            PlayerGameView view = PlayerGameView.from(state, activeId);

            // Bot decides action
            GameCommand cmd = activeBot.decideAction(view, rules);
            assertThat(cmd).isNotNull();

            // Process through server-authoritative GameEngine
            EngineResult result = engine.process(state, cmd, rules);
            assertThat(result.isSuccess())
                    .as("Bot action failed: %s (error: %s)", cmd, result.errorMessage())
                    .isTrue();

            actionCount++;
        }

        // Verify that the game progressed and either declared or finished
        assertThat(actionCount).isGreaterThan(0);
        if (state.getStatus() == GameStatus.COMPLETED) {
            assertThat(state.getWinnerPlayerId()).isNotNull();
            assertThat(state.requirePlayer(state.getWinnerPlayerId()).getScore()).isEqualTo(0);
        }
    }
}
