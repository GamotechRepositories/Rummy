package com.rummy.gameservice.compliance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 24: Responsible Gaming & Player Protection Tests")
class ResponsibleGamingServiceTest {

    private ResponsibleGamingService service;

    @BeforeEach
    void setUp() {
        service = new ResponsibleGamingService(null);
    }

    @Test
    @DisplayName("New player profile has sensible default limits")
    void testDefaultProfile() {
        ResponsibleGamingDocument profile = service.getOrCreateProfile("USR_AU_100");
        assertThat(profile.getPlayerId()).isEqualTo("USR_AU_100");
        assertThat(profile.getDailySessionLimitMinutes()).isEqualTo(120);
        assertThat(profile.getDailyTokenLossLimit()).isEqualTo(5000L);
        assertThat(profile.isSelfExcluded()).isFalse();

        ResponsibleGamingService.PlayerEligibilityStatus status = service.checkEligibility("USR_AU_100");
        assertThat(status.isEligible()).isTrue();
    }

    @Test
    @DisplayName("Updating limits successfully persists settings")
    void testUpdateLimits() {
        ResponsibleGamingDocument updated = service.updateLimits("USR_AU_100", 60, 2000L, 15);
        assertThat(updated.getDailySessionLimitMinutes()).isEqualTo(60);
        assertThat(updated.getDailyTokenLossLimit()).isEqualTo(2000L);
        assertThat(updated.getRealityCheckIntervalMinutes()).isEqualTo(15);
    }

    @Test
    @DisplayName("Cool-off break temporarily blocks player eligibility")
    void testCoolOff() {
        service.applyCoolOff("USR_AU_100", Duration.ofHours(24));
        ResponsibleGamingService.PlayerEligibilityStatus status = service.checkEligibility("USR_AU_100");

        assertThat(status.isEligible()).isFalse();
        assertThat(status.code()).isEqualTo("COOL_OFF_ACTIVE");
    }

    @Test
    @DisplayName("Self-exclusion blocks player immediately with reason")
    void testSelfExclusion() {
        service.selfExclude("USR_AU_100", null, "Player requested permanent break");
        ResponsibleGamingService.PlayerEligibilityStatus status = service.checkEligibility("USR_AU_100");

        assertThat(status.isEligible()).isFalse();
        assertThat(status.code()).isEqualTo("SELF_EXCLUDED");
    }
}
