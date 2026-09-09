package com.rummy.gameservice.fraud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 25: Anti-Fraud & Collusion Detection Engine Tests")
class FraudDetectionServiceTest {

    private FraudDetectionService fraudService;

    @BeforeEach
    void setUp() {
        fraudService = new FraudDetectionService(null);
        fraudService.clearAlerts();
    }

    @Test
    @DisplayName("Allows players with distinct public IPs to join same table")
    void testDistinctIpJoin() {
        boolean player1 = fraudService.verifyTableJoin("TBL_01", "USR_A", "203.0.113.10");
        boolean player2 = fraudService.verifyTableJoin("TBL_01", "USR_B", "203.0.113.20");

        assertThat(player1).isTrue();
        assertThat(player2).isTrue();
        assertThat(fraudService.getRecentAlerts()).isEmpty();
    }

    @Test
    @DisplayName("Blocks second player and triggers HIGH severity alert when joining from identical external IP")
    void testIpCollusionBlocked() {
        boolean player1 = fraudService.verifyTableJoin("TBL_02", "USR_A", "198.51.100.55");
        assertThat(player1).isTrue();

        boolean player2 = fraudService.verifyTableJoin("TBL_02", "USR_COLLUDER", "198.51.100.55");
        assertThat(player2).isFalse();

        List<FraudAlert> alerts = fraudService.getRecentAlerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).alertType()).isEqualTo("IP_COLLUSION");
        assertThat(alerts.get(0).severity()).isEqualTo("HIGH");
        assertThat(alerts.get(0).involvedPlayers()).containsExactly("USR_A", "USR_COLLUDER");
    }

    @Test
    @DisplayName("Detects repeated intentional drops between partners as chip dumping")
    void testChipDumpingDetection() {
        fraudService.recordDropAction("TBL_03", "USR_DUMPER", "USR_BENEFICIARY");
        fraudService.recordDropAction("TBL_03", "USR_DUMPER", "USR_BENEFICIARY");
        assertThat(fraudService.getRecentAlerts()).isEmpty();

        // 3rd drop against same opponent triggers chip dumping alert
        fraudService.recordDropAction("TBL_03", "USR_DUMPER", "USR_BENEFICIARY");
        List<FraudAlert> alerts = fraudService.getRecentAlerts();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).alertType()).isEqualTo("CHIP_DUMPING");
        assertThat(alerts.get(0).severity()).isEqualTo("CRITICAL");
    }
}
