package com.rummy.engine.command;

import java.time.Instant;

/**
 * System or scheduler command triggered when a player's turn timer expires.
 */
public record TimeoutCommand(
        String commandId,
        String gameId,
        String playerId,
        Instant timestamp
) implements GameCommand {}
