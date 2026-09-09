package com.rummy.engine.command;

import java.time.Instant;
import java.util.Objects;

/**
 * Command to discard one card from hand onto the open discard pile.
 */
public record DiscardCommand(
        String commandId,
        String gameId,
        String playerId,
        String cardInstanceId,
        Instant timestamp
) implements GameCommand {
    public DiscardCommand {
        Objects.requireNonNull(cardInstanceId, "cardInstanceId must not be null");
    }
}
