package com.rummy.gameservice.compliance;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Phase 24: Player Protection & Responsible Gaming Profile Document.
 * Governed by the Australian Interactive Gambling Act 2001 (IGA) standards.
 */
@Document(collection = "responsible_gaming_profiles")
public class ResponsibleGamingDocument {

    @Id
    private String id;
    private String playerId;

    // Daily play limits
    private int dailySessionLimitMinutes; // 0 = unlimited
    private long dailyTokenLossLimit;     // 0 = unlimited
    private int realityCheckIntervalMinutes; // e.g. 30 or 60 minutes

    // Self-exclusion & cool-off
    private boolean isSelfExcluded;
    private Instant selfExclusionExpiresAt; // null if permanent or not excluded
    private String exclusionReason;

    private Instant coolOffExpiresAt; // temporary break (24h, 7d, etc.)

    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version;

    public ResponsibleGamingDocument() {
        this.dailySessionLimitMinutes = 120; // 2 hours default sensible play limit
        this.dailyTokenLossLimit = 5000;
        this.realityCheckIntervalMinutes = 30;
        this.isSelfExcluded = false;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public ResponsibleGamingDocument(String playerId) {
        this();
        this.playerId = playerId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public int getDailySessionLimitMinutes() {
        return dailySessionLimitMinutes;
    }

    public void setDailySessionLimitMinutes(int dailySessionLimitMinutes) {
        this.dailySessionLimitMinutes = dailySessionLimitMinutes;
    }

    public long getDailyTokenLossLimit() {
        return dailyTokenLossLimit;
    }

    public void setDailyTokenLossLimit(long dailyTokenLossLimit) {
        this.dailyTokenLossLimit = dailyTokenLossLimit;
    }

    public int getRealityCheckIntervalMinutes() {
        return realityCheckIntervalMinutes;
    }

    public void setRealityCheckIntervalMinutes(int realityCheckIntervalMinutes) {
        this.realityCheckIntervalMinutes = realityCheckIntervalMinutes;
    }

    public boolean isSelfExcluded() {
        return isSelfExcluded;
    }

    public void setSelfExcluded(boolean selfExcluded) {
        isSelfExcluded = selfExcluded;
    }

    public Instant getSelfExclusionExpiresAt() {
        return selfExclusionExpiresAt;
    }

    public void setSelfExclusionExpiresAt(Instant selfExclusionExpiresAt) {
        this.selfExclusionExpiresAt = selfExclusionExpiresAt;
    }

    public String getExclusionReason() {
        return exclusionReason;
    }

    public void setExclusionReason(String exclusionReason) {
        this.exclusionReason = exclusionReason;
    }

    public Instant getCoolOffExpiresAt() {
        return coolOffExpiresAt;
    }

    public void setCoolOffExpiresAt(Instant coolOffExpiresAt) {
        this.coolOffExpiresAt = coolOffExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
