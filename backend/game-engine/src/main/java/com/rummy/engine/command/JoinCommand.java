package com.rummy.engine.command;

import java.time.Instant;

/**
 * Command to seat a player at the table.
 */
public record JoinCommand(
        String commandId,
        String gameId,
        String playerId,
        String displayName,
        int seatIndex,
        boolean isBot,
        Instant timestamp,
        String avatarId
) implements GameCommand {
    public JoinCommand(
            String commandId,
            String gameId,
            String playerId,
            String displayName,
            int seatIndex,
            boolean isBot,
            Instant timestamp
    ) {
        this(commandId, gameId, playerId, displayName, seatIndex, isBot, timestamp, null);
    }
}
