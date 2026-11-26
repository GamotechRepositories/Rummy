package com.rummy.gameservice.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.engine.event.GameEvent;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.GameState;
import com.rummy.engine.model.PlayerState;
import com.rummy.gameservice.persistence.document.GameDocument;
import com.rummy.gameservice.persistence.document.GameDocument.PlayerSnapshot;
import com.rummy.gameservice.persistence.document.GameEventDocument;
import com.rummy.gameservice.persistence.document.GameResultDocument;
import com.rummy.gameservice.persistence.document.StakeEscrowDocument;
import com.rummy.gameservice.persistence.document.UserProfileDocument;
import com.rummy.gameservice.persistence.document.WalletTransactionDocument;
import com.rummy.gameservice.persistence.repository.GameEventRepository;
import com.rummy.gameservice.persistence.repository.GameRepository;
import com.rummy.gameservice.persistence.repository.GameResultRepository;
import com.rummy.gameservice.persistence.repository.UserProfileRepository;
import com.rummy.gameservice.persistence.repository.WalletTransactionRepository;
import com.rummy.gameservice.wallet.GameSettlementResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service orchestrating asynchronous MongoDB persistence for Rummy matches,
 * results, audit event trails, and user career statistics.
 *
 * Adheres to Section 22 ("Zero hot-path DB writes"): Writes are handled off the
 * in-memory game execution loop via an asynchronous thread pool.
 */
@Service
public class GamePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(GamePersistenceService.class);
    private static final int PLAYER_HISTORY_LIMIT = 50;
    private static final int FILTERED_HISTORY_SCAN_LIMIT = 200;

    private final GameRepository gameRepository;
    private final GameResultRepository gameResultRepository;
    private final GameEventRepository gameEventRepository;
    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService asyncWriter = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentLinkedQueue<GameEventDocument> eventQueue = new ConcurrentLinkedQueue<>();
    private final Map<String, Object> settlementLocks = new ConcurrentHashMap<>();
    private final Map<String, GameSettlementResult> pendingSettlements = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, GameSettlementResult> eldest) {
                    return size() > 2048;
                }
            });
    private WalletTransactionRepository walletTransactionRepository;
    private MongoTemplate mongoTemplate;

    @Autowired
    public GamePersistenceService(GameRepository gameRepository,
                                  GameResultRepository gameResultRepository,
                                  GameEventRepository gameEventRepository,
                                  UserProfileRepository userProfileRepository,
                                  ObjectMapper objectMapper) {
        this.gameRepository = Objects.requireNonNull(gameRepository);
        this.gameResultRepository = Objects.requireNonNull(gameResultRepository);
        this.gameEventRepository = Objects.requireNonNull(gameEventRepository);
        this.userProfileRepository = Objects.requireNonNull(userProfileRepository);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        
        // Start background flusher for batch DB inserts
        this.flusher.scheduleAtFixedRate(this::flushEvents, 2, 2, TimeUnit.SECONDS);
    }

    @Autowired(required = false)
    public void setWalletTransactionRepository(WalletTransactionRepository walletTransactionRepository) {
        this.walletTransactionRepository = walletTransactionRepository;
    }

    @Autowired(required = false)
    public void setMongoTemplate(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }
    
    private void flushEvents() {
        if (eventQueue.isEmpty()) return;
        List<GameEventDocument> batch = new ArrayList<>();
        GameEventDocument doc;
        while ((doc = eventQueue.poll()) != null && batch.size() < 2000) {
            batch.add(doc);
        }
        if (!batch.isEmpty()) {
            try {
                gameEventRepository.saveAll(batch);
                log.debug("[Persistence] Flushed batch of {} events to DB", batch.size());
            } catch (Exception e) {
                log.error("[Persistence] Error batch saving game events: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Asynchronously records game initialization.
     */
    public void recordGameStarted(GameState state, String tableId) {
        asyncWriter.submit(() -> {
            try {
                String cutJokerStr = state.getCutJoker() != null
                        ? state.getCutJoker().getCard().toString()
                        : null;

                List<PlayerSnapshot> playerSnapshots = state.getPlayers().stream()
                        .map(p -> new PlayerSnapshot(
                                p.getPlayerId(),
                                p.getDisplayName(),
                                p.getSeatIndex(),
                                p.getScore(),
                                p.getStatus().name(),
                                p.isBot(),
                                false
                        ))
                        .toList();

                GameDocument doc = new GameDocument(
                        state.getGameId(),
                        tableId,
                        state.getRulesetId(),
                        state.getStatus().name(),
                        state.getCreatedAt(),
                        null,
                        null,
                        cutJokerStr,
                        playerSnapshots,
                        0,
                        0
                );

                gameRepository.save(doc);
                log.info("[Persistence] Recorded game start for gameId={}", state.getGameId());

                // Ensure non-bot user profiles exist
                for (PlayerState p : state.getPlayers()) {
                    if (!p.isBot()) {
                        getOrCreateUserProfile(p.getPlayerId(), p.getDisplayName());
                    }
                }
            } catch (Exception e) {
                log.error("[Persistence] Failed to record game start for gameId={}: {}", state.getGameId(), e.getMessage(), e);
            }
        });
    }

    /**
     * Asynchronously records completed game, player results, and updates career statistics.
     */
    public void recordGameFinished(GameState state, String tableId) {
        asyncWriter.submit(() -> {
            try {
                Instant finishedAt = state.getFinishedAt() != null ? state.getFinishedAt() : Instant.now();
                long durationSeconds = Math.max(0, Duration.between(state.getCreatedAt(), finishedAt).toSeconds());
                String winnerId = state.getWinnerPlayerId();

                int totalTurns = state.getPlayers().stream().mapToInt(PlayerState::getTurnsCompleted).sum();

                List<PlayerSnapshot> playerSnapshots = state.getPlayers().stream()
                        .map(p -> new PlayerSnapshot(
                                p.getPlayerId(),
                                p.getDisplayName(),
                                p.getSeatIndex(),
                                p.getScore(),
                                p.getStatus().name(),
                                p.isBot(),
                                p.getPlayerId().equals(winnerId)
                        ))
                        .toList();

                String cutJokerStr = state.getCutJoker() != null
                        ? state.getCutJoker().getCard().toString()
                        : null;

                GameDocument doc = new GameDocument(
                        state.getGameId(),
                        tableId,
                        state.getRulesetId(),
                        state.getStatus().name(),
                        state.getCreatedAt(),
                        finishedAt,
                        winnerId,
                        cutJokerStr,
                        playerSnapshots,
                        totalTurns,
                        durationSeconds
                );
                gameRepository.save(doc);

                // Insert individual GameResultDocument records and update user profiles
                Object lock = settlementLocks.computeIfAbsent(state.getGameId(), k -> new Object());
                synchronized (lock) {
                    GameSettlementResult settled = pendingSettlements.get(state.getGameId());
                    for (PlayerState p : state.getPlayers()) {
                        boolean won = p.getPlayerId().equals(winnerId);
                        GameResultDocument resultDoc = new GameResultDocument(
                                state.getGameId(),
                                tableId,
                                p.getPlayerId(),
                                p.getDisplayName(),
                                p.getScore(),
                                won,
                                p.getStatus().name(),
                                finishedAt
                        );
                        resultDoc.setRulesetId(state.getRulesetId());
                        if (settled != null && settled.playerDetails() != null) {
                            GameSettlementResult.PlayerSettlementDetail detail = settled.playerDetails().get(p.getPlayerId());
                            if (detail != null && detail.netWalletDelta() != null) {
                                resultDoc.setNetAmountRs(detail.netWalletDelta().setScale(2, RoundingMode.HALF_UP).doubleValue());
                            }
                        }
                        gameResultRepository.save(resultDoc);

                        // Update user career stats if human player
                        if (!p.isBot()) {
                            UserProfileDocument profile = getOrCreateUserProfile(p.getPlayerId(), p.getDisplayName());
                            profile.setGamesPlayed(profile.getGamesPlayed() + 1);
                            if (won) {
                                profile.setGamesWon(profile.getGamesWon() + 1);
                                profile.setVirtualPoints(profile.getVirtualPoints() + 500); // 500 points prize
                            } else {
                                // Penalty points deduction
                                profile.setVirtualPoints(Math.max(0, profile.getVirtualPoints() - p.getScore()));
                            }
                            profile.setTotalScore(profile.getTotalScore() + p.getScore());
                            profile.setUpdatedAt(Instant.now());
                            userProfileRepository.save(profile);
                        }
                    }
                }

                log.info("[Persistence] Completed game persistence for gameId={}, winner={}", state.getGameId(), winnerId);
            } catch (Exception e) {
                log.error("[Persistence] Failed to record game finished for gameId={}: {}", state.getGameId(), e.getMessage(), e);
            }
        });
    }

    /**
     * Records net INR settlement deltas onto the finished game result documents once WalletService settles.
     */
    public void recordGameSettlement(GameSettlementResult result) {
        if (result == null || result.gameId() == null || result.playerDetails() == null) {
            return;
        }
        pendingSettlements.put(result.gameId(), result);
        asyncWriter.submit(() -> {
            Object lock = settlementLocks.computeIfAbsent(result.gameId(), k -> new Object());
            try {
                synchronized (lock) {
                    List<GameResultDocument> existing = gameResultRepository.findByGameId(result.gameId());
                    if (existing != null && !existing.isEmpty()) {
                        for (GameResultDocument doc : existing) {
                            GameSettlementResult.PlayerSettlementDetail detail = result.playerDetails().get(doc.getPlayerId());
                            if (detail != null && detail.netWalletDelta() != null) {
                                doc.setNetAmountRs(detail.netWalletDelta().setScale(2, RoundingMode.HALF_UP).doubleValue());
                                gameResultRepository.save(doc);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[Persistence] Failed to attach settlement delta for gameId={}: {}", result.gameId(), e.getMessage());
            } finally {
                settlementLocks.remove(result.gameId(), lock);
            }
        });
    }

    /**
     * Asynchronously records an immutable game audit event.
     */
    public void recordGameEventAsync(String gameId, GameEvent event) {
        asyncWriter.submit(() -> {
            try {
                String payloadJson = objectMapper.writeValueAsString(event);
                GameEventDocument doc = new GameEventDocument(
                        gameId,
                        event.sequence(),
                        event.eventType(),
                        null,
                        payloadJson,
                        event.timestamp()
                );
                eventQueue.offer(doc);
                
                // Flush immediately if the queue grows too fast under heavy load
                if (eventQueue.size() >= 2000) {
                    flusher.execute(this::flushEvents);
                }
            } catch (JsonProcessingException e) {
                log.error("[Persistence] Error serializing game event: {}", e.getMessage());
            } catch (Exception e) {
                log.error("[Persistence] Error queuing game event doc: {}", e.getMessage());
            }
        });
    }

    /** Most recent results for a player (capped so long-time players don't trigger huge reads). */
    public List<GameResultDocument> getPlayerHistory(String playerId) {
        return getPlayerHistory(playerId, null, false);
    }

    public List<GameResultDocument> getPlayerHistory(String playerId, Collection<String> allowedRulesets) {
        return getPlayerHistory(playerId, allowedRulesets, false);
    }

    /**
     * Most recent results for a player optionally scoped to specific variants (ruleset IDs)
     * and optionally excluding tournament table rows (which belong in Tournament History).
     */
    public List<GameResultDocument> getPlayerHistory(
            String playerId,
            Collection<String> allowedRulesets,
            boolean excludeTournaments) {
        Set<String> normalized = new HashSet<>();
        if (allowedRulesets != null) {
            for (String r : allowedRulesets) {
                if (r != null && !r.isBlank()) {
                    normalized.add(r.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        if (normalized.isEmpty() && !excludeTournaments) {
            List<GameResultDocument> results = gameResultRepository.findByPlayerIdOrderByCreatedAtDesc(
                    playerId, PageRequest.of(0, PLAYER_HISTORY_LIMIT));
            return enrichNetAmounts(playerId, enrichRulesetIds(results));
        }

        List<GameResultDocument> scanned = enrichRulesetIds(
                gameResultRepository.findByPlayerIdOrderByCreatedAtDesc(
                        playerId, PageRequest.of(0, FILTERED_HISTORY_SCAN_LIMIT))
        );
        List<GameResultDocument> filtered = new ArrayList<>();
        for (GameResultDocument doc : scanned) {
            if (excludeTournaments && doc.getTableId() != null && doc.getTableId().startsWith("TBL_TRN_")) {
                continue;
            }
            if (!normalized.isEmpty()) {
                String ruleset = doc.getRulesetId();
                if (ruleset == null || !normalized.contains(ruleset.trim().toUpperCase(Locale.ROOT))) {
                    continue;
                }
            }
            filtered.add(doc);
            if (filtered.size() >= PLAYER_HISTORY_LIMIT) {
                break;
            }
        }
        return enrichNetAmounts(playerId, filtered);
    }

    private List<GameResultDocument> enrichRulesetIds(List<GameResultDocument> results) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> missingGameIds = new LinkedHashSet<>();
        for (GameResultDocument doc : results) {
            if ((doc.getRulesetId() == null || doc.getRulesetId().isBlank())
                    && doc.getGameId() != null && !doc.getGameId().isBlank()) {
                missingGameIds.add(doc.getGameId());
            }
        }
        if (!missingGameIds.isEmpty()) {
            Iterable<GameDocument> games = gameRepository.findAllById(missingGameIds);
            if (games != null) {
                Map<String, String> rulesetByGameId = new HashMap<>();
                for (GameDocument game : games) {
                    if (game != null && game.getGameId() != null && game.getRulesetId() != null) {
                        rulesetByGameId.put(game.getGameId(), game.getRulesetId());
                    }
                }
                for (GameResultDocument doc : results) {
                    if (doc.getRulesetId() == null || doc.getRulesetId().isBlank()) {
                        String resolved = rulesetByGameId.get(doc.getGameId());
                        if (resolved != null) {
                            doc.setRulesetId(resolved);
                        }
                    }
                }
            }
        }
        return results;
    }

    private List<GameResultDocument> enrichNetAmounts(String playerId, List<GameResultDocument> results) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        if (walletTransactionRepository == null && mongoTemplate == null) {
            return results;
        }
        Set<String> missingGameIds = new LinkedHashSet<>();
        for (GameResultDocument doc : results) {
            boolean isTournamentTable = doc.getTableId() != null && doc.getTableId().startsWith("TBL_TRN_");
            if (!isTournamentTable && doc.getNetAmountRs() == null
                    && doc.getGameId() != null && !doc.getGameId().isBlank()) {
                missingGameIds.add(doc.getGameId());
            }
        }
        if (missingGameIds.isEmpty()) {
            return results;
        }

        Map<String, List<WalletTransactionDocument>> txByGameId = new HashMap<>();
        if (walletTransactionRepository != null) {
            try {
                List<WalletTransactionDocument> txs = walletTransactionRepository.findByGameIdIn(missingGameIds);
                if (txs != null) {
                    for (WalletTransactionDocument tx : txs) {
                        if (tx != null && playerId.equals(tx.getPlayerId()) && tx.getGameId() != null) {
                            txByGameId.computeIfAbsent(tx.getGameId(), k -> new ArrayList<>()).add(tx);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[Persistence] Could not enrich match transactions: {}", e.getMessage());
            }
        }

        Map<String, List<StakeEscrowDocument>> escrowsByGameId = new HashMap<>();
        if (mongoTemplate != null) {
            try {
                List<StakeEscrowDocument> escrows = mongoTemplate.find(
                        Query.query(Criteria.where("playerId").is(playerId).and("gameId").in(missingGameIds)),
                        StakeEscrowDocument.class
                );
                for (StakeEscrowDocument escrow : escrows) {
                    if (escrow != null && escrow.getGameId() != null) {
                        escrowsByGameId.computeIfAbsent(escrow.getGameId(), k -> new ArrayList<>()).add(escrow);
                    }
                }
            } catch (Exception e) {
                log.debug("[Persistence] Could not enrich match escrows: {}", e.getMessage());
            }
        }

        for (GameResultDocument doc : results) {
            boolean isTournamentTable = doc.getTableId() != null && doc.getTableId().startsWith("TBL_TRN_");
            if (isTournamentTable || doc.getNetAmountRs() != null || doc.getGameId() == null) {
                continue;
            }
            String gameId = doc.getGameId();
            BigDecimal settledInitialStake = BigDecimal.ZERO;
            for (StakeEscrowDocument e : escrowsByGameId.getOrDefault(gameId, Collections.emptyList())) {
                if (e.getId() != null && !e.getId().startsWith("REJOIN_FEE_") && "SETTLED".equals(e.getStatus())) {
                    settledInitialStake = settledInitialStake.add(BigDecimal.valueOf(e.getAmount()));
                }
            }

            BigDecimal rejoinDebits = BigDecimal.ZERO;
            BigDecimal winCredits = BigDecimal.ZERO;
            BigDecimal refundCredits = BigDecimal.ZERO;
            BigDecimal winNetPrizeMeta = null;
            Integer refundPenaltyMeta = null;
            BigDecimal refundAmountMeta = null;

            for (WalletTransactionDocument tx : txByGameId.getOrDefault(gameId, Collections.emptyList())) {
                String type = tx.getTransactionType() != null ? tx.getTransactionType().toUpperCase(Locale.ROOT) : "";
                BigDecimal amt = tx.getAmount() != null ? tx.getAmount() : BigDecimal.ZERO;
                if ("REJOIN_FEE".equals(type)) {
                    rejoinDebits = rejoinDebits.add(amt);
                } else if ("GAME_WIN".equals(type)) {
                    winCredits = winCredits.add(amt);
                    if (tx.getMetadata() != null) {
                        winNetPrizeMeta = parseBigDecimal(tx.getMetadata().get("netPrize"));
                    }
                } else if (type.contains("REFUND")) {
                    refundCredits = refundCredits.add(amt);
                    if ("GAME_REFUND".equals(type) && tx.getMetadata() != null) {
                        BigDecimal pen = parseBigDecimal(tx.getMetadata().get("penalty"));
                        BigDecimal ref = parseBigDecimal(tx.getMetadata().get("refund"));
                        if (pen != null) refundPenaltyMeta = pen.intValue();
                        if (ref != null) refundAmountMeta = ref;
                    }
                }
            }

            BigDecimal net = null;
            if (settledInitialStake.compareTo(BigDecimal.ZERO) > 0) {
                net = winCredits.add(refundCredits).subtract(settledInitialStake).subtract(rejoinDebits);
            } else if (doc.isWon() && winNetPrizeMeta != null && isPointsBasedRuleset(doc.getRulesetId())) {
                net = winNetPrizeMeta;
            } else if (!doc.isWon() && refundPenaltyMeta != null && refundAmountMeta != null
                    && refundAmountMeta.compareTo(BigDecimal.ZERO) > 0 && isPointsBasedRuleset(doc.getRulesetId())) {
                int cap = is21CardRuleset(doc.getRulesetId()) ? 120 : 80;
                int pen = refundPenaltyMeta;
                if (pen > 0 && pen < cap) {
                    net = refundAmountMeta.multiply(BigDecimal.valueOf(pen))
                            .divide(BigDecimal.valueOf(cap - pen), 2, RoundingMode.HALF_UP)
                            .negate();
                }
            }

            if (net != null) {
                doc.setNetAmountRs(net.setScale(2, RoundingMode.HALF_UP).doubleValue());
            }
        }
        return results;
    }

    private static boolean is21CardRuleset(String rulesetId) {
        if (rulesetId == null) return false;
        String upper = rulesetId.toUpperCase(Locale.ROOT);
        return upper.contains("21");
    }

    private static boolean isPointsBasedRuleset(String rulesetId) {
        if (rulesetId == null) return false;
        String upper = rulesetId.toUpperCase(Locale.ROOT);
        return upper.contains("POINT") || upper.contains("21");
    }

    private static BigDecimal parseBigDecimal(Object raw) {
        if (raw == null) return null;
        if (raw instanceof BigDecimal bd) return bd;
        if (raw instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Optional<GameDocument> getGame(String gameId) {
        return gameRepository.findById(gameId);
    }

    public List<GameEventDocument> getGameEvents(String gameId) {
        return gameEventRepository.findByGameIdOrderBySequenceAsc(gameId);
    }

    public UserProfileDocument getOrCreateUserProfile(String userId, String displayName) {
        return userProfileRepository.findByUserId(userId).orElseGet(() -> {
            UserProfileDocument newProfile = new UserProfileDocument(
                    userId,
                    displayName != null ? displayName : userId,
                    10000L // default 10,000 virtual points
            );
            return userProfileRepository.save(newProfile);
        });
    }

    public Optional<UserProfileDocument> getUserProfile(String userId) {
        return userProfileRepository.findByUserId(userId);
    }

    @PreDestroy
    public void shutdown() {
        flushEvents(); // Final flush before shutdown
        flusher.shutdown();
        asyncWriter.shutdown();
    }
}
