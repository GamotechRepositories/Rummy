package com.rummy.engine.command;

import java.time.Instant;

/**
 * Command to start deal and initiate gameplay when all seated players are ready.
 */
public record StartGameCommand(
        String commandId,
        String gameId,
        String playerId,
        Instant timestamp
) implements GameCommand {}
