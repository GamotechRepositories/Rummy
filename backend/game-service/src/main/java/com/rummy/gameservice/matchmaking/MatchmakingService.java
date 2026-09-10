package com.rummy.gameservice.matchmaking;

import com.rummy.engine.bot.BotDifficulty;
import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.command.ReadyCommand;
import com.rummy.engine.rules.PointsRummyRules;
import com.rummy.engine.rules.RummyRules;
import com.rummy.engine.rules.RulesetRegistry;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.routing.PlayerPresenceService;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.rummy.gameservice.wallet.WalletService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Phase 18: Real-time Stake & Variant Matchmaking Engine with AI Fallback.
 */
@Service
public class MatchmakingService {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingService.class);

    private final TableManager tableManager;
    private final TableRoutingRegistry routingRegistry;
    private final PlayerPresenceService presenceService;
    private final WalletService walletService;

    private final long aiFallbackTimeoutMs;
    private final long maxQueueTimeoutMs;

    private final Map<String, MatchmakingTicket> tickets = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<MatchmakingTicket>> queues = new ConcurrentHashMap<>();

    private final ScheduledExecutorService matchingScheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    public MatchmakingService(
            TableManager tableManager,
            TableRoutingRegistry routingRegistry,
            PlayerPresenceService presenceService,
            @Autowired(required = false) WalletService walletService,
            @Value("${matchmaking.ai-fallback-timeout-ms:15000}") long aiFallbackTimeoutMs,
            @Value("${matchmaking.max-queue-timeout-ms:45000}") long maxQueueTimeoutMs) {
        this.tableManager = Objects.requireNonNull(tableManager);
        this.routingRegistry = Objects.requireNonNull(routingRegistry);
        this.presenceService = Objects.requireNonNull(presenceService);
        this.walletService = walletService;
        this.aiFallbackTimeoutMs = aiFallbackTimeoutMs;
        this.maxQueueTimeoutMs = maxQueueTimeoutMs;
    }

    public MatchmakingService(
            TableManager tableManager,
            TableRoutingRegistry routingRegistry,
            PlayerPresenceService presenceService,
            long aiFallbackTimeoutMs,
            long maxQueueTimeoutMs) {
        this(tableManager, routingRegistry, presenceService, null, aiFallbackTimeoutMs, maxQueueTimeoutMs);
    }

    @PostConstruct
    public void startMatchingLoop() {
        matchingScheduler.scheduleWithFixedDelay(this::processQueues, 250, 250, TimeUnit.MILLISECONDS);
        log.info("[Matchmaking] Matchmaking service loop started with AI fallback timeout: {}ms", aiFallbackTimeoutMs);
    }

    /**
     * Submit a player into the matchmaking queue.
     */
    public MatchmakingTicket enqueue(MatchmakingRequest request) {
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
        tickets.put(ticketId, ticket);

        String queueKey = ticket.getQueueKey();
        queues.computeIfAbsent(queueKey, k -> new ConcurrentLinkedQueue<>()).add(ticket);

        log.info("[Matchmaking] Enqueued player {} for queueKey={}", request.getPlayerId(), queueKey);
        return ticket;
    }

    public Optional<MatchmakingTicket> getTicket(String ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }

    public boolean cancelTicket(String ticketId) {
        MatchmakingTicket ticket = tickets.get(ticketId);
        if (ticket != null && ticket.getStatus() == MatchmakingTicket.Status.QUEUED) {
            ticket.setStatus(MatchmakingTicket.Status.CANCELLED);
            log.info("[Matchmaking] Cancelled ticket {}", ticketId);
            return true;
        }
        return false;
    }

    void processQueues() {
        for (Map.Entry<String, ConcurrentLinkedQueue<MatchmakingTicket>> entry : queues.entrySet()) {
            String queueKey = entry.getKey();
            ConcurrentLinkedQueue<MatchmakingTicket> queue = entry.getValue();

            List<MatchmakingTicket> validWaiting = new ArrayList<>();
            MatchmakingTicket candidate;

            while ((candidate = queue.poll()) != null) {
                if (candidate.getStatus() != MatchmakingTicket.Status.QUEUED) {
                    continue; // Skip cancelled or already matched
                }
                validWaiting.add(candidate);
            }

            if (validWaiting.isEmpty()) {
                continue;
            }

            // Check if we can form full human player groups
            int targetSize = validWaiting.get(0).getMaxPlayers();
            List<MatchmakingTicket> matchedGroup = new ArrayList<>();

            for (MatchmakingTicket ticket : validWaiting) {
                matchedGroup.add(ticket);
                if (matchedGroup.size() == targetSize) {
                    createAndAssignTable(matchedGroup, false);
                    matchedGroup.clear();
                }
            }

            // For remaining players in queue (e.g. 2, 3, 4, or 5 players in a 6-player table):
            if (!matchedGroup.isEmpty()) {
                boolean anyTimedOut = matchedGroup.stream().anyMatch(t ->
                        t.isAllowAiFallback() && Duration.between(t.getCreatedAt(), Instant.now()).toMillis() >= aiFallbackTimeoutMs
                );

                if (anyTimedOut) {
                    // Group ALL waiting real players together into 1 shared table, and fill remaining seats with bots!
                    // e.g. 3 real players + 3 bots = 6 players
                    createAndAssignTable(new ArrayList<>(matchedGroup), true);
                    matchedGroup.clear();
                } else {
                    for (MatchmakingTicket leftover : matchedGroup) {
                        long elapsed = Duration.between(leftover.getCreatedAt(), Instant.now()).toMillis();
                        if (elapsed >= maxQueueTimeoutMs) {
                            leftover.setStatus(MatchmakingTicket.Status.EXPIRED);
                            log.info("[Matchmaking] Ticket {} expired after {}ms", leftover.getTicketId(), elapsed);
                        } else {
                            queue.add(leftover);
                        }
                    }
                }
            }
        }
    }

    private void createAndAssignTable(List<MatchmakingTicket> humanTickets, boolean fillWithAi) {
        String tableId = "TBL_MM_" + UUID.randomUUID().toString().substring(0, 8);
        MatchmakingTicket first = humanTickets.get(0);

        RummyRules rules = RulesetRegistry.getRuleset(first.getRulesetId()).orElseGet(PointsRummyRules::new);
        TableActor actor = tableManager.getOrCreateTable(tableId, rules);
        routingRegistry.registerTableOwnership(tableId);

        // Register all human players and debit stake
        for (MatchmakingTicket ticket : humanTickets) {
            routingRegistry.registerPlayerTable(ticket.getPlayerId(), tableId);
            ticket.setMatchedTableId(tableId);
            ticket.setMatchedServerId(routingRegistry.getServerInstanceId());
            ticket.setStatus(MatchmakingTicket.Status.MATCHED);

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

            log.info("[Matchmaking] Matched human player {} into table {}", ticket.getPlayerId(), tableId);
        }

        // If AI fill requested, add AI bots to table
        if (fillWithAi) {
            int neededBots = first.getMaxPlayers() - humanTickets.size();
            for (int i = 1; i <= neededBots; i++) {
                String botId = "BOT_" + UUID.randomUUID().toString().substring(0, 4);
                String botName = "RoyalBot_" + i;
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

    public int getQueuedPlayerCount() {
        return (int) tickets.values().stream()
                .filter(t -> t.getStatus() == MatchmakingTicket.Status.QUEUED)
                .count();
    }

    @PreDestroy
    public void shutdown() {
        matchingScheduler.shutdownNow();
    }
}
