package com.rummy.gameservice.session;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * REST API for soft-reconnect / resume-to-table.
 */
@RestController
@RequestMapping("/api/session")
@CrossOrigin(origins = "*")
public class PlayerSessionController {

    private final PlayerSessionService sessionService;

    public PlayerSessionController(PlayerSessionService sessionService) {
        this.sessionService = Objects.requireNonNull(sessionService);
    }

    /**
     * GET /api/session/active?playerId=PLAYER_123
     * → { active: true, tableId, gameStatus, ... } or { active: false }
     */
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getActiveSession(@RequestParam String playerId) {
        return sessionService.findActiveSession(playerId)
                .<ResponseEntity<Map<String, Object>>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of("active", false)));
    }

    /**
     * Explicit clear (e.g. client Leave before WS is up).
     */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearSession(@RequestBody Map<String, String> body) {
        String playerId = body.get("playerId");
        sessionService.clearPlayerBinding(playerId);
        return ResponseEntity.ok(Map.of("cleared", true, "playerId", playerId != null ? playerId : ""));
    }
}
