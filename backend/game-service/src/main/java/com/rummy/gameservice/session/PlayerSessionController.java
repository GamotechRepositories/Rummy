package com.rummy.gameservice.session;

import com.rummy.gameservice.security.AuthenticatedPlayer;
import com.rummy.gameservice.security.VariantAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final VariantAccessPolicy variantPolicy;

    public PlayerSessionController(PlayerSessionService sessionService) {
        this(sessionService, null);
    }

    @Autowired
    public PlayerSessionController(
            PlayerSessionService sessionService,
            @Autowired(required = false) VariantAccessPolicy variantPolicy) {
        this.sessionService = Objects.requireNonNull(sessionService);
        this.variantPolicy = variantPolicy;
    }

    /**
     * GET /api/session/active
     * → { active: true, tableId, rulesetId, gameStatus, ... } or { active: false }
     */
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getActiveSession(HttpServletRequest request,
                                                                @RequestParam(required = false) String playerId,
                                                                @RequestParam(name = "rulesets", required = false) String rulesets) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        if (variantPolicy != null) {
            VariantAccessPolicy.FilterScope scope = variantPolicy.resolveScope(request, rulesets);
            if (scope.restricted()) {
                if (scope.rulesets().isEmpty()) {
                    return ResponseEntity.ok(Map.of("active", false));
                }
                return sessionService.findActiveSession(owner, scope.rulesets())
                        .<ResponseEntity<Map<String, Object>>>map(ResponseEntity::ok)
                        .orElseGet(() -> ResponseEntity.ok(Map.of("active", false)));
            }
        } else if (rulesets != null && !rulesets.isBlank()) {
            return sessionService.findActiveSession(owner, VariantAccessPolicy.parseCsv(rulesets))
                    .<ResponseEntity<Map<String, Object>>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.ok(Map.of("active", false)));
        }
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
