package com.rummy.gameservice.matchmaking;

import com.rummy.engine.bot.BotDifficulty;
import com.rummy.engine.bot.IndianBotNames;
import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.command.ReadyCommand;
import com.rummy.engine.rules.PointsRummyRules;
import com.rummy.engine.rules.RummyRules;
import com.rummy.engine.rules.RulesetRegistry;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.routing.PlayerPresenceService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.wallet.WalletService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Phase 18: Stake & variant matchmaking with AI fallback.
 * Uses {@link MatchmakingStore} — Redis when {@code rummy.redis.enabled=true}, else in-memory.
 */
@Service
public class MatchmakingService {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingService.class);

    private final TableManager tableManager;
    private final TableRoutingRegistry routingRegistry;
    private final PlayerPresenceService presenceService;
    private final WalletService walletService;
    private final MatchmakingStore store;

    private final long aiFallbackTimeoutMs;
    private final long maxQueueTimeoutMs;
    private final long humanOnlyMs;
    private final long botIntervalMs;
    private final long dealDelayMs;

    private final ScheduledExecutorService matchingScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object lobbyLock = new Object();
    private final Map<String, OpenTable> openByQueue = new HashMap<>();
    private final Map<String, OpenTable> openByTable = new HashMap<>();

    @Autowired
    public MatchmakingService(
            TableManager tableManager,
            TableRoutingRegistry routingRegistry,
            PlayerPresenceService presenceService,
            MatchmakingStore store,
            @Autowired(required = false) WalletService walletService,
            @Value("${matchmaking.ai-fallback-timeout-ms:15000}") long aiFallbackTimeoutMs,
            @Value("${matchmaking.max-queue-timeout-ms:45000}") long maxQueueTimeoutMs,
            @Value("${matchmaking.human-only-ms:5000}") long humanOnlyMs,
            @Value("${matchmaking.bot-interval-ms:2000}") long botIntervalMs,
            @Value("${matchmaking.deal-delay-ms:15000}") long dealDelayMs) {
        this.tableManager = Objects.requireNonNull(tableManager);
        this.routingRegistry = Objects.requireNonNull(routingRegistry);
        this.presenceService = Objects.requireNonNull(presenceService);
        this.store = Objects.requireNonNull(store);
        this.walletService = walletService;
        this.aiFallbackTimeoutMs = aiFallbackTimeoutMs;
        this.maxQueueTimeoutMs = maxQueueTimeoutMs;
        this.humanOnlyMs = humanOnlyMs;
        this.botIntervalMs = botIntervalMs;
        this.dealDelayMs = dealDelayMs;
    }

    /** Test helper constructor with production lobby timings. */
    public MatchmakingService(
            TableManager tableManager,
            TableRoutingRegistry routingRegistry,
            PlayerPresenceService presenceService,
            long aiFallbackTimeoutMs,
            long maxQueueTimeoutMs) {
        this(tableManager, routingRegistry, presenceService, aiFallbackTimeoutMs, maxQueueTimeoutMs,
                5_000L, 2_000L, 15_000L);
    }

    /** Test helper constructor. */
    public MatchmakingService(
            TableManager tableManager,
            TableRoutingRegistry routingRegistry,
            PlayerPresenceService presenceService,
            long aiFallbackTimeoutMs,
            long maxQueueTimeoutMs,
            long humanOnlyMs,
            long botIntervalMs,
            long dealDelayMs) {
        this(tableManager, routingRegistry, presenceService, new InMemoryMatchmakingStore(),
                null, aiFallbackTimeoutMs, maxQueueTimeoutMs, humanOnlyMs, botIntervalMs, dealDelayMs);
    }

    @PostConstruct
    public void startMatchingLoop() {
        matchingScheduler.scheduleWithFixedDelay(this::processQueues, 250, 250, TimeUnit.MILLISECONDS);
        log.info("[Matchmaking] Loop started (store={}, aiFallback={}ms)",
                store.getClass().getSimpleName(), aiFallbackTimeoutMs);
    }

    public MatchmakingTicket enqueue(MatchmakingRequest request) {
        store.cancelActiveTicketsForPlayer(request.getPlayerId());

        String ticketId = "TKT_" + UUID.randomUUID().toString().substring(0, 8);
        MatchmakingTicket ticket = new MatchmakingTicket(
                ticketId,
                request.getPlayerId(),
                request.getPlayerName(),
                request.getRulesetId(),
                request.getStakeTier(),
                request.getMaxPlayers(),
                request.isAllowAiFallback()
        );

        presenceService.updatePresence(request.getPlayerId());

        if (request.isAllowAiFallback()) {
            openOrJoinLobby(ticket, request);
            return ticket;
        }

        store.saveTicket(ticket);
        store.enqueue(ticket.getQueueKey(), ticket.getTicketId());

        log.info("[Matchmaking] Enqueued player {} for queueKey={} ticket={}",
                request.getPlayerId(), ticket.getQueueKey(), ticketId);
        return ticket;
    }

    public Optional<MatchmakingTicket> getTicket(String ticketId) {
        return store.findTicket(ticketId);
    }

    public boolean cancelTicket(String ticketId) {
        Optional<MatchmakingTicket> opt = store.findTicket(ticketId);
        if (opt.isEmpty()) return false;
        MatchmakingTicket ticket = opt.get();
        if (ticket.getStatus() == MatchmakingTicket.Status.QUEUED) {
            ticket.setStatus(MatchmakingTicket.Status.CANCELLED);
            store.saveTicket(ticket);
            log.info("[Matchmaking] Cancelled ticket {}", ticketId);
            return true;
        }
        if (ticket.getStatus() == MatchmakingTicket.Status.MATCHED) {
            return releaseLobbyHuman(ticket);
        }
        return false;
    }

    /**
     * A waiting human disconnected or left. Frees their reserved seat.
     * The table is destroyed when nobody real remains.
     */
    public void onWaitingHumanLeft(String playerId, String tableId) {
        if (playerId == null || tableId == null) return;
        synchronized (lobbyLock) {
            OpenTable open = openByTable.get(tableId);
            if (open == null || open.seatingClosed) return;
            open.humanIds.remove(playerId);
            routingRegistry.unregisterPlayer(playerId);
            if (open.humanIds.isEmpty()) {
                discardOpenTable(open, true);
            }
            log.info("[Matchmaking] Waiting human {} left lobby {}", playerId, tableId);
        }
    }

    /**
     * Last human left before the deal. Cancels bot timers and drops the waiting table.
     */
    public void abandonTable(String tableId) {
        if (tableId == null) return;
        synchronized (lobbyLock) {
            OpenTable open = openByTable.get(tableId);
            if (open == null || open.seatingClosed) return;
            discardOpenTable(open, true);
        }
    }

    void processQueues() {
        try {
            processQueuesUnsafe();
        } catch (Exception e) {
            log.error("[Matchmaking] processQueues failed — will retry next tick", e);
        }
    }

    private void processQueuesUnsafe() {
        for (String queueKey : store.listQueueKeys()) {
            Optional<MatchmakingStore.QueueSnapshot> claimed = store.claimQueue(queueKey, 2000);
            if (claimed.isEmpty()) {
                continue;
            }

            List<MatchmakingTicket> validWaiting = new ArrayList<>();
            for (MatchmakingTicket candidate : claimed.get().tickets()) {
                if (candidate.getStatus() != MatchmakingTicket.Status.QUEUED) {
                    continue;
                }
                if (presenceService != null && !presenceService.isPlayerOnline(candidate.getPlayerId())) {
                    candidate.setStatus(MatchmakingTicket.Status.CANCELLED);
                    store.saveTicket(candidate);
                    log.info("[Matchmaking] Skipped offline player ticket {}", candidate.getTicketId());
                    continue;
                }
                validWaiting.add(candidate);
            }

            if (validWaiting.isEmpty()) {
                store.releaseQueue(queueKey, List.of());
                continue;
            }

            int targetSize = validWaiting.get(0).getMaxPlayers();
            List<MatchmakingTicket> matchedGroup = new ArrayList<>();
            Set<String> groupedPlayerIds = new HashSet<>();
            List<String> leftovers = new ArrayList<>();

            for (MatchmakingTicket ticket : validWaiting) {
                if (groupedPlayerIds.contains(ticket.getPlayerId())) {
                    ticket.setStatus(MatchmakingTicket.Status.CANCELLED);
                    store.saveTicket(ticket);
                    continue;
                }
                matchedGroup.add(ticket);
                groupedPlayerIds.add(ticket.getPlayerId());
                if (matchedGroup.size() == targetSize) {
                    createAndAssignTable(new ArrayList<>(matchedGroup), false);
                    matchedGroup.clear();
                    groupedPlayerIds.clear();
                }
            }

            if (!matchedGroup.isEmpty()) {
                boolean anyTimedOut = matchedGroup.stream().anyMatch(t ->
                        t.isAllowAiFallback()
                                && Duration.between(t.getCreatedAt(), Instant.now()).toMillis() >= aiFallbackTimeoutMs
                );

                if (anyTimedOut) {
                    createAndAssignTable(new ArrayList<>(matchedGroup), true);
                } else {
                    for (MatchmakingTicket leftover : matchedGroup) {
                        long elapsed = Duration.between(leftover.getCreatedAt(), Instant.now()).toMillis();
                        if (elapsed >= maxQueueTimeoutMs) {
                            leftover.setStatus(MatchmakingTicket.Status.EXPIRED);
                            store.saveTicket(leftover);
                            log.info("[Matchmaking] Ticket {} expired after {}ms", leftover.getTicketId(), elapsed);
                        } else {
                            leftovers.add(leftover.getTicketId());
                        }
                    }
                }
            }

            store.releaseQueue(queueKey, leftovers);
        }
    }

    private void createAndAssignTable(List<MatchmakingTicket> humanTickets, boolean fillWithAi) {
        String tableId = "TBL_MM_" + UUID.randomUUID().toString().substring(0, 8);
        MatchmakingTicket first = humanTickets.get(0);

        RummyRules rules = RulesetRegistry.getRuleset(first.getRulesetId()).orElseGet(PointsRummyRules::new);
        TableActor actor = tableManager.getOrCreateTable(tableId, rules);
        routingRegistry.registerTableOwnership(tableId);
        actor.setExpectedPlayers(first.getMaxPlayers());

        for (MatchmakingTicket ticket : humanTickets) {
            routingRegistry.registerPlayerTable(ticket.getPlayerId(), tableId);

            if (walletService != null && ticket.getStakeTier() > 0) {
                try {
                    String txKey = "STAKE_" + tableId + "_" + ticket.getPlayerId();
                    walletService.debit(
                            ticket.getPlayerId(),
                            BigDecimal.valueOf(ticket.getStakeTier()),
                            "GAME_ENTRY_STAKE",
                            txKey,
                            tableId,
                            "Table entry stake for " + ticket.getRulesetId(),
                            Map.of("tableId", tableId, "stakeTier", ticket.getStakeTier())
                    );
                } catch (Exception e) {
                    log.warn("[Matchmaking] Could not debit stake for {}: {}", ticket.getPlayerId(), e.getMessage());
                }
            }
        }

        if (fillWithAi) {
            int neededBots = Math.max(0, first.getMaxPlayers() - humanTickets.size());

            if (neededBots > 0) {
                Set<String> usedNames = new HashSet<>();
                for (MatchmakingTicket humanTicket : humanTickets) {
                    if (humanTicket.getPlayerName() != null) {
                        usedNames.add(humanTicket.getPlayerName());
                    }
                }
                for (int i = 1; i <= neededBots; i++) {
                    String botId = "BOT_" + UUID.randomUUID().toString().substring(0, 4);
                    String botName = IndianBotNames.nextUnique(usedNames);
                    int seatIndex = humanTickets.size() + (i - 1);

                    actor.registerBot(botId, botName, BotDifficulty.MEDIUM);
                    actor.processCommand(new JoinCommand(
                            UUID.randomUUID().toString(),
                            actor.getState().getGameId(),
                            botId,
                            botName,
                            seatIndex,
                            true,
                            Instant.now()
                    ), "MM_BOT_JOIN");

                    actor.processCommand(new ReadyCommand(
                            UUID.randomUUID().toString(),
                            actor.getState().getGameId(),
                            botId,
                            Instant.now()
                    ), "MM_BOT_READY");

                    log.info("[Matchmaking] Added AI bot {} ({}) to table {}", botId, botName, tableId);
                }
            }
        }

        // Publish MATCHED only after bots are seated, so clients join a full table.
        for (MatchmakingTicket ticket : humanTickets) {
            ticket.setMatchedTableId(tableId);
            ticket.setMatchedServerId(routingRegistry.getServerInstanceId());
            ticket.setStatus(MatchmakingTicket.Status.MATCHED);
            store.saveTicket(ticket);
            log.info("[Matchmaking] Matched human player {} into table {} on {}",
                    ticket.getPlayerId(), tableId, routingRegistry.getServerInstanceId());
        }
    }

    public int getQueuedPlayerCount() {
        return store.countQueued();
    }

    private void openOrJoinLobby(MatchmakingTicket ticket, MatchmakingRequest request) {
        int maxPlayers = Math.max(2, Math.min(6, request.getMaxPlayers()));
        synchronized (lobbyLock) {
            OpenTable open = openByQueue.get(ticket.getQueueKey());
            if (open == null || !canAccept(open)) {
                open = createLobbyTable(ticket, maxPlayers);
            } else if (!hasRoomWithoutEvict(open)) {
                tableManager.getTable(open.tableId).ifPresent(TableActor::evictLatestBot);
            }
            open.humanIds.add(ticket.getPlayerId());
            assignHuman(ticket, open.tableId);
        }
    }

    private boolean canAccept(OpenTable open) {
        if (open.seatingClosed) return false;
        if (Duration.between(open.openedAt, Instant.now()).toMillis() >= dealDelayMs) return false;
        Optional<TableActor> actorOpt = tableManager.getTable(open.tableId);
        if (actorOpt.isEmpty() || !actorOpt.get().isSeatingOpen()) return false;
        return hasRoomWithoutEvict(open) || actorOpt.get().countBots() > 0;
    }

    private boolean hasRoomWithoutEvict(OpenTable open) {
        TableActor actor = tableManager.getTable(open.tableId).orElse(null);
        if (actor == null) return false;
        int pending = Math.max(0, open.humanIds.size() - actor.countHumans());
        return actor.getState().getPlayers().size() + pending < open.maxPlayers;
    }

    private OpenTable createLobbyTable(MatchmakingTicket ticket, int maxPlayers) {
        String tableId = "TBL_MM_" + UUID.randomUUID().toString().substring(0, 8);
        RummyRules rules = RulesetRegistry.getRuleset(ticket.getRulesetId()).orElseGet(PointsRummyRules::new);
        TableActor actor = tableManager.getOrCreateTable(tableId, rules);
        routingRegistry.registerTableOwnership(tableId);
        actor.setExpectedPlayers(maxPlayers);
        Instant openedAt = Instant.now();
        actor.armDealHold(openedAt.plusMillis(dealDelayMs));

        OpenTable open = new OpenTable(tableId, ticket.getQueueKey(), maxPlayers, openedAt);
        openByQueue.put(ticket.getQueueKey(), open);
        openByTable.put(tableId, open);
        scheduleLobby(open);
        log.info("[Matchmaking] Opened lobby table {} for {}", tableId, ticket.getQueueKey());
        return open;
    }

    private void scheduleLobby(OpenTable open) {
        int maxBots = Math.max(0, open.maxPlayers - 1);
        for (int i = 0; i < maxBots; i++) {
            long delay = humanOnlyMs + (long) i * botIntervalMs;
            if (delay >= dealDelayMs) break;
            ScheduledFuture<?> task = matchingScheduler.schedule(() -> fillOneBot(open.tableId), delay, TimeUnit.MILLISECONDS);
            open.tasks.add(task);
        }
        ScheduledFuture<?> deal = matchingScheduler.schedule(() -> dealLobby(open.tableId), dealDelayMs, TimeUnit.MILLISECONDS);
        open.tasks.add(deal);
    }

    private void fillOneBot(String tableId) {
        synchronized (lobbyLock) {
            OpenTable open = openByTable.get(tableId);
            if (open == null || open.seatingClosed) return;
            TableActor actor = tableManager.getTable(tableId).orElse(null);
            if (actor == null || !actor.isSeatingOpen()) return;
            int pending = Math.max(0, open.humanIds.size() - actor.countHumans());
            actor.seatOneWaitingBot(pending);
        }
    }

    private void dealLobby(String tableId) {
        synchronized (lobbyLock) {
            OpenTable open = openByTable.get(tableId);
            if (open == null || open.seatingClosed) return;
            TableActor actor = tableManager.getTable(tableId).orElse(null);
            if (actor == null) {
                discardOpenTable(open, false);
                return;
            }
            if (actor.countHumans() == 0) {
                log.info("[Matchmaking] Lobby {} had no human at deal time; discarding", tableId);
                discardOpenTable(open, true);
                return;
            }
            open.seatingClosed = true;
            openByQueue.remove(open.queueKey, open);
            openByTable.remove(tableId);
            cancelTasks(open);
            actor.closeSeatingAndDeal();
        }
    }

    private void assignHuman(MatchmakingTicket ticket, String tableId) {
        routingRegistry.registerPlayerTable(ticket.getPlayerId(), tableId);
        if (walletService != null && ticket.getStakeTier() > 0) {
            try {
                String txKey = "STAKE_" + tableId + "_" + ticket.getPlayerId();
                walletService.debit(
                        ticket.getPlayerId(),
                        BigDecimal.valueOf(ticket.getStakeTier()),
                        "GAME_ENTRY_STAKE",
                        txKey,
                        tableId,
                        "Table entry stake for " + ticket.getRulesetId(),
                        Map.of("tableId", tableId, "stakeTier", ticket.getStakeTier())
                );
            } catch (Exception e) {
                log.warn("[Matchmaking] Could not debit stake for {}: {}", ticket.getPlayerId(), e.getMessage());
            }
        }
        ticket.setMatchedTableId(tableId);
        ticket.setMatchedServerId(routingRegistry.getServerInstanceId());
        ticket.setStatus(MatchmakingTicket.Status.MATCHED);
        store.saveTicket(ticket);
        log.info("[Matchmaking] Seated human {} into open table {}", ticket.getPlayerId(), tableId);
    }

    private boolean releaseLobbyHuman(MatchmakingTicket ticket) {
        synchronized (lobbyLock) {
            String tableId = ticket.getMatchedTableId();
            OpenTable open = tableId == null ? null : openByTable.get(tableId);
            if (open == null || open.seatingClosed) {
                return false;
            }
            open.humanIds.remove(ticket.getPlayerId());
            ticket.setStatus(MatchmakingTicket.Status.CANCELLED);
            store.saveTicket(ticket);
            tableManager.getTable(tableId).ifPresent(actor -> actor.removeWaitingHuman(ticket.getPlayerId()));
            routingRegistry.unregisterPlayer(ticket.getPlayerId());
            if (open.humanIds.isEmpty()) {
                discardOpenTable(open, true);
            }
            log.info("[Matchmaking] Released {} from lobby {}", ticket.getPlayerId(), tableId);
            return true;
        }
    }

    private void discardOpenTable(OpenTable open, boolean removeActor) {
        open.seatingClosed = true;
        openByQueue.remove(open.queueKey, open);
        openByTable.remove(open.tableId);
        cancelTasks(open);
        if (removeActor) {
            tableManager.removeTable(open.tableId);
        }
    }

    private static void cancelTasks(OpenTable open) {
        for (ScheduledFuture<?> task : open.tasks) {
            task.cancel(false);
        }
        open.tasks.clear();
    }

    private static final class OpenTable {
        final String tableId;
        final String queueKey;
        final int maxPlayers;
        final Instant openedAt;
        final Set<String> humanIds = new HashSet<>();
        final List<ScheduledFuture<?>> tasks = new ArrayList<>();
        boolean seatingClosed;

        OpenTable(String tableId, String queueKey, int maxPlayers, Instant openedAt) {
            this.tableId = tableId;
            this.queueKey = queueKey;
            this.maxPlayers = maxPlayers;
            this.openedAt = openedAt;
        }
    }

    @PreDestroy
    public void shutdown() {
        matchingScheduler.shutdownNow();
    }
}
