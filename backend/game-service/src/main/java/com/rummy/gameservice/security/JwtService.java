package com.rummy.gameservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Phase 28: Cryptographic JWT Token Provider & Authenticator.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final int MIN_SECRET_BYTES = 32;
    public static final String OPERATOR_CLAIM = "op";

    private final SecretKey signingKey;
    private final Duration tokenTtl;

    @Autowired
    public JwtService(
            @Value("${rummy.security.jwt.secret:}") String secret,
            @Value("${rummy.security.jwt.ttl-hours:720}") long ttlHours,
            @Value("${rummy.security.jwt.require-secret:false}") boolean requireSecret) {
        this.signingKey = Keys.hmacShaKeyFor(resolveKeyBytes(secret, requireSecret));
        this.tokenTtl = Duration.ofHours(ttlHours);
    }

    public JwtService(String secret, long ttlHours) {
        this(secret, ttlHours, true);
    }

    private static byte[] resolveKeyBytes(String secret, boolean requireSecret) {
        if (secret == null || secret.isBlank()) {
            if (requireSecret) {
                throw new IllegalStateException("RUMMY_JWT_SECRET must be set (at least " + MIN_SECRET_BYTES + " bytes)");
            }
            log.warn("[JWT] RUMMY_JWT_SECRET not set — using a random per-process key. "
                    + "Tokens will be invalid after restart and across nodes. Never run production like this.");
            byte[] random = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            return random;
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("RUMMY_JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        return keyBytes;
    }

    /**
     * Generates a signed JWT session token for a player.
     */
    public String generateToken(String playerId, String displayName) {
        return generateToken(playerId, displayName, null, tokenTtl);
    }

    /** A player session token; {@code operatorId} is the operator that launched the player (may be null). */
    public String generateToken(String playerId, String displayName, String operatorId, Duration ttl) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(playerId)
                .claim("name", displayName)
                .claim("role", "PLAYER");
        if (operatorId != null) {
            builder.claim(OPERATOR_CLAIM, operatorId);
        }
        return builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
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
            log.debug("[JWT] Invalid token validation attempt: {}", e.getMessage());
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

    public Duration getTokenTtl() {
        return tokenTtl;
    }
}
