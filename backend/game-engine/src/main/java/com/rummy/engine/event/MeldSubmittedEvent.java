package com.rummy.engine.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Emitted when a losing player submits their card groups during showdown.
 */
public record MeldSubmittedEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        String playerId
) implements GameEvent {
    public MeldSubmittedEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(gameId);
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(playerId);
    }
}
