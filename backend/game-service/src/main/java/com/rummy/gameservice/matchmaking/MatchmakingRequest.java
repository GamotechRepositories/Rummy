package com.rummy.gameservice.matchmaking;

public class MatchmakingRequest {
    private String playerId;
    private String playerName;
    private String rulesetId = "INDIAN_POINTS";
    private int stakeTier = 100;
    private int maxPlayers = 2;
    private boolean allowAiFallback = true;

    public MatchmakingRequest() {}

    public MatchmakingRequest(String playerId, String playerName, String rulesetId, int stakeTier, int maxPlayers, boolean allowAiFallback) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.rulesetId = rulesetId;
        this.stakeTier = stakeTier;
        this.maxPlayers = maxPlayers;
        this.allowAiFallback = allowAiFallback;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public String getRulesetId() {
        return rulesetId;
    }

    public void setRulesetId(String rulesetId) {
        this.rulesetId = rulesetId;
    }

    public int getStakeTier() {
        return stakeTier;
    }

    public void setStakeTier(int stakeTier) {
        this.stakeTier = stakeTier;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public boolean isAllowAiFallback() {
        return allowAiFallback;
    }

    public void setAllowAiFallback(boolean allowAiFallback) {
        this.allowAiFallback = allowAiFallback;
    }
}
