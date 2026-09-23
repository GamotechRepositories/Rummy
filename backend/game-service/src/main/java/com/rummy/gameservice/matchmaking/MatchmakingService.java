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

    private final ScheduledExecutorService matchingScheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    public MatchmakingService(
            TableManager tableManager,
            TableRoutingRegistry routingRegistry,
            PlayerPresenceService presenceService,
            MatchmakingStore store,
            @Autowired(required = false) WalletService walletService,
            @Value("${matchmaking.ai-fallback-timeout-ms:15000}") long aiFallbackTimeoutMs,
            @Value("${matchmaking.max-queue-timeout-ms:45000}") long maxQueueTimeoutMs) {
        this.tableManager = Objects.requireNonNull(tableManager);
        this.routingRegistry = Objects.requireNonNull(routingRegistry);
        this.presenceService = Objects.requireNonNull(presenceService);
        this.store = Objects.requireNonNull(store);
        this.walletService = walletService;
        this.aiFallbackTimeoutMs = aiFallbackTimeoutMs;
        this.maxQueueTimeoutMs = maxQueueTimeoutMs;
    }

    /** Test helper constructor. */
    public MatchmakingService(
            TableManager tableManager,
            TableRoutingRegistry routingRegistry,
            PlayerPresenceService presenceService,
            long aiFallbackTimeoutMs,
            long maxQueueTimeoutMs) {
        this(tableManager, routingRegistry, presenceService, new InMemoryMatchmakingStore(),
                null, aiFallbackTimeoutMs, maxQueueTimeoutMs);
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
        if (ticket.getStatus() != MatchmakingTicket.Status.QUEUED) return false;
        ticket.setStatus(MatchmakingTicket.Status.CANCELLED);
        store.saveTicket(ticket);
        log.info("[Matchmaking] Cancelled ticket {}", ticketId);
        return true;
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

    @PreDestroy
    public void shutdown() {
        matchingScheduler.shutdownNow();
    }
}
