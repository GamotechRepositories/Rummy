package com.rummy.gameservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 28: Cryptographic JWT Token Provider & Authenticator.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey signingKey;
    private final Duration tokenTtl;

    public JwtService(
            @Value("${rummy.security.jwt.secret:australian-rummy-master-secret-key-production-grade-32-chars!}") String secret,
            @Value("${rummy.security.jwt.ttl-hours:24}") long ttlHours) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.tokenTtl = Duration.ofHours(ttlHours);
    }

    /**
     * Generates a signed JWT session token for a player.
     */
    public String generateToken(String playerId, String displayName) {
        Instant now = Instant.now();
        Instant expiry = now.plus(tokenTtl);

        return Jwts.builder()
                .subject(playerId)
                .claim("name", displayName)
                .claim("role", "PLAYER")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses and validates a JWT token, returning the validated claims.
     */
    public Optional<Claims> validateAndGetClaims(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (claims.getExpiration().before(new Date())) {
                log.warn("[JWT] Expired token for subject={}", claims.getSubject());
                return Optional.empty();
            }

            return Optional.of(claims);
        } catch (Exception e) {
            log.warn("[JWT] Invalid token validation attempt: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public boolean validateToken(String token) {
        return validateAndGetClaims(token).isPresent();
    }

    public String getPlayerIdFromToken(String token) {
        return extractPlayerId(token).orElse(null);
    }

    public Optional<String> extractPlayerId(String token) {
        return validateAndGetClaims(token).map(Claims::getSubject);
    }
}
