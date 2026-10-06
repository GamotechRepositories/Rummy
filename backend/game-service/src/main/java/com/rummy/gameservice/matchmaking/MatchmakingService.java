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
import com.rummy.gameservice.lifecycle.NodeDrainState;
import com.rummy.gameservice.routing.PlayerPresenceService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.wallet.StakeEscrowService;
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
    private NodeDrainState drainState;
    private StakeEscrowService escrows;

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

    /**
     * Escrows the entry stake, then queues or seats the player.
     *
     * @throws IllegalStateException when the stake cannot be debited (e.g. insufficient balance);
     *                               the player is not queued in that case.
     */
    public MatchmakingTicket enqueue(MatchmakingRequest request) {
        if (isDraining()) {
            throw new NodeDrainingException();
        }
        for (MatchmakingTicket superseded : store.cancelActiveTicketsForPlayer(request.getPlayerId())) {
            refundStake(superseded, "superseded by a new search");
        }

        String ticketId = "TKT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MatchmakingTicket ticket = new MatchmakingTicket(
                ticketId,
                request.getPlayerId(),
                request.getPlayerName(),
                request.getAvatarId(),
                request.getRulesetId(),
                request.getStakeTier(),
                request.getMaxPlayers(),
                request.isAllowAiFallback()
        );
        ticket.setPool(request.getPool());

        escrowStake(ticket);
        try {
            presenceService.updatePresence(request.getPlayerId());

            if (request.isAllowAiFallback()) {
                openOrJoinLobby(ticket, request);
                return ticket;
            }

            store.saveTicket(ticket);
            store.enqueue(nodeQueueKey(ticket), ticket.getTicketId());
        } catch (RuntimeException e) {
            refundStake(ticket, "matchmaking error");
            throw e;
        }

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
            refundStake(ticket, "search cancelled");
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
            String ticketId = open.humanTickets.remove(playerId);
            routingRegistry.unregisterPlayer(playerId);
            if (ticketId != null) {
                store.findTicket(ticketId).ifPresent(t -> refundStake(t, "left lobby before the deal"));
            }
            if (open.humanTickets.isEmpty()) {
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
        if (isDraining()) {
            // Tables formed here would be killed by the shutdown; other nodes claim the shared queue.
            return;
        }
        for (String queueKey : store.listQueueKeys()) {
            if (!isLocalQueue(queueKey)) {
                continue;
            }
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
                    refundStake(candidate, "player went offline");
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
                    refundStake(ticket, "duplicate ticket");
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
                            refundStake(leftover, "no match found in time");
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
        actor.setStakeTier(first.getStakeTier());

        for (MatchmakingTicket ticket : humanTickets) {
            routingRegistry.registerPlayerTable(ticket.getPlayerId(), tableId);
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

                    actor.registerBot(botId, botName, BotDifficulty.HARD);
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
            assignStake(ticket, actor);
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
            open.humanTickets.put(ticket.getPlayerId(), ticket.getTicketId());
            assignHuman(ticket, open.tableId);
            tableManager.getTable(open.tableId).ifPresent(actor -> assignStake(ticket, actor));
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
        int pending = Math.max(0, open.humanTickets.size() - actor.countHumans());
        return actor.getState().getPlayers().size() + pending < open.maxPlayers;
    }

    private OpenTable createLobbyTable(MatchmakingTicket ticket, int maxPlayers) {
        String tableId = "TBL_MM_" + UUID.randomUUID().toString().substring(0, 8);
        RummyRules rules = RulesetRegistry.getRuleset(ticket.getRulesetId()).orElseGet(PointsRummyRules::new);
        TableActor actor = tableManager.getOrCreateTable(tableId, rules);
        routingRegistry.registerTableOwnership(tableId);
        actor.setExpectedPlayers(maxPlayers);
        actor.setStakeTier(ticket.getStakeTier());
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
            int pending = Math.max(0, open.humanTickets.size() - actor.countHumans());
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
            // Matched humans who never connected are not dealt in; give their stake back.
            open.humanTickets.forEach((playerId, ticketId) -> {
                if (actor.getState().getPlayer(playerId).isEmpty()) {
                    routingRegistry.unregisterPlayer(playerId);
                    store.findTicket(ticketId).ifPresent(t -> refundStake(t, "did not join before the deal"));
                }
            });
            actor.closeSeatingAndDeal();
        }
    }

    private void assignHuman(MatchmakingTicket ticket, String tableId) {
        routingRegistry.registerPlayerTable(ticket.getPlayerId(), tableId);
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
            open.humanTickets.remove(ticket.getPlayerId());
            ticket.setStatus(MatchmakingTicket.Status.CANCELLED);
            store.saveTicket(ticket);
            tableManager.getTable(tableId).ifPresent(actor -> actor.removeWaitingHuman(ticket.getPlayerId()));
            routingRegistry.unregisterPlayer(ticket.getPlayerId());
            refundStake(ticket, "left lobby before the deal");
            if (open.humanTickets.isEmpty()) {
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
        open.humanTickets.forEach((playerId, ticketId) -> {
            routingRegistry.unregisterPlayer(playerId);
            store.findTicket(ticketId).ifPresent(t -> refundStake(t, "lobby closed without a deal"));
        });
        open.humanTickets.clear();
        if (removeActor) {
            tableManager.removeTable(open.tableId);
            routingRegistry.unregisterTable(open.tableId);
        }
    }

    private void escrowStake(MatchmakingTicket ticket) {
        if (walletService == null || ticket.getStakeTier() <= 0) {
            return;
        }
        String key = escrowKey(ticket);
        if (escrows != null) {
            escrows.open(key, ticket.getPlayerId(), ticket.getStakeTier(), null, null);
        }
        try {
            walletService.debit(
                    ticket.getPlayerId(),
                    BigDecimal.valueOf(ticket.getStakeTier()),
                    "GAME_ENTRY_STAKE",
                    key,
                    null,
                    "Table entry stake for " + ticket.getRulesetId(),
                    Map.of("ticketId", ticket.getTicketId(), "stakeTier", ticket.getStakeTier())
            );
        } catch (RuntimeException e) {
            if (escrows != null) {
                escrows.discard(key);
            }
            throw e;
        }
    }

    /** Idempotent: safe to call from every exit path; pays back only a stake that was actually escrowed. */
    private void refundStake(MatchmakingTicket ticket, String reason) {
        if (walletService == null || ticket.getStakeTier() <= 0) {
            return;
        }
        String key = escrowKey(ticket);
        try {
            if (!walletService.hasTransaction(key)) {
                return;
            }
            if (escrows != null && !escrows.claimRefund(key)) {
                return;
            }
            walletService.credit(
                    ticket.getPlayerId(),
                    BigDecimal.valueOf(ticket.getStakeTier()),
                    "GAME_ENTRY_REFUND",
                    "STAKE_REFUND_" + ticket.getTicketId(),
                    null,
                    "Entry stake refunded: " + reason,
                    Map.of("ticketId", ticket.getTicketId(), "reason", reason)
            );
        } catch (Exception e) {
            if (escrows != null) {
                escrows.reopen(key);
            }
            log.error("[Matchmaking] RECONCILE REQUIRED: stake refund failed for ticket {} player {}: {}",
                    ticket.getTicketId(), ticket.getPlayerId(), e.getMessage());
        }
    }

    private void assignStake(MatchmakingTicket ticket, TableActor actor) {
        if (escrows != null && ticket.getStakeTier() > 0) {
            escrows.assignToTable(escrowKey(ticket), actor.getTableId(), actor.getState().getGameId());
        }
    }

    private static String escrowKey(MatchmakingTicket ticket) {
        return "STAKE_" + ticket.getTicketId();
    }

    /**
     * Queues are per node: players reach a node through sticky load-balancer affinity, so the table
     * must be created on the node that holds their connection. The shared store still lets any node
     * look up or cancel a ticket.
     */
    private String nodeQueueKey(MatchmakingTicket ticket) {
        return routingRegistry.getServerInstanceId() + "|" + ticket.getQueueKey();
    }

    private boolean isLocalQueue(String queueKey) {
        return queueKey.startsWith(routingRegistry.getServerInstanceId() + "|");
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
        /** Reserved human seats: playerId → ticketId (needed to refund the escrowed stake). */
        final Map<String, String> humanTickets = new HashMap<>();
        final List<ScheduledFuture<?>> tasks = new ArrayList<>();
        boolean seatingClosed;

        OpenTable(String tableId, String queueKey, int maxPlayers, Instant openedAt) {
            this.tableId = tableId;
            this.queueKey = queueKey;
            this.maxPlayers = maxPlayers;
            this.openedAt = openedAt;
        }
    }

    @Autowired(required = false)
    public void setDrainState(NodeDrainState drainState) {
        this.drainState = drainState;
    }

    @Autowired(required = false)
    public void setEscrows(StakeEscrowService escrows) {
        this.escrows = escrows;
    }

    private boolean isDraining() {
        return drainState != null && drainState.isDraining();
    }

    /**
     * Called once the node has finished draining. This node's open lobbies and queued tickets would
     * be stranded once it stops, so they are cancelled and their stakes returned now.
     */
    public void releaseLocalWorkOnShutdown() {
        synchronized (lobbyLock) {
            for (OpenTable open : new ArrayList<>(openByTable.values())) {
                discardOpenTable(open, true);
            }
        }
        for (String queueKey : store.listQueueKeys()) {
            if (!isLocalQueue(queueKey)) {
                continue;
            }
            Optional<MatchmakingStore.QueueSnapshot> claimed = store.claimQueue(queueKey, 5000);
            if (claimed.isEmpty()) {
                continue;
            }
            for (MatchmakingTicket ticket : claimed.get().tickets()) {
                if (ticket.getStatus() == MatchmakingTicket.Status.QUEUED) {
                    ticket.setStatus(MatchmakingTicket.Status.CANCELLED);
                    store.saveTicket(ticket);
                    refundStake(ticket, "server restarting");
                }
            }
            store.releaseQueue(queueKey, List.of());
        }
    }

    @PreDestroy
    public void shutdown() {
        matchingScheduler.shutdownNow();
    }
}
