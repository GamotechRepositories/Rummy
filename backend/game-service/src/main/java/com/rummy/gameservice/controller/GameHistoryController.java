package com.rummy.gameservice.controller;

import com.rummy.gameservice.persistence.GamePersistenceService;
import com.rummy.gameservice.persistence.document.GameDocument;
import com.rummy.gameservice.persistence.document.GameEventDocument;
import com.rummy.gameservice.persistence.document.GameResultDocument;
import com.rummy.gameservice.persistence.document.UserProfileDocument;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST API for querying player match history, career stats, and game replay events.
 */
@RestController
@RequestMapping("/api/history")
@CrossOrigin(origins = "*")
public class GameHistoryController {

    private final GamePersistenceService persistenceService;

    public GameHistoryController(GamePersistenceService persistenceService) {
        this.persistenceService = Objects.requireNonNull(persistenceService);
    }

    @GetMapping("/player/{playerId}")
    public ResponseEntity<Map<String, Object>> getPlayerHistory(@PathVariable String playerId) {
        List<GameResultDocument> results = persistenceService.getPlayerHistory(playerId);
        UserProfileDocument profile = persistenceService.getOrCreateUserProfile(playerId, playerId);

        double winRate = profile.getGamesPlayed() > 0
                ? ((double) profile.getGamesWon() / profile.getGamesPlayed()) * 100.0
                : 0.0;

        return ResponseEntity.ok(Map.of(
                "playerId", playerId,
                "profile", profile,
                "winRate", Math.round(winRate * 10.0) / 10.0,
                "results", results
        ));
    }

    @GetMapping("/game/{gameId}")
    public ResponseEntity<Map<String, Object>> getGameDetails(@PathVariable String gameId) {
        return persistenceService.getGame(gameId)
                .map(game -> {
                    List<GameEventDocument> events = persistenceService.getGameEvents(gameId);
                    return ResponseEntity.ok(Map.of(
                            "game", (Object) game,
                            "events", (Object) events
                    ));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
