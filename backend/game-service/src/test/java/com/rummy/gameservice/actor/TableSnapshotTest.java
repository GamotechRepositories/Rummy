package com.rummy.gameservice.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.engine.bot.BotDifficulty;
import com.rummy.engine.command.DrawCommand;
import com.rummy.engine.command.DropCommand;
import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.command.ReadyCommand;
import com.rummy.engine.command.StartGameCommand;
import com.rummy.engine.command.DrawSource;
import com.rummy.engine.model.GameStateSnapshots;
import com.rummy.engine.model.GameStatus;
import com.rummy.engine.model.TurnPhase;
import com.rummy.engine.rules.RulesetRegistry;
import com.rummy.engine.rules.RummyRules;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Table snapshots (crash restore)")
class TableSnapshotTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private TableManager crashed;
    private TableManager survivor;

    @BeforeEach
    void setUp() {
        crashed = new TableManager(json, null, null, null, null, new TableRoutingRegistry(null, "node-a"), 100, 180, 600);
        survivor = new TableManager(json, null, null, null, null, new TableRoutingRegistry(null, "node-b"), 100, 180, 600);
    }

    @AfterEach
    void tearDown() {
        crashed.shutdown();
        survivor.shutdown();
    }

    @Test
    @DisplayName("A mid-turn match restores with identical cards and a fresh turn clock")
    void midTurnRestore() throws Exception {
        TableActor original = dealtTable("TBL_MID", RulesetRegistry.requireRuleset("POINTS_13"), false);
        original.setStakeTier(50);
        String gameId = original.getState().getGameId();
        String onTurn = original.getState().getTurnState().getCurrentPlayerId();
        original.processCommand(new DrawCommand("d", gameId, onTurn, DrawSource.CLOSED_DECK, Instant.now()), "draw");

        TableActor restored = survivor.restoreTable(roundTrip(original), Duration.ofSeconds(10));

        assertThat(GameStateSnapshots.capture(restored.getState()).deck())
                .isEqualTo(GameStateSnapshots.capture(original.getState()).deck());
        assertThat(restored.getState().requirePlayer(onTurn).getHandSnapshot())
                .isEqualTo(original.getState().requirePlayer(onTurn).getHandSnapshot());
        assertThat(restored.getState().getTurnState().getPhase()).isEqualTo(TurnPhase.AWAITING_DISCARD);
        assertThat(restored.getState().getTurnState().getTurnDeadline()).isAfter(Instant.now().plusSeconds(10));
        assertThat(restored.getStakeTier()).isEqualTo(50);
        assertThat(restored.isLive()).isTrue();
    }

    @Test
    @DisplayName("A pool match between deals keeps its history and bots, then deals the next round")
    void poolBetweenDealsRestore() throws Exception {
        RummyRules pool = RulesetRegistry.requireRuleset("POOL_101");
        TableActor original = dealtTable("TBL_POOL", pool, true);
        String gameId = original.getState().getGameId();
        TurnTestSupport.dropOnTurn(original, "drop", "P1");
        assertThat(original.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(original.isLive()).as("next deal pending").isTrue();

        TableSnapshot snap = roundTrip(original);
        assertThat(snap.nextDealScheduledAt()).isNotNull();
        assertThat(snap.bots()).extracting(TableSnapshot.Bot::playerId).containsExactly("BOT_1");

        TableActor restored = survivor.restoreTable(snap, Duration.ZERO);

        assertThat(restored.getDealHistory()).hasSize(1);
        assertThat(restored.captureSnapshot().orElseThrow().bots()).extracting(TableSnapshot.Bot::displayName)
                .containsExactly("Bot One");
        long deadline = System.currentTimeMillis() + 10_000;
        while (restored.getState().getDealNumber() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(restored.getState().getDealNumber()).isEqualTo(2);
        assertThat(restored.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(restored.getState().requirePlayer("P1").getCumulativeScore())
                .isEqualTo(original.getState().requirePlayer("P1").getCumulativeScore());
    }

    @Test
    @DisplayName("Only dealt, unfinished matches are snapshotted")
    void onlyLiveMatchesSnapshotted() {
        TableActor waiting = crashed.getOrCreateTable("TBL_WAIT", null);
        assertThat(waiting.captureSnapshot()).isEmpty();

        TableActor finished = dealtTable("TBL_DONE", RulesetRegistry.requireRuleset("POINTS_13"), false);
        TurnTestSupport.dropOnTurn(finished, "d", "P2");
        assertThat(finished.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(finished.captureSnapshot()).isEmpty();
    }

    @Test
    @DisplayName("Every state change is counted so unchanged tables are not rewritten")
    void mutationsCounted() {
        TableActor actor = dealtTable("TBL_COUNT", RulesetRegistry.requireRuleset("POINTS_13"), false);
        long before = actor.mutationCount();
        String gameId = actor.getState().getGameId();
        String onTurn = actor.getState().getTurnState().getCurrentPlayerId();
        actor.processCommand(new DrawCommand("d", gameId, onTurn, DrawSource.CLOSED_DECK, Instant.now()), "draw");
        assertThat(actor.mutationCount()).isGreaterThan(before);
    }

    private TableSnapshot roundTrip(TableActor actor) throws Exception {
        String payload = json.writeValueAsString(actor.captureSnapshot().orElseThrow());
        return json.readValue(payload, TableSnapshot.class);
    }

    private TableActor dealtTable(String tableId, RummyRules rules, boolean withBot) {
        TableActor actor = crashed.getOrCreateTable(tableId, rules);
        String gameId = actor.getState().getGameId();
        Instant now = Instant.now();
        actor.processCommand(new JoinCommand("j1", gameId, "P1", "One", 0, false, now), "j1");
        actor.processCommand(new ReadyCommand("r1", gameId, "P1", now), "r1");
        if (withBot) {
            actor.registerBot("BOT_1", "Bot One", BotDifficulty.HARD);
            actor.processCommand(new JoinCommand("j2", gameId, "BOT_1", "Bot One", 1, true, now), "j2");
            actor.processCommand(new ReadyCommand("r2", gameId, "BOT_1", now), "r2");
        } else {
            actor.processCommand(new JoinCommand("j2", gameId, "P2", "Two", 1, false, now), "j2");
            actor.processCommand(new ReadyCommand("r2", gameId, "P2", now), "r2");
        }
        actor.processCommand(new StartGameCommand("s", gameId, "P1", now), "start");
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        return actor;
    }
}
