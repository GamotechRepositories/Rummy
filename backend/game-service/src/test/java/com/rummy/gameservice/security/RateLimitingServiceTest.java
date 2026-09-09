package com.rummy.gameservice.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitingServiceTest {

    @Test
    void testTokenBucketAllowsUpToCapacity() {
        RateLimitingService limiter = new RateLimitingService(5, 1.0);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("client-1"), "Request " + i + " should be allowed");
        }

        // 6th request immediately exceeds capacity
        assertFalse(limiter.tryAcquire("client-1"), "Request 6 should be rejected");

        // Independent client should still have full quota
        assertTrue(limiter.tryAcquire("client-2"), "Independent client should be allowed");
    }

    @Test
    void testResetClearsClientState() {
        RateLimitingService limiter = new RateLimitingService(2, 1.0);

        assertTrue(limiter.tryAcquire("client-1"));
        assertTrue(limiter.tryAcquire("client-1"));
        assertFalse(limiter.tryAcquire("client-1"));

        limiter.reset("client-1");
        assertTrue(limiter.tryAcquire("client-1"), "After reset, tokens should be replenished to max");
    }

    @Test
    void testNullOrBlankKeyPassesThrough() {
        RateLimitingService limiter = new RateLimitingService(2, 1.0);
        assertTrue(limiter.tryAcquire(null));
        assertTrue(limiter.tryAcquire("   "));
    }
}
