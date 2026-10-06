package com.rummy.gameservice.compliance;

import com.rummy.gameservice.security.AuthenticatedPlayer;
import jakarta.servlet.http.HttpServletRequest;
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
public class ResponsibleGamingController {

    private final ResponsibleGamingService responsibleGamingService;

    public ResponsibleGamingController(ResponsibleGamingService responsibleGamingService) {
        this.responsibleGamingService = Objects.requireNonNull(responsibleGamingService);
    }

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpServletRequest request,
                                                           @RequestParam(required = false) String playerId) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        ResponsibleGamingDocument profile = responsibleGamingService.getOrCreateProfile(owner);
        ResponsibleGamingService.PlayerEligibilityStatus status = responsibleGamingService.checkEligibility(owner);

        return ResponseEntity.ok(Map.of(
                "playerId", owner,
                "dailySessionLimitMinutes", profile.getDailySessionLimitMinutes(),
                "dailyTokenLossLimit", profile.getDailyTokenLossLimit(),
                "realityCheckIntervalMinutes", profile.getRealityCheckIntervalMinutes(),
                "isSelfExcluded", profile.isSelfExcluded(),
                "selfExclusionExpiresAt", profile.getSelfExclusionExpiresAt() != null ? profile.getSelfExclusionExpiresAt().toString() : "PERMANENT_OR_NONE",
                "coolOffExpiresAt", profile.getCoolOffExpiresAt() != null ? profile.getCoolOffExpiresAt().toString() : "NONE",
                "eligibilityStatus", status,
                "helplineNotice", "Free confidential support and responsible gaming assistance available 24/7."
        ));
    }

    @PostMapping("/limits")
    public ResponseEntity<Map<String, Object>> updateLimits(
            HttpServletRequest request,
            @RequestParam(required = false) String playerId,
            @RequestParam(defaultValue = "120") int sessionMinutes,
            @RequestParam(defaultValue = "5000") long lossLimit,
            @RequestParam(defaultValue = "30") int realityCheckMinutes) {

        String owner = AuthenticatedPlayer.resolve(request, playerId);
        ResponsibleGamingDocument updated = responsibleGamingService.updateLimits(owner, sessionMinutes, lossLimit, realityCheckMinutes);
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
            HttpServletRequest request,
            @RequestParam(required = false) String playerId,
            @RequestParam(defaultValue = "24") int hours) {

        String owner = AuthenticatedPlayer.resolve(request, playerId);
        ResponsibleGamingDocument updated = responsibleGamingService.applyCoolOff(owner, Duration.ofHours(hours));
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Cool-off period activated for " + hours + " hours.",
                "coolOffExpiresAt", updated.getCoolOffExpiresAt().toString()
        ));
    }

    @PostMapping("/self-exclude")
    public ResponseEntity<Map<String, Object>> selfExclude(
            HttpServletRequest request,
            @RequestParam(required = false) String playerId,
            @RequestParam(defaultValue = "0") int days, // 0 = permanent
            @RequestParam(defaultValue = "Player request") String reason) {

        String owner = AuthenticatedPlayer.resolve(request, playerId);
        Duration duration = days > 0 ? Duration.ofDays(days) : null;
        ResponsibleGamingDocument updated = responsibleGamingService.selfExclude(owner, duration, reason);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", duration == null ? "Account permanently self-excluded." : "Account self-excluded for " + days + " days.",
                "isSelfExcluded", true,
                "expiresAt", updated.getSelfExclusionExpiresAt() != null ? updated.getSelfExclusionExpiresAt().toString() : "PERMANENT"
        ));
    }

    @GetMapping("/check-eligibility")
    public ResponseEntity<ResponsibleGamingService.PlayerEligibilityStatus> checkEligibility(
            HttpServletRequest request,
            @RequestParam(required = false) String playerId) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        return ResponseEntity.ok(responsibleGamingService.checkEligibility(owner));
    }
}
