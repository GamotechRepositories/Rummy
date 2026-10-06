package com.rummy.gameservice.session;

import com.rummy.gameservice.security.AuthenticatedPlayer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * REST API for soft-reconnect / resume-to-table.
 */
@RestController
@RequestMapping("/api/session")
public class PlayerSessionController {

    private final PlayerSessionService sessionService;

    public PlayerSessionController(PlayerSessionService sessionService) {
        this.sessionService = Objects.requireNonNull(sessionService);
    }

    /**
     * GET /api/session/active
     * → { active: true, tableId, gameStatus, ... } or { active: false }
     */
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getActiveSession(HttpServletRequest request,
                                                                @RequestParam(required = false) String playerId) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        return sessionService.findActiveSession(owner)
                .<ResponseEntity<Map<String, Object>>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of("active", false)));
    }

    /**
     * Explicit clear (e.g. client Leave before WS is up).
     */
    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearSession(HttpServletRequest request,
                                                            @RequestBody(required = false) Map<String, String> body) {
        String owner = AuthenticatedPlayer.resolve(request, body != null ? body.get("playerId") : null);
        sessionService.clearPlayerBinding(owner);
        return ResponseEntity.ok(Map.of("cleared", true, "playerId", owner));
    }
}
