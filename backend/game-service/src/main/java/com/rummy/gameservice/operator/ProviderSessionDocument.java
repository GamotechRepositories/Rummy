package com.rummy.gameservice.operator;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Durable store of an Operations/operator player's launched session context so balance checks,
 * transfers, late wins/refunds, currency formatting, and exit redirects (`close_url`) survive
 * server restarts and multi-node routing.
 */
@Document(collection = "operator_provider_sessions")
public class ProviderSessionDocument {

    @Id
    private String playerId;
    private String operatorId;
    private String partnerCode;
    private String gid;
    private String gameCode;
    private String sessionToken;
    private String username;
    private String userId;
    private String currency;
    private String lang;
    private String extsess;
    private String closeUrl;
    private Instant updatedAt;
    @Indexed(expireAfter = "0s")
    private Instant expireAt;

    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
    public String getPartnerCode() { return partnerCode; }
    public void setPartnerCode(String partnerCode) { this.partnerCode = partnerCode; }
    public String getGid() { return gid; }
    public void setGid(String gid) { this.gid = gid; }
    public String getGameCode() { return gameCode; }
    public void setGameCode(String gameCode) { this.gameCode = gameCode; }
    public String getSessionToken() { return sessionToken; }
    public void setSessionToken(String sessionToken) { this.sessionToken = sessionToken; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getLang() { return lang; }
    public void setLang(String lang) { this.lang = lang; }
    public String getExtsess() { return extsess; }
    public void setExtsess(String extsess) { this.extsess = extsess; }
    public String getCloseUrl() { return closeUrl; }
    public void setCloseUrl(String closeUrl) { this.closeUrl = closeUrl; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
}
