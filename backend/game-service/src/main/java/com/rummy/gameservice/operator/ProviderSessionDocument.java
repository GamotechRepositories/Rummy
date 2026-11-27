package com.rummy.gameservice.operator;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Durable store of an operator player's active external provider session token (e.g. Oreng/Gamotech)
 * so settlements, balance lookups, and outbox retries survive server restarts and multi-node routing.
 */
@Document(collection = "operator_provider_sessions")
public class ProviderSessionDocument {

    @Id
    private String playerId;
    private String operatorId;
    private String gameCode;
    private String sessionToken;
    private Instant updatedAt;
    @Indexed(expireAfter = "0s")
    private Instant expireAt;

    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
    public String getGameCode() { return gameCode; }
    public void setGameCode(String gameCode) { this.gameCode = gameCode; }
    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
}
