package com.rummy.gameservice.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A finished match waiting to be paid out. Written before the payout runs, so a crash or a failed
 * payout is retried (by any node, once the holder is dead) instead of leaving stakes stuck in escrow.
 */
@Document(collection = "settlement_jobs")
@CompoundIndex(name = "status_due_idx", def = "{'status': 1, 'nextAttemptAt': 1}")
public class SettlementJobDocument {

    @Id
    private String gameId;
    private String status;
    private String holderNode;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant createdAt;
    private Instant updatedAt;
    /** Set once the job is final; MongoDB deletes the record afterwards. */
    @Indexed(expireAfter = "0s")
    private Instant expireAt;
    /** {@code SettlementService.Job} as JSON. */
    private String payload;

    public SettlementJobDocument() {
    }

    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHolderNode() { return holderNode; }
    public void setHolderNode(String holderNode) { this.holderNode = holderNode; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
