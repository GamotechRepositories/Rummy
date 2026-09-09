package com.rummy.engine.event;

import com.rummy.engine.command.DrawSource;
import com.rummy.engine.model.CardInstance;

import java.time.Instant;

public record CardDrawnEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        String playerId,
        DrawSource source,
        CardInstance drawnCard,
        int newHandSize
) implements GameEvent {}
