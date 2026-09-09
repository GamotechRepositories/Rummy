package com.rummy.gameservice.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Immutable Financial Ledger Transaction Document.
 * Stores auditable financial events: DEPOSIT, WITHDRAWAL, GAME_ENTRY, GAME_WIN, GAME_REFUND, PROMOTIONAL_CREDIT.
 */
@Document(collection = "wallet_transactions")
public class WalletTransactionDocument implements Serializable {

    @Id
    private String id;

    @Indexed(unique = true)
    private String idempotencyKey;

    @Indexed
    private String playerId;

    @Indexed
    private String gameId;

    private String transactionType; // DEPOSIT, WITHDRAWAL, GAME_ENTRY, GAME_WIN, GAME_REFUND, PROMOTIONAL_CREDIT
    private BigDecimal amount;
    private BigDecimal balanceBefore;
    private BigDecimal balanceAfter;
    private String status; // SUCCESS, PENDING, REVERSED, FAILED
    private String currency;
    private String description;
    private Instant createdAt;
    private Map<String, Object> metadata;

    public WalletTransactionDocument() {
        this.createdAt = Instant.now();
        this.status = "SUCCESS";
    }

    public WalletTransactionDocument(String idempotencyKey, String playerId, String gameId,
                                     String transactionType, BigDecimal amount,
                                     BigDecimal balanceBefore, BigDecimal balanceAfter,
                                     String currency, String description, Map<String, Object> metadata) {
        this.idempotencyKey = idempotencyKey;
        this.playerId = playerId;
        this.gameId = gameId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.balanceBefore = balanceBefore;
        this.balanceAfter = balanceAfter;
        this.currency = currency;
        this.description = description;
        this.status = "SUCCESS";
        this.createdAt = Instant.now();
        this.metadata = metadata;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public String getGameId() {
        return gameId;
    }

    public void setGameId(String gameId) {
        this.gameId = gameId;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getBalanceBefore() {
        return balanceBefore;
    }

    public void setBalanceBefore(BigDecimal balanceBefore) {
        this.balanceBefore = balanceBefore;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
