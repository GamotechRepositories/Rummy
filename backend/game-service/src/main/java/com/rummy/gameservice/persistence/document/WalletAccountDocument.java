package com.rummy.gameservice.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A player's INR balance as recorded by this platform, with optimistic concurrency control (@Version).
 *
 * <p>For players launched by an operator the operator's wallet holds the money; this balance mirrors the
 * last balance the operator reported. Accounts start at zero: no money is ever created here.
 */
@Document(collection = "wallet_accounts")
public class WalletAccountDocument implements Serializable {

    public static final String CURRENCY = "INR";

    @Id
    private String id;

    @Indexed(unique = true)
    private String playerId;

    private BigDecimal balance;
    /** Pre-INR accounts stored their balance here; read only when {@link #balance} was never written. */
    private BigDecimal freePlayBalance;
    private String currency;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    public WalletAccountDocument() {
        this.balance = BigDecimal.ZERO;
        this.currency = CURRENCY;
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

    public BigDecimal getBalance() {
        if (balance != null) {
            return balance;
        }
        return freePlayBalance != null ? freePlayBalance : BigDecimal.ZERO;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public String getCurrency() {
        return currency != null ? currency : CURRENCY;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
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
