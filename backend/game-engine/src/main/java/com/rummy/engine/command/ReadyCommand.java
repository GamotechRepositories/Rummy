package com.rummy.engine.command;

import java.time.Instant;

/**
 * Command to indicate player readiness.
 */
public record ReadyCommand(
        String commandId,
        String gameId,
        String playerId,
        Instant timestamp
) implements GameCommand {}
