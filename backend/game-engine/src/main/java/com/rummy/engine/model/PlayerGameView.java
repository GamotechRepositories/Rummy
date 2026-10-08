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
        long viewerChipBalance,
        boolean hasTakenFirstTurn,
        String drawnCardInstanceId,
        boolean isDrawnFromDiscard,
        /** Dropping now would cost the first-drop penalty (no turn played or missed yet). */
        boolean firstDropAvailable,
        /** Entry stake of a paid table in rupees (points tables: the most a player can lose). 0 for free tables. */
        int stakeTier,
        /** The current turn has run past its normal time and is on the active player's extra-time bank. */
        boolean inExtraTime,
        /** The active player may take the open card now (jokers only as the deal's first open card). */
        boolean topDiscardPickable,
        /** Pool/Deals: prize the winner would get now (all entries and rejoins, less the platform fee). Null otherwise. */
        java.math.BigDecimal prizePool,
        /** Pool: prize split state for this viewer; null when no split can be requested or is pending. */
        SplitView split,
        /** Showdown timing */
        Instant showdownDeadline,
        String closureReason,
        boolean hasSubmittedMeld,
        Map<String, List<CardGroup>> submittedMelds
) implements Serializable {

    /** Table facts the engine state does not hold: the pool rejoin window, the prize and any split offer. */
    public record TableExtras(int rejoinCutoff, boolean rejoinOpen, java.math.BigDecimal prizePool, SplitView split) {
        public static final TableExtras NONE = new TableExtras(0, false, null, null);
    }

    /**
     * A pool prize split. {@code payouts} are the amounts each remaining player would receive;
     * everyone listed must accept before the split happens.
     */
    public record SplitView(
            boolean canRequest,
            String requestedBy,
            Map<String, java.math.BigDecimal> payouts,
            List<String> acceptedBy,
            boolean awaitingMyAnswer,
            Integer secondsLeft
    ) implements Serializable {
    }

    public PlayerGameView(
            String tableId, String gameId, String viewerPlayerId, GameStatus gameStatus, long sequence,
            List<CardInstance> hand, List<OpponentView> opponents, CardInstance topDiscard, CardInstance cutJoker,
            int closedDeckRemaining, String activePlayerId, TurnPhase turnPhase, Instant turnDeadline, boolean isMyTurn,
            String winnerId, List<CardInstance> discardHistory, int viewerScore, PlayerStatus viewerStatus,
            List<CardGroup> winningGroups, int viewerSeatIndex, String rulesetId, int dealNumber, int eliminationThreshold,
            int viewerCumulativeScore, boolean viewerIsEliminated, List<PlayerStanding> standings,
            List<DealScoreRecord> dealHistory, Integer nextDealCountdown, String tournamentWinnerId, int dealerSeatIndex,
            boolean canRejoin, int rejoinScore, int rejoinFee, List<String> freshlyEliminatedNames, int totalDeals,
            long viewerChipBalance, boolean hasTakenFirstTurn, String drawnCardInstanceId, boolean isDrawnFromDiscard) {
        this(tableId, gameId, viewerPlayerId, gameStatus, sequence, hand, opponents, topDiscard, cutJoker, closedDeckRemaining,
                activePlayerId, turnPhase, turnDeadline, isMyTurn, winnerId, discardHistory, viewerScore, viewerStatus,
                winningGroups, viewerSeatIndex, rulesetId, dealNumber, eliminationThreshold, viewerCumulativeScore, viewerIsEliminated,
                standings, dealHistory, nextDealCountdown, tournamentWinnerId, dealerSeatIndex, canRejoin, rejoinScore, rejoinFee,
                freshlyEliminatedNames, totalDeals, viewerChipBalance, hasTakenFirstTurn, drawnCardInstanceId, isDrawnFromDiscard,
                !hasTakenFirstTurn, 0, false, false, null, null, null, null, false, null);
    }

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
            long chipBalance,
            String avatarId
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
                List<CardInstance> hand,
                long chipBalance
        ) {
            this(playerId, displayName, seatIndex, status, cardCount, score, cumulativeScore, isEliminated, isBot, hand, chipBalance, null);
        }

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
            this(playerId, displayName, seatIndex, status, cardCount, score, cumulativeScore, isEliminated, isBot, hand, 0L, null);
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
            this(playerId, displayName, seatIndex, status, cardCount, score, score, status == PlayerStatus.ELIMINATED, isBot, List.of(), 0L, null);
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
            this(playerId, displayName, seatIndex, status, cardCount, score, score, status == PlayerStatus.ELIMINATED, isBot, hand, 0L, null);
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
                freshlyEliminatedNames, 1, 0L, false, null, false);
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
            List<String> freshlyEliminatedNames,
            int totalDeals,
            long viewerChipBalance,
            boolean hasTakenFirstTurn
    ) {
        this(tableId, gameId, viewerPlayerId, gameStatus, sequence, hand, opponents, topDiscard, cutJoker, closedDeckRemaining,
                activePlayerId, turnPhase, turnDeadline, isMyTurn, winnerId, discardHistory, viewerScore, viewerStatus,
                winningGroups, viewerSeatIndex, rulesetId, dealNumber, eliminationThreshold, viewerCumulativeScore, viewerIsEliminated,
                standings, dealHistory, nextDealCountdown, tournamentWinnerId, dealerSeatIndex, canRejoin, rejoinScore, rejoinFee,
                freshlyEliminatedNames, totalDeals, viewerChipBalance, hasTakenFirstTurn, null, false);
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
        return from(state, viewerPlayerId, eliminationThreshold, dealHistory, nextDealCountdown, tournamentWinnerId,
                rejoinFee, freshlyEliminatedNames, totalDeals, 0);
    }

    public static PlayerGameView from(GameState state,
                                      String viewerPlayerId,
                                      int eliminationThreshold,
                                      List<DealScoreRecord> dealHistory,
                                      Integer nextDealCountdown,
                                      String tournamentWinnerId,
                                      int rejoinFee,
                                      List<String> freshlyEliminatedNames,
                                      int totalDeals,
                                      int stakeTier) {
        return from(state, viewerPlayerId, eliminationThreshold, dealHistory, nextDealCountdown, tournamentWinnerId,
                rejoinFee, freshlyEliminatedNames, totalDeals, stakeTier, TableExtras.NONE);
    }

    public static PlayerGameView from(GameState state,
                                      String viewerPlayerId,
                                      int eliminationThreshold,
                                      List<DealScoreRecord> dealHistory,
                                      Integer nextDealCountdown,
                                      String tournamentWinnerId,
                                      int rejoinFee,
                                      List<String> freshlyEliminatedNames,
                                      int totalDeals,
                                      int stakeTier,
                                      TableExtras extras) {
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(viewerPlayerId, "viewerPlayerId must not be null");
        TableExtras table = extras != null ? extras : TableExtras.NONE;

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
                        player.getChipBalance(),
                        player.getAvatarId()
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

        boolean isViewerEliminated = viewer.getStatus() == PlayerStatus.ELIMINATED;
        boolean canRejoin = eliminationThreshold > 0
                && table.rejoinOpen()
                && table.rejoinCutoff() > 0
                && isViewerEliminated
                && tournamentWinnerId == null
                && activeSurvivors.size() >= 1
                && maxActiveScore <= table.rejoinCutoff();

        int rejoinScore = canRejoin ? maxActiveScore + 1 : 0;

        String drawnCardInstanceId = isMyTurn && turn != null ? turn.getDrawnCardInstanceId() : null;
        boolean isDrawnFromDiscard = isMyTurn && turn != null && turn.isDrawnFromDiscard();

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
                viewer.getChipBalance(),
                viewer.hasTakenFirstTurn(),
                drawnCardInstanceId,
                isDrawnFromDiscard,
                !viewer.hasTakenFirstTurn() && viewer.getConsecutiveMissedTurns() == 0,
                stakeTier,
                turn != null && turn.isExtraTime(),
                state.getStatus() == GameStatus.IN_PROGRESS && state.isTopDiscardPickable(),
                table.prizePool(),
                table.split(),
                state.getShowdownDeadline(),
                null,
                state.getSubmittedMelds().containsKey(viewerPlayerId),
                state.getSubmittedMelds()
        );
    }
}
