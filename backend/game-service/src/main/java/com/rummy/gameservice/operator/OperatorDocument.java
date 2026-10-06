package com.rummy.gameservice.operator;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A partner platform whose players play here. The operator holds the players' money; this platform
 * calls the operator's wallet API to debit stakes and credit winnings.
 */
@Document(collection = "operators")
public class OperatorDocument {

    @Id
    private String id;
    private String name;
    /** Shared HMAC secret: signs our wallet calls to the operator and verifies the operator's launch calls. */
    private String secret;
    /** Base URL of the operator's wallet API ({@code /balance}, {@code /debit}, {@code /credit}, {@code /rollback}). */
    private String walletUrl;
    /** Operator page where players add money; opened by the game's "Add cash" button. */
    private String cashierUrl;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getWalletUrl() { return walletUrl; }
    public void setWalletUrl(String walletUrl) { this.walletUrl = walletUrl; }
    public String getCashierUrl() { return cashierUrl; }
    public void setCashierUrl(String cashierUrl) { this.cashierUrl = cashierUrl; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
