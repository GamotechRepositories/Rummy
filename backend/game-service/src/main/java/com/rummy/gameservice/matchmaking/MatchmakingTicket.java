package com.rummy.gameservice.matchmaking;

import java.time.Instant;

public class MatchmakingTicket {

    public enum Status {
        QUEUED,
        MATCHED,
        CANCELLED,
        EXPIRED
    }

    private final String ticketId;
    private final String playerId;
    private final String playerName;
    private final String rulesetId;
    private final int stakeTier;
    private final int maxPlayers;
    private final boolean allowAiFallback;
    private final Instant createdAt;

    private volatile Status status;
    private volatile String matchedTableId;
    private volatile String matchedServerId;

    public MatchmakingTicket(String ticketId, String playerId, String playerName,
                             String rulesetId, int stakeTier, int maxPlayers, boolean allowAiFallback) {
        this.ticketId = ticketId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.rulesetId = rulesetId;
        this.stakeTier = stakeTier;
        this.maxPlayers = maxPlayers;
        this.allowAiFallback = allowAiFallback;
        this.createdAt = Instant.now();
        this.status = Status.QUEUED;
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getRulesetId() {
        return rulesetId;
    }

    public int getStakeTier() {
        return stakeTier;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public boolean isAllowAiFallback() {
        return allowAiFallback;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getMatchedTableId() {
        return matchedTableId;
    }

    public void setMatchedTableId(String matchedTableId) {
        this.matchedTableId = matchedTableId;
    }

    public String getMatchedServerId() {
        return matchedServerId;
    }

    public void setMatchedServerId(String matchedServerId) {
        this.matchedServerId = matchedServerId;
    }

    public String getQueueKey() {
        return rulesetId + ":" + stakeTier + ":" + maxPlayers;
    }
}
