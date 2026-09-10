package com.rummy.gameservice.admin;

import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.matchmaking.MatchmakingService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 20: Real-Time Admin & Observability Metrics Service.
 * Registers custom Micrometer gauges, Prometheus metrics, and system diagnostic monitors.
 */
@Service
public class AdminMetricsService {

    private final TableManager tableManager;
    private final MatchmakingService matchmakingService;
    private final TableRoutingRegistry routingRegistry;
    private final MeterRegistry meterRegistry;

    private final Counter gamesCompletedCounter;
    private final Counter commandProcessedCounter;
    private final Instant serverStartTime = Instant.now();

    @Autowired
    public AdminMetricsService(TableManager tableManager,
                               MatchmakingService matchmakingService,
                               TableRoutingRegistry routingRegistry,
                               MeterRegistry meterRegistry) {
        this.tableManager = Objects.requireNonNull(tableManager);
        this.matchmakingService = Objects.requireNonNull(matchmakingService);
        this.routingRegistry = Objects.requireNonNull(routingRegistry);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);

        // Custom Micrometer Metrics
        Gauge.builder("rummy.active.tables", tableManager, TableManager::activeTableCount)
                .description("Number of active in-memory TableActors")
                .register(meterRegistry);

        Gauge.builder("rummy.matchmaking.queue.size", matchmakingService, MatchmakingService::getQueuedPlayerCount)
                .description("Number of players in matchmaking queue")
                .register(meterRegistry);

        this.gamesCompletedCounter = Counter.builder("rummy.games.completed.total")
                .description("Total number of games completed")
                .register(meterRegistry);

        this.commandProcessedCounter = Counter.builder("rummy.commands.processed.total")
                .description("Total number of WebSocket commands processed")
                .register(meterRegistry);
    }

    public void incrementGamesCompleted() {
        gamesCompletedCounter.increment();
    }

    public void incrementCommandsProcessed() {
        commandProcessedCounter.increment();
    }

    private volatile boolean draining = false;

    public boolean isDraining() {
        return draining;
    }

    public void setDraining(boolean draining) {
        this.draining = draining;
    }

    public Map<String, Object> getSystemDiagnostics() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("serverInstanceId", routingRegistry.getServerInstanceId());
        diag.put("uptimeSeconds", (Instant.now().toEpochMilli() - serverStartTime.toEpochMilli()) / 1000);
        diag.put("jvmUptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        diag.put("activeTables", tableManager.activeTableCount());
        diag.put("matchmakingQueueSize", matchmakingService.getQueuedPlayerCount());
        diag.put("isDraining", draining);
        diag.put("usedMemoryMb", usedMemory / (1024 * 1024));
        diag.put("totalMemoryMb", totalMemory / (1024 * 1024));
        diag.put("maxMemoryMb", maxMemory / (1024 * 1024));
        diag.put("availableProcessors", runtime.availableProcessors());
        diag.put("complianceMode", "FREE_PLAY_ONLY");
        diag.put("fairPlayCompliant", true);

        return diag;
    }
}
