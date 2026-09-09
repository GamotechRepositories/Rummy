package com.rummy.gameservice.fraud;

import com.rummy.gameservice.kafka.GameEventProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Phase 25: Real-time Anti-Fraud & Collusion Detection Engine.
 * Monitors player network footprints, table clustering, and anomalous gameplay patterns.
 */
@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    private final GameEventProducer eventProducer;

    // In-memory bounded alert history for admin telemetry
    private final Deque<FraudAlert> recentAlerts = new ConcurrentLinkedDeque<>();
    private static final int MAX_ALERT_HISTORY = 100;

    // Per-table player IP tracking: tableId -> (playerId -> clientIp)
    private final Map<String, Map<String, String>> tableIpMap = new ConcurrentHashMap<>();

    // Drop tracking for chip dumping detection: (playerA -> (playerB -> consecutiveDrops))
    private final Map<String, Map<String, Integer>> dropPairHistory = new ConcurrentHashMap<>();

    @Autowired
    public FraudDetectionService(@Autowired(required = false) GameEventProducer eventProducer) {
        this.eventProducer = eventProducer;
        log.info("[FraudEngine] FraudDetectionService initialized");
    }

    /**
     * Verifies if a player is permitted to join a table without IP collusion.
     * Rejects join if another player on the table shares the exact same non-local IP address.
     */
    public boolean verifyTableJoin(String tableId, String playerId, String clientIp) {
        if (clientIp == null || clientIp.isBlank() || isLocalhost(clientIp)) {
            // Localhost / test IPs permitted for development & simulation
            registerPlayerIp(tableId, playerId, clientIp);
            return true;
        }

        Map<String, String> playersOnTable = tableIpMap.computeIfAbsent(tableId, k -> new ConcurrentHashMap<>());
        for (Map.Entry<String, String> entry : playersOnTable.entrySet()) {
            if (!entry.getKey().equals(playerId) && clientIp.equalsIgnoreCase(entry.getValue())) {
                // Collusion alert triggered!
                FraudAlert alert = FraudAlert.of(
                        "IP_COLLUSION",
                        "HIGH",
                        tableId,
                        List.of(entry.getKey(), playerId),
                        "Detected identical IP address (" + clientIp + ") between concurrent table seats.",
                        Map.of("ipAddress", clientIp, "player1", entry.getKey(), "player2", playerId)
                );
                recordAlert(alert);
                log.warn("[FraudEngine] Blocked table join due to IP collusion: table={}, players=[{}, {}], ip={}",
                        tableId, entry.getKey(), playerId, clientIp);
                return false;
            }
        }

        registerPlayerIp(tableId, playerId, clientIp);
        return true;
    }

    /**
     * Inspects a game drop action to flag potential chip dumping between colluding partners.
     */
    public void recordDropAction(String tableId, String droppingPlayerId, String declaringPlayerId) {
        if (declaringPlayerId == null || droppingPlayerId.equals(declaringPlayerId)) {
            return;
        }

        dropPairHistory.computeIfAbsent(droppingPlayerId, k -> new ConcurrentHashMap<>())
                .merge(declaringPlayerId, 1, Integer::sum);

        int count = dropPairHistory.get(droppingPlayerId).get(declaringPlayerId);
        if (count >= 3) {
            FraudAlert alert = FraudAlert.of(
                    "CHIP_DUMPING",
                    "CRITICAL",
                    tableId,
                    List.of(droppingPlayerId, declaringPlayerId),
                    "Suspicious drop pattern detected: Player " + droppingPlayerId + " dropped " + count + " times against " + declaringPlayerId,
                    Map.of("dropCount", count, "droppingPlayer", droppingPlayerId, "beneficiary", declaringPlayerId)
            );
            recordAlert(alert);
        }
    }

    public void unregisterPlayerFromTable(String tableId, String playerId) {
        Map<String, String> map = tableIpMap.get(tableId);
        if (map != null) {
            map.remove(playerId);
            if (map.isEmpty()) {
                tableIpMap.remove(tableId);
            }
        }
    }

    public void recordAlert(FraudAlert alert) {
        recentAlerts.addFirst(alert);
        while (recentAlerts.size() > MAX_ALERT_HISTORY) {
            recentAlerts.removeLast();
        }
        if (eventProducer != null) {
            eventProducer.publishAuditEvent(alert.tableId(), "FRAUD_ALERT", alert);
        }
    }

    public List<FraudAlert> getRecentAlerts() {
        return new ArrayList<>(recentAlerts);
    }

    public void clearAlerts() {
        recentAlerts.clear();
        dropPairHistory.clear();
        tableIpMap.clear();
    }

    private void registerPlayerIp(String tableId, String playerId, String clientIp) {
        tableIpMap.computeIfAbsent(tableId, k -> new ConcurrentHashMap<>())
                .put(playerId, clientIp != null ? clientIp : "127.0.0.1");
    }

    private boolean isLocalhost(String ip) {
        return "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "localhost".equalsIgnoreCase(ip);
    }
}
