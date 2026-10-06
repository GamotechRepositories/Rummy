package com.rummy.gameservice.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Latest restorable copy of a running match, written by the node hosting it. If that node dies, a
 * surviving node claims the record (compare-and-set on {@code ownerNode}) and resumes the match.
 */
@Document(collection = "table_snapshots")
public class TableSnapshotDocument {

    @Id
    private String tableId;
    @Indexed
    private String gameId;
    private String ownerNode;
    /** Seated humans, so a player whose routing was lost with the node can still find their table. */
    @Indexed
    private List<String> humans;
    private Instant savedAt;
    @Indexed(expireAfter = "0s")
    private Instant expireAt;
    /** {@code TableSnapshot} as JSON. */
    private String payload;
    /** Its node stopped the match on purpose (deploy) and released it: any node may resume it at once. */
    private boolean handedOff;

    public TableSnapshotDocument() {
    }

    public boolean isHandedOff() { return handedOff; }
    public void setHandedOff(boolean handedOff) { this.handedOff = handedOff; }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }
    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }
    public String getOwnerNode() { return ownerNode; }
    public void setOwnerNode(String ownerNode) { this.ownerNode = ownerNode; }
    public List<String> getHumans() { return humans; }
    public void setHumans(List<String> humans) { this.humans = humans; }
    public Instant getSavedAt() { return savedAt; }
    public void setSavedAt(Instant savedAt) { this.savedAt = savedAt; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
