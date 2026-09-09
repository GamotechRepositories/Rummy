package com.rummy.engine.event;

import java.time.Instant;

public record PlayerJoinedEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        String playerId,
        String displayName,
        int seatIndex,
        boolean isBot
) implements GameEvent {}
