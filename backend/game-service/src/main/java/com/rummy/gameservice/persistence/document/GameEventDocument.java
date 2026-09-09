package com.rummy.gameservice.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Persisted Game Event Document for immutable match auditing and sequence playback.
 */
@Document(collection = "game_events")
@CompoundIndex(name = "game_sequence_idx", def = "{'gameId': 1, 'sequence': 1}")
public class GameEventDocument {

    @Id
    private String id;

    @Indexed
    private String gameId;

    private long sequence;

    private String eventType;

    private String playerId;

    private String payloadJson;

    private Instant timestamp;

    public GameEventDocument() {}

    public GameEventDocument(String gameId,
                             long sequence,
                             String eventType,
                             String playerId,
                             String payloadJson,
                             Instant timestamp) {
        this.gameId = gameId;
        this.sequence = sequence;
        this.eventType = eventType;
        this.playerId = playerId;
        this.payloadJson = payloadJson;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
