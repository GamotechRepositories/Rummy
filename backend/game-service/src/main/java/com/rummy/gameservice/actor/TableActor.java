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
import com.rummy.gameservice.websocket.SessionOutbox;
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

    private static final ExecutorService vThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private GameState state;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, BotPlayerAgent> botAgents = new ConcurrentHashMap<>();
    private final Map<String, Integer> rejoinCounts = new ConcurrentHashMap<>();
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

    /** Seats that must be filled before the deal. Set by matchmaking (2 or 6). 0 means a private table. */
    private int expectedPlayers;
    /** Matchmaking owns bot fill; the 15s private-table fallback must not add more. */
    private boolean matchmakingOwnsFill;
    /** Lobby tables do not deal until this instant. Null for private and already-full matches. */
    private Instant dealNotBefore;
    /** After the lobby window, a late arrival must not take a seat on this table. */
    private boolean seatingClosed;

    private final com.rummy.gameservice.wallet.WalletService walletService;
    private com.rummy.gameservice.wallet.StakeEscrowService escrows;
    private com.rummy.gameservice.wallet.SettlementService settlements;
    /** Rejoin fees debited per player; never decremented, so each fee gets its own idempotency key. */
    private final Map<String, Integer> rejoinFeesCharged = new HashMap<>();
    /** Only matchmaking sets a stake, after debiting entry fees. Private tables stay at 0 and never settle. */
    private int stakeTier = 0;
    private com.rummy.gameservice.wallet.GameSettlementResult lastSettlement;

    private final List<PlayerGameView.DealScoreRecord> dealHistory = new CopyOnWriteArrayList<>();
    private final List<String> lastEliminatedNames = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> nextDealFuture;
    private Integer nextDealCountdown = null;
    private Instant nextDealScheduledAt = null;
    private String tournamentWinnerId = null;
    private int effectiveTotalDeals;
    private final Set<String> voluntaryAbandoners = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Pool break between deals: long enough to rejoin or ask for a split. */
    static final int POOL_BREAK_SECONDS = 10;
    /** Everyone asked to split must answer within this long; silence counts as no. */
    static final int SPLIT_ANSWER_SECONDS = 15;
    /** Next deal after a split is turned down. */
    private static final int AFTER_SPLIT_DECLINED_SECONDS = 5;

    /** A pool prize split waiting for answers; lives only during one break between deals. */
    private record SplitOffer(String requestedBy, Map<String, Integer> dropsLeft,
                              Map<String, java.math.BigDecimal> payouts, Set<String> accepted, Instant deadline) {
    }

    private SplitOffer splitOffer;
    /** One split request per break, so a refused split cannot be re-asked to stall the table. */
    private boolean splitAskedThisBreak;

    private Instant lastActivityAt = Instant.now();
    /** Set once the whole match (all deals) is over and settled; drives memory reclamation. */
    private Instant matchFinishedAt;
    private boolean destroyed;
    /** Snapshot handed to another node; no further change may happen here. */
    private boolean frozen;
    /** Bumped on every state change, so the snapshot writer only re-saves tables that moved. */
    private volatile long mutations;

    public TableActor(String tableId,
                      GameState initialState,
                      RummyRules rules,
                      GameEngine engine,
                      ObjectMapper objectMapper,
                      ScheduledExecutorService scheduler,
                      com.rummy.gameservice.persistence.GamePersistenceService persistenceService,
                      com.rummy.gameservice.kafka.GameEventProducer eventProducer,
                      com.rummy.gameservice.session.PlayerSessionService sessionService,
                      com.rummy.gameservice.wallet.WalletService walletService) {
        this.tableId = Objects.requireNonNull(tableId);
        this.state = Objects.requireNonNull(initialState);
        this.rules = Objects.requireNonNull(rules);
        this.effectiveTotalDeals = rules.getTotalDeals();
        this.engine = Objects.requireNonNull(engine);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.objectMapper.findAndRegisterModules();
        this.scheduler = Objects.requireNonNull(scheduler);
        this.persistenceService = persistenceService;
        this.eventProducer = eventProducer;
        this.sessionService = sessionService;
        this.walletService = walletService;
    }

    void setEscrows(com.rummy.gameservice.wallet.StakeEscrowService escrows) {
        this.escrows = escrows;
    }

    void setSettlements(com.rummy.gameservice.wallet.SettlementService settlements) {
        this.settlements = settlements;
    }

    public TableActor(String tableId,
                      GameState initialState,
                      RummyRules rules,
                      GameEngine engine,
                      ObjectMapper objectMapper,
                      ScheduledExecutorService scheduler,
                      com.rummy.gameservice.persistence.GamePersistenceService persistenceService,
                      com.rummy.gameservice.kafka.GameEventProducer eventProducer,
                      com.rummy.gameservice.session.PlayerSessionService sessionService) {
        this(tableId, initialState, rules, engine, objectMapper, scheduler, persistenceService, eventProducer, sessionService, null);
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
        if (destroyed) {
            return;
        }
        sessions.put(playerId, session);
        lastActivityAt = Instant.now();
        log.debug("[TableActor:{}] Registered session for player {}", tableId, playerId);

        // Send full initial player view to reconnecting or joining player
        if (state.getPlayer(playerId).isPresent()) {
            sendPlayerView(playerId, null);
        }

        if (lastSettlement != null && session.isOpen()) {
            try {
                WsServerMessage msg = WsServerMessage.of("GAME_SETTLEMENT", null, tableId, state.getSequence(), lastSettlement);
                SessionOutbox.send(session, objectMapper.writeValueAsString(msg));
            } catch (IOException e) {
                log.warn("[TableActor:{}] Could not serialize settlement for {}: {}", tableId, playerId, e.getMessage());
            }
        }

        if (state.getStatus() == GameStatus.WAITING_FOR_PLAYERS) {
            checkAndScheduleAutoBotFallback();
        }
    }

    public synchronized void unregisterSession(String playerId) {
        removeSession(playerId, sessions.get(playerId));
    }

    /**
     * Drops the session only if it is still the player's current one, so a stale socket closing
     * after a quick reconnect does not detach the new connection.
     */
    public synchronized void unregisterSession(String playerId, WebSocketSession closing) {
        removeSession(playerId, closing);
    }

    private void removeSession(String playerId, WebSocketSession expected) {
        if (expected == null || !sessions.remove(playerId, expected)) {
            return;
        }
        lastActivityAt = Instant.now();
        log.debug("[TableActor:{}] Unregistered session for player {}", tableId, playerId);
        if (!hasOpenSession() && state.getStatus() == GameStatus.WAITING_FOR_PLAYERS) {
            cancelAutoBotFallback();
        }
    }

    private boolean hasOpenSession() {
        return sessions.values().stream().anyMatch(WebSocketSession::isOpen);
    }

    /**
     * Why this table can be dropped from memory, or empty while it must stay.
     * Tables with a live deal, a pending next deal, or an open matchmaking lobby are never reclaimed,
     * because their escrowed stakes still have to be settled.
     */
    public synchronized Optional<String> reclaimReason(Instant now, java.time.Duration finishedGrace, java.time.Duration abandonedAfter) {
        if (destroyed) {
            return Optional.of("already destroyed");
        }
        if (matchFinishedAt != null) {
            return now.isAfter(matchFinishedAt.plus(finishedGrace))
                    ? Optional.of("match finished at " + matchFinishedAt)
                    : Optional.empty();
        }
        if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
            return Optional.empty();
        }
        if (dealNotBefore != null && !seatingClosed) {
            // Open lobby: matchmaking deals or discards it on its own timer.
            return Optional.empty();
        }
        if (hasOpenSession() || !now.isAfter(lastActivityAt.plus(abandonedAfter))) {
            return Optional.empty();
        }
        return Optional.of("waiting room idle since " + lastActivityAt);
    }

    /**
     * True while players have money or a result riding on this table: a deal in progress, the gap
     * before the next pool/deals round, or a matchmaking lobby about to deal. A draining node waits for these.
     */
    public synchronized boolean isLive() {
        if (destroyed || matchFinishedAt != null) {
            return false;
        }
        return switch (state.getStatus()) {
            case DEALING, IN_PROGRESS, DECLARING, SETTLING -> true;
            case COMPLETED -> nextDealFuture != null && !nextDealFuture.isDone();
            case WAITING_FOR_PLAYERS -> dealNotBefore != null && !seatingClosed && countHumans() > 0;
            default -> false;
        };
    }

    /**
     * Ends an unfinished started match without a winner and returns every human's entry (plus any
     * rejoin fees). Used when a node must shut down before the match could finish.
     * Waiting rooms are skipped: their stakes are still held by matchmaking tickets.
     */
    public synchronized void abortMatch(String reason) {
        if (destroyed || matchFinishedAt != null || state.getStatus() == GameStatus.WAITING_FOR_PLAYERS) {
            return;
        }
        matchFinishedAt = Instant.now();
        mutations++;
        if (nextDealFuture != null) {
            nextDealFuture.cancel(false);
        }
        if (escrows != null) {
            escrows.refundGame(state.getGameId(), reason);
        } else if (walletService != null && stakeTier > 0) {
            for (PlayerState p : state.getPlayers()) {
                if (p.isBot()) {
                    continue;
                }
                int paidEntries = 1 + rejoinCounts.getOrDefault(p.getPlayerId(), 0);
                try {
                    walletService.credit(
                            p.getPlayerId(),
                            java.math.BigDecimal.valueOf((long) stakeTier * paidEntries),
                            "GAME_ABORT_REFUND",
                            "ABORT_REFUND_" + state.getGameId() + "_" + p.getPlayerId(),
                            state.getGameId(),
                            "Game cancelled: " + reason,
                            Map.of("tableId", tableId, "entries", paidEntries)
                    );
                } catch (Exception e) {
                    log.error("[TableActor:{}] RECONCILE REQUIRED: abort refund failed for {}: {}", tableId, p.getPlayerId(), e.getMessage());
                }
            }
        }
        broadcastMessage(WsServerMessage.of("TABLE_CLOSED", null, tableId, state.getSequence(),
                Map.of("reason", reason, "refunded", stakeTier > 0,
                        "message", "Game was cancelled for server maintenance."
                                + (stakeTier > 0 ? " Your entry fee has been refunded." : ""))));
        log.warn("[TableActor:{}] Match aborted ({}); entries refunded", tableId, reason);
    }

    public synchronized List<String> humanPlayerIds() {
        return state.getPlayers().stream()
                .filter(p -> !p.isBot())
                .map(PlayerState::getPlayerId)
                .toList();
    }

    public synchronized void registerBot(String botId, String displayName, BotDifficulty difficulty) {
        BotPlayerAgent bot = new BotPlayerAgent(botId, displayName, difficulty);
        botAgents.put(botId, bot);
    }

    /**
     * Matchmade tables deal only once this many seats are taken, unless a timed lobby
     * closes first and deals with whoever is already seated.
     */
    public synchronized void setExpectedPlayers(int count) {
        int cap = Math.min(rules.getMaxPlayers(), Math.max(2, count));
        this.expectedPlayers = cap;
        this.matchmakingOwnsFill = true;
        log.info("[TableActor:{}] Matchmade table expects {} players", tableId, cap);
    }

    /** Hold the deal until {@code dealAt}. Server-owned lobby seating uses this. */
    public synchronized void armDealHold(Instant dealAt) {
        this.dealNotBefore = dealAt;
        this.seatingClosed = false;
    }

    public synchronized boolean isSeatingOpen() {
        return !seatingClosed
                && state.getStatus() == GameStatus.WAITING_FOR_PLAYERS
                && (dealNotBefore == null || Instant.now().isBefore(dealNotBefore));
    }

    public synchronized int countBots() {
        return (int) state.getPlayers().stream().filter(PlayerState::isBot).count();
    }

    public synchronized int countHumans() {
        return (int) state.getPlayers().stream().filter(p -> !p.isBot()).count();
    }

    /**
     * Seat one server-created bot into a free seat. No-op when the table is full
     * once {@code reservedSeats} (humans matched but not yet connected) are counted.
     * The bot agent is idle until the deal creates a turn.
     */
    public synchronized boolean seatOneWaitingBot(int reservedSeats) {
        if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS || seatingClosed) {
            return false;
        }
        int cap = expectedPlayers >= 2 ? expectedPlayers : rules.getMaxPlayers();
        if (state.getPlayers().size() + Math.max(0, reservedSeats) >= cap) {
            return false;
        }

        Set<Integer> occupied = new HashSet<>();
        Set<String> usedNames = new HashSet<>();
        for (PlayerState player : state.getPlayers()) {
            occupied.add(player.getSeatIndex());
            if (player.getDisplayName() != null) {
                usedNames.add(player.getDisplayName());
            }
        }
        int freeSeat = -1;
        for (int seat = 0; seat < cap; seat++) {
            if (!occupied.contains(seat)) {
                freeSeat = seat;
                break;
            }
        }
        if (freeSeat < 0) {
            return false;
        }

        String botId = "BOT_" + UUID.randomUUID().toString().substring(0, 8);
        String botName = IndianBotNames.nextUnique(usedNames);
        registerBot(botId, botName, BotDifficulty.HARD);
        Instant now = Instant.now();
        processCommand(new JoinCommand(
                UUID.randomUUID().toString(),
                state.getGameId(),
                botId,
                botName,
                freeSeat,
                true,
                now
        ), "LOBBY_BOT_JOIN");
        processCommand(new ReadyCommand(
                UUID.randomUUID().toString(),
                state.getGameId(),
                botId,
                now
        ), "LOBBY_BOT_READY");
        log.info("[TableActor:{}] Seated waiting bot {} ({}) at seat {}", tableId, botId, botName, freeSeat);
        return state.getPlayer(botId).isPresent();
    }

    /** Drop the most recently seated bot so a real player can take the seat. Waiting room only. */
    public synchronized boolean evictLatestBot() {
        if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
            return false;
        }
        PlayerState latest = null;
        for (PlayerState player : state.getPlayers()) {
            if (player.isBot()) {
                latest = player;
            }
        }
        if (latest == null) {
            return false;
        }
        String botId = latest.getPlayerId();
        state.removePlayer(botId);
        botAgents.remove(botId);
        refreshHumanViews();
        log.info("[TableActor:{}] Evicted bot {} for an arriving player", tableId, botId);
        return true;
    }

    /** Remove a human who left before the deal. */
    public synchronized boolean removeWaitingHuman(String playerId) {
        if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
            return false;
        }
        var player = state.getPlayer(playerId);
        if (player.isEmpty() || player.get().isBot()) {
            return false;
        }
        state.removePlayer(playerId);
        refreshHumanViews();
        log.info("[TableActor:{}] Removed waiting human {}", tableId, playerId);
        return true;
    }

    /** Close the lobby window and deal to whoever is seated. */
    public synchronized void closeSeatingAndDeal() {
        this.dealNotBefore = null;
        this.seatingClosed = true;
        if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
            return;
        }
        if (state.getPlayers().size() < 2) {
            log.info("[TableActor:{}] Lobby closed with {} player(s); deal not started", tableId, state.getPlayers().size());
            return;
        }
        Instant now = Instant.now();
        for (PlayerState player : new ArrayList<>(state.getPlayers())) {
            if (player.getStatus() != PlayerStatus.READY) {
                processCommand(new ReadyCommand(
                        UUID.randomUUID().toString(),
                        state.getGameId(),
                        player.getPlayerId(),
                        now
                ), "LOBBY_READY");
            }
        }
        String starterId = state.getPlayers().get(0).getPlayerId();
        processCommand(new StartGameCommand(
                UUID.randomUUID().toString(),
                state.getGameId(),
                starterId,
                now
        ), "LOBBY_DEAL");
        log.info("[TableActor:{}] Dealt lobby table with {} players", tableId, state.getPlayers().size());
    }

    private void refreshHumanViews() {
        for (String playerId : sessions.keySet()) {
            if (state.getPlayer(playerId).isPresent()) {
                sendPlayerView(playerId, null);
            }
        }
    }

    public synchronized boolean shouldAutoStart() {
        if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
            return false;
        }
        if (dealNotBefore != null && Instant.now().isBefore(dealNotBefore)) {
            return false;
        }
        int required = expectedPlayers >= 2 ? expectedPlayers : 2;
        if (!seatingClosed && state.getPlayers().size() < required) {
            return false;
        }
        if (seatingClosed && state.getPlayers().size() < 2) {
            return false;
        }
        return state.getPlayers().stream().allMatch(p -> p.getStatus() == PlayerStatus.READY);
    }

    /**
     * Executes a command sequentially inside the synchronized monitor of this table.
     */
    public synchronized EngineResult processCommand(GameCommand command, String requestId) {
        log.debug("[TableActor:{}] Processing command: {} (req: {})", tableId, command.getClass().getSimpleName(), requestId);

        if (destroyed) {
            return EngineResult.failure(this.state, "Table is closed");
        }
        if (frozen) {
            return EngineResult.failure(this.state, "Table is moving to another server");
        }

        if (command instanceof StartGameCommand && holdingForPlayers()) {
            log.info("[TableActor:{}] Holding deal until {} seats are filled ({} seated)",
                    tableId, expectedPlayers, state.getPlayers().size());
            return EngineResult.failure(this.state, "Table is still filling");
        }

        EngineResult result = engine.process(this.state, command, this.rules);

        if (!result.isSuccess()) {
            log.warn("[TableActor:{}] Command rejected: {}", tableId, result.errorMessage());
            sendErrorToPlayer(command.playerId(), "COMMAND_REJECTED", result.errorMessage(), requestId);
            return result;
        }

        // State successfully mutated
        this.state = result.state();
        this.lastActivityAt = Instant.now();
        mutations++;

        // 1. Broadcast individual game events, persist, and stream to Kafka
        for (GameEvent event : result.events()) {
            broadcastMessage(WsServerMessage.of("GAME_EVENT", requestId, tableId, event.sequence(), event));
            if (eventProducer != null) {
                eventProducer.publishGameEvent(state.getGameId(), event);
            }
            if (persistenceService != null) {
                persistenceService.recordGameEventAsync(state.getGameId(), event);
            }
            if (event instanceof com.rummy.engine.event.CardDrawnEvent drawnEvent) {
                if (drawnEvent.source() == DrawSource.DISCARD_PILE && drawnEvent.drawnCard() != null) {
                    Card pickedCard = drawnEvent.drawnCard().getCard();
                    for (Map.Entry<String, BotPlayerAgent> entry : botAgents.entrySet()) {
                        if (!entry.getKey().equals(drawnEvent.playerId())) {
                            entry.getValue().recordOpponentPick(drawnEvent.playerId(), pickedCard);
                        }
                    }
                }
            }
            if (event instanceof com.rummy.engine.event.GameStartedEvent) {
                for (BotPlayerAgent botAgent : botAgents.values()) {
                    botAgent.resetDealMemory();
                }
                if (rules.isDealsGame() && state.getDealNumber() == 1) {
                    long initialChips = rules.getInitialChipsPerPlayer();
                    for (PlayerState p : state.getPlayers()) {
                        if (p.getChipBalance() <= 0) {
                            p.setChipBalance(initialChips);
                        }
                    }
                }
                if (persistenceService != null) {
                    persistenceService.recordGameStarted(state, tableId);
                }
            }

            if (event instanceof com.rummy.engine.event.GameFinishedEvent finishedEvent) {
                boolean isPool = rules.isEliminationGame();
                boolean isDeals = rules.isDealsGame();
                int threshold = rules.getEliminationThreshold();
                int totalDeals = rules.getTotalDeals();

                if (isDeals) {
                    String winnerId = finishedEvent.winnerPlayerId();
                    int totalChipsWon = 0;

                    for (PlayerState p : state.getPlayers()) {
                        if (!p.getPlayerId().equals(winnerId)) {
                            int penalty = finishedEvent.finalScores().getOrDefault(p.getPlayerId(), rules.getMaximumPenalty());
                            // Penalty has already been added to p.cumulativeScore by GameEngine (markDropped or handleDeclare)
                            int chipsLost = (int) Math.min(Math.max(0, p.getChipBalance()), penalty);
                            p.setChipBalance(p.getChipBalance() - chipsLost);
                            totalChipsWon += chipsLost;
                        }
                    }

                    PlayerState winner = state.getPlayer(winnerId).orElse(null);
                    if (winner != null) {
                        winner.setChipBalance(winner.getChipBalance() + totalChipsWon);
                    }

                    Map<String, Integer> currentChips = new HashMap<>();
                    Map<String, Integer> currentCumulatives = new HashMap<>();
                    for (PlayerState p : state.getPlayers()) {
                        currentChips.put(p.getPlayerId(), (int) p.getChipBalance());
                        currentCumulatives.put(p.getPlayerId(), p.getCumulativeScore());
                    }

                    PlayerGameView.DealScoreRecord dealRecord = new PlayerGameView.DealScoreRecord(
                            state.getDealNumber(),
                            finishedEvent.winnerPlayerId(),
                            new HashMap<>(finishedEvent.finalScores()),
                            currentChips
                    );
                    dealHistory.add(dealRecord);

                    log.info("[TableActor:{}] DEALS Deal {}/{} complete. Winner {} gained {} chips. Current chips: {}",
                            tableId, state.getDealNumber(), effectiveTotalDeals, winnerId, totalChipsWon, currentChips);

                    List<PlayerState> activeSurvivors = state.getPlayers().stream()
                            .filter(p -> p.getStatus() != PlayerStatus.ELIMINATED)
                            .toList();

                    if (activeSurvivors.size() <= 1) {
                        // All opponents forfeited/abandoned table; surviving player is immediate champion!
                        PlayerState tournamentWinner = activeSurvivors.isEmpty()
                                ? fallbackWinner()
                                : activeSurvivors.get(0);
                        this.tournamentWinnerId = tournamentWinner.getPlayerId();
                        log.info("[TableActor:{}] DEALS MATCH COMPLETED (by forfeit)! Tournament winner is {} ({})",
                                tableId, tournamentWinner.getPlayerId(), tournamentWinner.getDisplayName());

                        if (persistenceService != null) {
                            persistenceService.recordGameFinished(state, tableId);
                        }
                        settleMatchWallet(tournamentWinner.getPlayerId(), currentCumulatives, requestId);
                        if (sessionService != null) {
                            sessionService.clearAllHumanBindings(tableId);
                            log.info("[TableActor:{}] Cleared player session bindings after deals match finish", tableId);
                        }
                    } else if (state.getDealNumber() < effectiveTotalDeals) {
                        log.info("[TableActor:{}] Deal {} complete. Next deal (Deal {}) in 5s...",
                                tableId, state.getDealNumber(), state.getDealNumber() + 1);

                        this.nextDealCountdown = 5;
                        this.nextDealScheduledAt = Instant.now().plusSeconds(5);

                        Map<String, Object> dealsEventPayload = new HashMap<>();
                        dealsEventPayload.put("dealNumber", state.getDealNumber());
                        dealsEventPayload.put("totalDeals", effectiveTotalDeals);
                        dealsEventPayload.put("winnerPlayerId", finishedEvent.winnerPlayerId());
                        dealsEventPayload.put("dealScores", finishedEvent.finalScores());
                        dealsEventPayload.put("chipBalances", currentChips);
                        dealsEventPayload.put("nextDealCountdownSeconds", 5);

                        broadcastMessage(WsServerMessage.of("DEALS_DEAL_COMPLETED", requestId, tableId, state.getSequence(), dealsEventPayload));

                        if (nextDealFuture != null && !nextDealFuture.isDone()) {
                            nextDealFuture.cancel(false);
                        }
                        nextDealFuture = scheduler.schedule(() -> {
                            synchronized (TableActor.this) {
                                startNextDeal();
                            }
                        }, 5, TimeUnit.SECONDS);

                    } else {
                        // All scheduled deals completed! Check if top players are tied in chips AND cumulative penalty
                        List<PlayerState> ranked = new ArrayList<>(activeSurvivors);
                        ranked.sort((p1, p2) -> {
                            int chipCmp = Long.compare(p2.getChipBalance(), p1.getChipBalance()); // highest chips first
                            if (chipCmp != 0) return chipCmp;
                            return Integer.compare(p1.getCumulativeScore(), p2.getCumulativeScore()); // lowest penalty first
                        });

                        PlayerState p1 = ranked.get(0);
                        PlayerState p2 = ranked.size() > 1 ? ranked.get(1) : null;
                        boolean isTie = p2 != null
                                && p1.getChipBalance() == p2.getChipBalance()
                                && p1.getCumulativeScore() == p2.getCumulativeScore();

                        // Maximum 2 tie-breaker sudden-death deals to guarantee fair tournament resolution
                        if (isTie && effectiveTotalDeals < rules.getTotalDeals() + 2) {
                            effectiveTotalDeals++;
                            log.info("[TableActor:{}] DEALS MATCH TIE DETECTED between {} and {} ({} chips, {} penalty)! Triggering sudden-death tie-breaker deal {}/{}",
                                    tableId, p1.getPlayerId(), p2.getPlayerId(), p1.getChipBalance(), p1.getCumulativeScore(), state.getDealNumber() + 1, effectiveTotalDeals);

                            this.nextDealCountdown = 5;
                            this.nextDealScheduledAt = Instant.now().plusSeconds(5);

                            Map<String, Object> dealsEventPayload = new HashMap<>();
                            dealsEventPayload.put("dealNumber", state.getDealNumber());
                            dealsEventPayload.put("totalDeals", effectiveTotalDeals);
                            dealsEventPayload.put("winnerPlayerId", finishedEvent.winnerPlayerId());
                            dealsEventPayload.put("dealScores", finishedEvent.finalScores());
                            dealsEventPayload.put("chipBalances", currentChips);
                            dealsEventPayload.put("nextDealCountdownSeconds", 5);
                            dealsEventPayload.put("isTieBreaker", true);

                            broadcastMessage(WsServerMessage.of("DEALS_DEAL_COMPLETED", requestId, tableId, state.getSequence(), dealsEventPayload));

                            if (nextDealFuture != null && !nextDealFuture.isDone()) {
                                nextDealFuture.cancel(false);
                            }
                            nextDealFuture = scheduler.schedule(() -> {
                                synchronized (TableActor.this) {
                                    startNextDeal();
                                }
                            }, 5, TimeUnit.SECONDS);

                        } else {
                            PlayerState tournamentWinner = p1;
                            this.tournamentWinnerId = tournamentWinner.getPlayerId();
                            log.info("[TableActor:{}] DEALS MATCH COMPLETED! Tournament winner is {} ({}) with {} chips (penalty: {})",
                                    tableId, tournamentWinner.getPlayerId(), tournamentWinner.getDisplayName(), tournamentWinner.getChipBalance(), tournamentWinner.getCumulativeScore());

                            if (persistenceService != null) {
                                persistenceService.recordGameFinished(state, tableId);
                            }
                            settleMatchWallet(tournamentWinner.getPlayerId(), currentCumulatives, requestId);
                            if (sessionService != null) {
                                sessionService.clearAllHumanBindings(tableId);
                                log.info("[TableActor:{}] Cleared player session bindings after deals match finish", tableId);
                            }
                        }
                    }
                } else if (isPool) {
                    Map<String, Integer> currentCumulatives = new HashMap<>();
                    List<String> freshlyEliminated = new ArrayList<>();
                    List<String> freshlyEliminatedIds = new ArrayList<>();
                    for (PlayerState p : state.getPlayers()) {
                        currentCumulatives.put(p.getPlayerId(), p.getCumulativeScore());
                        if (p.getCumulativeScore() >= threshold && p.getStatus() != PlayerStatus.ELIMINATED) {
                            p.markEliminated();
                            freshlyEliminatedIds.add(p.getPlayerId());
                            freshlyEliminated.add(p.getDisplayName() != null ? p.getDisplayName() : p.getPlayerId());
                            log.info("[TableActor:{}] Player {} eliminated with score {}/{}",
                                    tableId, p.getPlayerId(), p.getCumulativeScore(), threshold);
                        }
                    }
                    this.lastEliminatedNames.clear();
                    this.lastEliminatedNames.addAll(freshlyEliminated);

                    PlayerGameView.DealScoreRecord dealRecord = new PlayerGameView.DealScoreRecord(
                            state.getDealNumber(),
                            finishedEvent.winnerPlayerId(),
                            new HashMap<>(finishedEvent.finalScores()),
                            currentCumulatives
                    );
                    dealHistory.add(dealRecord);

                    List<PlayerState> activeSurvivors = state.getPlayers().stream()
                            .filter(p -> p.getStatus() != PlayerStatus.ELIMINATED)
                            .toList();

                    if (activeSurvivors.size() > 1) {
                        // Match continues! For any players who rejoined mid-deal and sat out, update their starting score to current leader + 1
                        int newMaxActive = activeSurvivors.stream()
                                .filter(p -> p.getStatus() != PlayerStatus.READY)
                                .mapToInt(PlayerState::getCumulativeScore)
                                .max()
                                .orElse(0);

                        int rejoinCutoff = rules.getRejoinMaxActiveThreshold();

                        for (PlayerState p : state.getPlayers()) {
                            if (p.getStatus() == PlayerStatus.READY) {
                                if (rejoinCutoff > 0 && newMaxActive > rejoinCutoff) {
                                    // Leader score exceeded cutoff during missed deal; refund rejoin fee
                                    log.info("[TableActor:{}] Refunding rejoin fee to {} as leader score {} exceeds cutoff {}",
                                            tableId, p.getPlayerId(), newMaxActive, rejoinCutoff);
                                    if (walletService != null && stakeTier > 0 && claimLatestRejoinFee(p.getPlayerId())) {
                                        String refundKey = "REJOIN_REFUND_" + state.getGameId() + "_" + p.getPlayerId() + "_" + state.getDealNumber();
                                        try {
                                            walletService.credit(
                                                    p.getPlayerId(),
                                                    java.math.BigDecimal.valueOf(stakeTier),
                                                    "REJOIN_REFUND",
                                                    refundKey,
                                                    state.getGameId(),
                                                    "Rejoin fee refund (leader score exceeded cutoff)",
                                                    Map.of("tableId", tableId, "dealNumber", state.getDealNumber())
                                            );
                                            rejoinCounts.computeIfPresent(p.getPlayerId(), (k, v) -> v > 1 ? v - 1 : null);
                                        } catch (Exception ex) {
                                            log.warn("[TableActor:{}] Failed to refund rejoin fee: {}", tableId, ex.getMessage());
                                        }
                                    }
                                    p.markEliminated();
                                    currentCumulatives.put(p.getPlayerId(), p.getCumulativeScore());
                                } else {
                                    int adjustedScore = Math.max(p.getCumulativeScore(), newMaxActive + 1);
                                    p.setCumulativeScore(adjustedScore);
                                    currentCumulatives.put(p.getPlayerId(), adjustedScore);
                                }
                            }
                        }

                        // Re-evaluate activeSurvivors after any refunds
                        activeSurvivors = state.getPlayers().stream()
                                .filter(p -> p.getStatus() != PlayerStatus.ELIMINATED)
                                .toList();

                        log.info("[TableActor:{}] Deal {} complete. {} survivors remain. Next deal in {}s...",
                                tableId, state.getDealNumber(), activeSurvivors.size(), POOL_BREAK_SECONDS);

                        Map<String, Object> poolEventPayload = new HashMap<>();
                        poolEventPayload.put("dealNumber", state.getDealNumber());
                        poolEventPayload.put("winnerPlayerId", finishedEvent.winnerPlayerId());
                        poolEventPayload.put("dealScores", finishedEvent.finalScores());
                        poolEventPayload.put("cumulativeScores", currentCumulatives);
                        poolEventPayload.put("eliminatedPlayerIds", freshlyEliminatedIds);
                        poolEventPayload.put("eliminatedPlayerNames", freshlyEliminated);
                        poolEventPayload.put("survivorsRemaining", activeSurvivors.size());
                        poolEventPayload.put("nextDealCountdownSeconds", POOL_BREAK_SECONDS);

                        scheduleNextDealIn(POOL_BREAK_SECONDS);
                        broadcastMessage(WsServerMessage.of("POOL_DEAL_COMPLETED", requestId, tableId, state.getSequence(), poolEventPayload));

                    } else {
                        // Match complete! 1 or 0 survivors remain
                        PlayerState tournamentWinner = activeSurvivors.size() == 1
                                ? activeSurvivors.get(0)
                                : fallbackWinner();
                        this.tournamentWinnerId = tournamentWinner.getPlayerId();
                        log.info("[TableActor:{}] POOL MATCH COMPLETED! Tournament winner is {} ({}) with score {}",
                                tableId, tournamentWinner.getPlayerId(), tournamentWinner.getDisplayName(), tournamentWinner.getCumulativeScore());

                        // If any player had rejoined mid-deal and sat out, refund their fee as match has ended
                        for (PlayerState p : state.getPlayers()) {
                            if (p.getStatus() == PlayerStatus.READY && !p.getPlayerId().equals(tournamentWinner.getPlayerId())) {
                                int rCount = rejoinCounts.getOrDefault(p.getPlayerId(), 0);
                                if (rCount > 0 && walletService != null && stakeTier > 0 && claimLatestRejoinFee(p.getPlayerId())) {
                                    String refundKey = "REJOIN_REFUND_" + state.getGameId() + "_" + p.getPlayerId() + "_" + state.getDealNumber();
                                    try {
                                        walletService.credit(
                                                p.getPlayerId(),
                                                java.math.BigDecimal.valueOf(stakeTier),
                                                "REJOIN_REFUND",
                                                refundKey,
                                                state.getGameId(),
                                                "Rejoin fee refund (match finished before next deal)",
                                                Map.of("tableId", tableId, "dealNumber", state.getDealNumber())
                                        );
                                        rejoinCounts.computeIfPresent(p.getPlayerId(), (k, v) -> v > 1 ? v - 1 : null);
                                        log.info("[TableActor:{}] Refunded rejoin fee to {} because match completed", tableId, p.getPlayerId());
                                    } catch (Exception ex) {
                                        log.warn("[TableActor:{}] Failed to refund rejoin fee: {}", tableId, ex.getMessage());
                                    }
                                }
                                p.markEliminated();
                            }
                        }

                        if (persistenceService != null) {
                            persistenceService.recordGameFinished(state, tableId);
                        }
                        settleMatchWallet(tournamentWinner.getPlayerId(), currentCumulatives, requestId);
                        if (sessionService != null) {
                            sessionService.clearAllHumanBindings(tableId);
                            log.info("[TableActor:{}] Cleared player session bindings after pool match finish", tableId);
                        }
                    }
                } else {
                    // Non-pool game (e.g. Points Rummy)
                    if (persistenceService != null) {
                        persistenceService.recordGameFinished(state, tableId);
                    }
                    settleMatchWallet(finishedEvent.winnerPlayerId(), finishedEvent.finalScores(), requestId);
                    for (String leaver : voluntaryAbandoners) {
                        state.getPlayer(leaver).ifPresent(PlayerState::markEliminated);
                    }
                    if (sessionService != null) {
                        sessionService.clearAllHumanBindings(tableId);
                        log.info("[TableActor:{}] Cleared player session bindings after game finish", tableId);
                    }
                }
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
            Integer remainingCountdown = null;
            if (nextDealScheduledAt != null) {
                long secs = java.time.Duration.between(Instant.now(), nextDealScheduledAt).toSeconds();
                remainingCountdown = (int) Math.max(0, secs);
            }
            PlayerGameView view = PlayerGameView.from(
                    state,
                    playerId,
                    rules.getEliminationThreshold(),
                    dealHistory,
                    remainingCountdown,
                    tournamentWinnerId,
                    stakeTier,
                    new ArrayList<>(lastEliminatedNames),
                    effectiveTotalDeals,
                    stakeTier,
                    tableExtrasFor(playerId)
            );
            // Serialize under the table lock: the view references live state that the next command mutates.
            try {
                WsServerMessage msg = WsServerMessage.of("GAME_VIEW", requestId, tableId, state.getSequence(), view);
                SessionOutbox.send(session, objectMapper.writeValueAsString(msg));
            } catch (IOException e) {
                log.error("[TableActor:{}] Failed to serialize view for player {}: {}", tableId, playerId, e.getMessage());
            }
        }
    }

    private void broadcastMessage(WsServerMessage message) {
        TextMessage textMessage;
        try {
            textMessage = new TextMessage(objectMapper.writeValueAsString(message));
        } catch (IOException e) {
            log.error("[TableActor:{}] Failed to serialize broadcast {}: {}", tableId, message.type(), e.getMessage());
            return;
        }
        for (WebSocketSession session : sessions.values()) {
            if (session != null && session.isOpen()) {
                SessionOutbox.send(session, textMessage);
            }
        }
    }

    private void sendErrorToPlayer(String playerId, String errorCode, String errorMessage, String requestId) {
        WebSocketSession session = sessions.get(playerId);
        if (session != null && session.isOpen()) {
            try {
                WsErrorMessage err = new WsErrorMessage(errorCode, errorMessage, requestId);
                WsServerMessage msg = WsServerMessage.of("ERROR", requestId, tableId, err);
                SessionOutbox.send(session, objectMapper.writeValueAsString(msg));
            } catch (IOException e) {
                log.error("[TableActor:{}] Failed to serialize error for player {}: {}", tableId, playerId, e.getMessage());
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

        PlayerGameView botViewForThink = PlayerGameView.from(state, activePlayerId);
        long thinkMillis = bot.getThinkTimeMillis(botViewForThink, rules, state.getPlayers().size());
        log.info("[TableActor:{}] Bot {} thinking for {}ms before acting ({})", tableId, activePlayerId, thinkMillis, turn.getPhase());

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
                
                vThreadExecutor.submit(() -> {
                    GameCommand botAction = bot.decideAction(botView, rules);
                    processCommand(botAction, "BOT_ACTION_" + UUID.randomUUID());
                });
            }
        }, thinkMillis, TimeUnit.MILLISECONDS);
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

    private boolean holdingForPlayers() {
        if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
            return false;
        }
        if (dealNotBefore != null && Instant.now().isBefore(dealNotBefore)) {
            return true;
        }
        return !seatingClosed && expectedPlayers >= 2 && state.getPlayers().size() < expectedPlayers;
    }

    private void checkAndScheduleAutoBotFallback() {
        if (matchmakingOwnsFill || state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
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
                    registerBot(botId, botName, BotDifficulty.HARD);

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

                if (shouldAutoStart()) {
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

    public int getStakeTier() {
        return stakeTier;
    }

    public void setStakeTier(int stakeTier) {
        this.stakeTier = stakeTier;
    }

    public com.rummy.gameservice.wallet.GameSettlementResult getLastSettlement() {
        return lastSettlement;
    }

    public List<PlayerGameView.DealScoreRecord> getDealHistory() {
        return Collections.unmodifiableList(dealHistory);
    }

    public String getTournamentWinnerId() {
        return tournamentWinnerId;
    }

    /** Ends the match and queues its payout; players get {@code GAME_SETTLEMENT} once it is paid. */
    private void settleMatchWallet(String winnerId, Map<String, Integer> finalScores, String requestId) {
        settleMatchWallet(winnerId, finalScores, requestId, null);
    }

    private void settleMatchWallet(String winnerId, Map<String, Integer> finalScores, String requestId,
                                   Map<String, Integer> splitDrops) {
        matchFinishedAt = Instant.now();
        mutations++;
        if (walletService == null || stakeTier <= 0 || settlements == null) {
            return;
        }
        List<String> playerIds = state.getPlayers().stream().map(PlayerState::getPlayerId).toList();
        settlements.submit(new com.rummy.gameservice.wallet.SettlementService.Job(
                state.getGameId(), tableId, rules.getRulesetId(), stakeTier, winnerId,
                finalScores != null ? new HashMap<>(finalScores) : Map.of(), playerIds, new HashMap<>(rejoinCounts),
                splitDrops != null ? new LinkedHashMap<>(splitDrops) : null),
                new com.rummy.gameservice.wallet.SettlementService.Listener() {
                    @Override
                    public void settled(com.rummy.gameservice.wallet.GameSettlementResult result) {
                        onSettled(result, requestId);
                    }

                    @Override
                    public void cancelled() {
                        onSettlementCancelled(requestId);
                    }
                });
    }

    private synchronized void onSettled(com.rummy.gameservice.wallet.GameSettlementResult result, String requestId) {
        if (result == null) {
            return;
        }
        this.lastSettlement = result;
        if (!destroyed) {
            broadcastMessage(WsServerMessage.of("GAME_SETTLEMENT", requestId, tableId, state.getSequence(), result));
        }
    }

    private synchronized void onSettlementCancelled(String requestId) {
        if (!destroyed) {
            broadcastMessage(WsServerMessage.of("TABLE_CLOSED", requestId, tableId, state.getSequence(),
                    Map.of("reason", "server connection lost", "refunded", true,
                            "message", "This game was cancelled after a server connection problem. Your entry fee has been refunded.")));
        }
    }

    synchronized void startNextDeal() {
        if (destroyed || frozen) {
            return;
        }
        this.nextDealCountdown = null;
        this.nextDealScheduledAt = null;
        this.lastEliminatedNames.clear();
        this.splitOffer = null;
        this.splitAskedThisBreak = false;

        if (state.getStatus() != GameStatus.COMPLETED || tournamentWinnerId != null) {
            return;
        }

        long activeSurvivors = state.getPlayers().stream()
                .filter(p -> p.getStatus() != PlayerStatus.ELIMINATED)
                .count();
        if (activeSurvivors < 2) {
            log.info("[TableActor:{}] Fewer than 2 survivors remain ({}). Concluding tournament.", tableId, activeSurvivors);
            PlayerState tournamentWinner = state.getPlayers().stream()
                    .filter(p -> p.getStatus() != PlayerStatus.ELIMINATED)
                    .findFirst()
                    .orElseGet(this::fallbackWinner);
            this.tournamentWinnerId = tournamentWinner.getPlayerId();

            Map<String, Integer> currentCumulatives = new HashMap<>();
            for (PlayerState p : state.getPlayers()) {
                currentCumulatives.put(p.getPlayerId(), p.getCumulativeScore());
            }

            if (persistenceService != null) {
                persistenceService.recordGameFinished(state, tableId);
            }
            settleMatchWallet(tournamentWinner.getPlayerId(), currentCumulatives, "TOURNAMENT_WINNER_SURVIVOR");
            if (sessionService != null) {
                sessionService.clearAllHumanBindings(tableId);
                log.info("[TableActor:{}] Cleared player session bindings after tournament finish", tableId);
            }
            refreshHumanViews();
            return;
        }

        log.info("[TableActor:{}] Starting next deal (Deal {})...", tableId, state.getDealNumber() + 1);
        for (BotPlayerAgent botAgent : botAgents.values()) {
            botAgent.resetDealMemory();
        }
        state.incrementDealNumber();

        Deck freshDeck = Deck.createMultiPackDeck(
                rules.getDeckCount(),
                rules.getPrintedJokersPerDeck(),
                new java.security.SecureRandom()
        );
        state.prepareForNewDeal(freshDeck);

        for (PlayerState player : state.getPlayers()) {
            if (player.getStatus() != PlayerStatus.ELIMINATED) {
                player.setStatus(PlayerStatus.READY);
            }
        }

        PlayerState starter = state.getPlayers().stream()
                .filter(p -> p.getStatus() == PlayerStatus.READY)
                .findFirst()
                .orElse(null);

        if (starter != null) {
            processCommand(new StartGameCommand(
                    UUID.randomUUID().toString(),
                    state.getGameId(),
                    starter.getPlayerId(),
                    Instant.now()
            ), "AUTO_NEXT_DEAL");
        }
    }

    private String rejoinFeeKey(String playerId, int feeNumber) {
        return "REJOIN_FEE_" + state.getGameId() + "_" + playerId + "_" + feeNumber;
    }

    /** Claims the player's most recent rejoin fee for a refund; false if another path already refunded it. */
    private boolean claimLatestRejoinFee(String playerId) {
        int feeNumber = rejoinFeesCharged.getOrDefault(playerId, 0);
        return escrows == null || feeNumber == 0 || escrows.claimRefund(rejoinFeeKey(playerId, feeNumber));
    }

    public synchronized boolean handleRejoin(String playerId, String requestId) {
        if (frozen) {
            sendErrorToPlayer(playerId, "TABLE_RECOVERING", "Reconnecting you to your game…", requestId);
            return false;
        }
        if (!rules.isEliminationGame()) {
            sendErrorToPlayer(playerId, "REJOIN_NOT_ALLOWED", "Rejoin is only available in elimination games", requestId);
            return false;
        }

        PlayerState player = state.getPlayer(playerId).orElse(null);
        if (player == null || player.getStatus() != PlayerStatus.ELIMINATED) {
            sendErrorToPlayer(playerId, "NOT_ELIMINATED", "Player is not eliminated", requestId);
            return false;
        }

        if (voluntaryAbandoners.contains(playerId)) {
            sendErrorToPlayer(playerId, "VOLUNTARY_LEAVE_NO_REJOIN", "Players who voluntarily forfeited the table cannot rejoin", requestId);
            return false;
        }

        List<PlayerState> activeSurvivors = state.getPlayers().stream()
                .filter(p -> p.getStatus() != PlayerStatus.ELIMINATED)
                .toList();

        if (tournamentWinnerId != null || activeSurvivors.isEmpty()) {
            sendErrorToPlayer(playerId, "GAME_ALREADY_FINISHED", "Match has already concluded", requestId);
            return false;
        }

        if (!betweenDeals()) {
            sendErrorToPlayer(playerId, "REJOIN_CLOSED", "You can rejoin only in the break between deals", requestId);
            return false;
        }
        if (splitOffer != null) {
            sendErrorToPlayer(playerId, "SPLIT_PENDING", "The remaining players are deciding on a prize split", requestId);
            return false;
        }

        int maxActiveScore = activeSurvivors.stream()
                .mapToInt(PlayerState::getCumulativeScore)
                .max()
                .orElse(0);

        int rejoinCutoff = rules.getRejoinMaxActiveThreshold();
        if (maxActiveScore > rejoinCutoff) {
            sendErrorToPlayer(playerId, "SCORE_TOO_HIGH", "Cannot rejoin: Leader score is " + maxActiveScore + " (max allowed is " + rejoinCutoff + ")", requestId);
            return false;
        }

        if (walletService != null && stakeTier > 0) {
            int feeNumber = rejoinFeesCharged.getOrDefault(playerId, 0) + 1;
            String feeKey = rejoinFeeKey(playerId, feeNumber);
            if (escrows != null) {
                escrows.open(feeKey, playerId, stakeTier, tableId, state.getGameId());
            }
            try {
                walletService.debit(
                        playerId,
                        java.math.BigDecimal.valueOf(stakeTier),
                        "REJOIN_FEE",
                        feeKey,
                        state.getGameId(),
                        "Pool Rummy Rejoin Fee",
                        Map.of("tableId", tableId)
                );
            } catch (Exception e) {
                if (escrows != null) {
                    escrows.discard(feeKey);
                }
                log.warn("[TableActor:{}] Failed to deduct rejoin fee for {}: {}", tableId, playerId, e.getMessage());
                sendErrorToPlayer(playerId, "INSUFFICIENT_FUNDS", "Failed to debit rejoin fee: " + e.getMessage(), requestId);
                return false;
            }
            rejoinFeesCharged.put(playerId, feeNumber);
        }

        rejoinCounts.merge(playerId, 1, Integer::sum);
        mutations++;

        int newScore = maxActiveScore + 1;
        player.setStatus(PlayerStatus.READY);
        player.setScore(0);
        player.setCumulativeScore(newScore);

        log.info("[TableActor:{}] Player {} REJOINED table with starting score {}", tableId, playerId, newScore);

        Map<String, Object> payload = new HashMap<>();
        payload.put("playerId", playerId);
        payload.put("displayName", player.getDisplayName());
        payload.put("newScore", newScore);
        payload.put("rejoinFee", stakeTier);
        broadcastMessage(WsServerMessage.of("PLAYER_REJOINED", requestId, tableId, state.getSequence(), payload));

        for (String pid : sessions.keySet()) {
            if (state.getPlayer(pid).isPresent()) {
                sendPlayerView(pid, requestId);
            }
        }
        return true;
    }

    public synchronized void handleVoluntaryLeave(String playerId, String reqId) {
        if (frozen) {
            sendErrorToPlayer(playerId, "TABLE_RECOVERING", "Your game is moving to another server; leave again in a moment.", reqId);
            return;
        }
        voluntaryAbandoners.add(playerId);
        mutations++;
        var playerOpt = state.getPlayer(playerId);
        if (playerOpt.isPresent()) {
            PlayerState p = playerOpt.get();
            if (p.getStatus() == PlayerStatus.ACTIVE && state.getStatus() == GameStatus.IN_PROGRESS) {
                processCommand(new DropCommand(reqId, state.getGameId(), playerId, Instant.now(), true), "LEAVE_FORFEIT");
            }
            boolean pointsHandOpen = !rules.isEliminationGame() && !rules.isDealsGame()
                    && state.getStatus() == GameStatus.IN_PROGRESS && p.getStatus() == PlayerStatus.DROPPED;
            if (pointsHandOpen) {
                // Eliminated players score 0, so stay DROPPED until this hand is settled at the drop penalty.
                log.info("[TableActor:{}] Player {} left; eliminated after this hand settles", tableId, playerId);
            } else {
                p.markEliminated();
                log.info("[TableActor:{}] Player {} marked eliminated upon voluntary leave", tableId, playerId);
            }
        }
        if (splitOffer != null && splitOffer.dropsLeft().containsKey(playerId)) {
            declineSplit(playerId, "left");
        }
        concludeIfLastSurvivorBetweenDeals();
        refreshHumanViews();
    }

    /** In the break between deals, a match left with one player is over: that player wins now, not after the break. */
    private void concludeIfLastSurvivorBetweenDeals() {
        if (!(rules.isEliminationGame() || rules.isDealsGame()) || state.getStatus() != GameStatus.COMPLETED
                || tournamentWinnerId != null || matchFinishedAt != null || nextDealScheduledAt == null) {
            return;
        }
        long survivors = state.getPlayers().stream().filter(p -> p.getStatus() != PlayerStatus.ELIMINATED).count();
        if (survivors > 1) {
            return;
        }
        if (nextDealFuture != null) {
            nextDealFuture.cancel(false);
        }
        log.info("[TableActor:{}] Only {} player left between deals; ending the match now", tableId, survivors);
        startNextDeal();
    }

    private boolean betweenDeals() {
        return state.getStatus() == GameStatus.COMPLETED && nextDealScheduledAt != null
                && tournamentWinnerId == null && matchFinishedAt == null;
    }

    /** Winner when nobody is left standing: never a player who walked out while someone else stayed. */
    private PlayerState fallbackWinner() {
        List<PlayerState> stayed = state.getPlayers().stream()
                .filter(p -> !voluntaryAbandoners.contains(p.getPlayerId()))
                .toList();
        List<PlayerState> candidates = stayed.isEmpty() ? state.getPlayers() : stayed;
        Comparator<PlayerState> best = rules.isDealsGame()
                ? Comparator.<PlayerState>comparingLong(PlayerState::getChipBalance).reversed()
                        .thenComparingInt(PlayerState::getCumulativeScore)
                : Comparator.comparingInt(PlayerState::getCumulativeScore);
        return candidates.stream().min(best).orElse(state.getPlayers().get(0));
    }

    private void scheduleNextDealIn(int seconds) {
        if (nextDealFuture != null && !nextDealFuture.isDone()) {
            nextDealFuture.cancel(false);
        }
        this.nextDealCountdown = seconds;
        this.nextDealScheduledAt = Instant.now().plusSeconds(seconds);
        nextDealFuture = scheduler.schedule(() -> {
            synchronized (TableActor.this) {
                startNextDeal();
            }
        }, seconds, TimeUnit.SECONDS);
    }

    // ---- Pool prize split ----

    private java.math.BigDecimal poolNetPrize() {
        java.math.BigDecimal entry = java.math.BigDecimal.valueOf(stakeTier);
        int seats = 0;
        for (PlayerState p : state.getPlayers()) {
            seats += 1 + rejoinCounts.getOrDefault(p.getPlayerId(), 0);
        }
        return com.rummy.gameservice.wallet.PoolSplit.netPrize(entry.multiply(java.math.BigDecimal.valueOf(seats)));
    }

    private boolean isBot(String playerId) {
        return botAgents.containsKey(playerId) || playerId.startsWith("BOT_");
    }

    /** Why {@code playerId} cannot ask for a split right now; null when they can. */
    private String splitUnavailableReason(String playerId) {
        if (!rules.isEliminationGame()) {
            return "Split is only available in pool games";
        }
        if (stakeTier <= 0) {
            return "Split is only available on cash tables";
        }
        if (!betweenDeals()) {
            return "Split can be asked only in the break between deals";
        }
        if (splitOffer != null) {
            return "A split is already being decided";
        }
        if (splitAskedThisBreak) {
            return "Split was already asked in this break";
        }
        List<PlayerState> survivors = state.getPlayers().stream()
                .filter(p -> p.getStatus() != PlayerStatus.ELIMINATED)
                .toList();
        if (survivors.stream().noneMatch(p -> p.getPlayerId().equals(playerId)) || voluntaryAbandoners.contains(playerId)) {
            return "Only players still in the game can ask for a split";
        }
        if (!com.rummy.gameservice.wallet.PoolSplit.tableSizeAllows(state.getPlayers().size(), survivors.size())) {
            return state.getPlayers().size() == 2
                    ? "Split is not available on a 2-player table"
                    : "Split needs " + (state.getPlayers().size() == 3 ? "2" : "2 or 3") + " players left";
        }
        if (survivors.stream().anyMatch(p -> isBot(p.getPlayerId()))) {
            return "Split is not available while a computer player is still in the game";
        }
        return null;
    }

    private LinkedHashMap<String, Integer> currentDropsLeft() {
        LinkedHashMap<String, Integer> drops = new LinkedHashMap<>();
        for (PlayerState p : state.getPlayers()) {
            if (p.getStatus() != PlayerStatus.ELIMINATED) {
                drops.put(p.getPlayerId(), com.rummy.gameservice.wallet.PoolSplit.dropsRemaining(
                        p.getCumulativeScore(), rules.getEliminationThreshold(), rules.getFirstDropPenalty()));
            }
        }
        return drops;
    }

    private Optional<Map<String, java.math.BigDecimal>> currentSplitPayouts(Map<String, Integer> drops) {
        return com.rummy.gameservice.wallet.PoolSplit.payouts(drops, java.math.BigDecimal.valueOf(stakeTier), poolNetPrize());
    }

    public synchronized boolean handleSplitRequest(String playerId, String requestId) {
        if (frozen) {
            sendErrorToPlayer(playerId, "TABLE_RECOVERING", "Reconnecting you to your game…", requestId);
            return false;
        }
        String reason = splitUnavailableReason(playerId);
        if (reason != null) {
            sendErrorToPlayer(playerId, "SPLIT_NOT_ALLOWED", reason, requestId);
            return false;
        }
        LinkedHashMap<String, Integer> drops = currentDropsLeft();
        Map<String, java.math.BigDecimal> payouts = currentSplitPayouts(drops).orElse(null);
        if (payouts == null) {
            sendErrorToPlayer(playerId, "SPLIT_NOT_ALLOWED", "Not enough prize left to split at these scores", requestId);
            return false;
        }
        splitAskedThisBreak = true;
        Set<String> accepted = new LinkedHashSet<>();
        accepted.add(playerId);
        Instant deadline = Instant.now().plusSeconds(SPLIT_ANSWER_SECONDS);
        SplitOffer offer = new SplitOffer(playerId, drops, payouts, accepted, deadline);
        splitOffer = offer;
        mutations++;

        if (nextDealFuture != null && !nextDealFuture.isDone()) {
            nextDealFuture.cancel(false);
        }
        nextDealCountdown = SPLIT_ANSWER_SECONDS;
        nextDealScheduledAt = deadline;
        nextDealFuture = scheduler.schedule(() -> {
            synchronized (TableActor.this) {
                if (splitOffer == offer && !destroyed && !frozen) {
                    declineSplit(null, "timeout");
                }
            }
        }, SPLIT_ANSWER_SECONDS, TimeUnit.SECONDS);

        log.info("[TableActor:{}] {} asked to split the prize: {}", tableId, playerId, payouts);
        Map<String, Object> payload = new HashMap<>();
        payload.put("requestedBy", playerId);
        payload.put("payouts", payouts);
        payload.put("dropsLeft", drops);
        payload.put("answerSeconds", SPLIT_ANSWER_SECONDS);
        broadcastMessage(WsServerMessage.of("SPLIT_REQUESTED", requestId, tableId, state.getSequence(), payload));
        refreshHumanViews();
        return true;
    }

    public synchronized boolean handleSplitResponse(String playerId, boolean accept, String requestId) {
        if (frozen) {
            sendErrorToPlayer(playerId, "TABLE_RECOVERING", "Reconnecting you to your game…", requestId);
            return false;
        }
        SplitOffer offer = splitOffer;
        if (offer == null || !offer.dropsLeft().containsKey(playerId)) {
            sendErrorToPlayer(playerId, "SPLIT_NOT_PENDING", "There is no split waiting for your answer", requestId);
            return false;
        }
        if (!accept) {
            declineSplit(playerId, "declined");
            return true;
        }
        offer.accepted().add(playerId);
        mutations++;
        if (offer.accepted().containsAll(offer.dropsLeft().keySet())) {
            completeSplit(offer, requestId);
        } else {
            broadcastMessage(WsServerMessage.of("SPLIT_UPDATED", requestId, tableId, state.getSequence(),
                    Map.of("acceptedBy", new ArrayList<>(offer.accepted()))));
            refreshHumanViews();
        }
        return true;
    }

    private void declineSplit(String byPlayer, String reason) {
        splitOffer = null;
        mutations++;
        scheduleNextDealIn(AFTER_SPLIT_DECLINED_SECONDS);
        log.info("[TableActor:{}] Prize split off ({}{})", tableId, reason, byPlayer != null ? " by " + byPlayer : "");
        Map<String, Object> payload = new HashMap<>();
        payload.put("declinedBy", byPlayer);
        payload.put("reason", reason);
        payload.put("nextDealCountdownSeconds", AFTER_SPLIT_DECLINED_SECONDS);
        broadcastMessage(WsServerMessage.of("SPLIT_DECLINED", null, tableId, state.getSequence(), payload));
        refreshHumanViews();
    }

    private void completeSplit(SplitOffer offer, String requestId) {
        splitOffer = null;
        if (nextDealFuture != null) {
            nextDealFuture.cancel(false);
        }
        nextDealCountdown = null;
        nextDealScheduledAt = null;

        String winnerId = offer.payouts().entrySet().stream()
                .max(Map.Entry.<String, java.math.BigDecimal>comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(offer.requestedBy());
        this.tournamentWinnerId = winnerId;
        Map<String, Integer> cumulatives = new HashMap<>();
        for (PlayerState p : state.getPlayers()) {
            cumulatives.put(p.getPlayerId(), p.getCumulativeScore());
        }
        log.info("[TableActor:{}] Prize split agreed: {}", tableId, offer.payouts());

        if (persistenceService != null) {
            persistenceService.recordGameFinished(state, tableId);
        }
        settleMatchWallet(winnerId, cumulatives, requestId, offer.dropsLeft());
        broadcastMessage(WsServerMessage.of("SPLIT_ACCEPTED", requestId, tableId, state.getSequence(),
                Map.of("payouts", offer.payouts())));
        if (sessionService != null) {
            sessionService.clearAllHumanBindings(tableId);
        }
        refreshHumanViews();
    }

    private PlayerGameView.TableExtras tableExtrasFor(String viewerId) {
        if (!rules.isEliminationGame() && !rules.isDealsGame()) {
            return PlayerGameView.TableExtras.NONE;
        }
        java.math.BigDecimal prize = stakeTier > 0 ? poolNetPrize() : null;
        if (!rules.isEliminationGame()) {
            return new PlayerGameView.TableExtras(0, false, prize, null);
        }
        boolean rejoinOpen = betweenDeals() && splitOffer == null && !voluntaryAbandoners.contains(viewerId);

        PlayerGameView.SplitView split = null;
        SplitOffer offer = splitOffer;
        if (offer != null) {
            Integer secondsLeft = (int) Math.max(0, java.time.Duration.between(Instant.now(), offer.deadline()).toSeconds());
            split = new PlayerGameView.SplitView(false, offer.requestedBy(), offer.payouts(),
                    new ArrayList<>(offer.accepted()),
                    offer.dropsLeft().containsKey(viewerId) && !offer.accepted().contains(viewerId),
                    secondsLeft);
        } else if (splitUnavailableReason(viewerId) == null) {
            Map<String, java.math.BigDecimal> preview = currentSplitPayouts(currentDropsLeft()).orElse(null);
            if (preview != null) {
                split = new PlayerGameView.SplitView(true, null, preview, List.of(), false, null);
            }
        }
        return new PlayerGameView.TableExtras(rules.getRejoinMaxActiveThreshold(), rejoinOpen, prize, split);
    }

    public int getEffectiveTotalDeals() {
        return effectiveTotalDeals;
    }

    public long mutationCount() {
        return mutations;
    }

    /**
     * A copy of the match that another process can resume, or empty when there is nothing worth
     * restoring: not dealt yet (lobby stakes are refunded instead), finished, or closed.
     */
    public synchronized Optional<TableSnapshot> captureSnapshot() {
        if (destroyed || matchFinishedAt != null) {
            return Optional.empty();
        }
        boolean betweenDeals = state.getStatus() == GameStatus.COMPLETED
                && nextDealFuture != null && !nextDealFuture.isDone();
        if (state.getStatus() != GameStatus.IN_PROGRESS && !betweenDeals) {
            return Optional.empty();
        }
        List<TableSnapshot.Bot> bots = botAgents.values().stream()
                .map(b -> new TableSnapshot.Bot(b.getPlayerId(),
                        state.getPlayer(b.getPlayerId()).map(PlayerState::getDisplayName).orElse(null),
                        b.getDifficulty().name()))
                .toList();
        return Optional.of(new TableSnapshot(
                TableSnapshot.CURRENT_VERSION,
                tableId,
                GameStateSnapshots.capture(state),
                stakeTier,
                expectedPlayers,
                matchmakingOwnsFill,
                bots,
                new HashMap<>(rejoinCounts),
                new HashMap<>(rejoinFeesCharged),
                new ArrayList<>(voluntaryAbandoners),
                new ArrayList<>(dealHistory),
                new ArrayList<>(lastEliminatedNames),
                betweenDeals && nextDealScheduledAt != null ? nextDealScheduledAt.toString() : null,
                tournamentWinnerId,
                effectiveTotalDeals,
                new TableSnapshot.WindDown(botsOnlyWindDown, turnsSinceLastBotDrop, turnsUntilNextBotDrop, lastWindDownTurnCounted)));
    }

    /** Loads match bookkeeping from a snapshot into a freshly built actor (before {@link #resumeAfterRestore}). */
    synchronized void applySnapshot(TableSnapshot snap) {
        this.stakeTier = snap.stakeTier();
        this.expectedPlayers = snap.expectedPlayers();
        this.matchmakingOwnsFill = snap.matchmakingOwnsFill();
        this.seatingClosed = true;
        this.dealNotBefore = null;
        for (TableSnapshot.Bot bot : snap.bots()) {
            registerBot(bot.playerId(), bot.displayName(), BotDifficulty.valueOf(bot.difficulty()));
        }
        rejoinCounts.putAll(snap.rejoinCounts());
        rejoinFeesCharged.putAll(snap.rejoinFeesCharged());
        voluntaryAbandoners.addAll(snap.voluntaryAbandoners());
        dealHistory.addAll(snap.dealHistory());
        lastEliminatedNames.addAll(snap.lastEliminatedNames());
        this.nextDealScheduledAt = snap.nextDealScheduledAt() != null ? Instant.parse(snap.nextDealScheduledAt()) : null;
        this.tournamentWinnerId = snap.tournamentWinnerId();
        this.effectiveTotalDeals = snap.effectiveTotalDeals();
        TableSnapshot.WindDown windDown = snap.windDown();
        if (windDown != null) {
            this.botsOnlyWindDown = windDown.active();
            this.turnsSinceLastBotDrop = windDown.turnsSinceLastDrop();
            this.turnsUntilNextBotDrop = windDown.turnsUntilNextDrop();
            this.lastWindDownTurnCounted = windDown.lastTurnCounted();
        }
    }

    /**
     * Restarts the clocks of a restored match. The player on turn gets a full turn again: the outage
     * used up their old deadline and they should not be penalised for it. A pending next deal waits at
     * least {@code minNextDealDelay} so players have time to reconnect.
     */
    synchronized void resumeAfterRestore(java.time.Duration minNextDealDelay) {
        Instant now = Instant.now();
        lastActivityAt = now;
        TurnState turn = state.getTurnState();
        if (state.getStatus() == GameStatus.IN_PROGRESS && turn != null) {
            java.time.Duration turnLength = java.time.Duration.between(turn.getTurnStartedAt(), turn.getTurnDeadline());
            state.setTurnState(new TurnState(turn.getTurnNumber(), turn.getCurrentPlayerId(), turn.getPhase(),
                    now, now.plus(turnLength), turn.getDrawnCardInstanceId(), turn.isDrawnFromDiscard()));
            resetTurnTimer();
            triggerBotTurnIfApplicable();
        } else if (state.getStatus() == GameStatus.COMPLETED && nextDealScheduledAt != null) {
            Instant dealAt = nextDealScheduledAt.isAfter(now.plus(minNextDealDelay)) ? nextDealScheduledAt : now.plus(minNextDealDelay);
            this.nextDealScheduledAt = dealAt;
            this.nextDealCountdown = (int) Math.max(0, java.time.Duration.between(now, dealAt).toSeconds());
            nextDealFuture = scheduler.schedule(() -> {
                synchronized (TableActor.this) {
                    startNextDeal();
                }
            }, java.time.Duration.between(now, dealAt).toMillis(), TimeUnit.MILLISECONDS);
        }
        mutations++;
        log.info("[TableActor:{}] Restored match {} (deal {}, {}) after a server crash",
                tableId, state.getGameId(), state.getDealNumber(), state.getStatus());
    }

    /**
     * Stops the match so another node can take it over (deploy, scale-down): returns its snapshot and
     * stops every clock; after this no move is accepted here. Empty when there is nothing to hand over.
     */
    public synchronized Optional<TableSnapshot> freezeForHandoff() {
        Optional<TableSnapshot> snapshot = captureSnapshot();
        if (snapshot.isPresent()) {
            frozen = true;
            for (ScheduledFuture<?> f : new ScheduledFuture<?>[]{turnTimeoutFuture, botTurnFuture, nextDealFuture}) {
                if (f != null) {
                    f.cancel(false);
                }
            }
        }
        return snapshot;
    }

    /** The handoff could not be saved: cancel the frozen match with refunds instead. */
    public synchronized void abortFrozenMatch(String reason) {
        frozen = false;
        abortMatch(reason);
    }

    /** Disconnects the players of a handed-off match so they reconnect to the node that resumes it. */
    public synchronized void closeSessionsForHandoff() {
        for (WebSocketSession session : sessions.values()) {
            try {
                if (session.isOpen()) {
                    session.close(org.springframework.web.socket.CloseStatus.SERVICE_RESTARTED);
                }
            } catch (IOException e) {
                log.debug("[TableActor:{}] Closing session after handoff failed: {}", tableId, e.getMessage());
            }
        }
    }

    public synchronized void destroy() {
        destroyed = true;
        if (turnTimeoutFuture != null) {
            turnTimeoutFuture.cancel(true);
        }
        if (botTurnFuture != null) {
            botTurnFuture.cancel(true);
        }
        if (nextDealFuture != null) {
            nextDealFuture.cancel(true);
        }
        cancelAutoBotFallback();
        botsOnlyWindDown = false;
        sessions.clear();
        botAgents.clear();
    }
}
