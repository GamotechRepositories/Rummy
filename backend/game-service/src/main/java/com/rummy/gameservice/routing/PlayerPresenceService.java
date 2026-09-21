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
 * Phase 17: Player presence. Prefer Redis when enabled so all nodes share online state.
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
                redisTemplate.opsForValue().set(
                        PRESENCE_PREFIX + playerId, String.valueOf(now.toEpochMilli()), PRESENCE_TTL);
            } catch (Exception e) {
                log.warn("[Presence] Redis presence update failed for {}: {}", playerId, e.getMessage());
            }
        }
    }

    public boolean isPlayerOnline(String playerId) {
        // Cross-node: Redis first when coordination is enabled
        if (redisTemplate != null) {
            try {
                Boolean hasKey = redisTemplate.hasKey(PRESENCE_PREFIX + playerId);
                if (Boolean.TRUE.equals(hasKey)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("[Presence] Redis check failed for {}: {}", playerId, e.getMessage());
            }
        }

        Instant lastSeen = localPresenceMap.get(playerId);
        if (lastSeen == null) return false;
        return Duration.between(lastSeen, Instant.now()).compareTo(PRESENCE_TTL) <= 0;
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
