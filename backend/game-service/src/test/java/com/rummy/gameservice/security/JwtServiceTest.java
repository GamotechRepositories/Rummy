package com.rummy.gameservice.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService("SecretSigningKeyForTestingPurposesMustBeAtLeast32BytesLong!", 3600);
    }

    @Test
    void testGenerateAndValidateToken() {
        String token = jwtService.generateToken("player_123", "Alice");
        assertNotNull(token);

        assertTrue(jwtService.validateToken(token));
        Optional<Claims> claimsOpt = jwtService.validateAndGetClaims(token);
        assertTrue(claimsOpt.isPresent());

        Claims claims = claimsOpt.get();
        assertEquals("player_123", claims.getSubject());
        assertEquals("Alice", claims.get("name", String.class));
        assertEquals("player_123", jwtService.getPlayerIdFromToken(token));
    }

    @Test
    void testTamperedTokenFailsValidation() {
        String token = jwtService.generateToken("player_123", "Alice");
        String tamperedToken = token.substring(0, token.length() - 5) + "abcde";

        assertFalse(jwtService.validateToken(tamperedToken));
        assertNull(jwtService.getPlayerIdFromToken(tamperedToken));
        assertTrue(jwtService.validateAndGetClaims(tamperedToken).isEmpty());
    }

    @Test
    void testInvalidTokenStrings() {
        assertFalse(jwtService.validateToken(null));
        assertFalse(jwtService.validateToken(""));
        assertFalse(jwtService.validateToken("invalid.token.structure"));
    }
}
