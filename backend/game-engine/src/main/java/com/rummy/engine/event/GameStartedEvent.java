package com.rummy.engine.event;

import com.rummy.engine.model.CardInstance;

import java.time.Instant;
import java.util.List;

public record GameStartedEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        CardInstance cutJoker,
        CardInstance initialDiscard,
        List<String> playerIds,
        String startingPlayerId,
        Instant turnDeadline
) implements GameEvent {}
