package com.rummy.gameservice.tournament;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB document mapping to the shared {@code tournaments} collection
 * managed by both admin-backend and game-service.
 */
@Document(collection = "tournaments")
public class TournamentDocument {

    @Id
    private String id;

    // Indexes on `tournaments` are managed by admin-backend (Mongoose);
    // omitting @Indexed here avoids Spring Data index-name conflicts on startup.
    private String tournamentId;

    private String title;
    private String variant = "DEALS_3";
    private int tableSizePerRound = 6;
    private int minPlayers = 36;
    private int maxPlayers = 120;
    private double entryFeeRs = 0;
    private double fixedPrizeRs = 0;
    private int checkInWindowMinutes = 5;
    private Instant checkInEndsAt;
    private List<String> checkedInPlayerIds = new ArrayList<>();
    private List<PrizeTier> prizes = new ArrayList<>();
    private Instant registrationStartsAt;
    private Instant registrationEndsAt;

    private Instant startsAt;

    private String status = "DRAFT"; // DRAFT, REGISTERING, STARTING, RUNNING, COMPLETED, CANCELLED

    private String operatorScope = "ALL";
    private List<Registration> registrations = new ArrayList<>();
    private List<RoundRecord> rounds = new ArrayList<>();
    private List<FinalRanking> finalRankings = new ArrayList<>();
    private double grossPoolRs = 0;
    private double platformProfitRs = 0;
    private String createdByAdminId = "";
    private String cancelledReason;

    public static class PrizeTier {
        private int rank;
        private double percentage;

        public PrizeTier() {}
        public PrizeTier(int rank, double percentage) {
            this.rank = rank;
            this.percentage = percentage;
        }
        public int getRank() { return rank; }
        public void setRank(int rank) { this.rank = rank; }
        public double getPercentage() { return percentage; }
        public void setPercentage(double percentage) { this.percentage = percentage; }
    }

    public static class Registration {
        private String playerId;
        private String displayName;
        private String operatorId = "DEFAULT";
        private Instant registeredAt = Instant.now();
        private String entryTxnId;
        private boolean refunded = false;

        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getOperatorId() { return operatorId; }
        public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
        public Instant getRegisteredAt() { return registeredAt; }
        public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
        public String getEntryTxnId() { return entryTxnId; }
        public void setEntryTxnId(String entryTxnId) { this.entryTxnId = entryTxnId; }
        public boolean isRefunded() { return refunded; }
        public void setRefunded(boolean refunded) { this.refunded = refunded; }
    }

    public static class RoundRecord {
        private int roundNumber;
        private List<String> tableIds = new ArrayList<>();
        private List<String> qualifiedPlayerIds = new ArrayList<>();
        private String status = "PENDING"; // PENDING, RUNNING, DONE
        private Instant startedAt;
        private Instant finishedAt;

        public int getRoundNumber() { return roundNumber; }
        public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }
        public List<String> getTableIds() { return tableIds; }
        public void setTableIds(List<String> tableIds) { this.tableIds = tableIds; }
        public List<String> getQualifiedPlayerIds() { return qualifiedPlayerIds; }
        public void setQualifiedPlayerIds(List<String> qualifiedPlayerIds) { this.qualifiedPlayerIds = qualifiedPlayerIds; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getStartedAt() { return startedAt; }
        public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
        public Instant getFinishedAt() { return finishedAt; }
        public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    }

    public static class FinalRanking {
        private int rank;
        private String playerId;
        private String displayName;
        private double prizeRs;

        public FinalRanking() {}
        public FinalRanking(int rank, String playerId, String displayName, double prizeRs) {
            this.rank = rank;
            this.playerId = playerId;
            this.displayName = displayName;
            this.prizeRs = prizeRs;
        }
        public int getRank() { return rank; }
        public void setRank(int rank) { this.rank = rank; }
        public String getPlayerId() { return playerId; }
        public void setPlayerId(String playerId) { this.playerId = playerId; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public double getPrizeRs() { return prizeRs; }
        public void setPrizeRs(double prizeRs) { this.prizeRs = prizeRs; }
    }

    // ── Getters & Setters ──
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTournamentId() { return tournamentId; }
    public void setTournamentId(String tournamentId) { this.tournamentId = tournamentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getVariant() { return variant; }
    public void setVariant(String variant) { this.variant = variant; }
    public int getTableSizePerRound() { return tableSizePerRound; }
    public void setTableSizePerRound(int tableSizePerRound) { this.tableSizePerRound = tableSizePerRound; }
    public int getMinPlayers() { return minPlayers; }
    public void setMinPlayers(int minPlayers) { this.minPlayers = minPlayers; }
    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
    public double getEntryFeeRs() { return entryFeeRs; }
    public void setEntryFeeRs(double entryFeeRs) { this.entryFeeRs = entryFeeRs; }
    public double getFixedPrizeRs() { return fixedPrizeRs; }
    public void setFixedPrizeRs(double fixedPrizeRs) { this.fixedPrizeRs = fixedPrizeRs; }
    public int getCheckInWindowMinutes() { return checkInWindowMinutes; }
    public void setCheckInWindowMinutes(int checkInWindowMinutes) { this.checkInWindowMinutes = checkInWindowMinutes; }
    public Instant getCheckInEndsAt() { return checkInEndsAt; }
    public void setCheckInEndsAt(Instant checkInEndsAt) { this.checkInEndsAt = checkInEndsAt; }
    public List<String> getCheckedInPlayerIds() { return checkedInPlayerIds; }
    public void setCheckedInPlayerIds(List<String> checkedInPlayerIds) { this.checkedInPlayerIds = checkedInPlayerIds; }
    public List<PrizeTier> getPrizes() { return prizes; }
    public void setPrizes(List<PrizeTier> prizes) { this.prizes = prizes; }
    public Instant getRegistrationStartsAt() { return registrationStartsAt; }
    public void setRegistrationStartsAt(Instant registrationStartsAt) { this.registrationStartsAt = registrationStartsAt; }
    public Instant getRegistrationEndsAt() { return registrationEndsAt; }
    public void setRegistrationEndsAt(Instant registrationEndsAt) { this.registrationEndsAt = registrationEndsAt; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOperatorScope() { return operatorScope; }
    public void setOperatorScope(String operatorScope) { this.operatorScope = operatorScope; }
    public List<Registration> getRegistrations() { return registrations; }
    public void setRegistrations(List<Registration> registrations) { this.registrations = registrations; }
    public List<RoundRecord> getRounds() { return rounds; }
    public void setRounds(List<RoundRecord> rounds) { this.rounds = rounds; }
    public List<FinalRanking> getFinalRankings() { return finalRankings; }
    public void setFinalRankings(List<FinalRanking> finalRankings) { this.finalRankings = finalRankings; }
    public double getGrossPoolRs() { return grossPoolRs; }
    public void setGrossPoolRs(double grossPoolRs) { this.grossPoolRs = grossPoolRs; }
    public double getPlatformProfitRs() { return platformProfitRs; }
    public void setPlatformProfitRs(double platformProfitRs) { this.platformProfitRs = platformProfitRs; }
    public String getCreatedByAdminId() { return createdByAdminId; }
    public void setCreatedByAdminId(String createdByAdminId) { this.createdByAdminId = createdByAdminId; }
    public String getCancelledReason() { return cancelledReason; }
    public void setCancelledReason(String cancelledReason) { this.cancelledReason = cancelledReason; }
}
