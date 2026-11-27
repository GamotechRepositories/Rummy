package com.rummy.gameservice.privateroom;

import com.rummy.engine.command.JoinCommand;
import com.rummy.engine.command.ReadyCommand;
import com.rummy.engine.model.PlayerState;
import com.rummy.engine.rules.PointsRummyRules;
import com.rummy.engine.rules.RummyRules;
import com.rummy.engine.rules.RulesetRegistry;
import com.rummy.gameservice.actor.TableActor;
import com.rummy.gameservice.actor.TableManager;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.matchmaking.StakeTiers;
import com.rummy.gameservice.persistence.document.GameDocument;
import com.rummy.gameservice.persistence.document.SettlementJobDocument;
import com.rummy.gameservice.persistence.repository.GameRepository;
import com.rummy.gameservice.routing.TableRoutingRegistry;
import com.rummy.gameservice.wallet.GameSettlementResult;
import com.rummy.gameservice.wallet.SettlementJobStore;
import com.rummy.gameservice.wallet.StakeEscrowService;
import com.rummy.gameservice.wallet.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Manages player-created Private Rooms across all Rummy variants.
 * Reuses TableActor stake/escrow/rake settlement so every variant settles identically to Cash Games.
 */
@Service
public class PrivateRoomService {

    private static final Logger log = LoggerFactory.getLogger(PrivateRoomService.class);
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RNG = new SecureRandom();
    private static final int ROOM_EXPIRY_MINUTES = 15;

    private final PrivateRoomRepository roomRepo;
    private final TableManager tableManager;
    private final WalletService walletService;
    private final TableRoutingRegistry routingRegistry;
    private StakeEscrowService escrows;
    private ClusterNodeService clusterNodes;
    private GameRepository gameRepo;
    private SettlementJobStore settlementJobs;

    public PrivateRoomService(
            PrivateRoomRepository roomRepo,
            TableManager tableManager,
            @Autowired(required = false) WalletService walletService,
            @Autowired(required = false) TableRoutingRegistry routingRegistry
    ) {
        this.roomRepo = roomRepo;
        this.tableManager = tableManager;
        this.walletService = walletService;
        this.routingRegistry = routingRegistry;
    }

    @Autowired(required = false)
    public void setEscrows(StakeEscrowService escrows) {
        this.escrows = escrows;
    }

    @Autowired(required = false)
    public void setClusterNodes(ClusterNodeService clusterNodes) {
        this.clusterNodes = clusterNodes;
    }

    @Autowired(required = false)
    public void setGameRepo(GameRepository gameRepo) {
        this.gameRepo = gameRepo;
    }

    @Autowired(required = false)
    public void setSettlementJobs(SettlementJobStore settlementJobs) {
        this.settlementJobs = settlementJobs;
    }

    public synchronized Map<String, Object> createRoom(
            String hostPlayerId,
            String hostDisplayName,
            String rawRulesetId,
            int requestedMaxPlayers,
            int stakeTier
    ) {
        String canonicalRuleset = StakeTiers.offeredRuleset(rawRulesetId)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported game variant: " + rawRulesetId));

        if (stakeTier != 0 && !StakeTiers.stakesFor(canonicalRuleset).contains(stakeTier)) {
            throw new IllegalArgumentException(
                    "Invalid stake for " + friendlyVariantName(canonicalRuleset)
                            + ". Choose Free (0) or one of: " + StakeTiers.stakesFor(canonicalRuleset));
        }

        ensureNoOtherWaitingRoom(hostPlayerId, null);

        int maxPlayers = ("DEALS_2".equals(canonicalRuleset) || "DEALS_RUMMY".equals(canonicalRuleset))
                ? 2
                : Math.max(2, Math.min(6, requestedMaxPlayers));

        String roomCode = generateUniqueCode();
        String tableId = "TBL_PRV_" + roomCode;
        String safeName = (hostDisplayName != null && !hostDisplayName.isBlank()) ? hostDisplayName : hostPlayerId;

        String escrowKey = "PRV_" + roomCode + "_" + hostPlayerId;
        escrowPlayerStake(hostPlayerId, stakeTier, escrowKey, canonicalRuleset, roomCode);

        PrivateRoomDocument doc = new PrivateRoomDocument();
        doc.setRoomCode(roomCode);
        doc.setTableId(tableId);
        doc.setHostPlayerId(hostPlayerId);
        doc.setHostDisplayName(safeName);
        doc.setRulesetId(canonicalRuleset);
        doc.setMaxPlayers(maxPlayers);
        doc.setStakeTier(stakeTier);
        doc.setStatus("WAITING");
        doc.setGrossCollectionRs(stakeTier);
        doc.setCreatedAt(Instant.now());
        doc.setExpiresAt(Instant.now().plus(ROOM_EXPIRY_MINUTES, ChronoUnit.MINUTES));

        PrivateRoomDocument.RoomPlayer host = new PrivateRoomDocument.RoomPlayer();
        host.setPlayerId(hostPlayerId);
        host.setDisplayName(safeName);
        host.setSeat(0);
        host.setEntryTxnId(escrowKey);
        host.setDebitedAmount(stakeTier);
        host.setRefunded(false);
        host.setJoinedAt(Instant.now());
        doc.getPlayers().add(host);

        roomRepo.save(doc);
        log.info("[PrivateRoom] Created room {} ({}, {}P, stake ₹{}) by {}",
                roomCode, canonicalRuleset, maxPlayers, stakeTier, hostPlayerId);

        return Map.of("success", true, "room", toView(doc, hostPlayerId));
    }

    public synchronized Map<String, Object> joinRoom(String rawCode, String playerId, String displayName) {
        return joinRoom(rawCode, playerId, displayName, null);
    }

    public synchronized Map<String, Object> joinRoom(
            String rawCode, String playerId, String displayName, Collection<String> allowedRulesets) {
        String roomCode = normalizeCode(rawCode);
        PrivateRoomDocument doc = roomRepo.findByRoomCode(roomCode)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomCode));

        if (allowedRulesets != null && !allowedRulesets.isEmpty()) {
            Set<String> allowedUpper = new HashSet<>();
            for (String rs : allowedRulesets) {
                if (rs != null && !rs.isBlank()) {
                    allowedUpper.add(rs.trim().toUpperCase(Locale.ROOT));
                }
            }
            String roomRuleset = doc.getRulesetId() != null ? doc.getRulesetId().toUpperCase(Locale.ROOT) : "";
            if (!allowedUpper.isEmpty() && !allowedUpper.contains(roomRuleset)) {
                throw new IllegalArgumentException(
                        "Room " + roomCode + " is for " + friendlyVariantName(roomRuleset)
                                + ". Please join it from the " + friendlyVariantName(roomRuleset) + " lobby.");
            }
        }

        if (!"WAITING".equals(doc.getStatus())) {
            throw new IllegalStateException("Room " + roomCode + " is no longer accepting players (" + doc.getStatus() + ").");
        }
        boolean alreadyIn = doc.getPlayers().stream().anyMatch(p -> p.getPlayerId().equals(playerId));
        if (alreadyIn) {
            return Map.of("success", true, "room", toView(doc, playerId));
        }
        ensureNoOtherWaitingRoom(playerId, roomCode);
        if (doc.getPlayers().size() >= doc.getMaxPlayers()) {
            throw new IllegalStateException("Room " + roomCode + " is full.");
        }

        String safeName = (displayName != null && !displayName.isBlank()) ? displayName : playerId;
        String escrowKey = "PRV_" + roomCode + "_" + playerId;
        escrowPlayerStake(playerId, doc.getStakeTier(), escrowKey, doc.getRulesetId(), roomCode);

        PrivateRoomDocument.RoomPlayer rp = new PrivateRoomDocument.RoomPlayer();
        rp.setPlayerId(playerId);
        rp.setDisplayName(safeName);
        rp.setSeat(doc.getPlayers().size());
        rp.setEntryTxnId(escrowKey);
        rp.setDebitedAmount(doc.getStakeTier());
        rp.setRefunded(false);
        rp.setJoinedAt(Instant.now());
        doc.getPlayers().add(rp);
        doc.setGrossCollectionRs(doc.getGrossCollectionRs() + doc.getStakeTier());

        roomRepo.save(doc);
        log.info("[PrivateRoom] Player {} joined room {} ({}/{})",
                playerId, roomCode, doc.getPlayers().size(), doc.getMaxPlayers());

        return Map.of("success", true, "room", toView(doc, playerId));
    }

    public synchronized Map<String, Object> leaveRoom(String rawCode, String playerId) {
        String roomCode = normalizeCode(rawCode);
        PrivateRoomDocument doc = roomRepo.findByRoomCode(roomCode)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomCode));

        if (!"WAITING".equals(doc.getStatus())) {
            throw new IllegalStateException("Cannot leave a room that is already " + doc.getStatus());
        }

        if (doc.getHostPlayerId().equals(playerId)) {
            doc.setStatus("CANCELLED");
            doc.setCancelledReason("Host closed the room before start.");
            doc.setFinishedAt(Instant.now());
            refundAllUnrefunded(doc, "Host closed private room");
            roomRepo.save(doc);
            return Map.of("success", true, "cancelled", true, "room", toView(doc, playerId));
        }

        Optional<PrivateRoomDocument.RoomPlayer> targetOpt = doc.getPlayers().stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst();
        if (targetOpt.isPresent()) {
            PrivateRoomDocument.RoomPlayer rp = targetOpt.get();
            refundSinglePlayer(doc, rp, "Left private room before start");
            doc.getPlayers().remove(rp);
            for (int i = 0; i < doc.getPlayers().size(); i++) {
                doc.getPlayers().get(i).setSeat(i);
            }
            doc.setGrossCollectionRs(Math.max(0, doc.getGrossCollectionRs() - rp.getDebitedAmount()));
            roomRepo.save(doc);
        }
        return Map.of("success", true, "room", toView(doc, playerId));
    }

    public synchronized Map<String, Object> kickPlayer(String rawCode, String hostPlayerId, String targetPlayerId) {
        String roomCode = normalizeCode(rawCode);
        PrivateRoomDocument doc = roomRepo.findByRoomCode(roomCode)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomCode));

        if (!doc.getHostPlayerId().equals(hostPlayerId)) {
            throw new IllegalStateException("Only the room host can kick players.");
        }
        if (!"WAITING".equals(doc.getStatus())) {
            throw new IllegalStateException("Players can only be kicked while the room is WAITING.");
        }
        if (hostPlayerId.equals(targetPlayerId)) {
            throw new IllegalArgumentException("Host cannot kick themselves. Use Close Room instead.");
        }

        PrivateRoomDocument.RoomPlayer target = doc.getPlayers().stream()
                .filter(p -> p.getPlayerId().equals(targetPlayerId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Player is not in this room."));

        refundSinglePlayer(doc, target, "Removed from private room by host");
        doc.getPlayers().remove(target);
        for (int i = 0; i < doc.getPlayers().size(); i++) {
            doc.getPlayers().get(i).setSeat(i);
        }
        doc.setGrossCollectionRs(Math.max(0, doc.getGrossCollectionRs() - target.getDebitedAmount()));
        roomRepo.save(doc);

        log.info("[PrivateRoom] Host {} kicked {} from room {}", hostPlayerId, targetPlayerId, roomCode);
        return Map.of("success", true, "room", toView(doc, hostPlayerId));
    }

    private void ensureNoOtherWaitingRoom(String playerId, String allowedRoomCode) {
        List<PrivateRoomDocument> mine = roomRepo.findByPlayersPlayerIdOrderByCreatedAtDesc(playerId);
        for (PrivateRoomDocument d : mine) {
            if ("WAITING".equals(d.getStatus())
                    && (allowedRoomCode == null || !allowedRoomCode.equals(d.getRoomCode()))) {
                throw new IllegalStateException(
                        "You are already in an active waiting room (" + d.getRoomCode() + "). Leave or close it first.");
            }
        }
    }

    public synchronized Map<String, Object> startRoom(String rawCode, String playerId) {
        String roomCode = normalizeCode(rawCode);
        PrivateRoomDocument doc = roomRepo.findByRoomCode(roomCode)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomCode));

        if (!doc.getHostPlayerId().equals(playerId)) {
            throw new IllegalStateException("Only the room host can start the game.");
        }
        if (!"WAITING".equals(doc.getStatus())) {
            throw new IllegalStateException("Room is already " + doc.getStatus());
        }
        if (doc.getPlayers().size() < 2) {
            throw new IllegalStateException("At least 2 players are required to start the room.");
        }

        RummyRules rules = RulesetRegistry.getRuleset(doc.getRulesetId()).orElseGet(PointsRummyRules::new);
        String tableId = doc.getTableId();
        TableActor actor = tableManager.getOrCreateTable(tableId, rules);
        if (routingRegistry != null) {
            routingRegistry.registerTableOwnership(tableId);
        }
        actor.setExpectedPlayers(doc.getPlayers().size());
        actor.setStakeTier(doc.getStakeTier());

        String gameId = actor.getState().getGameId();
        for (int i = 0; i < doc.getPlayers().size(); i++) {
            PrivateRoomDocument.RoomPlayer rp = doc.getPlayers().get(i);
            if (escrows != null && doc.getStakeTier() > 0 && rp.getEntryTxnId() != null) {
                escrows.assignToTable(rp.getEntryTxnId(), tableId, gameId);
            }
            if (routingRegistry != null) {
                routingRegistry.registerPlayerTable(rp.getPlayerId(), tableId);
            }
            actor.processCommand(new JoinCommand(
                    UUID.randomUUID().toString(),
                    gameId,
                    rp.getPlayerId(),
                    rp.getDisplayName(),
                    i,
                    false,
                    Instant.now()
            ), "PRV_JOIN");
            actor.processCommand(new ReadyCommand(
                    UUID.randomUUID().toString(),
                    gameId,
                    rp.getPlayerId(),
                    Instant.now()
            ), "PRV_READY");
        }

        doc.setStatus("RUNNING");
        doc.setStartedAt(Instant.now());
        roomRepo.save(doc);

        log.info("[PrivateRoom] Started room {} -> table {} ({} players, {}, stake ₹{})",
                roomCode, tableId, doc.getPlayers().size(), doc.getRulesetId(), doc.getStakeTier());

        return Map.of("success", true, "tableId", tableId, "room", toView(doc, playerId));
    }

    public Map<String, Object> getRoom(String rawCode, String playerId) {
        String roomCode = normalizeCode(rawCode);
        PrivateRoomDocument doc = roomRepo.findByRoomCode(roomCode)
                .orElseThrow(() -> new IllegalArgumentException("Room not found: " + roomCode));
        return Map.of("success", true, "room", toView(doc, playerId));
    }

    public List<Map<String, Object>> listMyRooms(String playerId) {
        return listMyRooms(playerId, null);
    }

    public List<Map<String, Object>> listMyRooms(String playerId, String rulesetsCsv) {
        Set<String> allowedRulesets = null;
        if (rulesetsCsv != null && !rulesetsCsv.isBlank()) {
            allowedRulesets = new HashSet<>();
            for (String token : rulesetsCsv.split(",")) {
                String trimmed = token.trim().toUpperCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    allowedRulesets.add(trimmed);
                }
            }
        }
        Set<String> filterSet = allowedRulesets;
        return roomRepo.findByPlayersPlayerIdOrderByCreatedAtDesc(playerId).stream()
                .filter(d -> filterSet == null || filterSet.isEmpty() || (d.getRulesetId() != null && filterSet.contains(d.getRulesetId().toUpperCase(Locale.ROOT))))
                .limit(20)
                .map(d -> toView(d, playerId))
                .toList();
    }

    @Scheduled(fixedDelay = 3000)
    public void tickPrivateRooms() {
        List<PrivateRoomDocument> active = roomRepo.findByStatusIn(List.of("WAITING", "RUNNING", "CANCELLED"));
        Instant now = Instant.now();

        for (PrivateRoomDocument doc : active) {
            try {
                if ("WAITING".equals(doc.getStatus()) && doc.getExpiresAt() != null && now.isAfter(doc.getExpiresAt())) {
                    doc.setStatus("EXPIRED");
                    doc.setCancelledReason("Room expired after 15 minutes without starting.");
                    doc.setFinishedAt(now);
                    refundAllUnrefunded(doc, "Private room expired");
                    roomRepo.save(doc);
                } else if ("CANCELLED".equals(doc.getStatus())) {
                    String reason = doc.getCancelledReason() != null ? doc.getCancelledReason() : "Private room cancelled";
                    if (doc.getTableId() != null) {
                        tableManager.getTable(doc.getTableId()).ifPresent(actor -> {
                            String st = actor.getState().getStatus().name();
                            if (!"COMPLETED".equals(st) && !"ABORTED".equals(st)) {
                                actor.abortMatch(reason);
                            }
                        });
                    }
                    boolean anyUnrefunded = doc.getPlayers().stream().anyMatch(p -> !p.isRefunded() && p.getDebitedAmount() > 0);
                    if (anyUnrefunded) {
                        refundAllUnrefunded(doc, reason);
                        if (doc.getFinishedAt() == null) doc.setFinishedAt(now);
                        roomRepo.save(doc);
                    }
                } else if ("RUNNING".equals(doc.getStatus())) {
                    syncRunningRoom(doc, now);
                }
            } catch (Exception e) {
                log.warn("[PrivateRoom] Tick error for room {}: {}", doc.getRoomCode(), e.getMessage());
            }
        }
    }

    private void syncRunningRoom(PrivateRoomDocument doc, Instant now) {
        Optional<TableActor> actorOpt = tableManager.getTable(doc.getTableId());
        if (actorOpt.isEmpty()) {
            // Do not cancel if another healthy node hosts this table
            if (routingRegistry != null && routingRegistry.isOwnedByRemoteServer(doc.getTableId())) {
                String owner = routingRegistry.getServerForTable(doc.getTableId()).orElse(null);
                if (owner != null && (clusterNodes == null || clusterNodes.isAlive(owner))) {
                    return;
                }
            }

            // Check if the game already completed / settled before table eviction
            if (tryHydrateCompletedRoomFromDb(doc, now)) {
                return;
            }

            if (doc.getStartedAt() != null && now.isAfter(doc.getStartedAt().plusSeconds(10))) {
                doc.setStatus("CANCELLED");
                doc.setCancelledReason("Table closed or aborted");
                doc.setFinishedAt(now);
                refundAllUnrefunded(doc, "Private room table closed unexpectedly");
                roomRepo.save(doc);
            }
            return;
        }

        TableActor actor = actorOpt.get();
        GameSettlementResult settlement = actor.getLastSettlement();
        if (settlement != null) {
            applySettlementToRoom(doc, settlement, actor, now);
            roomRepo.save(doc);
        } else if ("ABORTED".equals(actor.getState().getStatus().name())) {
            doc.setStatus("CANCELLED");
            doc.setCancelledReason("Table aborted");
            doc.setFinishedAt(now);
            refundAllUnrefunded(doc, "Private room aborted");
            roomRepo.save(doc);
        } else if (doc.getStakeTier() == 0
                && (actor.getTournamentWinnerId() != null || "COMPLETED".equals(actor.getState().getStatus().name()))) {
            doc.setStatus("COMPLETED");
            doc.setWinnerPlayerId(actor.getTournamentWinnerId());
            doc.setFinishedAt(now);
            roomRepo.save(doc);
        }
    }

    private boolean tryHydrateCompletedRoomFromDb(PrivateRoomDocument doc, Instant now) {
        if (gameRepo == null || doc.getTableId() == null) {
            return false;
        }
        List<GameDocument> games = gameRepo.findByTableId(doc.getTableId());
        if (games == null || games.isEmpty()) {
            return false;
        }
        GameDocument completed = games.stream()
                .filter(g -> g != null && "COMPLETED".equalsIgnoreCase(g.getStatus()))
                .reduce((first, second) -> second)
                .orElse(null);
        if (completed == null) {
            return false;
        }
        String gameId = completed.getGameId();
        if (settlementJobs != null && gameId != null) {
            Optional<SettlementJobDocument> jobOpt = settlementJobs.find(gameId);
            if (jobOpt.isPresent()) {
                String jobStatus = jobOpt.get().getStatus();
                if ("PENDING".equals(jobStatus) || "RETRY".equals(jobStatus)) {
                    return true; // Wait for settlement retry to finish
                }
            }
        }
        GameSettlementResult cached = walletService != null && gameId != null ? walletService.getSettlement(gameId) : null;
        if (cached != null) {
            applySettlementToRoom(doc, cached, null, completed.getFinishedAt() != null ? completed.getFinishedAt() : now);
        } else {
            doc.setStatus("COMPLETED");
            doc.setWinnerPlayerId(completed.getWinnerPlayerId());
            doc.setFinishedAt(completed.getFinishedAt() != null ? completed.getFinishedAt() : now);
        }
        roomRepo.save(doc);
        return true;
    }

    private void applySettlementToRoom(PrivateRoomDocument doc, GameSettlementResult settlement, TableActor actor, Instant now) {
        doc.setGrossCollectionRs(settlement.totalGrossPot().doubleValue());
        doc.setPlatformRakeRs(settlement.platformRakeAmount().doubleValue());
        doc.setRakeRate(settlement.platformRakeRate().doubleValue());
        doc.setWinnerPayoutRs(settlement.netWinnerPrize().doubleValue());
        doc.setWinnerPlayerId(settlement.winnerPlayerId());
        String winName = actor != null
                ? actor.getState().getPlayers().stream()
                        .filter(p -> p.getPlayerId().equals(settlement.winnerPlayerId()))
                        .map(PlayerState::getDisplayName)
                        .findFirst()
                        .orElse(null)
                : null;
        if (winName == null) {
            winName = doc.getPlayers().stream()
                    .filter(p -> p.getPlayerId().equals(settlement.winnerPlayerId()))
                    .map(PrivateRoomDocument.RoomPlayer::getDisplayName)
                    .findFirst()
                    .orElse(settlement.winnerPlayerId());
        }
        doc.setWinnerDisplayName(winName);
        if (settlement.playerDetails() != null) {
            for (PrivateRoomDocument.RoomPlayer rp : doc.getPlayers()) {
                GameSettlementResult.PlayerSettlementDetail det = settlement.playerDetails().get(rp.getPlayerId());
                if (det != null) {
                    rp.setPenaltyPoints(det.penaltyPoints());
                    rp.setLossAmount(det.lossAmount() != null ? det.lossAmount().doubleValue() : 0.0);
                    rp.setRefundAmount(det.refundAmount() != null ? det.refundAmount().doubleValue() : 0.0);
                    rp.setWinAmount(det.winAmount() != null ? det.winAmount().doubleValue() : 0.0);
                    rp.setNetWalletDelta(det.netWalletDelta() != null ? det.netWalletDelta().doubleValue() : 0.0);
                }
            }
        }
        doc.setStatus("COMPLETED");
        doc.setFinishedAt(now);
    }

    private void escrowPlayerStake(String playerId, int stakeTier, String escrowKey, String rulesetId, String roomCode) {
        if (walletService == null || stakeTier <= 0) return;
        if (escrows != null) {
            escrows.open(escrowKey, playerId, stakeTier, null, null);
        }
        try {
            walletService.debit(
                    playerId,
                    BigDecimal.valueOf(stakeTier),
                    "PRIVATE_ROOM_ENTRY",
                    escrowKey,
                    roomCode,
                    "Private Room Entry (" + rulesetId + "): " + roomCode,
                    Map.of("roomCode", roomCode, "rulesetId", rulesetId, "stakeTier", stakeTier)
            );
        } catch (RuntimeException e) {
            if (escrows != null) {
                escrows.discard(escrowKey);
            }
            throw e;
        }
    }

    private void refundAllUnrefunded(PrivateRoomDocument doc, String reason) {
        for (PrivateRoomDocument.RoomPlayer rp : doc.getPlayers()) {
            refundSinglePlayer(doc, rp, reason);
        }
    }

    private void refundSinglePlayer(PrivateRoomDocument doc, PrivateRoomDocument.RoomPlayer rp, String reason) {
        if (rp.isRefunded() || rp.getDebitedAmount() <= 0 || walletService == null) {
            rp.setRefunded(true);
            return;
        }
        String key = rp.getEntryTxnId() != null ? rp.getEntryTxnId() : ("PRV_" + doc.getRoomCode() + "_" + rp.getPlayerId());
        if (escrows != null && !escrows.claimRefund(key)) {
            rp.setRefunded(true);
            return;
        }
        try {
            walletService.credit(
                    rp.getPlayerId(),
                    BigDecimal.valueOf(rp.getDebitedAmount()),
                    "PRIVATE_ROOM_REFUND",
                    "REFUND_" + key,
                    doc.getRoomCode(),
                    reason + " (" + doc.getRoomCode() + ")",
                    Map.of("roomCode", doc.getRoomCode())
            );
            rp.setRefunded(true);
            doc.setRefundedTotalRs(doc.getRefundedTotalRs() + rp.getDebitedAmount());
        } catch (Exception e) {
            if (escrows != null) escrows.reopen(key);
            log.error("[PrivateRoom] Failed refund for {} in room {}: {}", rp.getPlayerId(), doc.getRoomCode(), e.getMessage());
        }
    }

    private static String friendlyVariantName(String rulesetId) {
        return switch (rulesetId) {
            case "POINTS_13" -> "Point Rummy";
            case "POOL_101", "POOL_201" -> "Pool Rummy";
            case "DEALS_2", "DEALS_3" -> "Deals Rummy";
            case "RUMMY_21" -> "21-Card Rummy";
            default -> rulesetId;
        };
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_CHARS.charAt(RNG.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (roomRepo.findByRoomCode(code).isEmpty()) return code;
        }
        return "RM" + Long.toString(System.currentTimeMillis(), 36).toUpperCase().substring(0, 4);
    }

    private static String normalizeCode(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Room code is required.");
        return raw.trim().toUpperCase();
    }

    private Map<String, Object> toView(PrivateRoomDocument doc, String viewerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomCode", doc.getRoomCode());
        m.put("tableId", doc.getTableId());
        m.put("hostPlayerId", doc.getHostPlayerId());
        m.put("hostDisplayName", doc.getHostDisplayName());
        m.put("rulesetId", doc.getRulesetId());
        m.put("maxPlayers", doc.getMaxPlayers());
        m.put("stakeTier", doc.getStakeTier());
        m.put("status", doc.getStatus());
        m.put("isHost", viewerId != null && viewerId.equals(doc.getHostPlayerId()));
        m.put("players", doc.getPlayers());
        m.put("grossCollectionRs", doc.getGrossCollectionRs());
        m.put("platformRakeRs", doc.getPlatformRakeRs());
        m.put("winnerPayoutRs", doc.getWinnerPayoutRs());
        m.put("winnerPlayerId", doc.getWinnerPlayerId());
        m.put("winnerDisplayName", doc.getWinnerDisplayName());
        m.put("createdAt", doc.getCreatedAt());
        m.put("expiresAt", doc.getExpiresAt());
        m.put("startedAt", doc.getStartedAt());
        m.put("finishedAt", doc.getFinishedAt());
        return m;
    }
}
