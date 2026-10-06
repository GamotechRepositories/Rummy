package com.rummy.gameservice.session;

import com.rummy.engine.model.GameStatus;
import com.rummy.engine.model.PlayerState;
import com.rummy.engine.model.PlayerStatus;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.recovery.TableRecoveryService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Production session resume: soft-disconnect players can reclaim an in-progress table;
 * finished games and voluntary leave clear the binding.
 */
@Service
public class PlayerSessionService {

    private static final Logger log = LoggerFactory.getLogger(PlayerSessionService.class);

    private final TableRoutingRegistry routingRegistry;
    private final TableManager tableManager;
    private ClusterNodeService clusterNodes;
    private TableRecoveryService recovery;

    public PlayerSessionService(TableRoutingRegistry routingRegistry, @Lazy TableManager tableManager) {
        this.routingRegistry = routingRegistry;
        this.tableManager = tableManager;
    }

    @Autowired(required = false)
    public void setClusterNodes(ClusterNodeService clusterNodes) {
        this.clusterNodes = clusterNodes;
    }

    @Autowired(required = false)
    public void setRecovery(@Lazy TableRecoveryService recovery) {
        this.recovery = recovery;
    }

    public void bindPlayerToTable(String playerId, String tableId) {
        if (playerId == null || tableId == null) return;
        routingRegistry.registerPlayerTable(playerId, tableId);
        log.debug("[Session] Bound player {} → table {}", playerId, tableId);
    }

    public void clearPlayerBinding(String playerId) {
        if (playerId == null) return;
        routingRegistry.unregisterPlayer(playerId);
        log.debug("[Session] Cleared binding for player {}", playerId);
    }

    public void clearAllHumanBindings(String tableId) {
        tableManager.getTable(tableId).ifPresent(actor -> {
            for (PlayerState p : actor.getState().getPlayers()) {
                if (!p.isBot()) {
                    clearPlayerBinding(p.getPlayerId());
                }
            }
        });
    }

    /**
     * Returns an active resumable session, or empty if none / finished / stale.
     */
    public Optional<Map<String, Object>> findActiveSession(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return Optional.empty();
        }

        Optional<String> tableOpt = routingRegistry.getTableForPlayer(playerId);
        if (tableOpt.isEmpty()) {
            // Routing may have died with a crashed node; its match may still be resumable here.
            if (recovery == null) {
                return Optional.empty();
            }
            TableRecoveryService.RestoreResult restored = recovery.tryRestoreForPlayer(playerId);
            if (restored.outcome() == TableRecoveryService.Outcome.OWNER_ALIVE) {
                return Optional.of(recoveringSession(playerId, restored.tableId()));
            }
            if (restored.outcome() != TableRecoveryService.Outcome.RESTORED) {
                return Optional.empty();
            }
            tableOpt = Optional.of(restored.tableId());
        }

        String tableId = tableOpt.get();
        Optional<TableActor> actorOpt = tableManager.getTable(tableId);
        if (actorOpt.isEmpty()) {
            Optional<String> remoteOwner = clusterNodes != null
                    ? clusterNodes.liveRemoteOwner(tableId)
                    : Optional.empty();
            if (remoteOwner.isPresent()) {
                // Hosted on another live node; the socket handshake is redirected there.
                return Optional.of(Map.of(
                        "active", true,
                        "tableId", tableId,
                        "serverInstanceId", remoteOwner.get(),
                        "displayName", playerId
                ));
            }
            TableRecoveryService.RestoreResult restored = recovery != null
                    ? recovery.tryRestore(tableId)
                    : new TableRecoveryService.RestoreResult(TableRecoveryService.Outcome.NONE, tableId, null);
            if (restored.outcome() == TableRecoveryService.Outcome.OWNER_ALIVE) {
                return Optional.of(recoveringSession(playerId, tableId));
            }
            actorOpt = tableManager.getTable(tableId);
            if (actorOpt.isEmpty()) {
                // Stale routing (table finished and cleaned up, or its server died for good)
                clearPlayerBinding(playerId);
                return Optional.empty();
            }
        }

        TableActor actor = actorOpt.get();
        var state = actor.getState();
        GameStatus status = state.getStatus();

        // A pool/deals match is COMPLETED between deals but still running.
        if (status == GameStatus.ABORTED || (status == GameStatus.COMPLETED && !actor.isLive())) {
            clearPlayerBinding(playerId);
            return Optional.empty();
        }

        Optional<PlayerState> playerOpt = state.getPlayer(playerId);
        if (playerOpt.isEmpty()) {
            // Matched but WS JOIN not done yet — still resumable; do NOT clear binding
            if (status == GameStatus.WAITING_FOR_PLAYERS) {
                return Optional.of(Map.of(
                        "active", true,
                        "tableId", tableId,
                        "serverInstanceId", routingRegistry.getServerInstanceId(),
                        "gameStatus", status.name(),
                        "playerStatus", PlayerStatus.WAITING.name(),
                        "displayName", playerId,
                        "gameId", state.getGameId()
                ));
            }
            clearPlayerBinding(playerId);
            return Optional.empty();
        }

        PlayerState player = playerOpt.get();
        // Still seated (ACTIVE / DROPPED spectating / READY / etc.) while hand not finished
        return Optional.of(Map.of(
                "active", true,
                "tableId", tableId,
                "serverInstanceId", routingRegistry.getServerInstanceId(),
                "gameStatus", status.name(),
                "playerStatus", player.getStatus().name(),
                "displayName", player.getDisplayName() != null ? player.getDisplayName() : playerId,
                "gameId", state.getGameId()
        ));
    }

    /**
     * The player's table is on a server that just stopped responding; once it is declared dead this
     * node resumes the match when the client joins (it retries on {@code TABLE_RECOVERING}).
     */
    private Map<String, Object> recoveringSession(String playerId, String tableId) {
        return Map.of(
                "active", true,
                "tableId", tableId,
                "serverInstanceId", routingRegistry.getServerInstanceId(),
                "recovering", true,
                "displayName", playerId
        );
    }

    public boolean isResumableStatus(PlayerStatus status) {
        return status != PlayerStatus.ELIMINATED;
    }
}
