package com.rummy.engine.model.snapshot;

import java.util.List;

/**
 * Complete, immutable copy of a {@link com.rummy.engine.model.GameState} built from strings and
 * numbers only, so it can be stored and later restored exactly (deck order included).
 * Cards are stored by instance id (e.g. {@code D1_H_10}, {@code D2_JOKER_1}); instants as ISO-8601.
 */
public record GameStateSnapshot(
        int version,
        String gameId,
        String tableId,
        String rulesetId,
        String rulesetVersion,
        List<Player> players,
        List<String> deck,
        List<String> discardPile,
        String cutJoker,
        String finishCard,
        Turn turn,
        long sequence,
        String status,
        String winnerPlayerId,
        List<List<String>> winningGroups,
        int dealNumber,
        int dealerSeatIndex,
        String createdAt,
        String finishedAt) {

    public static final int CURRENT_VERSION = 1;

    public record Player(
            String playerId,
            String displayName,
            int seatIndex,
            boolean bot,
            String avatarId,
            List<String> hand,
            List<String> lastHand,
            String status,
            int score,
            int cumulativeScore,
            long chipBalance,
            boolean declared,
            boolean dropped,
            int consecutiveMissedTurns,
            int turnsCompleted,
            String lastActionAt) {
    }

    public record Turn(
            int turnNumber,
            String currentPlayerId,
            String phase,
            String startedAt,
            String deadline,
            String drawnCardInstanceId,
            boolean drawnFromDiscard) {
    }
}
