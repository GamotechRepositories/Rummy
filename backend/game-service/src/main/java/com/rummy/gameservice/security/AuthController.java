package com.rummy.gameservice.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Session token endpoints. Players only get a session through an operator launch
 * ({@code /api/operator/session}); there is no guest or self sign-up.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtService jwtService;
    private final Duration sessionTtl;

    @Autowired
    public AuthController(JwtService jwtService,
                          @Value("${rummy.operator.session-ttl-hours:12}") long sessionTtlHours) {
        this.jwtService = Objects.requireNonNull(jwtService);
        this.sessionTtl = Duration.ofHours(sessionTtlHours);
    }

    /** Re-issues a token for the same operator player. Requires a currently valid token. */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshToken(HttpServletRequest request,
                                                            @RequestBody(required = false) Map<String, String> body) {
        Optional<Claims> claims = ApiAuthFilter.bearerToken(request).flatMap(jwtService::validateAndGetClaims);
        String operatorId = claims.map(c -> c.get(JwtService.OPERATOR_CLAIM, String.class)).orElse(null);
        if (claims.isEmpty() || operatorId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "error", "Invalid or expired session, open the game from your operator again"
            ));
        }
        String requestedName = body != null ? body.get("name") : null;
        String displayName = requestedName != null && !requestedName.isBlank()
                ? PlayerNames.sanitize(requestedName)
                : PlayerNames.sanitize(claims.get().get("name", String.class));
        String playerId = claims.get().getSubject();
        return ResponseEntity.ok(Map.of(
                "token", jwtService.generateToken(playerId, displayName, operatorId, sessionTtl),
                "playerId", playerId,
                "displayName", displayName,
                "tokenType", "Bearer",
                "expiresInSeconds", sessionTtl.toSeconds()
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
