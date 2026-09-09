package com.rummy.gameservice.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 17: Table-to-Server and Player-to-Table Distributed Routing Registry.
 * Supports Redis Cluster with an automatic graceful in-memory fallback for standalone/local mode.
 */
@Service
public class TableRoutingRegistry {

    private static final Logger log = LoggerFactory.getLogger(TableRoutingRegistry.class);

    private static final String TABLE_PREFIX = "rummy:table:server:";
    private static final String PLAYER_PREFIX = "rummy:player:table:";
    private static final Duration ROUTING_TTL = Duration.ofHours(4);

    private final String serverInstanceId;
    private final StringRedisTemplate redisTemplate;

    // In-memory fallback caches when Redis is unavailable or in mock mode
    private final ConcurrentHashMap<String, String> localTableServerMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> localPlayerTableMap = new ConcurrentHashMap<>();

    @Autowired
    public TableRoutingRegistry(
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Value("${server.instance-id:server-default}") String serverInstanceId) {
        this.redisTemplate = redisTemplate;
        this.serverInstanceId = (serverInstanceId != null && !serverInstanceId.equals("server-default"))
                ? serverInstanceId
                : "server-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[Routing] Initialized TableRoutingRegistry with serverInstanceId: {}, Redis: {}",
                this.serverInstanceId, (redisTemplate != null ? "ENABLED" : "IN-MEMORY"));
    }

    public String getServerInstanceId() {
        return serverInstanceId;
    }

    /**
     * Registers table ownership by this server instance.
     */
    public void registerTableOwnership(String tableId) {
        localTableServerMap.put(tableId, serverInstanceId);
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(TABLE_PREFIX + tableId, serverInstanceId, ROUTING_TTL);
            } catch (Exception e) {
                log.warn("[Routing] Redis failed to register table ownership for {}: {}", tableId, e.getMessage());
            }
        }
        log.debug("[Routing] Registered table {} to server {}", tableId, serverInstanceId);
    }

    /**
     * Look up which game server owns the given table.
     */
    public Optional<String> getServerForTable(String tableId) {
        if (redisTemplate != null) {
            try {
                String server = redisTemplate.opsForValue().get(TABLE_PREFIX + tableId);
                if (server != null) return Optional.of(server);
            } catch (Exception e) {
                log.warn("[Routing] Redis lookup failed for table {}: {}", tableId, e.getMessage());
            }
        }
        return Optional.ofNullable(localTableServerMap.get(tableId));
    }

    /**
     * Maps a player to their active table.
     */
    public void registerPlayerTable(String playerId, String tableId) {
        localPlayerTableMap.put(playerId, tableId);
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(PLAYER_PREFIX + playerId, tableId, ROUTING_TTL);
            } catch (Exception e) {
                log.warn("[Routing] Redis failed to register player table for {}: {}", playerId, e.getMessage());
            }
        }
    }

    /**
     * Look up the active table for a player.
     */
    public Optional<String> getTableForPlayer(String playerId) {
        if (redisTemplate != null) {
            try {
                String tableId = redisTemplate.opsForValue().get(PLAYER_PREFIX + playerId);
                if (tableId != null) return Optional.of(tableId);
            } catch (Exception e) {
                log.warn("[Routing] Redis lookup failed for player {}: {}", playerId, e.getMessage());
            }
        }
        return Optional.ofNullable(localPlayerTableMap.get(playerId));
    }

    /**
     * Clears table and associated player mappings when table is closed.
     */
    public void unregisterTable(String tableId) {
        localTableServerMap.remove(tableId);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(TABLE_PREFIX + tableId);
            } catch (Exception e) {
                log.warn("[Routing] Redis delete failed for table {}: {}", tableId, e.getMessage());
            }
        }
    }

    /**
     * Clears a player's table mapping on table exit.
     */
    public void unregisterPlayer(String playerId) {
        localPlayerTableMap.remove(playerId);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(PLAYER_PREFIX + playerId);
            } catch (Exception e) {
                log.warn("[Routing] Redis delete failed for player {}: {}", playerId, e.getMessage());
            }
        }
    }

    public boolean isTableHostedLocally(String tableId) {
        return localTableServerMap.containsKey(tableId);
    }
}
