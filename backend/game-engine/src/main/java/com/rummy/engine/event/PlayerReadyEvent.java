package com.rummy.engine.event;

import java.time.Instant;

public record PlayerReadyEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        String playerId
) implements GameEvent {}
