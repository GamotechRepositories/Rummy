package com.rummy.gameservice.lifecycle;

import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.handler.GameWebSocketHandler;
import com.rummy.gameservice.matchmaking.MatchmakingService;
import com.rummy.gameservice.recovery.TableRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Drains this node before it stops, so a deploy or scale-down does not kill live games.
 *
 * <p>On shutdown (SIGTERM) or an admin request it: marks the pod not-ready so the load balancer
 * sends new players elsewhere, refuses new tables and matchmaking here, disconnects players who are
 * not in a live game so they reconnect to a healthy pod, and waits for live matches to finish.
 * Matches still running when the drain timeout expires are handed to another node, which resumes them
 * from their snapshot when the players reconnect (crash recovery enabled); otherwise, or if the handoff
 * cannot be saved, they are cancelled with full entry refunds.
 *
 * <p>Runs in the highest lifecycle phase so it stops before the web server closes sockets.
 * {@code spring.lifecycle.timeout-per-shutdown-phase} and the pod's
 * {@code terminationGracePeriodSeconds} must both exceed the drain timeout.
 */
@Component
public class GracefulDrainLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulDrainLifecycle.class);

    private final NodeDrainState drainState;
    private final TableManager tableManager;
    private final GameWebSocketHandler socketHandler;
    private final MatchmakingService matchmakingService;
    private final ApplicationEventPublisher events;
    private final Duration drainTimeout;
    private final Duration pollInterval;
    private volatile boolean running;
    private ClusterNodeService clusterNodes;
    private TableRecoveryService recovery;

    public GracefulDrainLifecycle(NodeDrainState drainState,
                                  TableManager tableManager,
                                  GameWebSocketHandler socketHandler,
                                  MatchmakingService matchmakingService,
                                  ApplicationEventPublisher events,
                                  @Value("${rummy.drain.timeout-seconds:1200}") long drainTimeoutSeconds,
                                  @Value("${rummy.drain.poll-interval-ms:2000}") long pollIntervalMs) {
        this.drainState = Objects.requireNonNull(drainState);
        this.tableManager = Objects.requireNonNull(tableManager);
        this.socketHandler = Objects.requireNonNull(socketHandler);
        this.matchmakingService = Objects.requireNonNull(matchmakingService);
        this.events = Objects.requireNonNull(events);
        this.drainTimeout = Duration.ofSeconds(drainTimeoutSeconds);
        this.pollInterval = Duration.ofMillis(pollIntervalMs);
    }

    /** Stop taking new work on this node without shutting it down (admin / pre-deploy). */
    public void beginDrain(String reason) {
        if (drainState.begin()) {
            log.warn("[Drain] Node entering drain ({}). Live tables: {}", reason, tableManager.liveTableCount());
            AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        }
        socketHandler.closeSessionsWithoutTable();
    }

    public void cancelDrain() {
        drainState.cancel();
        AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
        log.info("[Drain] Drain cancelled; node accepting traffic again");
    }

    /**
     * Blocks until no live match remains or the timeout passes, then cancels whatever is left.
     * Returns the number of matches that had to be aborted.
     */
    int drainAndWait() {
        beginDrain("shutdown");
        Instant deadline = Instant.now().plus(drainTimeout);
        long live;
        while ((live = tableManager.liveTableCount()) > 0 && Instant.now().isBefore(deadline)) {
            socketHandler.closeSessionsWithoutTable();
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        matchmakingService.releaseLocalWorkOnShutdown();
        int aborted = 0;
        if (live > 0) {
            int handedOff = recovery != null && recovery.enabled() ? recovery.handOffLiveTables("server maintenance") : 0;
            aborted = tableManager.abortLiveMatches("server maintenance");
            log.warn("[Drain] Timeout reached; handed off {} running match(es) to other nodes, cancelled {} with refunds",
                    handedOff, aborted);
        } else {
            log.info("[Drain] All live matches finished; node can stop");
        }
        return aborted;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        drainAndWait();
        if (recovery != null) {
            // Cancelled matches must lose their snapshots before peers may treat this node as crashed.
            recovery.flushSnapshots();
        }
        if (clusterNodes != null) {
            clusterNodes.leave();
        }
        running = false;
    }

    @Autowired(required = false)
    public void setClusterNodes(ClusterNodeService clusterNodes) {
        this.clusterNodes = clusterNodes;
    }

    @Autowired(required = false)
    public void setRecovery(TableRecoveryService recovery) {
        this.recovery = recovery;
    }

    @Override
    public void stop(Runnable callback) {
        Thread.ofPlatform().name("graceful-drain").start(() -> {
            try {
                stop();
            } catch (RuntimeException e) {
                log.error("[Drain] Drain failed; continuing shutdown", e);
            } finally {
                callback.run();
            }
        });
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
