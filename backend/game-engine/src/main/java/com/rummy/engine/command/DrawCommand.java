package com.rummy.engine.command;

import java.time.Instant;
import java.util.Objects;

/**
 * Command to draw a card from either the closed deck or open discard pile.
 */
public record DrawCommand(
        String commandId,
        String gameId,
        String playerId,
        DrawSource source,
        Instant timestamp
) implements GameCommand {
    public DrawCommand {
        Objects.requireNonNull(source, "DrawSource must not be null");
    }
}
