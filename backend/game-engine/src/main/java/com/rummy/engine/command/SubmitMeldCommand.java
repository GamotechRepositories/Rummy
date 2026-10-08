package com.rummy.engine.command;

import com.rummy.engine.rules.CardGroup;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Command for a losing player to submit their arranged card groups during showdown.
 */
public record SubmitMeldCommand(
        String commandId,
        String gameId,
        String playerId,
        List<CardGroup> groups,
        Instant timestamp
) implements GameCommand {
    public SubmitMeldCommand {
        Objects.requireNonNull(groups, "groups must not be null");
    }
}
