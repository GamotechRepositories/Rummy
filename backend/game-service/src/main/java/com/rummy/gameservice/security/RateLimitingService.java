package com.rummy.gameservice.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 29: Token-Bucket Rate Limiter per player/IP to prevent spamming and DoS attacks.
 */
@Service
public class RateLimitingService {

    private final int capacity;
    private final double refillRatePerSecond;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitingService(
            @Value("${rummy.security.rate-limit.capacity:15}") int capacity,
            @Value("${rummy.security.rate-limit.refill-rate:15.0}") double refillRatePerSecond) {
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
    }

    /**
     * Attempts to consume 1 token for the specified key (e.g. playerId or IP address).
     * Returns true if allowed, false if rate limited.
     */
    public boolean tryAcquire(String key) {
        if (key == null || key.isBlank()) return true;
        return buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillRatePerSecond)).tryConsume(1);
    }

    public void reset(String key) {
        buckets.remove(key);
    }

    public int getTrackedKeyCount() {
        return buckets.size();
    }

    private static final class TokenBucket {
        private final int maxTokens;
        private final double refillRate;
        private double availableTokens;
        private long lastRefillTimestampNanos;

        TokenBucket(int maxTokens, double refillRate) {
            this.maxTokens = maxTokens;
            this.refillRate = refillRate;
            this.availableTokens = maxTokens;
            this.lastRefillTimestampNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(int tokensToConsume) {
            refill();
            if (availableTokens >= tokensToConsume) {
                availableTokens -= tokensToConsume;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsedNanos = now - lastRefillTimestampNanos;
            if (elapsedNanos > 0) {
                double tokensToAdd = (elapsedNanos / 1_000_000_000.0) * refillRate;
                availableTokens = Math.min(maxTokens, availableTokens + tokensToAdd);
                lastRefillTimestampNanos = now;
            }
        }
    }
}
