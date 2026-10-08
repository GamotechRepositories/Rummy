package com.rummy.gameservice.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.engine.GameEngine;
import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.command.ReadyCommand;
import com.rummy.engine.command.StartGameCommand;
import com.rummy.engine.model.Deck;
import com.rummy.engine.model.GameState;
import com.rummy.engine.model.GameStatus;
import com.rummy.engine.model.PlayerState;
import com.rummy.engine.model.PlayerStatus;
import com.rummy.engine.rules.DealsRummyRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/** Deals Rummy: leaving mid-deal, chips-only ties, tie-breakers among the tied players. */
class DealsRulesTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ScheduledExecutorService scheduler;
    private TableActor actor;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        if (actor != null) {
            actor.destroy();
        }
        scheduler.shutdownNow();
    }

    private void startDeals(int players) {
        GameState state = new GameState("G_DL", "T_DL", "DEALS_2", "1.0.0", List.of(), Deck.createStandard13CardDeck());
        actor = new TableActor("T_DL", state, new DealsRummyRules(2), new GameEngine(() -> 0L, 0), objectMapper, scheduler);
        Instant now = Instant.now();
        for (int i = 1; i <= players; i++) {
            actor.processCommand(new JoinCommand("j" + i, "G_DL", "P" + i, "Player " + i, i - 1, false, now), "join-" + i);
            actor.processCommand(new ReadyCommand("r" + i, "G_DL", "P" + i, now), "ready-" + i);
        }
        actor.processCommand(new StartGameCommand("s", "G_DL", "P1", now), "start");
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
    }

    private PlayerState player(String id) {
        return actor.getState().requirePlayer(id);
    }

    @Test
    @DisplayName("Leaving mid-deal costs 80 chips, and they go to the deal winner")
    void leaverChipsGoToDealWinner() {
        startDeals(3);

        actor.handleVoluntaryLeave("P3", "leave");
        assertThat(player("P3").getStatus()).isEqualTo(PlayerStatus.DROPPED);
        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.IN_PROGRESS);

        TurnTestSupport.dropOnTurn(actor, "d2", "P2");

        assertThat(actor.getState().getStatus()).isEqualTo(GameStatus.COMPLETED);
        assertThat(player("P3").getChipBalance()).isEqualTo(80);
        assertThat(player("P2").getChipBalance()).isEqualTo(140);
        assertThat(player("P1").getChipBalance()).isEqualTo(260);
        assertThat(player("P3").getStatus()).isEqualTo(PlayerStatus.ELIMINATED);
    }

    @Test
    @DisplayName("Level on chips after the last deal means a tie-breaker, whatever the penalty totals; others sit it out")
    void chipsOnlyTieBreakerAmongTiedPlayers() {
        startDeals(3);
        TurnTestSupport.dropOnTurn(actor, "d3", "P3");
        TurnTestSupport.dropOnTurn(actor, "d2", "P2");
        actor.startNextDeal();
        assertThat(actor.getState().getDealNumber()).isEqualTo(2);

        player("P1").setChipBalance(160);
        player("P2").setChipBalance(220);
        player("P3").setChipBalance(100);
        TurnTestSupport.dropOnTurn(actor, "d3b", "P3");
        TurnTestSupport.dropOnTurn(actor, "d2b", "P2");

        assertThat(player("P1").getChipBalance()).isEqualTo(200);
        assertThat(player("P2").getChipBalance()).isEqualTo(200);
        assertThat(player("P1").getCumulativeScore()).isNotEqualTo(player("P2").getCumulativeScore());
        assertThat(actor.getTournamentWinnerId()).isNull();
        assertThat(actor.getEffectiveTotalDeals()).isEqualTo(3);
        assertThat(player("P3").getStatus()).isEqualTo(PlayerStatus.ELIMINATED);

        actor.startNextDeal();
        assertThat(actor.getState().getDealNumber()).isEqualTo(3);
        assertThat(player("P3").getHandSnapshot()).isEmpty();
        assertThat(player("P1").getStatus()).isEqualTo(PlayerStatus.ACTIVE);
        assertThat(player("P2").getStatus()).isEqualTo(PlayerStatus.ACTIVE);

        TurnTestSupport.dropOnTurn(actor, "d2c", "P2");
        assertThat(actor.getTournamentWinnerId()).isEqualTo("P1");
    }

    @Test
    @DisplayName("Still level after every tie-breaker: the match ends instead of dealing forever")
    void tieBreakersAreCapped() {
        startDeals(2);
        TurnTestSupport.dropOnTurn(actor, "d0", "P2");
        for (int deal = 2; deal <= 2 + TableActor.MAX_TIE_BREAKER_DEALS; deal++) {
            actor.startNextDeal();
            assertThat(actor.getState().getDealNumber()).isEqualTo(deal);
            player("P1").setChipBalance(160);
            player("P2").setChipBalance(200);
            TurnTestSupport.dropOnTurn(actor, "d" + deal, "P2");
            assertThat(player("P1").getChipBalance()).isEqualTo(player("P2").getChipBalance());
        }

        assertThat(actor.getEffectiveTotalDeals()).isEqualTo(2 + TableActor.MAX_TIE_BREAKER_DEALS);
        assertThat(actor.getTournamentWinnerId()).isNotNull();
    }
}
