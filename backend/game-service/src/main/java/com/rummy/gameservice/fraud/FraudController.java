package com.rummy.gameservice.fraud;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 25: Admin REST API for Security & Anti-Fraud Monitoring.
 */
@RestController
@RequestMapping("/api/fraud")
@CrossOrigin(origins = "*")
public class FraudController {

    private final FraudDetectionService fraudDetectionService;

    public FraudController(FraudDetectionService fraudDetectionService) {
        this.fraudDetectionService = Objects.requireNonNull(fraudDetectionService);
    }

    @GetMapping("/alerts")
    public ResponseEntity<List<FraudAlert>> getAlerts() {
        return ResponseEntity.ok(fraudDetectionService.getRecentAlerts());
    }

    @PostMapping("/verify-table-join")
    public ResponseEntity<Map<String, Object>> verifyJoin(
            @RequestParam String tableId,
            @RequestParam String playerId,
            @RequestParam(required = false) String clientIp) {

        boolean allowed = fraudDetectionService.verifyTableJoin(tableId, playerId, clientIp);
        return ResponseEntity.ok(Map.of(
                "tableId", tableId,
                "playerId", playerId,
                "allowed", allowed,
                "reason", allowed ? "PASSED_FRAUD_CHECK" : "IP_COLLUSION_DETECTED"
        ));
    }
}
