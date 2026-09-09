package com.rummy.gameservice.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 24: Responsible Gaming, Self-Exclusion & Player Protection Engine.
 * Enforces strict player protection boundaries under the Australian IGA 2001.
 */
@Service
public class ResponsibleGamingService {

    private static final Logger log = LoggerFactory.getLogger(ResponsibleGamingService.class);

    private final ResponsibleGamingRepository repository;
    private final Map<String, ResponsibleGamingDocument> inMemoryProfiles = new ConcurrentHashMap<>();

    @Autowired
    public ResponsibleGamingService(@Autowired(required = false) ResponsibleGamingRepository repository) {
        this.repository = repository;
        log.info("[ResponsibleGaming] Service initialized (Mongo persistent: {})", repository != null);
    }

    public ResponsibleGamingDocument getOrCreateProfile(String playerId) {
        if (repository != null) {
            try {
                return repository.findByPlayerId(playerId)
                        .orElseGet(() -> {
                            ResponsibleGamingDocument doc = new ResponsibleGamingDocument(playerId);
                            return repository.save(doc);
                        });
            } catch (Exception e) {
                log.warn("[ResponsibleGaming] Mongo read fallback for playerId={}: {}", playerId, e.getMessage());
            }
        }
        return inMemoryProfiles.computeIfAbsent(playerId, ResponsibleGamingDocument::new);
    }

    /**
     * Checks if a player is currently permitted to sit at a table and play.
     */
    public PlayerEligibilityStatus checkEligibility(String playerId) {
        ResponsibleGamingDocument profile = getOrCreateProfile(playerId);
        Instant now = Instant.now();

        // 1. Check permanent or timed self-exclusion
        if (profile.isSelfExcluded()) {
            if (profile.getSelfExclusionExpiresAt() == null || profile.getSelfExclusionExpiresAt().isAfter(now)) {
                return new PlayerEligibilityStatus(false, "SELF_EXCLUDED", "Account is currently self-excluded for player protection.");
            } else {
                // Exclusion expired
                profile.setSelfExcluded(false);
                profile.setSelfExclusionExpiresAt(null);
                saveProfile(profile);
            }
        }

        // 2. Check cool-off period
        if (profile.getCoolOffExpiresAt() != null && profile.getCoolOffExpiresAt().isAfter(now)) {
            long remainingMinutes = Duration.between(now, profile.getCoolOffExpiresAt()).toMinutes();
            return new PlayerEligibilityStatus(false, "COOL_OFF_ACTIVE",
                    "Cool-off period active. " + remainingMinutes + " minutes remaining.");
        }

        return new PlayerEligibilityStatus(true, "ELIGIBLE", "Player is eligible to play.");
    }

    /**
     * Updates player responsible gaming limits.
     */
    public ResponsibleGamingDocument updateLimits(String playerId, int sessionMinutes, long lossLimit, int realityCheckMinutes) {
        ResponsibleGamingDocument profile = getOrCreateProfile(playerId);
        profile.setDailySessionLimitMinutes(Math.max(0, sessionMinutes));
        profile.setDailyTokenLossLimit(Math.max(0, lossLimit));
        profile.setRealityCheckIntervalMinutes(Math.max(15, realityCheckMinutes));
        profile.setUpdatedAt(Instant.now());
        return saveProfile(profile);
    }

    /**
     * Applies a temporary cool-off break (e.g. 24 hours, 7 days, 30 days).
     */
    public ResponsibleGamingDocument applyCoolOff(String playerId, Duration duration) {
        ResponsibleGamingDocument profile = getOrCreateProfile(playerId);
        profile.setCoolOffExpiresAt(Instant.now().plus(duration));
        profile.setUpdatedAt(Instant.now());
        log.info("[ResponsibleGaming] Player {} initiated cool-off for {} hours", playerId, duration.toHours());
        return saveProfile(profile);
    }

    /**
     * Triggers self-exclusion (timed or permanent).
     */
    public ResponsibleGamingDocument selfExclude(String playerId, Duration duration, String reason) {
        ResponsibleGamingDocument profile = getOrCreateProfile(playerId);
        profile.setSelfExcluded(true);
        profile.setSelfExclusionExpiresAt(duration != null ? Instant.now().plus(duration) : null);
        profile.setExclusionReason(reason);
        profile.setUpdatedAt(Instant.now());
        log.warn("[ResponsibleGaming] Player {} self-excluded (Permanent: {}, Reason: {})",
                playerId, duration == null, reason);
        return saveProfile(profile);
    }

    private ResponsibleGamingDocument saveProfile(ResponsibleGamingDocument profile) {
        inMemoryProfiles.put(profile.getPlayerId(), profile);
        if (repository != null) {
            try {
                return repository.save(profile);
            } catch (Exception e) {
                log.warn("[ResponsibleGaming] Mongo save fallback for playerId={}: {}", profile.getPlayerId(), e.getMessage());
            }
        }
        return profile;
    }

    public record PlayerEligibilityStatus(boolean isEligible, String code, String message) {}
}
