package com.rummy.gameservice.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.engine.EngineResult;
import com.rummy.engine.GameEngine;
import com.rummy.engine.bot.BotDifficulty;
import com.rummy.engine.bot.BotPlayerAgent;
import com.rummy.engine.bot.HandEvaluator;
import com.rummy.engine.bot.IndianBotNames;
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
    private final com.rummy.gameservice.persistence.GamePersistenceService persistenceService;
    private final com.rummy.gameservice.kafka.GameEventProducer eventProducer;
    private final com.rummy.gameservice.session.PlayerSessionService sessionService;

    private GameState state;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, BotPlayerAgent> botAgents = new ConcurrentHashMap<>();
    private ScheduledFuture<?> turnTimeoutFuture;
    private ScheduledFuture<?> autoStartFallbackFuture;
    private ScheduledFuture<?> botTurnFuture;

    /**
     * After the last human drops, bots keep playing for a few rounds then drop one-by-one
     * so a spectating human still sees a natural table instead of an instant end.
     */
    private boolean botsOnlyWindDown;
    private int turnsSinceLastBotDrop;
    private int turnsUntilNextBotDrop;
    private int lastWindDownTurnCounted = -1;

    public TableActor(String tableId,
                      GameState initialState,
                      RummyRules rules,
                      GameEngine engine,
                      ObjectMapper objectMapper,
                      ScheduledExecutorService scheduler,
                      com.rummy.gameservice.persistence.GamePersistenceService persistenceService,
                      com.rummy.gameservice.kafka.GameEventProducer eventProducer,
                      com.rummy.gameservice.session.PlayerSessionService sessionService) {
        this.tableId = Objects.requireNonNull(tableId);
        this.state = Objects.requireNonNull(initialState);
        this.rules = Objects.requireNonNull(rules);
        this.engine = Objects.requireNonNull(engine);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.objectMapper.findAndRegisterModules();
        this.scheduler = Objects.requireNonNull(scheduler);
        this.persistenceService = persistenceService;
        this.eventProducer = eventProducer;
        this.sessionService = sessionService;
    }

    public TableActor(String tableId,
                      GameState initialState,
                      RummyRules rules,
                      GameEngine engine,
                      ObjectMapper objectMapper,
                      ScheduledExecutorService scheduler,
                      com.rummy.gameservice.persistence.GamePersistenceService persistenceService,
                      com.rummy.gameservice.kafka.GameEventProducer eventProducer) {
        this(tableId, initialState, rules, engine, objectMapper, scheduler, persistenceService, eventProducer, null);
    }

    public TableActor(String tableId,
                      GameState initialState,
                      RummyRules rules,
                      GameEngine engine,
                      ObjectMapper objectMapper,
                      ScheduledExecutorService scheduler,
                      com.rummy.gameservice.persistence.GamePersistenceService persistenceService) {
        this(tableId, initialState, rules, engine, objectMapper, scheduler, persistenceService, null, null);
    }

    public TableActor(String tableId,
                      GameState initialState,
                      RummyRules rules,
                      GameEngine engine,
                      ObjectMapper objectMapper,
                      ScheduledExecutorService scheduler) {
        this(tableId, initialState, rules, engine, objectMapper, scheduler, null, null, null);
    }

    public synchronized void registerSession(String playerId, WebSocketSession session) {
        sessions.put(playerId, session);
        log.info("[TableActor:{}] Registered session for player {}", tableId, playerId);

        // Send full initial player view to reconnecting or joining player
        if (state.getPlayer(playerId).isPresent()) {
            sendPlayerView(playerId, null);
        }

        if (state.getStatus() == GameStatus.WAITING_FOR_PLAYERS) {
            checkAndScheduleAutoBotFallback();
        }
    }

    public synchronized void unregisterSession(String playerId) {
        sessions.remove(playerId);
        log.info("[TableActor:{}] Unregistered session for player {}", tableId, playerId);
        boolean hasActiveHuman = sessions.values().stream().anyMatch(WebSocketSession::isOpen);
        if (!hasActiveHuman && state.getStatus() == GameStatus.WAITING_FOR_PLAYERS) {
            cancelAutoBotFallback();
        }
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

        // 1. Broadcast individual game events, persist, and stream to Kafka
        for (GameEvent event : result.events()) {
            broadcastMessage(WsServerMessage.of("GAME_EVENT", requestId, tableId, event.sequence(), event));
            if (eventProducer != null) {
                eventProducer.publishGameEvent(state.getGameId(), event);
            }
            if (persistenceService != null) {
                persistenceService.recordGameEventAsync(state.getGameId(), event);
                if (event instanceof com.rummy.engine.event.GameStartedEvent) {
                    persistenceService.recordGameStarted(state, tableId);
                } else if (event instanceof com.rummy.engine.event.GameFinishedEvent) {
                    persistenceService.recordGameFinished(state, tableId);
                }
            }
            if (event instanceof com.rummy.engine.event.GameFinishedEvent && sessionService != null) {
                // Match over — no more soft-reconnect into this table
                sessionService.clearAllHumanBindings(tableId);
                log.info("[TableActor:{}] Cleared player session bindings after game finish", tableId);
            }
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

        // 5. Update auto-bot fallback timer when in WAITING_FOR_PLAYERS
        if (state.getStatus() == GameStatus.WAITING_FOR_PLAYERS) {
            checkAndScheduleAutoBotFallback();
        } else {
            cancelAutoBotFallback();
        }

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
        if (bot == null) {
            return;
        }

        refreshBotsOnlyWindDown();

        // Random 3–6s think time so the seat feels like a human, not an instant AI.
        long thinkSeconds = ThreadLocalRandom.current().nextInt(3, 7);
        log.info("[TableActor:{}] Bot {} thinking for {}s before acting", tableId, activePlayerId, thinkSeconds);

        if (botTurnFuture != null && !botTurnFuture.isDone()) {
            botTurnFuture.cancel(false);
        }

        botTurnFuture = scheduler.schedule(() -> {
            synchronized (this) {
                if (state.getStatus() != GameStatus.IN_PROGRESS
                        || state.getTurnState() == null
                        || !state.getTurnState().getCurrentPlayerId().equals(activePlayerId)) {
                    return;
                }

                refreshBotsOnlyWindDown();

                TurnState currentTurn = state.getTurnState();
                if (botsOnlyWindDown
                        && currentTurn.getPhase() == TurnPhase.AWAITING_DRAW
                        && countActiveBots() > 1) {

                    if (currentTurn.getTurnNumber() != lastWindDownTurnCounted) {
                        lastWindDownTurnCounted = currentTurn.getTurnNumber();
                        turnsSinceLastBotDrop++;
                    }

                    if (turnsSinceLastBotDrop >= turnsUntilNextBotDrop) {
                        String weakestBotId = findWeakestActiveBotId();
                        // Only the weakest bot drops — wait until their turn so it looks natural.
                        if (weakestBotId != null && weakestBotId.equals(activePlayerId)) {
                            log.info(
                                    "[TableActor:{}] Bots-only wind-down: weakest bot {} drops after {} turns (threshold {})",
                                    tableId, activePlayerId, turnsSinceLastBotDrop, turnsUntilNextBotDrop
                            );
                            processCommand(new DropCommand(
                                    UUID.randomUUID().toString(),
                                    state.getGameId(),
                                    activePlayerId,
                                    Instant.now()
                            ), "BOT_WINDDOWN_DROP");

                            if (state.getStatus() == GameStatus.IN_PROGRESS && countActiveBots() > 1) {
                                armNextBotDropThreshold();
                            }
                            return;
                        }
                        log.debug(
                                "[TableActor:{}] Wind-down threshold reached; waiting for weakest bot {} (current {})",
                                tableId, weakestBotId, activePlayerId
                        );
                    }
                }

                PlayerGameView botView = PlayerGameView.from(state, activePlayerId);
                GameCommand botAction = bot.decideAction(botView, rules);
                processCommand(botAction, "BOT_ACTION_" + UUID.randomUUID());
            }
        }, thinkSeconds, TimeUnit.SECONDS);
    }

    private void refreshBotsOnlyWindDown() {
        if (state.getStatus() != GameStatus.IN_PROGRESS) {
            botsOnlyWindDown = false;
            return;
        }

        long activeHumans = countActiveHumans();
        long activeBots = countActiveBots();

        if (activeHumans > 0) {
            if (botsOnlyWindDown) {
                log.info("[TableActor:{}] Exiting bots-only wind-down — human still active", tableId);
            }
            botsOnlyWindDown = false;
            return;
        }

        // Need at least 2 bots to stage a natural wind-down; 1 bot already ends via engine drop rules.
        if (activeBots < 2) {
            botsOnlyWindDown = false;
            return;
        }

        if (!botsOnlyWindDown) {
            botsOnlyWindDown = true;
            armNextBotDropThreshold();
            log.info(
                    "[TableActor:{}] Entered bots-only wind-down ({} bots). Next drop after ~{} full rounds ({} turns)",
                    tableId,
                    activeBots,
                    Math.max(1, turnsUntilNextBotDrop / (int) activeBots),
                    turnsUntilNextBotDrop
            );
        }
    }

    private void armNextBotDropThreshold() {
        int rounds = ThreadLocalRandom.current().nextInt(4, 7); // 4–6 full rounds between bot drops
        int activeBots = (int) Math.max(1, countActiveBots());
        turnsSinceLastBotDrop = 0;
        lastWindDownTurnCounted = -1;
        turnsUntilNextBotDrop = rounds * activeBots;
        log.info(
                "[TableActor:{}] Next bot drop armed: {} rounds × {} bots = {} turns",
                tableId, rounds, activeBots, turnsUntilNextBotDrop
        );
    }

    private long countActiveHumans() {
        return state.getPlayers().stream()
                .filter(p -> !p.isBot() && p.getStatus() == PlayerStatus.ACTIVE)
                .count();
    }

    private long countActiveBots() {
        return state.getPlayers().stream()
                .filter(p -> p.isBot() && p.getStatus() == PlayerStatus.ACTIVE)
                .count();
    }

    /**
     * Picks the active bot most likely to lose (highest deadwood). Ties break by playerId for stability.
     */
    private String findWeakestActiveBotId() {
        Card cut = state.getCutJoker() != null ? state.getCutJoker().getCard() : null;
        String weakestId = null;
        int worstDeadwood = Integer.MIN_VALUE;

        for (PlayerState player : state.getPlayers()) {
            if (!player.isBot() || player.getStatus() != PlayerStatus.ACTIVE) {
                continue;
            }
            HandEvaluator.EvaluationResult eval = HandEvaluator.evaluateDeadwood(player.getHandSnapshot(), cut);
            int deadwood = eval.deadwoodPoints();
            if (deadwood > worstDeadwood
                    || (deadwood == worstDeadwood && (weakestId == null || player.getPlayerId().compareTo(weakestId) < 0))) {
                worstDeadwood = deadwood;
                weakestId = player.getPlayerId();
            }
        }

        if (weakestId != null) {
            log.info("[TableActor:{}] Weakest active bot for wind-down drop: {} (deadwood={})",
                    tableId, weakestId, worstDeadwood);
        }
        return weakestId;
    }

    public synchronized GameState getState() {
        return state;
    }

    public String getTableId() {
        return tableId;
    }

    private void checkAndScheduleAutoBotFallback() {
        if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
            cancelAutoBotFallback();
            return;
        }

        boolean hasHuman = state.getPlayers().stream().anyMatch(p -> !p.isBot());
        if (!hasHuman || state.getPlayers().size() >= 2) {
            if (state.getPlayers().size() >= 2) {
                cancelAutoBotFallback();
            }
            return;
        }

        if (autoStartFallbackFuture != null && !autoStartFallbackFuture.isDone()) {
            return;
        }

        log.info("[TableActor:{}] Scheduling 15s auto-bot fallback for waiting player...", tableId);
        autoStartFallbackFuture = scheduler.schedule(() -> {
            synchronized (this) {
                if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
                    return;
                }

                int currentCount = state.getPlayers().size();
                if (currentCount >= 2) {
                    return;
                }

                log.info("[TableActor:{}] Auto-bot fallback triggered: Table has {} player(s), adding bot to start match", tableId, currentCount);

                Set<Integer> occupied = new HashSet<>();
                for (PlayerState p : state.getPlayers()) {
                    occupied.add(p.getSeatIndex());
                }

                int neededBots = 2 - currentCount;
                Set<String> usedNames = new HashSet<>();
                for (PlayerState p : state.getPlayers()) {
                    if (p.getDisplayName() != null) {
                        usedNames.add(p.getDisplayName());
                    }
                }
                for (int i = 0; i < neededBots; i++) {
                    int freeSeat = 1;
                    for (int s = 0; s < 6; s++) {
                        if (!occupied.contains(s)) {
                            freeSeat = s;
                            occupied.add(s);
                            break;
                        }
                    }

                    String botId = "BOT_" + UUID.randomUUID().toString().substring(0, 4);
                    String botName = IndianBotNames.nextUnique(usedNames);
                    registerBot(botId, botName, BotDifficulty.MEDIUM);

                    processCommand(new JoinCommand(
                            UUID.randomUUID().toString(),
                            state.getGameId(),
                            botId,
                            botName,
                            freeSeat,
                            true,
                            Instant.now()
                    ), "FALLBACK_BOT_JOIN");

                    processCommand(new ReadyCommand(
                            UUID.randomUUID().toString(),
                            state.getGameId(),
                            botId,
                            Instant.now()
                    ), "FALLBACK_BOT_READY");
                }

                boolean allReady = state.getPlayers().stream().allMatch(p -> p.getStatus() == PlayerStatus.READY);
                if (allReady && state.getPlayers().size() >= 2 && state.getStatus() == GameStatus.WAITING_FOR_PLAYERS) {
                    String starterId = state.getPlayers().get(0).getPlayerId();
                    processCommand(new StartGameCommand(
                            UUID.randomUUID().toString(),
                            state.getGameId(),
                            starterId,
                            Instant.now()
                    ), "FALLBACK_AUTO_START");
                    log.info("[TableActor:{}] Auto-started game with bot via fallback", tableId);
                }
            }
        }, 15, TimeUnit.SECONDS);
    }

    private void cancelAutoBotFallback() {
        if (autoStartFallbackFuture != null && !autoStartFallbackFuture.isDone()) {
            autoStartFallbackFuture.cancel(false);
            autoStartFallbackFuture = null;
        }
    }

    public synchronized void destroy() {
        if (turnTimeoutFuture != null) {
            turnTimeoutFuture.cancel(true);
        }
        if (botTurnFuture != null) {
            botTurnFuture.cancel(true);
        }
        cancelAutoBotFallback();
        botsOnlyWindDown = false;
        sessions.clear();
        botAgents.clear();
    }
}
