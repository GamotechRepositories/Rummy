package com.rummy.engine.event;

import com.rummy.engine.model.CardInstance;

import java.time.Instant;

public record CardDiscardedEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        String playerId,
        CardInstance discardedCard,
        int newHandSize
) implements GameEvent {}
