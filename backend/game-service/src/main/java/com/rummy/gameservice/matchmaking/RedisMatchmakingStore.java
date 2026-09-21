package com.rummy.gameservice.matchmaking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Redis-backed matchmaking for multi-node production (Phase 17/18 master architecture).
 */
@Component
@Primary
@ConditionalOnProperty(name = "rummy.redis.enabled", havingValue = "true")
public class RedisMatchmakingStore implements MatchmakingStore {

    private static final Logger log = LoggerFactory.getLogger(RedisMatchmakingStore.class);

    private static final String TICKET_PREFIX = "rummy:mm:ticket:";
    private static final String QUEUE_PREFIX = "rummy:mm:queue:";
    private static final String QUEUE_INDEX = "rummy:mm:queues";
    private static final String LOCK_PREFIX = "rummy:mm:lock:";
    private static final String PLAYER_TICKET_PREFIX = "rummy:mm:player:";
    private static final Duration TICKET_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisMatchmakingStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        log.info("[Matchmaking] Redis shared queue store ACTIVE");
    }

    @Override
    public void saveTicket(MatchmakingTicket ticket) {
        try {
            String json = objectMapper.writeValueAsString(TicketDto.from(ticket));
            redis.opsForValue().set(TICKET_PREFIX + ticket.getTicketId(), json, TICKET_TTL);
            if (ticket.getStatus() == MatchmakingTicket.Status.QUEUED) {
                redis.opsForValue().set(PLAYER_TICKET_PREFIX + ticket.getPlayerId(), ticket.getTicketId(), TICKET_TTL);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save matchmaking ticket " + ticket.getTicketId(), e);
        }
    }

    @Override
    public Optional<MatchmakingTicket> findTicket(String ticketId) {
        try {
            String json = redis.opsForValue().get(TICKET_PREFIX + ticketId);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, TicketDto.class).toTicket());
        } catch (Exception e) {
            log.warn("[Matchmaking] Redis ticket read failed {}: {}", ticketId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void enqueue(String queueKey, String ticketId) {
        redis.opsForSet().add(QUEUE_INDEX, queueKey);
        redis.opsForList().rightPush(QUEUE_PREFIX + queueKey, ticketId);
    }

    @Override
    public Optional<QueueSnapshot> claimQueue(String queueKey, long lockMs) {
        if (!tryLock("q:" + queueKey, lockMs)) {
            return Optional.empty();
        }
        try {
            List<String> ids = redis.opsForList().range(QUEUE_PREFIX + queueKey, 0, -1);
            redis.delete(QUEUE_PREFIX + queueKey);
            if (ids == null || ids.isEmpty()) {
                unlock("q:" + queueKey);
                return Optional.empty();
            }
            List<MatchmakingTicket> tickets = new ArrayList<>();
            for (String id : ids) {
                findTicket(id).ifPresent(tickets::add);
            }
            if (tickets.isEmpty()) {
                unlock("q:" + queueKey);
                return Optional.empty();
            }
            return Optional.of(new QueueSnapshot(queueKey, tickets));
        } catch (Exception e) {
            unlock("q:" + queueKey);
            log.warn("[Matchmaking] claimQueue failed for {}: {}", queueKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void releaseQueue(String queueKey, List<String> leftoverTicketIds) {
        try {
            if (leftoverTicketIds != null && !leftoverTicketIds.isEmpty()) {
                redis.opsForList().rightPushAll(QUEUE_PREFIX + queueKey, leftoverTicketIds);
                redis.opsForSet().add(QUEUE_INDEX, queueKey);
            }
        } finally {
            unlock("q:" + queueKey);
        }
    }

    @Override
    public Set<String> listQueueKeys() {
        Set<String> keys = redis.opsForSet().members(QUEUE_INDEX);
        return keys != null ? keys : Set.of();
    }

    @Override
    public void cancelActiveTicketsForPlayer(String playerId) {
        try {
            String activeId = redis.opsForValue().get(PLAYER_TICKET_PREFIX + playerId);
            if (activeId != null) {
                findTicket(activeId).ifPresent(t -> {
                    if (t.getStatus() == MatchmakingTicket.Status.QUEUED) {
                        t.setStatus(MatchmakingTicket.Status.CANCELLED);
                        saveTicket(t);
                    }
                });
            }
        } catch (Exception e) {
            log.warn("[Matchmaking] cancelActiveTickets failed for {}: {}", playerId, e.getMessage());
        }
    }

    @Override
    public int countQueued() {
        try {
            int count = 0;
            for (String queueKey : listQueueKeys()) {
                Long size = redis.opsForList().size(QUEUE_PREFIX + queueKey);
                if (size != null) count += size.intValue();
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public boolean tryLock(String lockKey, long lockMs) {
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(
                    LOCK_PREFIX + lockKey, "1", Duration.ofMillis(Math.max(200, lockMs)));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.warn("[Matchmaking] Redis lock failed {}: {}", lockKey, e.getMessage());
            return false;
        }
    }

    @Override
    public void unlock(String lockKey) {
        try {
            redis.delete(LOCK_PREFIX + lockKey);
        } catch (Exception e) {
            log.warn("[Matchmaking] Redis unlock failed {}: {}", lockKey, e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TicketDto {
        public String ticketId;
        public String playerId;
        public String playerName;
        public String rulesetId;
        public int stakeTier;
        public int maxPlayers;
        public boolean allowAiFallback;
        public String createdAt;
        public String status;
        public String matchedTableId;
        public String matchedServerId;

        static TicketDto from(MatchmakingTicket t) {
            TicketDto d = new TicketDto();
            d.ticketId = t.getTicketId();
            d.playerId = t.getPlayerId();
            d.playerName = t.getPlayerName();
            d.rulesetId = t.getRulesetId();
            d.stakeTier = t.getStakeTier();
            d.maxPlayers = t.getMaxPlayers();
            d.allowAiFallback = t.isAllowAiFallback();
            d.createdAt = t.getCreatedAt().toString();
            d.status = t.getStatus().name();
            d.matchedTableId = t.getMatchedTableId();
            d.matchedServerId = t.getMatchedServerId();
            return d;
        }

        MatchmakingTicket toTicket() {
            MatchmakingTicket t = MatchmakingTicket.rehydrate(
                    ticketId, playerId, playerName, rulesetId, stakeTier, maxPlayers,
                    allowAiFallback, Instant.parse(createdAt));
            if (status != null) {
                t.setStatus(MatchmakingTicket.Status.valueOf(status));
            }
            t.setMatchedTableId(matchedTableId);
            t.setMatchedServerId(matchedServerId);
            return t;
        }
    }
}
