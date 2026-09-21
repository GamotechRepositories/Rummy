package com.rummy.gameservice.matchmaking;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence for matchmaking tickets/queues.
 * In-memory for single-node; Redis for multi-node production.
 */
public interface MatchmakingStore {

    void saveTicket(MatchmakingTicket ticket);

    Optional<MatchmakingTicket> findTicket(String ticketId);

    void enqueue(String queueKey, String ticketId);

    /** Atomically claim work for a queue; empty if lock not acquired or nothing waiting. */
    Optional<QueueSnapshot> claimQueue(String queueKey, long lockMs);

    void releaseQueue(String queueKey, List<String> leftoverTicketIds);

    Set<String> listQueueKeys();

    void cancelActiveTicketsForPlayer(String playerId);

    int countQueued();

    boolean tryLock(String lockKey, long lockMs);

    void unlock(String lockKey);

    record QueueSnapshot(String queueKey, List<MatchmakingTicket> tickets) {}
}
