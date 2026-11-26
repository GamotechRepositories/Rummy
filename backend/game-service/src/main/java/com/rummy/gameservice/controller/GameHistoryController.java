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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    public ResponseEntity<Map<String, Object>> getPlayerHistory(HttpServletRequest request, String playerId) {
        return getPlayerHistory(request, playerId, null, false);
    }

    public ResponseEntity<Map<String, Object>> getPlayerHistory(HttpServletRequest request, String playerId, String rulesets) {
        return getPlayerHistory(request, playerId, rulesets, false);
    }

    @GetMapping("/player/{playerId}")
    public ResponseEntity<Map<String, Object>> getPlayerHistory(
            HttpServletRequest request,
            @PathVariable String playerId,
            @RequestParam(name = "rulesets", required = false) String rulesets,
            @RequestParam(name = "excludeTournaments", required = false, defaultValue = "false") boolean excludeTournaments) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        Set<String> allowedRulesets = parseRulesets(rulesets);
        List<GameResultDocument> results = (allowedRulesets.isEmpty() && !excludeTournaments)
                ? persistenceService.getPlayerHistory(owner)
                : persistenceService.getPlayerHistory(owner, allowedRulesets, excludeTournaments);
        UserProfileDocument profile = persistenceService.getOrCreateUserProfile(owner, owner);

        boolean filtered = !allowedRulesets.isEmpty() || excludeTournaments;
        double winRate;
        if (filtered) {
            long won = results.stream().filter(GameResultDocument::isWon).count();
            winRate = results.isEmpty() ? 0.0 : ((double) won / results.size()) * 100.0;
        } else {
            winRate = profile.getGamesPlayed() > 0
                    ? ((double) profile.getGamesWon() / profile.getGamesPlayed()) * 100.0
                    : 0.0;
        }

        return ResponseEntity.ok(Map.of(
                "playerId", owner,
                "profile", profile,
                "winRate", Math.round(winRate * 10.0) / 10.0,
                "results", results,
                "filtered", filtered
        ));
    }

    private static Set<String> parseRulesets(String rulesets) {
        Set<String> parsed = new LinkedHashSet<>();
        if (rulesets == null || rulesets.isBlank()) {
            return parsed;
        }
        for (String token : rulesets.split(",")) {
            if (token != null && !token.isBlank()) {
                parsed.add(token.trim().toUpperCase(Locale.ROOT));
            }
        }
        return parsed;
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
