package com.rummy.gameservice.tournament;

import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.command.ReadyCommand;
import com.rummy.engine.model.PlayerState;
import com.rummy.engine.rules.DealsRummyRules;
import com.rummy.engine.rules.PointsRummyRules;
import com.rummy.engine.rules.RulesetRegistry;
import com.rummy.engine.rules.RummyRules;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.persistence.document.GameDocument;
import com.rummy.gameservice.persistence.repository.GameRepository;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.wallet.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates 6-player Deals Rummy (3 Deals) multi-table tournaments:
 * - Entry fee debit & auto-refund via Seamless WalletService
 * - Auto-start / auto-cancel scheduler when startsAt is reached
 * - Dynamic table balancing for arbitrary player counts (e.g. 75, 78, 80, 120)
 * - Multi-round progression (table winners + highest-chip wildcards -> multiple of 6)
 * - Fixed prize pool distribution to final winners
 */
@Service
public class TournamentService {

    private static final Logger log = LoggerFactory.getLogger(TournamentService.class);

    private final TournamentRepository tournamentRepository;
    private final TableManager tableManager;
    private final TableRoutingRegistry routingRegistry;
    private final WalletService walletService;

    /** Maps playerId -> currently assigned active tournament tableId for auto-seating */
    private final Map<String, String> playerActiveTable = new ConcurrentHashMap<>();
    private ClusterNodeService clusterNodes;
    private GameRepository gameRepository;

    public TournamentService(
            TournamentRepository tournamentRepository,
            TableManager tableManager,
            @Autowired(required = false) TableRoutingRegistry routingRegistry,
            @Autowired(required = false) WalletService walletService) {
        this.tournamentRepository = tournamentRepository;
        this.tableManager = tableManager;
        this.routingRegistry = routingRegistry;
        this.walletService = walletService;
    }

    @Autowired(required = false)
    public void setClusterNodes(ClusterNodeService clusterNodes) {
        this.clusterNodes = clusterNodes;
    }

    @Autowired(required = false)
    public void setGameRepository(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    // ── Public Query ──────────────────────────────────────────────────────────

    public Optional<String> getTournamentVariant(String tournamentId) {
        if (tournamentId == null || tournamentId.isBlank()) {
            return Optional.empty();
        }
        return tournamentRepository.findByTournamentId(tournamentId)
                .map(t -> (t.getVariant() != null && !t.getVariant().isBlank()) ? t.getVariant() : "DEALS_3");
    }

    public List<Map<String, Object>> listPublicTournaments(String playerId) {
        return listPublicTournaments(playerId, null);
    }

    public List<Map<String, Object>> listPublicTournaments(String playerId, String variant) {
        List<TournamentDocument> docs = tournamentRepository.findByStatusInOrderByStartsAtAsc(
                List.of("REGISTERING", "STARTING", "RUNNING", "COMPLETED", "CANCELLED")
        );

        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        List<Map<String, Object>> result = new ArrayList<>();
        for (TournamentDocument t : docs) {
            String docVariant = (t.getVariant() != null && !t.getVariant().isBlank()) ? t.getVariant() : "DEALS_3";
            if (variant != null && !variant.isBlank() && !variant.equalsIgnoreCase(docVariant)) {
                continue;
            }
            boolean isRegistered = playerId != null && t.getRegistrations().stream()
                    .anyMatch(r -> playerId.equals(r.getPlayerId()));
            if ("CANCELLED".equals(t.getStatus()) || "COMPLETED".equals(t.getStatus())) {
                // Only show completed/cancelled tournaments to players who actually registered (within 7 days)
                if (!isRegistered || (t.getStartsAt() != null && t.getStartsAt().isBefore(cutoff))) {
                    continue;
                }
            }
            boolean isCheckedIn = playerId != null && t.getCheckedInPlayerIds().contains(playerId);
            String activeTbl = playerId != null
                    ? playerActiveTable.getOrDefault(playerId, t.getPlayerActiveTables() != null ? t.getPlayerActiveTables().get(playerId) : null)
                    : null;
            String assignedTableId = isRegistered && isCheckedIn && "RUNNING".equals(t.getStatus())
                    ? activeTbl
                    : null;

            Integer myRank = null;
            double myPrizeRs = 0.0;
            if ("COMPLETED".equals(t.getStatus()) && playerId != null && t.getFinalRankings() != null) {
                for (TournamentDocument.FinalRanking fr : t.getFinalRankings()) {
                    if (playerId.equals(fr.getPlayerId())) {
                        myRank = fr.getRank();
                        myPrizeRs = fr.getPrizeRs();
                        break;
                    }
                }
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tournamentId", t.getTournamentId());
            item.put("title", t.getTitle());
            item.put("variant", t.getVariant());
            item.put("entryFeeRs", t.getEntryFeeRs());
            item.put("prizePoolRs", t.getFixedPrizeRs());
            item.put("isGuaranteed", true);
            item.put("minPlayers", t.getMinPlayers());
            item.put("maxPlayers", t.getMaxPlayers());
            item.put("registered", t.getRegistrations().size());
            item.put("checkedInCount", t.getCheckedInPlayerIds().size());
            item.put("checkInWindowMinutes", t.getCheckInWindowMinutes());
            item.put("checkInEndsAt", t.getCheckInEndsAt());
            item.put("registrationStartsAt", t.getRegistrationStartsAt());
            item.put("registrationEndsAt", t.getRegistrationEndsAt());
            item.put("startsAt", t.getStartsAt());
            item.put("status", t.getStatus());
            item.put("isRegistered", isRegistered);
            item.put("isCheckedIn", isCheckedIn);
            if (myRank != null) {
                item.put("myRank", myRank);
            }
            item.put("myPrizeRs", myPrizeRs);
            if (t.getCancelledReason() != null) {
                item.put("cancelledReason", t.getCancelledReason());
            }
            if (assignedTableId != null) {
                item.put("assignedTableId", assignedTableId);
            }
            result.add(item);
        }
        return result;
    }

    /**
     * Player-scoped tournament history across all statuses (no 7-day cutoff),
     * optionally filtered by comma-separated variant ruleset IDs.
     */
    public List<Map<String, Object>> listMyTournaments(String playerId, String variantsCsv) {
        if (playerId == null || playerId.isBlank()) {
            return List.of();
        }
        Set<String> allowedVariants = new HashSet<>();
        if (variantsCsv != null && !variantsCsv.isBlank()) {
            for (String token : variantsCsv.split(",")) {
                String trimmed = token.trim().toUpperCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    allowedVariants.add(trimmed);
                }
            }
        }

        List<TournamentDocument> docs = tournamentRepository.findByRegistrationsPlayerIdOrderByStartsAtDesc(playerId);
        if (docs == null) {
            docs = tournamentRepository.findByStatusInOrderByStartsAtAsc(
                    List.of("REGISTERING", "STARTING", "RUNNING", "COMPLETED", "CANCELLED")
            );
        }

        return docs.stream()
                .filter(t -> t.getRegistrations().stream().anyMatch(r -> playerId.equals(r.getPlayerId())))
                .filter(t -> {
                    if (allowedVariants.isEmpty()) return true;
                    String docVariant = (t.getVariant() != null && !t.getVariant().isBlank())
                            ? t.getVariant().trim().toUpperCase(Locale.ROOT)
                            : "DEALS_3";
                    return allowedVariants.contains(docVariant);
                })
                .sorted((a, b) -> {
                    Instant ia = a.getStartsAt() != null ? a.getStartsAt() : Instant.EPOCH;
                    Instant ib = b.getStartsAt() != null ? b.getStartsAt() : Instant.EPOCH;
                    return ib.compareTo(ia);
                })
                .limit(50)
                .map(t -> {
                    String docVariant = (t.getVariant() != null && !t.getVariant().isBlank())
                            ? t.getVariant()
                            : "DEALS_3";
                    Integer myRank = null;
                    double myPrizeRs = 0.0;
                    String winnerName = null;
                    if (t.getFinalRankings() != null && !t.getFinalRankings().isEmpty()) {
                        for (TournamentDocument.FinalRanking fr : t.getFinalRankings()) {
                            if (fr.getRank() == 1 && winnerName == null) {
                                winnerName = fr.getDisplayName();
                            }
                            if (playerId.equals(fr.getPlayerId())) {
                                myRank = fr.getRank();
                                myPrizeRs = fr.getPrizeRs();
                            }
                        }
                    }

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("tournamentId", t.getTournamentId());
                    item.put("title", t.getTitle());
                    item.put("variant", docVariant);
                    item.put("status", t.getStatus());
                    item.put("entryFeeRs", t.getEntryFeeRs());
                    item.put("prizePoolRs", t.getFixedPrizeRs());
                    item.put("startsAt", t.getStartsAt());
                    item.put("registered", t.getRegistrations().size());
                    item.put("maxPlayers", t.getMaxPlayers());
                    item.put("isCheckedIn", t.getCheckedInPlayerIds().contains(playerId));
                    item.put("roundsCount", t.getRounds() != null ? t.getRounds().size() : 0);
                    if (myRank != null) {
                        item.put("myRank", myRank);
                    }
                    item.put("myPrizeRs", myPrizeRs);
                    if (winnerName != null) {
                        item.put("winnerDisplayName", winnerName);
                    }
                    if (t.getCancelledReason() != null) {
                        item.put("cancelledReason", t.getCancelledReason());
                    }
                    return item;
                })
                .toList();
    }

    public Map<String, Object> getTournamentStatusByTable(String tableId, String playerId) {
        if (tableId == null || !tableId.startsWith("TBL_TRN_")) {
            throw new IllegalArgumentException("Not a tournament table: " + tableId);
        }
        // Format: TBL_TRN_<tournamentId>_R<round>_<idx>
        String remainder = tableId.substring("TBL_TRN_".length());
        int rIdx = remainder.lastIndexOf("_R");
        String tournamentId = rIdx > 0 ? remainder.substring(0, rIdx) : remainder;

        TournamentDocument t = tournamentRepository.findByTournamentId(tournamentId)
                .orElseThrow(() -> new IllegalArgumentException("Tournament not found: " + tournamentId));

        int roundNumber = 1;
        boolean roundDone = false;
        boolean qualified = false;
        int totalTablesInRound = 1;
        int finishedTables = 0;
        for (TournamentDocument.RoundRecord rr : t.getRounds()) {
            if (rr.getTableIds().contains(tableId)) {
                roundNumber = rr.getRoundNumber();
                roundDone = "DONE".equals(rr.getStatus());
                totalTablesInRound = Math.max(1, rr.getTableIds().size());
                if (roundDone) {
                    finishedTables = totalTablesInRound;
                } else {
                    for (String tid : rr.getTableIds()) {
                        boolean savedOutcome = rr.getTableOutcomes() != null && rr.getTableOutcomes().containsKey(tid);
                        if (savedOutcome) {
                            finishedTables++;
                            continue;
                        }
                        Optional<TableActor> a = tableManager.getTable(tid);
                        if (a.isPresent() && a.get().getTournamentWinnerId() != null) {
                            finishedTables++;
                        }
                    }
                }
                if (playerId != null && rr.getQualifiedPlayerIds().contains(playerId)) {
                    qualified = true;
                }
                break;
            }
        }

        String currentAssigned = playerId != null
                ? playerActiveTable.getOrDefault(playerId, t.getPlayerActiveTables() != null ? t.getPlayerActiveTables().get(playerId) : null)
                : null;
        String nextTableId = (currentAssigned != null && !currentAssigned.equals(tableId)) ? currentAssigned : null;

        Integer myRank = null;
        double myPrizeRs = 0.0;
        if (playerId != null && t.getFinalRankings() != null) {
            for (TournamentDocument.FinalRanking fr : t.getFinalRankings()) {
                if (playerId.equals(fr.getPlayerId())) {
                    myRank = fr.getRank();
                    myPrizeRs = fr.getPrizeRs();
                    break;
                }
            }
        }

        String docVariant = (t.getVariant() != null && !t.getVariant().isBlank()) ? t.getVariant() : "DEALS_3";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("tournamentId", t.getTournamentId());
        out.put("title", t.getTitle());
        out.put("variant", docVariant);
        out.put("status", t.getStatus());
        out.put("fixedPrizeRs", t.getFixedPrizeRs());
        out.put("roundNumber", roundNumber);
        out.put("totalRounds", t.getRounds().size());
        out.put("isFinalTable", totalTablesInRound == 1);
        out.put("finishedTables", finishedTables);
        out.put("totalTablesInRound", totalTablesInRound);
        out.put("roundDone", roundDone);
        out.put("qualified", qualified);
        out.put("nextTableId", nextTableId);
        out.put("finalRankings", t.getFinalRankings() != null ? t.getFinalRankings() : List.of());
        out.put("myRank", myRank);
        out.put("myPrizeRs", myPrizeRs);
        return out;
    }

    // ── Player Registration + Seamless Wallet Debit ───────────────────────────

    public synchronized Map<String, Object> registerPlayer(
            String tournamentId, String playerId, String displayName, String operatorId) {

        TournamentDocument t = tournamentRepository.findByTournamentId(tournamentId)
                .orElseThrow(() -> new IllegalArgumentException("Tournament not found: " + tournamentId));

        if (!"REGISTERING".equals(t.getStatus())) {
            throw new IllegalStateException("Registration is not open (status: " + t.getStatus() + ")");
        }
        Instant now = Instant.now();
        if (t.getRegistrationStartsAt() != null && now.isBefore(t.getRegistrationStartsAt())) {
            throw new IllegalStateException("Registration has not opened yet.");
        }
        if (t.getRegistrationEndsAt() != null && now.isAfter(t.getRegistrationEndsAt())) {
            throw new IllegalStateException("Registration is closed.");
        }
        if (t.getRegistrations().size() >= t.getMaxPlayers()) {
            throw new IllegalStateException("Tournament is already full.");
        }
        boolean already = t.getRegistrations().stream().anyMatch(r -> playerId.equals(r.getPlayerId()));
        if (already) {
            throw new IllegalStateException("Already registered for this tournament.");
        }

        String txnId = "TRN_ENTRY_" + tournamentId + "_" + playerId;
        if (t.getEntryFeeRs() > 0 && walletService != null) {
            walletService.debit(
                    playerId,
                    BigDecimal.valueOf(t.getEntryFeeRs()),
                    "TOURNAMENT_ENTRY",
                    txnId,
                    tournamentId,
                    "Tournament Entry: " + t.getTitle(),
                    Map.of("tournamentId", tournamentId)
            );
        }

        TournamentDocument.Registration reg = new TournamentDocument.Registration();
        reg.setPlayerId(playerId);
        reg.setDisplayName(displayName != null && !displayName.isBlank() ? displayName : playerId);
        reg.setOperatorId(operatorId != null && !operatorId.isBlank() ? operatorId : "DEFAULT");
        reg.setRegisteredAt(Instant.now());
        reg.setEntryTxnId(txnId);
        reg.setRefunded(false);

        t.getRegistrations().add(reg);
        t.setGrossPoolRs(t.getRegistrations().size() * t.getEntryFeeRs());
        t.setPlatformProfitRs(t.getGrossPoolRs() - t.getFixedPrizeRs());

        tournamentRepository.save(t);
        log.info("[Tournament] Player {} registered for {} ({}/{})",
                playerId, tournamentId, t.getRegistrations().size(), t.getMaxPlayers());

        return Map.of(
                "success", true,
                "message", "Registration successful.",
                "registered", t.getRegistrations().size(),
                "maxPlayers", t.getMaxPlayers()
        );
    }

    // ── Player Check-In (Waiting Lobby between startsAt and checkInEndsAt) ───

    public synchronized Map<String, Object> checkInPlayer(String tournamentId, String playerId) {
        TournamentDocument t = tournamentRepository.findByTournamentId(tournamentId)
                .orElseThrow(() -> new IllegalArgumentException("Tournament not found: " + tournamentId));

        if (!"STARTING".equals(t.getStatus())) {
            throw new IllegalStateException("Check-in is not open right now (status: " + t.getStatus() + ").");
        }
        if (t.getCheckInEndsAt() != null && Instant.now().isAfter(t.getCheckInEndsAt())) {
            throw new IllegalStateException("Check-in window has closed. You cannot join now.");
        }
        boolean registered = t.getRegistrations().stream().anyMatch(r -> playerId.equals(r.getPlayerId()));
        if (!registered) {
            throw new IllegalStateException("Only registered players can join this tournament.");
        }

        if (!t.getCheckedInPlayerIds().contains(playerId)) {
            t.getCheckedInPlayerIds().add(playerId);
            tournamentRepository.save(t);
            log.info("[Tournament] Player {} checked in to {} ({} checked in)",
                    playerId, tournamentId, t.getCheckedInPlayerIds().size());
        }

        return Map.of(
                "success", true,
                "message", "Joined waiting lobby.",
                "checkedInCount", t.getCheckedInPlayerIds().size(),
                "checkInEndsAt", t.getCheckInEndsAt()
        );
    }

    // ── Scheduler Loop (every 3s) ─────────────────────────────────────────────

    @Scheduled(fixedDelay = 3000)
    public void tickTournaments() {
        try {
            processScheduledStarts();
            processCheckInCompletions();
            processCancelledRefunds();
            processRunningRounds();
            processPendingPrizePayouts();
        } catch (Exception e) {
            log.error("[Tournament] Scheduler tick error", e);
        }
    }

    private void processScheduledStarts() {
        List<TournamentDocument> registering = tournamentRepository.findByStatus("REGISTERING");
        Instant now = Instant.now();

        for (TournamentDocument t : registering) {
            if (t.getStartsAt() == null || t.getStartsAt().isAfter(now)) {
                continue;
            }
            int count = t.getRegistrations().size();
            if (count < t.getMinPlayers()) {
                log.info("[Tournament] {} has {}/{} players (< minPlayers). Auto-cancelling.",
                        t.getTournamentId(), count, t.getMinPlayers());
                t.setStatus("CANCELLED");
                t.setCancelledReason("Minimum players (" + t.getMinPlayers() + ") not met.");
                clearTournamentActiveTables(t);
                refundAllUnrefunded(t);
                tournamentRepository.save(t);
            } else {
                int windowMins = Math.max(1, t.getCheckInWindowMinutes());
                Instant checkInEnd = t.getStartsAt().plus(windowMins, ChronoUnit.MINUTES);
                log.info("[Tournament] {} opening {}-min Check-In lobby until {} ({} registered).",
                        t.getTournamentId(), windowMins, checkInEnd, count);
                t.setStatus("STARTING");
                t.setCheckInEndsAt(checkInEnd);
                tournamentRepository.save(t);
            }
        }
    }

    private void processCheckInCompletions() {
        List<TournamentDocument> starting = tournamentRepository.findByStatus("STARTING");
        Instant now = Instant.now();

        for (TournamentDocument t : starting) {
            if (t.getCheckInEndsAt() == null || t.getCheckInEndsAt().isAfter(now)) {
                continue;
            }
            List<String> checkedIn = new ArrayList<>(t.getCheckedInPlayerIds());
            if (checkedIn.size() < 2) {
                String reason = checkedIn.isEmpty()
                        ? "No registered players checked in before match start."
                        : "Fewer than 2 players checked in before match start.";
                log.info("[Tournament] {} had {} checked-in player(s) by {}. Cancelling and refunding.",
                        t.getTournamentId(), checkedIn.size(), t.getCheckInEndsAt());
                t.setStatus("CANCELLED");
                t.setCancelledReason(reason);
                clearTournamentActiveTables(t);
                refundAllUnrefunded(t);
                tournamentRepository.save(t);
            } else {
                log.info("[Tournament] {} starting Round 1 with {}/{} checked-in players!",
                        t.getTournamentId(), checkedIn.size(), t.getRegistrations().size());
                t.setStatus("RUNNING");
                startRound(t, 1, checkedIn);
                tournamentRepository.save(t);
            }
        }
    }

    private void processCancelledRefunds() {
        List<TournamentDocument> cancelled = tournamentRepository.findByStatus("CANCELLED");
        for (TournamentDocument t : cancelled) {
            boolean anyPending = t.getRegistrations().stream().anyMatch(r -> !r.isRefunded());
            if (anyPending) {
                refundAllUnrefunded(t);
                tournamentRepository.save(t);
            }
        }
    }

    private void refundAllUnrefunded(TournamentDocument t) {
        if (t.getEntryFeeRs() <= 0 || walletService == null) {
            t.getRegistrations().forEach(r -> r.setRefunded(true));
            return;
        }
        for (TournamentDocument.Registration reg : t.getRegistrations()) {
            if (!reg.isRefunded()) {
                try {
                    String refundKey = "TRN_REFUND_" + t.getTournamentId() + "_" + reg.getPlayerId();
                    walletService.credit(
                            reg.getPlayerId(),
                            BigDecimal.valueOf(t.getEntryFeeRs()),
                            "TOURNAMENT_REFUND",
                            refundKey,
                            t.getTournamentId(),
                            "Tournament Cancelled Refund: " + t.getTitle(),
                            Map.of("tournamentId", t.getTournamentId())
                    );
                    reg.setRefunded(true);
                } catch (Exception e) {
                    log.error("[Tournament] Failed refund for player={} in {}",
                            reg.getPlayerId(), t.getTournamentId(), e);
                }
            }
        }
    }

    private void processRunningRounds() {
        List<TournamentDocument> running = tournamentRepository.findByStatus("RUNNING");
        Instant now = Instant.now();
        for (TournamentDocument t : running) {
            if (t.getRounds().isEmpty()) continue;
            TournamentDocument.RoundRecord currentRound = t.getRounds().get(t.getRounds().size() - 1);
            if (!"RUNNING".equals(currentRound.getStatus())) continue;
            if (currentRound.getTableOutcomes() == null) {
                currentRound.setTableOutcomes(new LinkedHashMap<>());
            }

            boolean allTablesFinished = true;
            boolean roundModified = false;
            boolean aborted = false;

            for (String tableId : currentRound.getTableIds()) {
                if (currentRound.getTableOutcomes().containsKey(tableId)) {
                    continue;
                }
                Optional<TableActor> actorOpt = tableManager.getTable(tableId);
                if (actorOpt.isPresent()) {
                    TableActor actor = actorOpt.get();
                    if (actor.getTournamentWinnerId() != null) {
                        currentRound.getTableOutcomes().put(tableId, buildOutcomeFromActor(tableId, actor));
                        actor.markTournamentResultRecorded();
                        roundModified = true;
                    } else {
                        allTablesFinished = false;
                    }
                    continue;
                }

                // Missing from local TableManager: verify if hosted on another live node or completed in DB
                if (routingRegistry != null && routingRegistry.isOwnedByRemoteServer(tableId)) {
                    String owner = routingRegistry.getServerForTable(tableId).orElse(null);
                    if (owner != null && (clusterNodes == null || clusterNodes.isAlive(owner))) {
                        allTablesFinished = false;
                        continue;
                    }
                }

                TournamentDocument.RoundTableOutcome dbOutcome = tryLoadOutcomeFromDb(t, tableId);
                if (dbOutcome != null) {
                    currentRound.getTableOutcomes().put(tableId, dbOutcome);
                    roundModified = true;
                    continue;
                }

                Instant started = currentRound.getStartedAt();
                if (started != null && now.isAfter(started.plusSeconds(60))) {
                    log.warn("[Tournament] {} Round {} table {} vanished unexpectedly; cancelling and refunding.",
                            t.getTournamentId(), currentRound.getRoundNumber(), tableId);
                    t.setStatus("CANCELLED");
                    t.setCancelledReason("Tournament table closed unexpectedly during Round " + currentRound.getRoundNumber() + ".");
                    clearTournamentActiveTables(t);
                    refundAllUnrefunded(t);
                    tournamentRepository.save(t);
                    aborted = true;
                    break;
                }
                allTablesFinished = false;
            }

            if (aborted) {
                continue;
            }
            if (allTablesFinished) {
                advanceOrCompleteTournament(t, currentRound);
            } else if (roundModified) {
                tournamentRepository.save(t);
            }
        }
    }

    private void processPendingPrizePayouts() {
        if (walletService == null) {
            return;
        }
        List<TournamentDocument> completed = tournamentRepository.findByStatus("COMPLETED");
        if (completed == null) {
            return;
        }
        for (TournamentDocument t : completed) {
            if (t.getFinalRankings() == null || t.getFinalRankings().isEmpty()) {
                continue;
            }
            boolean hasPending = t.getFinalRankings().stream()
                    .anyMatch(fr -> fr.getPrizeRs() > 0 && !fr.isPrizeCredited());
            if (hasPending && creditPendingTournamentPrizes(t)) {
                tournamentRepository.save(t);
            }
        }
    }

    // ── Dynamic Table Balancing & Round Progression ───────────────────────────

    private void startRound(TournamentDocument t, int roundNum, List<String> playerIds) {
        clearTournamentActiveTables(t);
        if (t.getPlayerActiveTables() == null) {
            t.setPlayerActiveTables(new LinkedHashMap<>());
        }

        List<String> shuffled = new ArrayList<>(playerIds);
        Collections.shuffle(shuffled);

        // Build balanced 5-6 player tables for any player count (e.g. 75, 78, 80, 120)
        int total = shuffled.size();
        int numTables = Math.max(1, (int) Math.ceil(total / 6.0));
        int baseSize = total / numTables;
        int remainder = total % numTables;

        Map<String, String> displayNames = new HashMap<>();
        for (TournamentDocument.Registration reg : t.getRegistrations()) {
            displayNames.put(reg.getPlayerId(), reg.getDisplayName());
        }

        String variant = (t.getVariant() != null && !t.getVariant().isBlank()) ? t.getVariant() : "DEALS_3";
        RummyRules rules = RulesetRegistry.getRuleset(variant)
                .orElseGet(() -> "POINTS_13".equalsIgnoreCase(variant)
                        ? new PointsRummyRules()
                        : new DealsRummyRules(3));
        List<String> tableIds = new ArrayList<>();
        int idx = 0;

        for (int i = 0; i < numTables; i++) {
            int seatCount = baseSize + (i < remainder ? 1 : 0);
            List<String> tablePlayers = shuffled.subList(idx, idx + seatCount);
            idx += seatCount;

            String tableId = "TBL_TRN_" + t.getTournamentId() + "_R" + roundNum + "_" + (i + 1);
            TableActor actor = tableManager.getOrCreateTable(tableId, rules);
            if (routingRegistry != null) {
                routingRegistry.registerTableOwnership(tableId);
            }
            actor.setExpectedPlayers(seatCount);
            actor.setStakeTier(0); // Settlement handled by TournamentService at final round

            for (int seat = 0; seat < tablePlayers.size(); seat++) {
                String pid = tablePlayers.get(seat);
                String name = displayNames.getOrDefault(pid, pid);
                if (routingRegistry != null) {
                    routingRegistry.registerPlayerTable(pid, tableId);
                }
                playerActiveTable.put(pid, tableId);
                t.getPlayerActiveTables().put(pid, tableId);

                actor.processCommand(new JoinCommand(
                        UUID.randomUUID().toString(),
                        actor.getState().getGameId(),
                        pid,
                        name,
                        seat,
                        false,
                        Instant.now()
                ), "TRN_SEAT");

                actor.processCommand(new ReadyCommand(
                        UUID.randomUUID().toString(),
                        actor.getState().getGameId(),
                        pid,
                        Instant.now()
                ), "TRN_READY");
            }

            tableIds.add(tableId);
            log.info("[Tournament] {} Round {} created table {} with {} players",
                    t.getTournamentId(), roundNum, tableId, seatCount);
        }

        TournamentDocument.RoundRecord roundRecord = new TournamentDocument.RoundRecord();
        roundRecord.setRoundNumber(roundNum);
        roundRecord.setTableIds(tableIds);
        roundRecord.setStatus("RUNNING");
        roundRecord.setStartedAt(Instant.now());
        t.getRounds().add(roundRecord);
    }

    private void advanceOrCompleteTournament(TournamentDocument t, TournamentDocument.RoundRecord currentRound) {
        if (currentRound.getTableOutcomes() == null) {
            currentRound.setTableOutcomes(new LinkedHashMap<>());
        }
        for (String tableId : currentRound.getTableIds()) {
            tableManager.getTable(tableId).ifPresent(actor -> {
                if (!currentRound.getTableOutcomes().containsKey(tableId) && actor.getTournamentWinnerId() != null) {
                    currentRound.getTableOutcomes().put(tableId, buildOutcomeFromActor(tableId, actor));
                }
                actor.markTournamentResultRecorded();
            });
        }

        currentRound.setStatus("DONE");
        currentRound.setFinishedAt(Instant.now());

        // Final table reached (only 1 table in this round)
        if (currentRound.getTableIds().size() == 1) {
            String finalTableId = currentRound.getTableIds().get(0);
            finalizeTournament(t, currentRound, finalTableId);
            return;
        }

        // Collect rank-1 winners from each table and rank-2+ runners-up sorted by chips/score
        List<String> tableWinners = new ArrayList<>();
        List<PlayerScore> runnersUp = new ArrayList<>();

        boolean isPoints = "POINTS_13".equalsIgnoreCase(t.getVariant());
        for (String tableId : currentRound.getTableIds()) {
            TournamentDocument.RoundTableOutcome outcome = currentRound.getTableOutcomes().get(tableId);
            if (outcome == null) {
                continue;
            }
            String winnerId = outcome.getWinnerId();
            if (winnerId != null) {
                tableWinners.add(winnerId);
            }
            List<TournamentDocument.TablePlayerResult> sorted = outcome.getPlayers().stream()
                    .filter(p -> !p.getPlayerId().equals(winnerId))
                    .sorted(isPoints
                            ? Comparator.comparingInt(TournamentDocument.TablePlayerResult::getScore)
                            : Comparator.comparingLong(TournamentDocument.TablePlayerResult::getChipBalance).reversed())
                    .toList();
            for (int r = 0; r < sorted.size(); r++) {
                TournamentDocument.TablePlayerResult runner = sorted.get(r);
                long tieMetric = isPoints ? -runner.getScore() : runner.getChipBalance();
                runnersUp.add(new PlayerScore(runner.getPlayerId(), tieMetric, r));
            }
        }

        runnersUp.sort(Comparator.comparingInt(PlayerScore::rankIndex)
                .thenComparing(Comparator.comparingLong(PlayerScore::chips).reversed()));

        // Target next round size = smallest multiple of 6 >= tableWinners.size()
        int winnerCount = tableWinners.size();
        int targetNextSize = Math.max(6, ((winnerCount + 5) / 6) * 6);
        int wildcardsNeeded = Math.max(0, targetNextSize - winnerCount);

        List<String> qualified = new ArrayList<>(tableWinners);
        for (int i = 0; i < Math.min(wildcardsNeeded, runnersUp.size()); i++) {
            qualified.add(runnersUp.get(i).playerId());
        }

        currentRound.setQualifiedPlayerIds(qualified);
        log.info("[Tournament] {} Round {} complete. {} winners + {} wildcards = {} advancing.",
                t.getTournamentId(), currentRound.getRoundNumber(),
                winnerCount, qualified.size() - winnerCount, qualified.size());

        startRound(t, currentRound.getRoundNumber() + 1, qualified);
        tournamentRepository.save(t);
    }

    private void finalizeTournament(TournamentDocument t, TournamentDocument.RoundRecord finalRound, String finalTableId) {
        Optional<TableActor> actorOpt = tableManager.getTable(finalTableId);
        TournamentDocument.RoundTableOutcome outcome = finalRound.getTableOutcomes().get(finalTableId);
        if (outcome == null && actorOpt.isPresent()) {
            outcome = buildOutcomeFromActor(finalTableId, actorOpt.get());
            finalRound.getTableOutcomes().put(finalTableId, outcome);
        }
        actorOpt.ifPresent(TableActor::markTournamentResultRecorded);
        clearTournamentActiveTables(t);

        String explicitWinnerId = outcome != null ? outcome.getWinnerId() : null;
        Set<String> actorTiedWinners = outcome != null && outcome.getTiedWinnerIds() != null
                ? new HashSet<>(outcome.getTiedWinnerIds())
                : Collections.emptySet();
        boolean isPoints = "POINTS_13".equalsIgnoreCase(t.getVariant());

        List<TournamentDocument.TablePlayerResult> rawPlayers = outcome != null && outcome.getPlayers() != null
                ? outcome.getPlayers()
                : Collections.emptyList();

        List<TournamentDocument.TablePlayerResult> finalists = rawPlayers.stream()
                .sorted(isPoints
                        ? Comparator.<TournamentDocument.TablePlayerResult>comparingInt(
                                        p -> p.getPlayerId().equals(explicitWinnerId) ? 0 : 1)
                                .thenComparingInt(TournamentDocument.TablePlayerResult::getScore)
                        : Comparator
                                .comparingLong(TournamentDocument.TablePlayerResult::getChipBalance).reversed()
                                .thenComparingInt(p -> p.getPlayerId().equals(explicitWinnerId) ? 0 : 1))
                .toList();

        Map<Integer, Double> pctByRank = new HashMap<>();
        if (t.getPrizes() != null && !t.getPrizes().isEmpty()) {
            for (TournamentDocument.PrizeTier tier : t.getPrizes()) {
                if (tier != null && tier.getRank() > 0 && tier.getPercentage() > 0) {
                    pctByRank.merge(tier.getRank(), tier.getPercentage(), Double::sum);
                }
            }
        }
        if (pctByRank.isEmpty()) {
            pctByRank.put(1, 100.0);
        }

        List<TournamentDocument.FinalRanking> rankings = new ArrayList<>();
        int idx = 0;
        while (idx < finalists.size()) {
            int startRank = idx + 1;
            TournamentDocument.TablePlayerResult first = finalists.get(idx);
            int endIdx = idx + 1;
            while (endIdx < finalists.size() && sameRankGroup(first, finalists.get(endIdx), isPoints, explicitWinnerId, actorTiedWinners)) {
                endIdx++;
            }
            int groupSize = endIdx - idx;
            double combinedPct = 0.0;
            for (int r = startRank; r < startRank + groupSize; r++) {
                combinedPct += pctByRank.getOrDefault(r, 0.0);
            }
            double groupEachPrize = 0.0;
            if (combinedPct > 0.0 && t.getFixedPrizeRs() > 0) {
                groupEachPrize = BigDecimal.valueOf(t.getFixedPrizeRs())
                        .multiply(BigDecimal.valueOf(combinedPct))
                        .divide(BigDecimal.valueOf(100L * groupSize), 2, RoundingMode.HALF_UP)
                        .doubleValue();
            }

            for (int g = idx; g < endIdx; g++) {
                TournamentDocument.TablePlayerResult p = finalists.get(g);
                rankings.add(new TournamentDocument.FinalRanking(startRank, p.getPlayerId(), p.getDisplayName(), groupEachPrize));
            }
            idx = endIdx;
        }

        t.setFinalRankings(rankings);
        t.setStatus("COMPLETED");
        creditPendingTournamentPrizes(t);
        tournamentRepository.save(t);
        log.info("[Tournament] {} COMPLETED! Winner: {} (prize Rs {})",
                t.getTournamentId(),
                rankings.isEmpty() ? "none" : rankings.get(0).getPlayerId(),
                t.getFixedPrizeRs());
    }

    private boolean sameRankGroup(TournamentDocument.TablePlayerResult a,
                                  TournamentDocument.TablePlayerResult b,
                                  boolean isPoints,
                                  String explicitWinnerId,
                                  Set<String> actorTiedWinners) {
        if (isPoints) {
            boolean aWin = a.getPlayerId().equals(explicitWinnerId);
            boolean bWin = b.getPlayerId().equals(explicitWinnerId);
            if (aWin != bWin) {
                return false;
            }
            return a.getScore() == b.getScore();
        }
        if (a.getChipBalance() != b.getChipBalance()) {
            return false;
        }
        // Deals table ends with >1 player sharing top chips only when all tie-breakers stayed level.
        return actorTiedWinners.isEmpty()
                || (actorTiedWinners.contains(a.getPlayerId()) && actorTiedWinners.contains(b.getPlayerId()))
                || (!a.getPlayerId().equals(explicitWinnerId) && !b.getPlayerId().equals(explicitWinnerId));
    }

    private boolean creditPendingTournamentPrizes(TournamentDocument t) {
        if (walletService == null || t.getFinalRankings() == null) {
            return false;
        }
        boolean anyUpdated = false;
        for (TournamentDocument.FinalRanking fr : t.getFinalRankings()) {
            if (fr.getPrizeRs() > 0 && !fr.isPrizeCredited()) {
                try {
                    String key = "TRN_PRIZE_" + t.getTournamentId() + "_R" + fr.getRank() + "_" + fr.getPlayerId();
                    walletService.credit(
                            fr.getPlayerId(),
                            BigDecimal.valueOf(fr.getPrizeRs()),
                            "TOURNAMENT_PRIZE",
                            key,
                            t.getTournamentId(),
                            "Tournament Prize (Rank #" + fr.getRank() + "): " + t.getTitle(),
                            Map.of("tournamentId", t.getTournamentId(), "rank", fr.getRank())
                    );
                    fr.setPrizeCredited(true);
                    anyUpdated = true;
                } catch (Exception e) {
                    log.error("[Tournament] Failed prize credit for rank {} player={} in {}: {}",
                            fr.getRank(), fr.getPlayerId(), t.getTournamentId(), e.getMessage(), e);
                }
            }
        }
        return anyUpdated;
    }

    private void clearTournamentActiveTables(TournamentDocument t) {
        if (t.getRegistrations() != null) {
            for (TournamentDocument.Registration r : t.getRegistrations()) {
                playerActiveTable.remove(r.getPlayerId());
            }
        }
        if (t.getPlayerActiveTables() != null) {
            for (String pid : t.getPlayerActiveTables().keySet()) {
                playerActiveTable.remove(pid);
            }
            t.getPlayerActiveTables().clear();
        }
    }

    private TournamentDocument.RoundTableOutcome buildOutcomeFromActor(String tableId, TableActor actor) {
        TournamentDocument.RoundTableOutcome outcome = new TournamentDocument.RoundTableOutcome();
        outcome.setTableId(tableId);
        outcome.setWinnerId(actor.getTournamentWinnerId());
        outcome.setTiedWinnerIds(new ArrayList<>(actor.getTiedWinnerIds()));
        List<TournamentDocument.TablePlayerResult> players = new ArrayList<>();
        for (PlayerState ps : actor.getState().getPlayers()) {
            players.add(new TournamentDocument.TablePlayerResult(
                    ps.getPlayerId(),
                    ps.getDisplayName(),
                    ps.getScore(),
                    ps.getCumulativeScore(),
                    ps.getChipBalance()
            ));
        }
        outcome.setPlayers(players);
        return outcome;
    }

    private TournamentDocument.RoundTableOutcome tryLoadOutcomeFromDb(TournamentDocument t, String tableId) {
        if (gameRepository == null) {
            return null;
        }
        List<GameDocument> games = gameRepository.findByTableId(tableId);
        if (games == null || games.isEmpty()) {
            return null;
        }
        GameDocument completed = games.stream()
                .filter(g -> g != null && "COMPLETED".equalsIgnoreCase(g.getStatus()))
                .reduce((first, second) -> second)
                .orElse(null);
        if (completed == null || completed.getWinnerPlayerId() == null) {
            return null;
        }
        TournamentDocument.RoundTableOutcome outcome = new TournamentDocument.RoundTableOutcome();
        outcome.setTableId(tableId);
        outcome.setWinnerId(completed.getWinnerPlayerId());
        List<TournamentDocument.TablePlayerResult> players = new ArrayList<>();
        if (completed.getPlayers() != null) {
            for (GameDocument.PlayerSnapshot ps : completed.getPlayers()) {
                players.add(new TournamentDocument.TablePlayerResult(
                        ps.playerId(),
                        ps.displayName(),
                        ps.finalScore(),
                        ps.finalScore(),
                        ps.playerId().equals(completed.getWinnerPlayerId()) ? 240L : 0L
                ));
            }
        }
        outcome.setPlayers(players);
        return outcome;
    }

    private record PlayerScore(String playerId, long chips, int rankIndex) {}
}
