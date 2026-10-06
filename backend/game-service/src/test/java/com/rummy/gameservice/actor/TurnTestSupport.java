package com.rummy.gameservice.actor;

import com.rummy.engine.EngineResult;
import com.rummy.engine.command.DropCommand;
import com.rummy.engine.model.GameState;
import com.rummy.engine.model.TurnState;

import java.time.Instant;

public final class TurnTestSupport {

    private TurnTestSupport() {
    }

    /** Drops {@code playerId}, first handing them the turn (drops are only allowed on your own turn). */
    public static EngineResult dropOnTurn(TableActor actor, String commandId, String playerId) {
        GameState state = actor.getState();
        TurnState turn = state.getTurnState();
        if (turn != null && !turn.getCurrentPlayerId().equals(playerId)) {
            state.setTurnState(TurnState.startTurn(turn.getTurnNumber(), playerId, Instant.now(), 30));
        }
        return actor.processCommand(new DropCommand(commandId, state.getGameId(), playerId, Instant.now()), commandId);
    }
}
