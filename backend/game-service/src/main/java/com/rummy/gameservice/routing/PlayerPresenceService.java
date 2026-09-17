package com.rummy.gameservice.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 17: Real-time Player Presence & Session Heartbeat Tracker.
 * Local map is authoritative for this JVM so matchmaking never blocks on a dead Redis.
 */
@Service
public class PlayerPresenceService {

    private static final Logger log = LoggerFactory.getLogger(PlayerPresenceService.class);

    private static final String PRESENCE_PREFIX = "rummy:presence:";
    private static final Duration PRESENCE_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, Instant> localPresenceMap = new ConcurrentHashMap<>();

    @Autowired
    public PlayerPresenceService(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void updatePresence(String playerId) {
        Instant now = Instant.now();
        localPresenceMap.put(playerId, now);
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(PRESENCE_PREFIX + playerId, String.valueOf(now.toEpochMilli()), PRESENCE_TTL);
            } catch (Exception e) {
                log.warn("[Presence] Redis presence update failed for {}: {}", playerId, e.getMessage());
            }
        }
    }

    public boolean isPlayerOnline(String playerId) {
        // Prefer in-memory presence so a down/slow Redis cannot stall matchmaking.
        Instant lastSeen = localPresenceMap.get(playerId);
        if (lastSeen != null && Duration.between(lastSeen, Instant.now()).compareTo(PRESENCE_TTL) <= 0) {
            return true;
        }

        if (redisTemplate != null) {
            try {
                Boolean hasKey = redisTemplate.hasKey(PRESENCE_PREFIX + playerId);
                if (Boolean.TRUE.equals(hasKey)) {
                    localPresenceMap.put(playerId, Instant.now());
                    return true;
                }
            } catch (Exception e) {
                log.warn("[Presence] Redis check failed for {}: {}", playerId, e.getMessage());
            }
        }

        return false;
    }

    public void removePresence(String playerId) {
        localPresenceMap.remove(playerId);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(PRESENCE_PREFIX + playerId);
            } catch (Exception e) {
                log.warn("[Presence] Redis presence delete failed for {}: {}", playerId, e.getMessage());
            }
        }
    }

    public int getLocalOnlineCount() {
        Instant cutoff = Instant.now().minus(PRESENCE_TTL);
        localPresenceMap.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        return localPresenceMap.size();
    }
}
