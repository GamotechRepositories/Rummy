package com.rummy.engine.event;

import com.rummy.engine.rules.CardGroup;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Emitted when a player declares and wins, starting the showdown phase.
 */
public record ShowdownStartedEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        String winnerId,
        List<CardGroup> winningGroups,
        Instant showdownDeadline
) implements GameEvent {
    public ShowdownStartedEvent {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(gameId);
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(winnerId);
        Objects.requireNonNull(winningGroups);
        Objects.requireNonNull(showdownDeadline);
    }
}
