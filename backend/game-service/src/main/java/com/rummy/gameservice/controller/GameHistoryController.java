package com.rummy.gameservice.controller;

import com.rummy.engine.model.GameStatus;
import com.rummy.gameservice.persistence.GamePersistenceService;
import com.rummy.gameservice.persistence.document.GameEventDocument;
import com.rummy.gameservice.persistence.document.GameResultDocument;
import com.rummy.gameservice.persistence.document.UserProfileDocument;
import com.rummy.gameservice.security.AuthenticatedPlayer;
import jakarta.servlet.http.HttpServletRequest;
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
public class GameHistoryController {

    private final GamePersistenceService persistenceService;

    public GameHistoryController(GamePersistenceService persistenceService) {
        this.persistenceService = Objects.requireNonNull(persistenceService);
    }

    @GetMapping("/player/{playerId}")
    public ResponseEntity<Map<String, Object>> getPlayerHistory(HttpServletRequest request, @PathVariable String playerId) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        List<GameResultDocument> results = persistenceService.getPlayerHistory(owner);
        UserProfileDocument profile = persistenceService.getOrCreateUserProfile(owner, owner);

        double winRate = profile.getGamesPlayed() > 0
                ? ((double) profile.getGamesWon() / profile.getGamesPlayed()) * 100.0
                : 0.0;

        return ResponseEntity.ok(Map.of(
                "playerId", owner,
                "profile", profile,
                "winRate", Math.round(winRate * 10.0) / 10.0,
                "results", results
        ));
    }

    /**
     * Replay of a finished game, visible only to players who sat at that table.
     * Events of an in-progress game would leak opponents' cards, so they are never served.
     */
    @GetMapping("/game/{gameId}")
    public ResponseEntity<Map<String, Object>> getGameDetails(HttpServletRequest request, @PathVariable String gameId) {
        String caller = AuthenticatedPlayer.resolve(request, null);
        return persistenceService.getGame(gameId)
                .filter(game -> GameStatus.COMPLETED.name().equals(game.getStatus()))
                .filter(game -> game.getPlayers() != null
                        && game.getPlayers().stream().anyMatch(p -> caller.equals(p.playerId())))
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
