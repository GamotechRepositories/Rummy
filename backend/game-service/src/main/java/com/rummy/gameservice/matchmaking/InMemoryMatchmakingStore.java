package com.rummy.gameservice.matchmaking;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Single-node matchmaking store (default when Redis is disabled).
 */
@Component
public class InMemoryMatchmakingStore implements MatchmakingStore {

    private final Map<String, MatchmakingTicket> tickets = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<String>> queues = new ConcurrentHashMap<>();
    private final Set<String> queueKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> locks = ConcurrentHashMap.newKeySet();

    @Override
    public void saveTicket(MatchmakingTicket ticket) {
        tickets.put(ticket.getTicketId(), ticket);
    }

    @Override
    public Optional<MatchmakingTicket> findTicket(String ticketId) {
        return Optional.ofNullable(tickets.get(ticketId));
    }

    @Override
    public void enqueue(String queueKey, String ticketId) {
        queueKeys.add(queueKey);
        queues.computeIfAbsent(queueKey, k -> new ConcurrentLinkedQueue<>()).add(ticketId);
    }

    @Override
    public Optional<QueueSnapshot> claimQueue(String queueKey, long lockMs) {
        if (!tryLock("q:" + queueKey, lockMs)) {
            return Optional.empty();
        }
        ConcurrentLinkedQueue<String> queue = queues.get(queueKey);
        if (queue == null || queue.isEmpty()) {
            unlock("q:" + queueKey);
            return Optional.empty();
        }
        List<MatchmakingTicket> claimed = new ArrayList<>();
        String id;
        while ((id = queue.poll()) != null) {
            MatchmakingTicket t = tickets.get(id);
            if (t != null) {
                claimed.add(t);
            }
        }
        if (claimed.isEmpty()) {
            unlock("q:" + queueKey);
            return Optional.empty();
        }
        return Optional.of(new QueueSnapshot(queueKey, claimed));
    }

    @Override
    public void releaseQueue(String queueKey, List<String> leftoverTicketIds) {
        ConcurrentLinkedQueue<String> queue = queues.computeIfAbsent(queueKey, k -> new ConcurrentLinkedQueue<>());
        if (leftoverTicketIds != null) {
            queue.addAll(leftoverTicketIds);
        }
        unlock("q:" + queueKey);
    }

    @Override
    public Set<String> listQueueKeys() {
        return Set.copyOf(queueKeys);
    }

    @Override
    public void cancelActiveTicketsForPlayer(String playerId) {
        for (MatchmakingTicket existing : tickets.values()) {
            if (existing.getPlayerId().equals(playerId) && existing.getStatus() == MatchmakingTicket.Status.QUEUED) {
                existing.setStatus(MatchmakingTicket.Status.CANCELLED);
                saveTicket(existing);
            }
        }
    }

    @Override
    public int countQueued() {
        return (int) tickets.values().stream()
                .filter(t -> t.getStatus() == MatchmakingTicket.Status.QUEUED)
                .count();
    }

    @Override
    public boolean tryLock(String lockKey, long lockMs) {
        return locks.add(lockKey);
    }

    @Override
    public void unlock(String lockKey) {
        locks.remove(lockKey);
    }
}
