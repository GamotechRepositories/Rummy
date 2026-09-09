package com.rummy.engine.command;

import com.rummy.engine.rules.CardGroup;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * Common interface for all table intent commands.
 */
public sealed interface GameCommand extends Serializable
        permits JoinCommand, ReadyCommand, StartGameCommand, DrawCommand,
                DiscardCommand, DeclareCommand, DropCommand, TimeoutCommand {

    String commandId();

    String gameId();

    String playerId();

    Instant timestamp();
}
