package com.rummy.gameservice.privateroom;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB document representing a player-created Private Room (shared with admin-backend).
 */
@Document(collection = "private_rooms")
public class PrivateRoomDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String roomCode;

    @Indexed
    private String tableId;

    private String hostPlayerId;
    private String hostDisplayName;
    private String rulesetId;      // POINTS_13, POOL_101, POOL_201, DEALS_2, DEALS_3, RUMMY_21
    private int maxPlayers;        // 2..6 (always 2 for DEALS_2)
    private int stakeTier;         // 0 = free/practice; >0 = entry fee / buy-in in INR
    private String status;         // WAITING | RUNNING | COMPLETED | CANCELLED | EXPIRED
    private String cancelledReason;

    private List<RoomPlayer> players = new ArrayList<>();

    // Financial summary (populated on settlement / cancellation)
    private double grossCollectionRs;
    private double platformRakeRs;
    private double rakeRate;
    private double winnerPayoutRs;
    private double refundedTotalRs;
    private String winnerPlayerId;
    private String winnerDisplayName;

    private Instant createdAt;
    private Instant expiresAt;
    private Instant startedAt;
    private Instant finishedAt;

    public static class RoomPlayer {
        private String playerId;
        private String displayName;
        private int seat;
        private String entryTxnId;
        private double debitedAmount;
        private boolean refunded;
        private int penaltyPoints;
        private double lossAmount;
        private double refundAmount;
        private double winAmount;
        private double netWalletDelta;
        private Instant joinedAt;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public int getSeat() { return seat; }
        public void setSeat(int seat) { this.seat = seat; }

        public String getEntryTxnId() { return entryTxnId; }
        public void setEntryTxnId(String entryTxnId) { this.entryTxnId = entryTxnId; }

        public double getDebitedAmount() { return debitedAmount; }
        public void setDebitedAmount(double debitedAmount) { this.debitedAmount = debitedAmount; }

        public boolean isRefunded() { return refunded; }
        public void setRefunded(boolean refunded) { this.refunded = refunded; }

        public int getPenaltyPoints() { return penaltyPoints; }
        public void setPenaltyPoints(int penaltyPoints) { this.penaltyPoints = penaltyPoints; }

        public double getLossAmount() { return lossAmount; }
        public void setLossAmount(double lossAmount) { this.lossAmount = lossAmount; }

        public double getRefundAmount() { return refundAmount; }
        public void setRefundAmount(double refundAmount) { this.refundAmount = refundAmount; }

        public double getWinAmount() { return winAmount; }
        public void setWinAmount(double winAmount) { this.winAmount = winAmount; }

        public double getNetWalletDelta() { return netWalletDelta; }
        public void setNetWalletDelta(double netWalletDelta) { this.netWalletDelta = netWalletDelta; }

        public Instant getJoinedAt() { return joinedAt; }
        public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRoomCode() { return roomCode; }
    public void setRoomCode(String roomCode) { this.roomCode = roomCode; }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }

    public String getHostPlayerId() { return hostPlayerId; }
    public void setHostPlayerId(String hostPlayerId) { this.hostPlayerId = hostPlayerId; }

    public String getHostDisplayName() { return hostDisplayName; }
    public void setHostDisplayName(String hostDisplayName) { this.hostDisplayName = hostDisplayName; }

    public String getRulesetId() { return rulesetId; }
    public void setRulesetId(String rulesetId) { this.rulesetId = rulesetId; }

    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }

    public int getStakeTier() { return stakeTier; }
    public void setStakeTier(int stakeTier) { this.stakeTier = stakeTier; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCancelledReason() { return cancelledReason; }
    public void setCancelledReason(String cancelledReason) { this.cancelledReason = cancelledReason; }

    public List<RoomPlayer> getPlayers() { return players; }
    public void setPlayers(List<RoomPlayer> players) { this.players = players; }

    public double getGrossCollectionRs() { return grossCollectionRs; }
    public void setGrossCollectionRs(double grossCollectionRs) { this.grossCollectionRs = grossCollectionRs; }

    public double getPlatformRakeRs() { return platformRakeRs; }
    public void setPlatformRakeRs(double platformRakeRs) { this.platformRakeRs = platformRakeRs; }

    public double getRakeRate() { return rakeRate; }
    public void setRakeRate(double rakeRate) { this.rakeRate = rakeRate; }

    public double getWinnerPayoutRs() { return winnerPayoutRs; }
    public void setWinnerPayoutRs(double winnerPayoutRs) { this.winnerPayoutRs = winnerPayoutRs; }

    public double getRefundedTotalRs() { return refundedTotalRs; }
    public void setRefundedTotalRs(double refundedTotalRs) { this.refundedTotalRs = refundedTotalRs; }

    public String getWinnerPlayerId() { return winnerPlayerId; }
    public void setWinnerPlayerId(String winnerPlayerId) { this.winnerPlayerId = winnerPlayerId; }

    public String getWinnerDisplayName() { return winnerDisplayName; }
    public void setWinnerDisplayName(String winnerDisplayName) { this.winnerDisplayName = winnerDisplayName; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
