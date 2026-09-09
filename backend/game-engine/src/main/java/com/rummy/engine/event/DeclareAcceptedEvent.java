package com.rummy.engine.event;

import com.rummy.engine.model.CardInstance;
import com.rummy.engine.rules.CardGroup;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DeclareAcceptedEvent(
        String eventId,
        String gameId,
        long sequence,
        Instant timestamp,
        String winnerPlayerId,
        CardInstance finishCard,
        List<CardGroup> winnerGroups,
        Map<String, Integer> playerScores
) implements GameEvent {}
