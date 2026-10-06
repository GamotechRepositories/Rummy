package com.rummy.gameservice.operator;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Links our opaque player id to the operator and the operator's own id for that player. */
@Document(collection = "operator_players")
@CompoundIndex(name = "operator_external_idx", def = "{'operatorId': 1, 'externalId': 1}", unique = true)
public class OperatorPlayerDocument {

    @Id
    private String playerId;
    private String operatorId;
    private String externalId;
    private Instant createdAt;
    private Instant lastLaunchAt;

    public OperatorPlayerDocument() {
    }

    public OperatorPlayerDocument(String playerId, String operatorId, String externalId) {
        this.playerId = playerId;
        this.operatorId = operatorId;
        this.externalId = externalId;
        this.createdAt = Instant.now();
        this.lastLaunchAt = this.createdAt;
    }

    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastLaunchAt() { return lastLaunchAt; }
    public void setLastLaunchAt(Instant lastLaunchAt) { this.lastLaunchAt = lastLaunchAt; }
}
