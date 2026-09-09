package com.rummy.engine.event;

import java.time.Instant;

public record PlayerDroppedEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        String playerId,
        boolean isFirstDrop,
        int penaltyPoints,
        long activePlayersRemaining
) implements GameEvent {}
