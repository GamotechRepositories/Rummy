package com.rummy.gameservice.operator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.gameservice.security.AuthController;
import com.rummy.gameservice.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MockOperatorPlayTest {

    @Test
    @DisplayName("play generates a fresh test player each call and redirects back to allowed returnTo origin")
    void playGeneratesUniquePlayersAndSafeRedirect() {
        OperatorRegistry registry = OperatorRegistry.inMemory();
        JwtService jwt = new JwtService("SecretSigningKeyForTestingPurposesMustBeAtLeast32BytesLong!", 1);
        OperatorLaunchService launches = new OperatorLaunchService(registry, jwt, null, "http://192.168.1.44:5173", 120, 12);
        MockOperatorController controller = new MockOperatorController(
                registry,
                launches,
                null,
                null,
                new ObjectMapper(),
                "http://localhost:8081",
                "",
                false,
                "http://localhost:*,http://192.168.*.*:*"
        );

        ResponseEntity<?> first = controller.play(null, null, null, "http://localhost:5174/lobby");
        ResponseEntity<?> second = controller.play(null, null, null, "http://192.168.1.44:5175");
        ResponseEntity<?> evil = controller.play(null, null, null, "https://evil.example.com");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(evil.getStatusCode()).isEqualTo(HttpStatus.FOUND);

        URI uri1 = first.getHeaders().getLocation();
        URI uri2 = second.getHeaders().getLocation();
        URI uriEvil = evil.getHeaders().getLocation();

        assertThat(uri1).isNotNull();
        assertThat(uri1.toString()).startsWith("http://localhost:5174/?launch=");
        assertThat(uri2).isNotNull();
        assertThat(uri2.toString()).startsWith("http://192.168.1.44:5175/?launch=");
        assertThat(uriEvil).isNotNull();
        assertThat(uriEvil.toString()).startsWith("http://192.168.1.44:5173/?launch=");

        String code1 = uri1.getQuery().substring("launch=".length());
        String code2 = uri2.getQuery().substring("launch=".length());
        var session1 = launches.redeem(code1).orElseThrow();
        var session2 = launches.redeem(code2).orElseThrow();

        assertThat(session1.playerId()).isNotEqualTo(session2.playerId());
        assertThat(session1.displayName()).startsWith("tester_");
        assertThat(session2.displayName()).startsWith("tester_");
    }

    @Test
    @DisplayName("AuthController.publicConfig reports mockOperatorEnabled and mockOperatorKeyRequired flags")
    void authConfigReportsMockKeyRequirement() {
        JwtService jwt = new JwtService("SecretSigningKeyForTestingPurposesMustBeAtLeast32BytesLong!", 1);
        AuthController openMock = new AuthController(jwt, 12, true, "");
        AuthController keyedMock = new AuthController(jwt, 12, true, "secret-key-123");

        Map<String, Object> body1 = openMock.publicConfig().getBody();
        Map<String, Object> body2 = keyedMock.publicConfig().getBody();

        assertThat(body1).containsEntry("mockOperatorEnabled", true).containsEntry("mockOperatorKeyRequired", false);
        assertThat(body2).containsEntry("mockOperatorEnabled", true).containsEntry("mockOperatorKeyRequired", true);
    }
}
