package com.rummy.gameservice.compliance;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 24: REST API for Responsible Gaming Settings, Reality Checks, and Self-Exclusion.
 */
@RestController
@RequestMapping("/api/responsible-gambling")
@CrossOrigin(origins = "*")
public class ResponsibleGamingController {

    private final ResponsibleGamingService responsibleGamingService;

    public ResponsibleGamingController(ResponsibleGamingService responsibleGamingService) {
        this.responsibleGamingService = Objects.requireNonNull(responsibleGamingService);
    }

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(@RequestParam String playerId) {
        ResponsibleGamingDocument profile = responsibleGamingService.getOrCreateProfile(playerId);
        ResponsibleGamingService.PlayerEligibilityStatus status = responsibleGamingService.checkEligibility(playerId);

        return ResponseEntity.ok(Map.of(
                "playerId", playerId,
                "dailySessionLimitMinutes", profile.getDailySessionLimitMinutes(),
                "dailyTokenLossLimit", profile.getDailyTokenLossLimit(),
                "realityCheckIntervalMinutes", profile.getRealityCheckIntervalMinutes(),
                "isSelfExcluded", profile.isSelfExcluded(),
                "selfExclusionExpiresAt", profile.getSelfExclusionExpiresAt() != null ? profile.getSelfExclusionExpiresAt().toString() : "PERMANENT_OR_NONE",
                "coolOffExpiresAt", profile.getCoolOffExpiresAt() != null ? profile.getCoolOffExpiresAt().toString() : "NONE",
                "eligibilityStatus", status,
                "helplineNotice", "Free confidential support available 24/7 at Gambling Help Online: 1800 858 858 or BetStop.gov.au"
        ));
    }

    @PostMapping("/limits")
    public ResponseEntity<Map<String, Object>> updateLimits(
            @RequestParam String playerId,
            @RequestParam(defaultValue = "120") int sessionMinutes,
            @RequestParam(defaultValue = "5000") long lossLimit,
            @RequestParam(defaultValue = "30") int realityCheckMinutes) {

        ResponsibleGamingDocument updated = responsibleGamingService.updateLimits(playerId, sessionMinutes, lossLimit, realityCheckMinutes);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Responsible play limits updated successfully",
                "dailySessionLimitMinutes", updated.getDailySessionLimitMinutes(),
                "dailyTokenLossLimit", updated.getDailyTokenLossLimit(),
                "realityCheckIntervalMinutes", updated.getRealityCheckIntervalMinutes()
        ));
    }

    @PostMapping("/cool-off")
    public ResponseEntity<Map<String, Object>> applyCoolOff(
            @RequestParam String playerId,
            @RequestParam(defaultValue = "24") int hours) {

        ResponsibleGamingDocument updated = responsibleGamingService.applyCoolOff(playerId, Duration.ofHours(hours));
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Cool-off period activated for " + hours + " hours.",
                "coolOffExpiresAt", updated.getCoolOffExpiresAt().toString()
        ));
    }

    @PostMapping("/self-exclude")
    public ResponseEntity<Map<String, Object>> selfExclude(
            @RequestParam String playerId,
            @RequestParam(defaultValue = "0") int days, // 0 = permanent
            @RequestParam(defaultValue = "Player request") String reason) {

        Duration duration = days > 0 ? Duration.ofDays(days) : null;
        ResponsibleGamingDocument updated = responsibleGamingService.selfExclude(playerId, duration, reason);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", duration == null ? "Account permanently self-excluded." : "Account self-excluded for " + days + " days.",
                "isSelfExcluded", true,
                "expiresAt", updated.getSelfExclusionExpiresAt() != null ? updated.getSelfExclusionExpiresAt().toString() : "PERMANENT"
        ));
    }

    @GetMapping("/check-eligibility")
    public ResponseEntity<ResponsibleGamingService.PlayerEligibilityStatus> checkEligibility(@RequestParam String playerId) {
        return ResponseEntity.ok(responsibleGamingService.checkEligibility(playerId));
    }
}
