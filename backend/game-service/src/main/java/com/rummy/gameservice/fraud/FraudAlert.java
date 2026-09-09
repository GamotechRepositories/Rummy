package com.rummy.gameservice.fraud;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 25: Immutable Security & Collusion Fraud Alert Record.
 */
public record FraudAlert(
        String alertId,
        String alertType,
        String severity, // LOW, MEDIUM, HIGH, CRITICAL
        String tableId,
        List<String> involvedPlayers,
        String description,
        Instant detectedAt,
        Map<String, Object> evidence
) {
    public static FraudAlert of(
            String alertType,
            String severity,
            String tableId,
            List<String> involvedPlayers,
            String description,
            Map<String, Object> evidence) {
        return new FraudAlert(
                "FA_" + UUID.randomUUID().toString().substring(0, 8),
                alertType,
                severity,
                tableId,
                involvedPlayers,
                description,
                Instant.now(),
                evidence
        );
    }
}
