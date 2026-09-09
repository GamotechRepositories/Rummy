package com.rummy.gameservice.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * MongoDB Wallet Account Document.
 * Supports Free-Play tokens and Real-Money balances with Australian compliance lock (IGA 2001)
 * and optimistic concurrency control (@Version).
 */
@Document(collection = "wallet_accounts")
public class WalletAccountDocument implements Serializable {

    @Id
    private String id;

    @Indexed(unique = true)
    private String playerId;

    private BigDecimal freePlayBalance;
    private BigDecimal realMoneyBalance; // Locked in free-play mode
    private BigDecimal reservedBalance;
    private String currency; // "AUD" or "TOKENS"
    private boolean isRealMoneyEnabled; // Must be false under IGA 2001 compliance gate
    private Instant lastDailyClaimAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    public WalletAccountDocument() {
        this.freePlayBalance = BigDecimal.valueOf(1000); // 1,000 complimentary free-play tokens
        this.realMoneyBalance = BigDecimal.ZERO;
        this.reservedBalance = BigDecimal.ZERO;
        this.currency = "AUD_FREE_PLAY";
        this.isRealMoneyEnabled = false;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public WalletAccountDocument(String playerId) {
        this();
        this.playerId = playerId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public BigDecimal getFreePlayBalance() {
        return freePlayBalance;
    }

    public void setFreePlayBalance(BigDecimal freePlayBalance) {
        this.freePlayBalance = freePlayBalance;
    }

    public BigDecimal getRealMoneyBalance() {
        return realMoneyBalance;
    }

    public void setRealMoneyBalance(BigDecimal realMoneyBalance) {
        this.realMoneyBalance = realMoneyBalance;
    }

    public BigDecimal getReservedBalance() {
        return reservedBalance;
    }

    public void setReservedBalance(BigDecimal reservedBalance) {
        this.reservedBalance = reservedBalance;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public boolean isRealMoneyEnabled() {
        return isRealMoneyEnabled;
    }

    public void setRealMoneyEnabled(boolean realMoneyEnabled) {
        isRealMoneyEnabled = realMoneyEnabled;
    }

    public Instant getLastDailyClaimAt() {
        return lastDailyClaimAt;
    }

    public void setLastDailyClaimAt(Instant lastDailyClaimAt) {
        this.lastDailyClaimAt = lastDailyClaimAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
