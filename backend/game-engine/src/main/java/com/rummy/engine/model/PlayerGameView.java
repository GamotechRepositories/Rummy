package com.rummy.engine.model;

import com.rummy.engine.rules.CardGroup;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Filtered, secure projection of GameState prepared for a specific connected player.
 * Strictly prevents information leakage:
 * - The viewer sees their own full hand during gameplay.
 * - For opponents during IN_PROGRESS, only card counts, statuses, and public scores are provided.
 * - When gameStatus reaches COMPLETED, all players' hands and winning melds are revealed for showdown transparency.
 * - Closed deck contents are NEVER exposed; only remaining card count is visible.
 * - Includes cumulative scores, deal number, elimination threshold, and round history for Pool / Multi-deal Rummy.
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
        List<CardGroup> winningGroups,
        int viewerSeatIndex,
        String rulesetId,
        int dealNumber,
        int eliminationThreshold,
        int viewerCumulativeScore,
        boolean viewerIsEliminated,
        List<PlayerStanding> standings,
        List<DealScoreRecord> dealHistory,
        Integer nextDealCountdown,
        String tournamentWinnerId,
        int dealerSeatIndex,
        boolean canRejoin,
        int rejoinScore,
        int rejoinFee,
        List<String> freshlyEliminatedNames,
        int totalDeals,
        long viewerChipBalance
) implements Serializable {

    public record OpponentView(
            String playerId,
            String displayName,
            int seatIndex,
            PlayerStatus status,
            int cardCount,
            int score,
            int cumulativeScore,
            boolean isEliminated,
            boolean isBot,
            List<CardInstance> hand,
            long chipBalance
    ) implements Serializable {
        public OpponentView(
                String playerId,
                String displayName,
                int seatIndex,
                PlayerStatus status,
                int cardCount,
                int score,
                int cumulativeScore,
                boolean isEliminated,
                boolean isBot,
                List<CardInstance> hand
        ) {
            this(playerId, displayName, seatIndex, status, cardCount, score, cumulativeScore, isEliminated, isBot, hand, 0L);
        }

        public OpponentView(
                String playerId,
                String displayName,
                int seatIndex,
                PlayerStatus status,
                int cardCount,
                int score,
                boolean isBot
        ) {
            this(playerId, displayName, seatIndex, status, cardCount, score, score, status == PlayerStatus.ELIMINATED, isBot, List.of(), 0L);
        }

        public OpponentView(
                String playerId,
                String displayName,
                int seatIndex,
                PlayerStatus status,
                int cardCount,
                int score,
                boolean isBot,
                List<CardInstance> hand
        ) {
            this(playerId, displayName, seatIndex, status, cardCount, score, score, status == PlayerStatus.ELIMINATED, isBot, hand, 0L);
        }
    }

    public record PlayerStanding(
            String playerId,
            String displayName,
            int seatIndex,
            int cumulativeScore,
            boolean isEliminated,
            PlayerStatus status,
            long chipBalance
    ) implements Serializable {
        public PlayerStanding(
                String playerId,
                String displayName,
                int seatIndex,
                int cumulativeScore,
                boolean isEliminated,
                PlayerStatus status
        ) {
            this(playerId, displayName, seatIndex, cumulativeScore, isEliminated, status, 0L);
        }
    }

    public record DealScoreRecord(
            int dealNumber,
            String winnerPlayerId,
            Map<String, Integer> roundScores,
            Map<String, Integer> cumulativeScores
    ) implements Serializable {}

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
        this(tableId, gameId, viewerPlayerId, gameStatus, sequence, hand, opponents, topDiscard, cutJoker, closedDeckRemaining,
                activePlayerId, turnPhase, turnDeadline, isMyTurn, null, List.of(), 0, PlayerStatus.ACTIVE, List.of(), 0);
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
        this(tableId, gameId, viewerPlayerId, gameStatus, sequence, hand, opponents, topDiscard, cutJoker, closedDeckRemaining,
                activePlayerId, turnPhase, turnDeadline, isMyTurn, winnerId, discardHistory, 0, PlayerStatus.ACTIVE, List.of(), 0);
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
        this(tableId, gameId, viewerPlayerId, gameStatus, sequence, hand, opponents, topDiscard, cutJoker, closedDeckRemaining,
                activePlayerId, turnPhase, turnDeadline, isMyTurn, winnerId, discardHistory, viewerScore, viewerStatus, List.of(), 0);
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
            PlayerStatus viewerStatus,
            List<CardGroup> winningGroups,
            int viewerSeatIndex
    ) {
        this(tableId, gameId, viewerPlayerId, gameStatus, sequence, hand, opponents, topDiscard, cutJoker, closedDeckRemaining,
                activePlayerId, turnPhase, turnDeadline, isMyTurn, winnerId, discardHistory, viewerScore, viewerStatus,
                winningGroups, viewerSeatIndex, "POINTS_13", 1, 0, viewerScore, viewerStatus == PlayerStatus.ELIMINATED,
                List.of(), List.of(), null, null, 0, false, 0, 0, List.of());
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
            PlayerStatus viewerStatus,
            List<CardGroup> winningGroups,
            int viewerSeatIndex,
            String rulesetId,
            int dealNumber,
            int eliminationThreshold,
            int viewerCumulativeScore,
            boolean viewerIsEliminated,
            List<PlayerStanding> standings,
            List<DealScoreRecord> dealHistory,
            Integer nextDealCountdown,
            String tournamentWinnerId,
            int dealerSeatIndex,
            boolean canRejoin,
            int rejoinScore,
            int rejoinFee,
            List<String> freshlyEliminatedNames
    ) {
        this(tableId, gameId, viewerPlayerId, gameStatus, sequence, hand, opponents, topDiscard, cutJoker, closedDeckRemaining,
                activePlayerId, turnPhase, turnDeadline, isMyTurn, winnerId, discardHistory, viewerScore, viewerStatus,
                winningGroups, viewerSeatIndex, rulesetId, dealNumber, eliminationThreshold, viewerCumulativeScore, viewerIsEliminated,
                standings, dealHistory, nextDealCountdown, tournamentWinnerId, dealerSeatIndex, canRejoin, rejoinScore, rejoinFee,
                freshlyEliminatedNames, 1, 0L);
    }

    /**
     * Factory that projects a player-specific view from the authoritative GameState.
     */
    public static PlayerGameView from(GameState state, String viewerPlayerId) {
        return from(state, viewerPlayerId, 0, List.of(), null, null, 0, List.of(), 1);
    }

    /**
     * Factory that projects a player-specific view with full tournament and pool context.
     */
    public static PlayerGameView from(GameState state,
                                      String viewerPlayerId,
                                      int eliminationThreshold,
                                      List<DealScoreRecord> dealHistory,
                                      Integer nextDealCountdown,
                                      String tournamentWinnerId) {
        return from(state, viewerPlayerId, eliminationThreshold, dealHistory, nextDealCountdown, tournamentWinnerId, 0, List.of(), 1);
    }

    public static PlayerGameView from(GameState state,
                                      String viewerPlayerId,
                                      int eliminationThreshold,
                                      List<DealScoreRecord> dealHistory,
                                      Integer nextDealCountdown,
                                      String tournamentWinnerId,
                                      int rejoinFee,
                                      List<String> freshlyEliminatedNames) {
        return from(state, viewerPlayerId, eliminationThreshold, dealHistory, nextDealCountdown, tournamentWinnerId, rejoinFee, freshlyEliminatedNames, 1);
    }

    public static PlayerGameView from(GameState state,
                                      String viewerPlayerId,
                                      int eliminationThreshold,
                                      List<DealScoreRecord> dealHistory,
                                      Integer nextDealCountdown,
                                      String tournamentWinnerId,
                                      int rejoinFee,
                                      List<String> freshlyEliminatedNames,
                                      int totalDeals) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(viewerPlayerId, "viewerPlayerId must not be null");

        boolean isCompleted = state.getStatus() == GameStatus.COMPLETED;

        PlayerState viewer = state.requirePlayer(viewerPlayerId);
        List<CardInstance> viewerHand = isCompleted ? viewer.getShowdownHand() : viewer.getHandSnapshot();

        List<OpponentView> opponents = new ArrayList<>();
        List<PlayerStanding> standings = new ArrayList<>();

        for (PlayerState player : state.getPlayers()) {
            standings.add(new PlayerStanding(
                    player.getPlayerId(),
                    player.getDisplayName(),
                    player.getSeatIndex(),
                    player.getCumulativeScore(),
                    player.getStatus() == PlayerStatus.ELIMINATED,
                    player.getStatus(),
                    player.getChipBalance()
            ));

            if (!player.getPlayerId().equals(viewerPlayerId)) {
                List<CardInstance> opponentHand = isCompleted ? player.getShowdownHand() : List.of();
                int opponentCardCount = isCompleted
                        ? opponentHand.size()
                        : player.getStatus() == PlayerStatus.DROPPED
                                ? player.getShowdownHand().size()
                                : player.getHandSize();
                opponents.add(new OpponentView(
                        player.getPlayerId(),
                        player.getDisplayName(),
                        player.getSeatIndex(),
                        player.getStatus(),
                        opponentCardCount,
                        player.getScore(),
                        player.getCumulativeScore(),
                        player.getStatus() == PlayerStatus.ELIMINATED,
                        player.isBot(),
                        opponentHand,
                        player.getChipBalance()
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

        int dealerSeatIndex = state.getDealerSeatIndex();

        List<PlayerState> activeSurvivors = state.getPlayers().stream()
                .filter(p -> p.getStatus() != PlayerStatus.ELIMINATED)
                .toList();

        int maxActiveScore = activeSurvivors.stream()
                .mapToInt(PlayerState::getCumulativeScore)
                .max()
                .orElse(0);

        int rejoinCutoff = 0;
        if (eliminationThreshold == 101) {
            rejoinCutoff = 79;
        } else if (eliminationThreshold == 201) {
            rejoinCutoff = 174;
        }

        boolean isViewerEliminated = viewer.getStatus() == PlayerStatus.ELIMINATED;
        boolean canRejoin = eliminationThreshold > 0
                && isViewerEliminated
                && tournamentWinnerId == null
                && activeSurvivors.size() >= 1
                && maxActiveScore <= rejoinCutoff;

        int rejoinScore = canRejoin ? maxActiveScore + 1 : 0;

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
                winningGroups,
                viewer.getSeatIndex(),
                state.getRulesetId(),
                state.getDealNumber(),
                eliminationThreshold,
                viewer.getCumulativeScore(),
                viewer.getStatus() == PlayerStatus.ELIMINATED,
                standings,
                dealHistory != null ? dealHistory : List.of(),
                nextDealCountdown,
                tournamentWinnerId,
                dealerSeatIndex,
                canRejoin,
                rejoinScore,
                rejoinFee,
                freshlyEliminatedNames != null ? freshlyEliminatedNames : List.of(),
                totalDeals,
                viewer.getChipBalance()
        );
    }
}
