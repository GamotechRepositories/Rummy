package com.rummy.gameservice.controller;

import com.rummy.engine.model.GameStatus;
import com.rummy.gameservice.persistence.GamePersistenceService;
import com.rummy.gameservice.persistence.document.GameDocument;
import com.rummy.gameservice.persistence.document.GameEventDocument;
import com.rummy.gameservice.persistence.document.GameResultDocument;
import com.rummy.gameservice.persistence.document.UserProfileDocument;
import com.rummy.gameservice.security.AuthenticatedPlayer;
import com.rummy.gameservice.security.VariantAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * REST API for querying player match history, career stats, and game replay events.
 */
@RestController
@RequestMapping("/api/history")
public class GameHistoryController {

    private final GamePersistenceService persistenceService;
    private final VariantAccessPolicy variantPolicy;

    public GameHistoryController(GamePersistenceService persistenceService) {
        this(persistenceService, null);
    }

    @Autowired
    public GameHistoryController(
            GamePersistenceService persistenceService,
            @Autowired(required = false) VariantAccessPolicy variantPolicy) {
        this.persistenceService = Objects.requireNonNull(persistenceService);
        this.variantPolicy = variantPolicy;
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
        Set<String> allowedRulesets;
        if (variantPolicy != null) {
            VariantAccessPolicy.FilterScope scope = variantPolicy.resolveScope(request, rulesets);
            if (scope.restricted() && scope.rulesets().isEmpty()) {
                UserProfileDocument profile = scopedProfile(persistenceService.getOrCreateUserProfile(owner, owner), List.of());
                return ResponseEntity.ok(Map.of(
                        "playerId", owner,
                        "profile", profile,
                        "winRate", 0.0,
                        "results", List.of(),
                        "filtered", true
                ));
            }
            allowedRulesets = scope.restricted() ? scope.rulesets() : Set.of();
        } else {
            allowedRulesets = parseRulesets(rulesets);
        }
        List<GameResultDocument> results = (allowedRulesets.isEmpty() && !excludeTournaments)
                ? persistenceService.getPlayerHistory(owner)
                : persistenceService.getPlayerHistory(owner, allowedRulesets, excludeTournaments);
        UserProfileDocument globalProfile = persistenceService.getOrCreateUserProfile(owner, owner);

        boolean filtered = !allowedRulesets.isEmpty() || excludeTournaments;
        UserProfileDocument responseProfile = filtered ? scopedProfile(globalProfile, results) : globalProfile;
        double winRate;
        if (filtered) {
            winRate = responseProfile.getGamesPlayed() > 0
                    ? ((double) responseProfile.getGamesWon() / responseProfile.getGamesPlayed()) * 100.0
                    : 0.0;
        } else {
            winRate = globalProfile.getGamesPlayed() > 0
                    ? ((double) globalProfile.getGamesWon() / globalProfile.getGamesPlayed()) * 100.0
                    : 0.0;
        }

        return ResponseEntity.ok(Map.of(
                "playerId", owner,
                "profile", responseProfile,
                "winRate", Math.round(winRate * 10.0) / 10.0,
                "results", results,
                "filtered", filtered
        ));
    }

    private static UserProfileDocument scopedProfile(UserProfileDocument base, List<GameResultDocument> results) {
        UserProfileDocument copy = new UserProfileDocument(base.getUserId(), base.getDisplayName(), base.getVirtualPoints());
        copy.setCreatedAt(base.getCreatedAt());
        copy.setUpdatedAt(base.getUpdatedAt());
        copy.setGamesPlayed(results.size());
        copy.setGamesWon((int) results.stream().filter(GameResultDocument::isWon).count());
        copy.setTotalScore(results.stream().mapToInt(GameResultDocument::getFinalScore).sum());
        return copy;
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
        Optional<GameDocument> matchOpt = persistenceService.getGame(gameId)
                .filter(game -> GameStatus.COMPLETED.name().equals(game.getStatus()))
                .filter(game -> game.getPlayers() != null
                        && game.getPlayers().stream().anyMatch(p -> caller.equals(p.playerId())));
        if (matchOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        GameDocument game = matchOpt.get();
        if (variantPolicy != null && !variantPolicy.isRulesetAllowed(request, game.getRulesetId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", "VARIANT_NOT_ALLOWED",
                    "message", "This match belongs to another game variant."
            ));
        }
        List<GameEventDocument> events = persistenceService.getGameEvents(gameId);
        return ResponseEntity.ok(Map.of(
                "game", game,
                "events", events
        ));
    }
}
