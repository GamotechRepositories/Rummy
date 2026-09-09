package com.rummy.gameservice.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.engine.EngineResult;
import com.rummy.engine.GameEngine;
import com.rummy.engine.bot.BotDifficulty;
import com.rummy.engine.bot.BotPlayerAgent;
import com.rummy.engine.command.*;
import com.rummy.engine.event.GameEvent;
import com.rummy.engine.model.*;
import com.rummy.engine.rules.RummyRules;
import com.rummy.gameservice.protocol.WsErrorMessage;
import com.rummy.gameservice.protocol.WsServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Sequential in-memory Table Actor per Section 27 (Phase 20).
 * Owns the authoritative GameState, executes commands serially to prevent race conditions,
 * and broadcasts zero-knowledge PlayerGameViews to connected sessions.
 */
public final class TableActor {

    private static final Logger log = LoggerFactory.getLogger(TableActor.class);

    private final String tableId;
    private final GameEngine engine;
    private final RummyRules rules;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;

    private GameState state;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, BotPlayerAgent> botAgents = new ConcurrentHashMap<>();
    private ScheduledFuture<?> turnTimeoutFuture;

    public TableActor(String tableId,
                      GameState initialState,
                      RummyRules rules,
                      GameEngine engine,
                      ObjectMapper objectMapper,
                      ScheduledExecutorService scheduler) {
        this.tableId = Objects.requireNonNull(tableId);
        this.state = Objects.requireNonNull(initialState);
        this.rules = Objects.requireNonNull(rules);
        this.engine = Objects.requireNonNull(engine);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    public synchronized void registerSession(String playerId, WebSocketSession session) {
        sessions.put(playerId, session);
        log.info("[TableActor:{}] Registered session for player {}", tableId, playerId);

        // Send full initial player view to reconnecting or joining player
        if (state.getPlayer(playerId).isPresent()) {
            sendPlayerView(playerId, null);
        }
    }

    public synchronized void unregisterSession(String playerId) {
        sessions.remove(playerId);
        log.info("[TableActor:{}] Unregistered session for player {}", tableId, playerId);
    }

    public synchronized void registerBot(String botId, String displayName, BotDifficulty difficulty) {
        BotPlayerAgent bot = new BotPlayerAgent(botId, displayName, difficulty);
        botAgents.put(botId, bot);
    }

    /**
     * Executes a command sequentially inside the synchronized monitor of this table.
     */
    public synchronized EngineResult processCommand(GameCommand command, String requestId) {
        log.debug("[TableActor:{}] Processing command: {} (req: {})", tableId, command.getClass().getSimpleName(), requestId);

        EngineResult result = engine.process(this.state, command, this.rules);

        if (!result.isSuccess()) {
            log.warn("[TableActor:{}] Command rejected: {}", tableId, result.errorMessage());
            sendErrorToPlayer(command.playerId(), "COMMAND_REJECTED", result.errorMessage(), requestId);
            return result;
        }

        // State successfully mutated
        this.state = result.state();

        // 1. Broadcast individual game events
        for (GameEvent event : result.events()) {
            broadcastMessage(WsServerMessage.of("GAME_EVENT", requestId, tableId, event.sequence(), event));
        }

        // 2. Broadcast private, sanitized PlayerGameViews to all connected human players
        for (String playerId : sessions.keySet()) {
            if (state.getPlayer(playerId).isPresent()) {
                sendPlayerView(playerId, requestId);
            }
        }

        // 3. Reset and schedule turn timeout timer
        resetTurnTimer();

        // 4. Trigger bot turn if active player is a bot
        triggerBotTurnIfApplicable();

        return result;
    }

    private void sendPlayerView(String playerId, String requestId) {
        WebSocketSession session = sessions.get(playerId);
        if (session != null && session.isOpen()) {
            try {
                PlayerGameView view = PlayerGameView.from(state, playerId);
                WsServerMessage msg = WsServerMessage.of("GAME_VIEW", requestId, tableId, state.getSequence(), view);
                String json = objectMapper.writeValueAsString(msg);
                session.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.error("[TableActor:{}] Failed to send view to player {}: {}", tableId, playerId, e.getMessage());
            }
        }
    }

    private void broadcastMessage(WsServerMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(json);
            for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
                WebSocketSession session = entry.getValue();
                if (session != null && session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }
        } catch (IOException e) {
            log.error("[TableActor:{}] Failed to broadcast message: {}", tableId, e.getMessage());
        }
    }

    private void sendErrorToPlayer(String playerId, String errorCode, String errorMessage, String requestId) {
        WebSocketSession session = sessions.get(playerId);
        if (session != null && session.isOpen()) {
            try {
                WsErrorMessage err = new WsErrorMessage(errorCode, errorMessage, requestId);
                WsServerMessage msg = WsServerMessage.of("ERROR", requestId, tableId, err);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
            } catch (IOException e) {
                log.error("[TableActor:{}] Failed to send error to player {}: {}", tableId, playerId, e.getMessage());
            }
        }
    }

    private void resetTurnTimer() {
        if (turnTimeoutFuture != null && !turnTimeoutFuture.isDone()) {
            turnTimeoutFuture.cancel(false);
        }

        TurnState turn = state.getTurnState();
        if (state.getStatus() == GameStatus.IN_PROGRESS && turn != null) {
            long delaySeconds = Math.max(1, turn.getTurnDeadline().getEpochSecond() - Instant.now().getEpochSecond());
            turnTimeoutFuture = scheduler.schedule(() -> {
                synchronized (this) {
                    if (state.getStatus() == GameStatus.IN_PROGRESS) {
                        TurnState currentTurn = state.getTurnState();
                        if (currentTurn != null && currentTurn.getCurrentPlayerId().equals(turn.getCurrentPlayerId())) {
                            log.info("[TableActor:{}] Turn timed out for player {}", tableId, turn.getCurrentPlayerId());
                            TimeoutCommand timeoutCmd = new TimeoutCommand(UUID.randomUUID().toString(),
                                    state.getGameId(), turn.getCurrentPlayerId(), Instant.now());
                            processCommand(timeoutCmd, "SYSTEM_TIMEOUT");
                        }
                    }
                }
            }, delaySeconds, TimeUnit.SECONDS);
        }
    }

    private void triggerBotTurnIfApplicable() {
        if (state.getStatus() != GameStatus.IN_PROGRESS) {
            return;
        }
        TurnState turn = state.getTurnState();
        if (turn == null) {
            return;
        }

        String activePlayerId = turn.getCurrentPlayerId();
        BotPlayerAgent bot = botAgents.get(activePlayerId);

        if (bot != null) {
            // Schedule bot move with a realistic 100ms thinking delay
            scheduler.schedule(() -> {
                synchronized (this) {
                    if (state.getStatus() == GameStatus.IN_PROGRESS &&
                            state.getTurnState() != null &&
                            state.getTurnState().getCurrentPlayerId().equals(activePlayerId)) {

                        PlayerGameView botView = PlayerGameView.from(state, activePlayerId);
                        GameCommand botAction = bot.decideAction(botView, rules);
                        processCommand(botAction, "BOT_ACTION_" + UUID.randomUUID());
                    }
                }
            }, 100, TimeUnit.MILLISECONDS);
        }
    }

    public synchronized GameState getState() {
        return state;
    }

    public String getTableId() {
        return tableId;
    }

    public synchronized void destroy() {
        if (turnTimeoutFuture != null) {
            turnTimeoutFuture.cancel(true);
        }
        sessions.clear();
        botAgents.clear();
    }
}
