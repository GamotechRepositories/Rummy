package com.rummy.engine.command;

import com.rummy.engine.rules.CardGroup;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Command to finish hand and declare with submitted card groups.
 */
public record DeclareCommand(
        String commandId,
        String gameId,
        String playerId,
        String finishCardInstanceId,
        List<CardGroup> groups,
        Instant timestamp
) implements GameCommand {
    public DeclareCommand {
        Objects.requireNonNull(finishCardInstanceId, "finishCardInstanceId must not be null");
        Objects.requireNonNull(groups, "groups must not be null");
    }
}
