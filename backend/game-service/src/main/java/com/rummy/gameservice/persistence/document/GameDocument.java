package com.rummy.gameservice.persistence.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted Game Document conforming to Section 22 (Phase 15) of master README.
 */
@Document(collection = "games")
public class GameDocument {

    @Id
    private String gameId;

    @Indexed
    private String tableId;

    private String rulesetId;

    @Indexed
    private String status;

    @Indexed
    private Instant startedAt;

    private Instant finishedAt;

    private String winnerPlayerId;

    private String cutJoker;

    private List<PlayerSnapshot> players = new ArrayList<>();

    private int totalTurns;

    private long durationSeconds;

    public GameDocument() {}

    public GameDocument(String gameId,
                        String tableId,
                        String rulesetId,
                        String status,
                        Instant startedAt,
                        Instant finishedAt,
                        String winnerPlayerId,
                        String cutJoker,
                        List<PlayerSnapshot> players,
                        int totalTurns,
                        long durationSeconds) {
        this.gameId = gameId;
        this.tableId = tableId;
        this.rulesetId = rulesetId;
        this.status = status;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.winnerPlayerId = winnerPlayerId;
        this.cutJoker = cutJoker;
        this.players = players != null ? players : new ArrayList<>();
        this.totalTurns = totalTurns;
        this.durationSeconds = durationSeconds;
    }

    public record PlayerSnapshot(
            String playerId,
            String displayName,
            int seatIndex,
            int finalScore,
            String status,
            boolean isBot,
            boolean won
    ) {}

    public String getGameId() { return gameId; }
    public void setGameId(String gameId) { this.gameId = gameId; }

    public String getTableId() { return tableId; }
    public void setTableId(String tableId) { this.tableId = tableId; }

    public String getRulesetId() { return rulesetId; }
    public void setRulesetId(String rulesetId) { this.rulesetId = rulesetId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public String getWinnerPlayerId() { return winnerPlayerId; }
    public void setWinnerPlayerId(String winnerPlayerId) { this.winnerPlayerId = winnerPlayerId; }

    public String getCutJoker() { return cutJoker; }
    public void setCutJoker(String cutJoker) { this.cutJoker = cutJoker; }

    public List<PlayerSnapshot> getPlayers() { return players; }
    public void setPlayers(List<PlayerSnapshot> players) { this.players = players; }

    public int getTotalTurns() { return totalTurns; }
    public void setTotalTurns(int totalTurns) { this.totalTurns = totalTurns; }

    public long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(long durationSeconds) { this.durationSeconds = durationSeconds; }
}
