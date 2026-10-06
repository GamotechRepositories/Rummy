package com.rummy.gameservice.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One escrowed stake (table entry or rejoin fee). The id equals the wallet debit's idempotency key,
 * so a refund can verify the money was actually taken.
 */
@Document(collection = "stake_escrows")
@CompoundIndex(name = "status_holder_idx", def = "{'status': 1, 'holderNode': 1}")
public class StakeEscrowDocument {

    @Id
    private String id;
    private String playerId;
    private long amount;
    private String status;
    /** Node responsible for settling or refunding; its death makes the stake refundable by any peer. */
    private String holderNode;
    private String tableId;
    @Indexed
    private String gameId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant closedAt;
    /** Set when the escrow reaches a final state; MongoDB deletes the record afterwards. */
    @Indexed(expireAfter = "0s")
    private Instant expireAt;

    public StakeEscrowDocument() {
    }

    public StakeEscrowDocument(String id, String playerId, long amount, String status,
                               String holderNode, String tableId, String gameId) {
        this.id = id;
        this.playerId = playerId;
        this.amount = amount;
        this.status = status;
        this.holderNode = holderNode;
        this.tableId = tableId;
        this.gameId = gameId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHolderNode() { return holderNode; }
    public void setHolderNode(String holderNode) { this.holderNode = holderNode; }
    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }
    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
}
