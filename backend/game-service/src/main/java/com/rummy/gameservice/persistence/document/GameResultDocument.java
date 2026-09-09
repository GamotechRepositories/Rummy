package com.rummy.gameservice.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Persisted Game Result Document conforming to Section 22 and 23 of master README.
 * Stores individual player outcomes for fast history queries.
 */
@Document(collection = "game_results")
@CompoundIndex(name = "player_created_idx", def = "{'playerId': 1, 'createdAt': -1}")
public class GameResultDocument {

    @Id
    private String id;

    @Indexed
    private String gameId;

    private String tableId;

    @Indexed
    private String playerId;

    private String displayName;

    private int finalScore;

    private boolean won;

    private String status;

    private Instant createdAt;

    public GameResultDocument() {}

    public GameResultDocument(String gameId,
                              String tableId,
                              String playerId,
                              String displayName,
                              int finalScore,
                              boolean won,
                              String status,
                              Instant createdAt) {
        this.gameId = gameId;
        this.tableId = tableId;
        this.playerId = playerId;
        this.displayName = displayName;
        this.finalScore = finalScore;
        this.won = won;
        this.status = status;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }

    public String getPlayerId() { return playerId; }
    public void setPlayerId(String playerId) { this.playerId = playerId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public int getFinalScore() { return finalScore; }
    public void setFinalScore(int finalScore) { this.finalScore = finalScore; }

    public boolean isWon() { return won; }
    public void setWon(boolean won) { this.won = won; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
