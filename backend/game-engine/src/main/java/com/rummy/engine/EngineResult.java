package com.rummy.engine;

import com.rummy.engine.event.GameEvent;
import com.rummy.engine.model.GameState;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Result returned by GameEngine after processing a command.
 */
public record EngineResult(
        GameState state,
        List<GameEvent> events,
        boolean isSuccess,
        String errorMessage
) implements Serializable {

    public static EngineResult success(GameState state, List<GameEvent> events) {
        return new EngineResult(state, events, true, null);
    }

    public static EngineResult failure(GameState state, String errorMessage) {
        return new EngineResult(state, Collections.emptyList(), false, errorMessage);
    }
}
