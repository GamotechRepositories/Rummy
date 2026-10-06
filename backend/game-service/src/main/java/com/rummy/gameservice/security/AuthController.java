package com.rummy.gameservice.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 28: Authentication Endpoints.
 * Guest identities are always minted by the server; clients can never pick their own playerId.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int MAX_NAME_LENGTH = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = Objects.requireNonNull(jwtService);
    }

    @PostMapping("/guest")
    public ResponseEntity<Map<String, Object>> createGuestToken(@RequestBody(required = false) Map<String, String> body) {
        String displayName = sanitizeName(body != null ? body.get("name") : null);
        String playerId = "USR_" + randomId();
        return ResponseEntity.ok(tokenResponse(playerId, displayName));
    }

    /** Re-issues a token for the same player. Requires a currently valid token. */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshToken(HttpServletRequest request,
                                                            @RequestBody(required = false) Map<String, String> body) {
        Optional<Claims> claims = ApiAuthFilter.bearerToken(request).flatMap(jwtService::validateAndGetClaims);
        if (claims.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "error", "Invalid or expired JWT token"
            ));
        }
        String requestedName = body != null ? body.get("name") : null;
        String displayName = requestedName != null && !requestedName.isBlank()
                ? sanitizeName(requestedName)
                : sanitizeName(claims.get().get("name", String.class));
        return ResponseEntity.ok(tokenResponse(claims.get().getSubject(), displayName));
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

    private Map<String, Object> tokenResponse(String playerId, String displayName) {
        return Map.of(
                "token", jwtService.generateToken(playerId, displayName),
                "playerId", playerId,
                "displayName", displayName,
                "tokenType", "Bearer",
                "expiresInSeconds", jwtService.getTokenTtl().toSeconds()
        );
    }

    private static String randomId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "RoyalPlayer_" + randomId().substring(0, 4);
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}<>\"'&]", "").trim();
        if (cleaned.isEmpty()) {
            return "RoyalPlayer_" + randomId().substring(0, 4);
        }
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }
}
