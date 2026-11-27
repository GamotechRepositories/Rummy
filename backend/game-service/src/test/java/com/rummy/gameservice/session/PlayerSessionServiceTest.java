package com.rummy.gameservice.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.rules.Pool101Rules;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerSessionServiceTest {

    private TableManager tableManager;
    private TableRoutingRegistry routingRegistry;
    private PlayerSessionService sessionService;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        routingRegistry = new TableRoutingRegistry(null, "node-1");
        tableManager = new TableManager(mapper, null, null, null, null);
        sessionService = new PlayerSessionService(routingRegistry, tableManager);
    }

    @AfterEach
    void tearDown() {
        tableManager.shutdown();
    }

    @Test
    void activeSessionRespectsAllowedRulesetsWithoutClearingBinding() {
        TableActor poolTable = tableManager.getOrCreateTable("TBL_POOL_1", new Pool101Rules());
        poolTable.processCommand(new JoinCommand(
                UUID.randomUUID().toString(),
                poolTable.getState().getGameId(),
                "P1",
                "Alice",
                0,
                false,
                Instant.now()
        ), "JOIN");
        sessionService.bindPlayerToTable("P1", "TBL_POOL_1");

        // Points frontend must NOT resume a Pool table, and must preserve Pool binding
        assertThat(sessionService.findActiveSession("P1", List.of("POINTS_13"))).isEmpty();
        assertThat(routingRegistry.getTableForPlayer("P1")).contains("TBL_POOL_1");

        // Pool frontend resumes the table and sees rulesetId
        Optional<Map<String, Object>> poolResume = sessionService.findActiveSession("P1", List.of("POOL_101", "POOL_201"));
        assertThat(poolResume).isPresent();
        assertThat(poolResume.get().get("tableId")).isEqualTo("TBL_POOL_1");
        assertThat(poolResume.get().get("rulesetId")).isEqualTo("POOL_101");
    }
}
