package com.rummy.engine.event;

import java.time.Instant;
import java.util.Map;

public record GameFinishedEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        String winnerPlayerId,
        Map<String, Integer> finalScores
) implements GameEvent {}
