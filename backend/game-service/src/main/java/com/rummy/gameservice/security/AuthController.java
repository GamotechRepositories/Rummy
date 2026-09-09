package com.rummy.gameservice.security;

import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 28: Authentication Endpoints.
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = Objects.requireNonNull(jwtService);
    }

    @PostMapping("/guest")
    public ResponseEntity<Map<String, Object>> createGuestToken(@RequestBody(required = false) Map<String, String> body) {
        String displayName = (body != null && body.containsKey("name") && !body.get("name").isBlank())
                ? body.get("name").trim()
                : "AussiePlayer_" + UUID.randomUUID().toString().substring(0, 4);

        String playerId = (body != null && body.containsKey("playerId") && !body.get("playerId").isBlank())
                ? body.get("playerId").trim()
                : "USR_" + UUID.randomUUID().toString().substring(0, 8);

        String token = jwtService.generateToken(playerId, displayName);

        return ResponseEntity.ok(Map.of(
                "token", token,
                "playerId", playerId,
                "displayName", displayName,
                "tokenType", "Bearer"
        ));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyToken(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        return jwtService.validateAndGetClaims(token)
                .map(claims -> ResponseEntity.ok(Map.of(
                        "valid", (Object) true,
                        "playerId", claims.getSubject(),
                        "name", claims.get("name", String.class),
                        "expiresAt", claims.getExpiration()
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "valid", false,
                        "error", "Invalid or expired JWT token"
                )));
    }
}
