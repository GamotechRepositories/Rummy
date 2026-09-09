package com.rummy.engine.event;

import java.time.Instant;

public record TurnChangedEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        String previousPlayerId,
        String nextPlayerId,
        int turnNumber,
        Instant turnDeadline
) implements GameEvent {}
