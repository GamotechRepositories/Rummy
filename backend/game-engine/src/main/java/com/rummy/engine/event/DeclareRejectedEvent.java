package com.rummy.engine.event;

import com.rummy.engine.model.CardInstance;

import java.time.Instant;
import java.util.List;

public record DeclareRejectedEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        String playerId,
        CardInstance finishCard,
        int penaltyPoints,
        List<String> errors
) implements GameEvent {}
