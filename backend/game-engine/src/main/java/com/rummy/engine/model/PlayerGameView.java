package com.rummy.engine.model;

import com.rummy.engine.rules.CardGroup;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Filtered, secure projection of GameState prepared for a specific connected player.
 * Strictly prevents information leakage:
 * - The viewer sees their own full hand during gameplay.
 * - For opponents during IN_PROGRESS, only card counts, statuses, and public scores are provided.
 * - When gameStatus reaches COMPLETED, all players' hands and winning melds are revealed for showdown transparency.
 * - Closed deck contents are NEVER exposed; only remaining card count is visible.
 */
public record PlayerGameView(
        String tableId,
        String gameId,
        String viewerPlayerId,
        GameStatus gameStatus,
        long sequence,
        List<CardInstance> hand,
        List<OpponentView> opponents,
        CardInstance topDiscard,
        CardInstance cutJoker,
        int closedDeckRemaining,
        String activePlayerId,
        TurnPhase turnPhase,
        Instant turnDeadline,
        boolean isMyTurn,
        String winnerId,
        List<CardInstance> discardHistory,
        int viewerScore,
        PlayerStatus viewerStatus,
        List<CardGroup> winningGroups
) implements Serializable {

    public PlayerGameView(
            String tableId,
            String gameId,
            String viewerPlayerId,
            GameStatus gameStatus,
            long sequence,
            List<CardInstance> hand,
            List<OpponentView> opponents,
            CardInstance topDiscard,
            CardInstance cutJoker,
            int closedDeckRemaining,
            String activePlayerId,
            TurnPhase turnPhase,
            Instant turnDeadline,
            boolean isMyTurn
    ) {
        this(tableId, gameId, viewerPlayerId, gameStatus, sequence, hand, opponents, topDiscard, cutJoker, closedDeckRemaining, activePlayerId, turnPhase, turnDeadline, isMyTurn, null, List.of(), 0, PlayerStatus.ACTIVE, List.of());
    }

    public PlayerGameView(
            String tableId,
            String gameId,
            String viewerPlayerId,
            GameStatus gameStatus,
            long sequence,
            List<CardInstance> hand,
            List<OpponentView> opponents,
            CardInstance topDiscard,
            CardInstance cutJoker,
            int closedDeckRemaining,
            String activePlayerId,
            TurnPhase turnPhase,
            Instant turnDeadline,
            boolean isMyTurn,
            String winnerId,
            List<CardInstance> discardHistory
    ) {
        this(tableId, gameId, viewerPlayerId, gameStatus, sequence, hand, opponents, topDiscard, cutJoker, closedDeckRemaining, activePlayerId, turnPhase, turnDeadline, isMyTurn, winnerId, discardHistory, 0, PlayerStatus.ACTIVE, List.of());
    }

    public PlayerGameView(
            String tableId,
            String gameId,
            String viewerPlayerId,
            GameStatus gameStatus,
            long sequence,
            List<CardInstance> hand,
            List<OpponentView> opponents,
            CardInstance topDiscard,
            CardInstance cutJoker,
            int closedDeckRemaining,
            String activePlayerId,
            TurnPhase turnPhase,
            Instant turnDeadline,
            boolean isMyTurn,
            String winnerId,
            List<CardInstance> discardHistory,
            int viewerScore,
            PlayerStatus viewerStatus
    ) {
        this(tableId, gameId, viewerPlayerId, gameStatus, sequence, hand, opponents, topDiscard, cutJoker, closedDeckRemaining, activePlayerId, turnPhase, turnDeadline, isMyTurn, winnerId, discardHistory, viewerScore, viewerStatus, List.of());
    }

    public record OpponentView(
            String playerId,
            String displayName,
            int seatIndex,
            PlayerStatus status,
            int cardCount,
            int score,
            boolean isBot,
            List<CardInstance> hand
    ) implements Serializable {
        public OpponentView(
                String playerId,
                String displayName,
                int seatIndex,
                PlayerStatus status,
                int cardCount,
                int score,
                boolean isBot
        ) {
            this(playerId, displayName, seatIndex, status, cardCount, score, isBot, List.of());
        }
    }

    /**
     * Factory that projects a player-specific view from the authoritative GameState.
     */
    public static PlayerGameView from(GameState state, String viewerPlayerId) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(viewerPlayerId, "viewerPlayerId must not be null");

        PlayerState viewer = state.requirePlayer(viewerPlayerId);
        List<CardInstance> viewerHand = viewer.getHandSnapshot();

        boolean isCompleted = state.getStatus() == GameStatus.COMPLETED;

        List<OpponentView> opponents = new ArrayList<>();
        for (PlayerState player : state.getPlayers()) {
            if (!player.getPlayerId().equals(viewerPlayerId)) {
                List<CardInstance> opponentHand = isCompleted ? player.getShowdownHand() : List.of();
                opponents.add(new OpponentView(
                        player.getPlayerId(),
                        player.getDisplayName(),
                        player.getSeatIndex(),
                        player.getStatus(),
                        player.getHandSize(),
                        player.getScore(),
                        player.isBot(),
                        opponentHand
                ));
            }
        }

        TurnState turn = state.getTurnState();
        String activePlayerId = turn != null ? turn.getCurrentPlayerId() : null;
        TurnPhase phase = turn != null ? turn.getPhase() : null;
        Instant deadline = turn != null ? turn.getTurnDeadline() : null;
        boolean isMyTurn = viewerPlayerId.equals(activePlayerId);

        List<CardGroup> winningGroups = isCompleted && state.getWinningGroups() != null
                ? state.getWinningGroups()
                : List.of();

        return new PlayerGameView(
                state.getTableId(),
                state.getGameId(),
                viewerPlayerId,
                state.getStatus(),
                state.getSequence(),
                viewerHand,
                opponents,
                state.topDiscard(),
                state.getCutJoker(),
                state.getDeck().remaining(),
                activePlayerId,
                phase,
                deadline,
                isMyTurn,
                state.getWinnerPlayerId(),
                state.getDiscardPile() != null ? new ArrayList<>(state.getDiscardPile()) : List.of(),
                viewer.getScore(),
                viewer.getStatus(),
                winningGroups
        );
    }
}
