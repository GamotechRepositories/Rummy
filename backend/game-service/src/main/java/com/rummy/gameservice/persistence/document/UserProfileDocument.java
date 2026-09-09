package com.rummy.gameservice.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Persisted User Profile Document tracking virtual points, games played, and win statistics.
 */
@Document(collection = "user_profiles")
public class UserProfileDocument {

    @Id
    private String userId;

    private String displayName;

    private long virtualPoints;

    private int gamesPlayed;

    private int gamesWon;

    private int totalScore;

    private Instant createdAt;

    private Instant updatedAt;

    public UserProfileDocument() {}

    public UserProfileDocument(String userId, String displayName, long virtualPoints) {
        this.userId = userId;
        this.displayName = displayName;
        this.virtualPoints = virtualPoints;
        this.gamesPlayed = 0;
        this.gamesWon = 0;
        this.totalScore = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public long getVirtualPoints() { return virtualPoints; }
    public void setVirtualPoints(long virtualPoints) { this.virtualPoints = virtualPoints; }

    public int getGamesPlayed() { return gamesPlayed; }
    public void setGamesPlayed(int gamesPlayed) { this.gamesPlayed = gamesPlayed; }

    public int getGamesWon() { return gamesWon; }
    public void setGamesWon(int gamesWon) { this.gamesWon = gamesWon; }

    public int getTotalScore() { return totalScore; }
    public void setTotalScore(int totalScore) { this.totalScore = totalScore; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
