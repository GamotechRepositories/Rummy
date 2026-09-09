package com.rummy.gameservice.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.matchmaking.MatchmakingService;
import com.rummy.gameservice.routing.PlayerPresenceService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AdminMetricsService & System Diagnostics Tests")
class AdminMetricsServiceTest {

    private AdminMetricsService metricsService;
    private TableManager tableManager;
    private MatchmakingService matchmakingService;
    private TableRoutingRegistry routingRegistry;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        tableManager = new TableManager(new ObjectMapper());
        routingRegistry = new TableRoutingRegistry(null, "test-server-instance");
        PlayerPresenceService presenceService = new PlayerPresenceService(null);
        matchmakingService = new MatchmakingService(tableManager, routingRegistry, presenceService, 4000L, 25000L);

        metricsService = new AdminMetricsService(tableManager, matchmakingService, routingRegistry, meterRegistry);
    }

    @Test
    @DisplayName("Micrometer gauges and counters are properly registered")
    void testMetricsRegistration() {
        assertThat(meterRegistry.find("rummy.active.tables").gauge()).isNotNull();
        assertThat(meterRegistry.find("rummy.matchmaking.queue.size").gauge()).isNotNull();
        assertThat(meterRegistry.find("rummy.games.completed.total").counter()).isNotNull();
        assertThat(meterRegistry.find("rummy.commands.processed.total").counter()).isNotNull();

        metricsService.incrementGamesCompleted();
        assertThat(meterRegistry.find("rummy.games.completed.total").counter().count()).isEqualTo(1.0);

        metricsService.incrementCommandsProcessed();
        assertThat(meterRegistry.find("rummy.commands.processed.total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("getSystemDiagnostics returns valid system memory and uptime metrics")
    void testSystemDiagnostics() {
        Map<String, Object> diag = metricsService.getSystemDiagnostics();
        assertThat(diag).containsKey("serverInstanceId");
        assertThat(diag).containsKey("activeTables");
        assertThat(diag).containsKey("usedMemoryMb");
        assertThat(diag).containsKey("complianceMode");
        assertThat(diag.get("complianceMode")).isEqualTo("FREE_PLAY_ONLY");
        assertThat(diag.get("interactiveGamblingAct2001Compliant")).isEqualTo(true);
    }
}
