package com.rummy.gameservice.session;

import com.rummy.engine.model.GameStatus;
import com.rummy.engine.model.PlayerState;
import com.rummy.engine.model.PlayerStatus;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public PlayerSessionService(TableRoutingRegistry routingRegistry, @Lazy TableManager tableManager) {
        this.routingRegistry = routingRegistry;
        this.tableManager = tableManager;
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
            return Optional.empty();
        }

        String tableId = tableOpt.get();
        Optional<TableActor> actorOpt = tableManager.getTable(tableId);
        if (actorOpt.isEmpty()) {
            // Stale routing (server restart / table GC)
            clearPlayerBinding(playerId);
            return Optional.empty();
        }

        TableActor actor = actorOpt.get();
        var state = actor.getState();
        GameStatus status = state.getStatus();

        if (status == GameStatus.COMPLETED || status == GameStatus.ABORTED) {
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

    public boolean isResumableStatus(PlayerStatus status) {
        return status != PlayerStatus.ELIMINATED;
    }
}
