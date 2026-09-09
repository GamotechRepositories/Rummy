package com.rummy.engine.event;

import java.io.Serializable;
import java.time.Instant;

/**
 * Base interface for all immutable, sequentially numbered game events.
 */
public sealed interface GameEvent extends Serializable
        permits PlayerJoinedEvent, PlayerReadyEvent, GameStartedEvent, CardDrawnEvent,
                CardDiscardedEvent, TurnChangedEvent, PlayerDroppedEvent,
                DeclareAcceptedEvent, DeclareRejectedEvent, GameFinishedEvent {

    String eventId();

    String gameId();

    long sequence();

    Instant timestamp();

    default String eventType() {
        return getClass().getSimpleName();
    }
}
