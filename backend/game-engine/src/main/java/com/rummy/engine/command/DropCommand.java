package com.rummy.engine.command;

import java.time.Instant;

/**
 * Command to drop out of the current hand.
 */
public record DropCommand(
        String commandId,
        String gameId,
        String playerId,
        Instant timestamp
) implements GameCommand {}
