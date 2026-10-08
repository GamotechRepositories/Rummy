package com.rummy.engine.command;

import java.time.Instant;

/**
 * Command to trigger the end of the showdown phase.
 */
public record ShowdownTimeoutCommand(
        String commandId,
        String gameId,
        Instant timestamp
) implements GameCommand {
    @Override
    public String playerId() {
        return "SYSTEM";
    }
}
