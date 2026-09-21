package com.rummy.engine;

import com.rummy.engine.command.*;
import com.rummy.engine.event.*;
import com.rummy.engine.model.*;
import com.rummy.engine.rules.CardGroup;
import com.rummy.engine.rules.DeclarationResult;
import com.rummy.engine.rules.RummyRules;

import java.time.Instant;
import java.util.*;

/**
 * Server-authoritative in-memory Game Engine.
 * Implements deterministic state transitions:
 * GameState + GameCommand -> EngineResult(GameState, List<GameEvent>)
 * 
 * Strictly decoupled from transport (WebSocket), storage (MongoDB), and networking.
 */
public final class GameEngine {

    public static final long DEFAULT_TURN_TIMEOUT_SECONDS = 30L;

    public EngineResult process(GameState state, GameCommand command, RummyRules rules) {
        Objects.requireNonNull(state, "GameState must not be null");
        Objects.requireNonNull(command, "GameCommand must not be null");
        Objects.requireNonNull(rules, "RummyRules must not be null");

        try {
            return switch (command) {
                case JoinCommand cmd -> handleJoin(state, cmd, rules);
                case ReadyCommand cmd -> handleReady(state, cmd);
                case StartGameCommand cmd -> handleStartGame(state, cmd, rules);
                case DrawCommand cmd -> handleDraw(state, cmd);
                case DiscardCommand cmd -> handleDiscard(state, cmd, rules);
                case DeclareCommand cmd -> handleDeclare(state, cmd, rules);
                case DropCommand cmd -> handleDrop(state, cmd, rules);
                case TimeoutCommand cmd -> handleTimeout(state, cmd, rules);
            };
        } catch (Exception e) {
            return EngineResult.failure(state, e.getMessage());
        }
    }

    private EngineResult handleJoin(GameState state, JoinCommand cmd, RummyRules rules) {
        if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
            return EngineResult.failure(state, "Cannot join table when game status is " + state.getStatus());
        }
        if (state.getPlayers().size() >= rules.getMaxPlayers()) {
            return EngineResult.failure(state, "Table is full (max " + rules.getMaxPlayers() + " players)");
        }
        if (state.getPlayer(cmd.playerId()).isPresent()) {
            return EngineResult.failure(state, "Player " + cmd.playerId() + " is already seated at this table");
        }
        if (state.getPlayerBySeat(cmd.seatIndex()).isPresent()) {
            return EngineResult.failure(state, "Seat " + cmd.seatIndex() + " is already occupied");
        }

        PlayerState newPlayer = new PlayerState(cmd.playerId(), cmd.displayName(), cmd.seatIndex(), cmd.isBot());
        state.addPlayer(newPlayer);

        long seq = state.nextSequence();
        String eventId = UUID.randomUUID().toString();
        PlayerJoinedEvent event = new PlayerJoinedEvent(eventId, state.getGameId(), seq, cmd.timestamp(),
                cmd.playerId(), cmd.displayName(), cmd.seatIndex(), cmd.isBot());

        return EngineResult.success(state, List.of(event));
    }

    private EngineResult handleReady(GameState state, ReadyCommand cmd) {
        if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
            return EngineResult.failure(state, "Cannot ready up when game status is " + state.getStatus());
        }
        PlayerState player = state.requirePlayer(cmd.playerId());
        player.setStatus(PlayerStatus.READY);

        long seq = state.nextSequence();
        String eventId = UUID.randomUUID().toString();
        PlayerReadyEvent event = new PlayerReadyEvent(eventId, state.getGameId(), seq, cmd.timestamp(), cmd.playerId());

        return EngineResult.success(state, List.of(event));
    }

    private EngineResult handleStartGame(GameState state, StartGameCommand cmd, RummyRules rules) {
        // Rematch / Play Next Deal after a finished hand
        if (state.getStatus() == GameStatus.COMPLETED || state.getStatus() == GameStatus.ABORTED) {
            if (state.getPlayers().size() < rules.getMinPlayers()) {
                return EngineResult.failure(state, "Not enough players to start a new deal (minimum " + rules.getMinPlayers() + ")");
            }
            Deck freshDeck = Deck.createMultiPackDeck(
                    rules.getDeckCount(),
                    rules.getPrintedJokersPerDeck(),
                    new java.security.SecureRandom());
            state.prepareForNewDeal(freshDeck);
            // Auto-ready seated players for immediate rematch
            for (PlayerState player : state.getPlayers()) {
                player.setStatus(PlayerStatus.READY);
            }
        }

        if (state.getStatus() != GameStatus.WAITING_FOR_PLAYERS) {
            return EngineResult.failure(state, "Cannot start game when status is " + state.getStatus());
        }
        if (state.getPlayers().size() < rules.getMinPlayers()) {
            return EngineResult.failure(state, "Not enough players to start (minimum " + rules.getMinPlayers() + ")");
        }
        boolean allReady = state.getPlayers().stream().allMatch(p -> p.getStatus() == PlayerStatus.READY);
        if (!allReady) {
            return EngineResult.failure(state, "All seated players must be READY before starting");
        }

        state.setStatus(GameStatus.DEALING);
        Deck deck = state.getDeck();
        deck.shuffle();

        // Cut wild joker
        CardInstance cutJoker = deck.draw();
        state.setCutJoker(cutJoker);

        // Deal cards to each player
        for (PlayerState player : state.getPlayers()) {
            player.addCards(deck.drawBatch(rules.getCardsPerPlayer()));
            player.setStatus(PlayerStatus.ACTIVE);
        }

        // Initial face-up discard card
        CardInstance initialDiscard = deck.draw();
        state.addToDiscardPile(initialDiscard);

        // First player is lowest seat index active player
        PlayerState firstPlayer = state.getPlayers().stream()
                .filter(p -> p.getStatus() == PlayerStatus.ACTIVE)
                .min(Comparator.comparingInt(PlayerState::getSeatIndex))
                .orElseThrow();

        TurnState turn = TurnState.startTurn(1, firstPlayer.getPlayerId(), cmd.timestamp(), DEFAULT_TURN_TIMEOUT_SECONDS);
        state.setTurnState(turn);
        state.setStatus(GameStatus.IN_PROGRESS);

        long seq = state.nextSequence();
        String eventId = UUID.randomUUID().toString();
        List<String> playerIds = state.getPlayers().stream().map(PlayerState::getPlayerId).toList();

        GameStartedEvent event = new GameStartedEvent(eventId, state.getGameId(), seq, cmd.timestamp(),
                cutJoker, initialDiscard, playerIds, firstPlayer.getPlayerId(), turn.getTurnDeadline());

        return EngineResult.success(state, List.of(event));
    }

    private EngineResult handleDraw(GameState state, DrawCommand cmd) {
        if (state.getStatus() != GameStatus.IN_PROGRESS) {
            return EngineResult.failure(state, "Game is not in progress");
        }
        TurnState turn = state.getTurnState();
        if (turn == null || !turn.getCurrentPlayerId().equals(cmd.playerId())) {
            return EngineResult.failure(state, "It is not your turn to draw");
        }
        if (turn.getPhase() != TurnPhase.AWAITING_DRAW) {
            return EngineResult.failure(state, "Cannot draw; current phase is " + turn.getPhase());
        }

        PlayerState player = state.requirePlayer(cmd.playerId());
        if (player.getHandSize() != 13) {
            return EngineResult.failure(state, "Expected hand size of 13 before draw, but found " + player.getHandSize());
        }

        CardInstance drawnCard;
        boolean fromDiscard;

        if (cmd.source() == DrawSource.CLOSED_DECK) {
            if (state.getDeck().isEmpty()) {
                // Recycle discard pile per rule R8
                if (state.getDiscardPile().size() <= 1) {
                    return EngineResult.failure(state, "Deck and discard pile are exhausted");
                }
                CardInstance topDiscard = state.takeTopDiscard();
                List<CardInstance> recyclePile = new ArrayList<>(state.getDiscardPile());
                state.getDeck().recycleDiscardPile(recyclePile);
                state.addToDiscardPile(topDiscard);
            }
            drawnCard = state.getDeck().draw();
            fromDiscard = false;
        } else {
            if (state.getDiscardPile().isEmpty()) {
                return EngineResult.failure(state, "Discard pile is empty");
            }
            drawnCard = state.takeTopDiscard();
            fromDiscard = true;
        }

        player.addCard(drawnCard);
        state.setTurnState(turn.withCardDrawn(drawnCard.getInstanceId(), fromDiscard));

        long seq = state.nextSequence();
        String eventId = UUID.randomUUID().toString();
        CardDrawnEvent event = new CardDrawnEvent(eventId, state.getGameId(), seq, cmd.timestamp(),
                cmd.playerId(), cmd.source(), drawnCard, player.getHandSize());

        return EngineResult.success(state, List.of(event));
    }

    private EngineResult handleDiscard(GameState state, DiscardCommand cmd, RummyRules rules) {
        if (state.getStatus() != GameStatus.IN_PROGRESS) {
            return EngineResult.failure(state, "Game is not in progress");
        }
        TurnState turn = state.getTurnState();
        if (turn == null || !turn.getCurrentPlayerId().equals(cmd.playerId())) {
            return EngineResult.failure(state, "It is not your turn to discard");
        }
        if (turn.getPhase() != TurnPhase.AWAITING_DISCARD) {
            return EngineResult.failure(state, "Cannot discard; current phase is " + turn.getPhase());
        }

        PlayerState player = state.requirePlayer(cmd.playerId());
        if (player.getHandSize() != 14) {
            return EngineResult.failure(state, "Expected hand size of 14 before discard, but found " + player.getHandSize());
        }
        if (!player.hasCard(cmd.cardInstanceId())) {
            return EngineResult.failure(state, "Card " + cmd.cardInstanceId() + " is not in player's hand");
        }

        CardInstance discarded = player.removeCard(cmd.cardInstanceId());
        state.addToDiscardPile(discarded);
        player.recordTurnCompleted();

        List<GameEvent> events = new ArrayList<>();

        long discardSeq = state.nextSequence();
        String discardEventId = UUID.randomUUID().toString();
        events.add(new CardDiscardedEvent(discardEventId, state.getGameId(), discardSeq, cmd.timestamp(),
                cmd.playerId(), discarded, player.getHandSize()));

        // Advance to next active player
        Optional<PlayerState> nextOpt = state.nextActivePlayer(cmd.playerId());
        if (nextOpt.isEmpty()) {
            // No other active player remains -> end game
            return finishGameWithSingleRemainingPlayer(state, player, cmd.timestamp(), events);
        }

        PlayerState nextPlayer = nextOpt.get();
        TurnState nextTurn = TurnState.startTurn(turn.getTurnNumber() + 1, nextPlayer.getPlayerId(),
                cmd.timestamp(), DEFAULT_TURN_TIMEOUT_SECONDS);
        state.setTurnState(nextTurn);

        long turnSeq = state.nextSequence();
        String turnEventId = UUID.randomUUID().toString();
        events.add(new TurnChangedEvent(turnEventId, state.getGameId(), turnSeq, cmd.timestamp(),
                cmd.playerId(), nextPlayer.getPlayerId(), nextTurn.getTurnNumber(), nextTurn.getTurnDeadline()));

        return EngineResult.success(state, events);
    }

    private EngineResult handleDeclare(GameState state, DeclareCommand cmd, RummyRules rules) {
        if (state.getStatus() != GameStatus.IN_PROGRESS) {
            return EngineResult.failure(state, "Game is not in progress");
        }
        TurnState turn = state.getTurnState();
        if (turn == null || !turn.getCurrentPlayerId().equals(cmd.playerId())) {
            return EngineResult.failure(state, "It is not your turn to declare");
        }
        if (turn.getPhase() != TurnPhase.AWAITING_DISCARD) {
            return EngineResult.failure(state, "Cannot declare; must draw first");
        }

        PlayerState player = state.requirePlayer(cmd.playerId());
        int expectedHand = rules.getCardsPerPlayer() + 1;
        if (player.getHandSize() != expectedHand) {
            return EngineResult.failure(state, "Expected " + expectedHand + " cards (" + rules.getCardsPerPlayer() + " hand + 1 finish) to declare, but found " + player.getHandSize());
        }
        if (!player.hasCard(cmd.finishCardInstanceId())) {
            return EngineResult.failure(state, "Finish card " + cmd.finishCardInstanceId() + " not found in hand");
        }

        // Temporarily take finish card out of hand
        CardInstance finishCard = player.removeCard(cmd.finishCardInstanceId());
        state.setFinishCard(finishCard);

        DeclarationResult result = rules.validateDeclaration(cmd.groups(), state.getCutJoker().getCard());

        List<GameEvent> events = new ArrayList<>();

        if (result.isValid()) {
            // Valid declaration!
            player.markDeclared();
            player.setScore(0);
            state.setStatus(GameStatus.COMPLETED);
            state.setWinnerPlayerId(cmd.playerId());
            state.setWinningGroups(cmd.groups());

            // Score opponents
            Map<String, Integer> scoreMap = new HashMap<>();
            scoreMap.put(cmd.playerId(), 0);

            for (PlayerState opponent : state.getPlayers()) {
                if (!opponent.getPlayerId().equals(cmd.playerId()) && opponent.getStatus() == PlayerStatus.ACTIVE) {
                    List<CardGroup> opponentGroups = List.of(CardGroup.of(opponent.getHandSnapshot()));
                    int penalty = rules.calculateLosingScore(opponentGroups, state.getCutJoker().getCard());
                    opponent.setScore(penalty);
                    opponent.addCumulativeScore(penalty);
                    scoreMap.put(opponent.getPlayerId(), penalty);
                } else if (opponent.getStatus() == PlayerStatus.DROPPED) {
                    scoreMap.put(opponent.getPlayerId(), opponent.getScore());
                }
            }

            long declSeq = state.nextSequence();
            String declEventId = UUID.randomUUID().toString();
            events.add(new DeclareAcceptedEvent(declEventId, state.getGameId(), declSeq, cmd.timestamp(),
                    cmd.playerId(), finishCard, cmd.groups(), scoreMap));

            long finishSeq = state.nextSequence();
            String finishEventId = UUID.randomUUID().toString();
            events.add(new GameFinishedEvent(finishEventId, state.getGameId(), finishSeq, cmd.timestamp(),
                    cmd.playerId(), scoreMap));

            return EngineResult.success(state, events);
        } else {
            // Invalid / Wrong Declaration
            player.markDropped(rules.getWrongDeclarationPenalty());
            state.addToDiscardPile(finishCard);

            long declSeq = state.nextSequence();
            String declEventId = UUID.randomUUID().toString();
            events.add(new DeclareRejectedEvent(declEventId, state.getGameId(), declSeq, cmd.timestamp(),
                    cmd.playerId(), finishCard, rules.getWrongDeclarationPenalty(), result.errors()));

            // Check if only 1 active player remains after the wrong declaration
            Optional<PlayerState> nextOpt = state.nextActivePlayer(cmd.playerId());
            if (nextOpt.isEmpty()) {
                PlayerState lastPlayer = state.getPlayers().stream()
                        .filter(p -> p.getStatus() == PlayerStatus.ACTIVE)
                        .findFirst()
                        .orElse(player);
                return finishGameWithSingleRemainingPlayer(state, lastPlayer, cmd.timestamp(), events);
            }

            PlayerState nextPlayer = nextOpt.get();
            TurnState nextTurn = TurnState.startTurn(turn.getTurnNumber() + 1, nextPlayer.getPlayerId(),
                    cmd.timestamp(), DEFAULT_TURN_TIMEOUT_SECONDS);
            state.setTurnState(nextTurn);

            long turnSeq = state.nextSequence();
            String turnEventId = UUID.randomUUID().toString();
            events.add(new TurnChangedEvent(turnEventId, state.getGameId(), turnSeq, cmd.timestamp(),
                    cmd.playerId(), nextPlayer.getPlayerId(), nextTurn.getTurnNumber(), nextTurn.getTurnDeadline()));

            return EngineResult.success(state, events);
        }
    }

    private EngineResult handleDrop(GameState state, DropCommand cmd, RummyRules rules) {
        if (state.getStatus() != GameStatus.IN_PROGRESS) {
            return EngineResult.failure(state, "Game is not in progress");
        }
        PlayerState player = state.requirePlayer(cmd.playerId());
        if (player.getStatus() != PlayerStatus.ACTIVE) {
            return EngineResult.failure(state, "Player is not active");
        }

        boolean isFirstDrop = !player.hasTakenFirstTurn();
        int penalty = isFirstDrop ? rules.getFirstDropPenalty() : rules.getMiddleDropPenalty();
        player.markDropped(penalty);

        List<GameEvent> events = new ArrayList<>();
        long remainingActive = state.activePlayerCount();

        long dropSeq = state.nextSequence();
        String dropEventId = UUID.randomUUID().toString();
        events.add(new PlayerDroppedEvent(dropEventId, state.getGameId(), dropSeq, cmd.timestamp(),
                cmd.playerId(), isFirstDrop, penalty, remainingActive));

        if (remainingActive <= 1) {
            PlayerState winner = state.getPlayers().stream()
                    .filter(p -> p.getStatus() == PlayerStatus.ACTIVE)
                    .findFirst()
                    .orElse(player);
            return finishGameWithSingleRemainingPlayer(state, winner, cmd.timestamp(), events);
        }

        // If it was the dropped player's turn, advance turn
        TurnState turn = state.getTurnState();
        if (turn != null && turn.getCurrentPlayerId().equals(cmd.playerId())) {
            PlayerState nextPlayer = state.nextActivePlayer(cmd.playerId()).orElseThrow();
            TurnState nextTurn = TurnState.startTurn(turn.getTurnNumber() + 1, nextPlayer.getPlayerId(),
                    cmd.timestamp(), DEFAULT_TURN_TIMEOUT_SECONDS);
            state.setTurnState(nextTurn);

            long turnSeq = state.nextSequence();
            String turnEventId = UUID.randomUUID().toString();
            events.add(new TurnChangedEvent(turnEventId, state.getGameId(), turnSeq, cmd.timestamp(),
                    cmd.playerId(), nextPlayer.getPlayerId(), nextTurn.getTurnNumber(), nextTurn.getTurnDeadline()));
        }

        return EngineResult.success(state, events);
    }

    private EngineResult handleTimeout(GameState state, TimeoutCommand cmd, RummyRules rules) {
        if (state.getStatus() != GameStatus.IN_PROGRESS) {
            return EngineResult.failure(state, "Game is not in progress");
        }
        TurnState turn = state.getTurnState();
        if (turn == null || !turn.getCurrentPlayerId().equals(cmd.playerId())) {
            return EngineResult.failure(state, "Turn does not match timeout player");
        }

        PlayerState player = state.requirePlayer(cmd.playerId());
        player.incrementMissedTurns();

        List<GameEvent> events = new ArrayList<>();

        if (player.getConsecutiveMissedTurns() >= 3) {
            // Auto-drop rule: 3 consecutive missed turns
            int penalty = rules.getAutoDropPenalty();
            player.markDropped(penalty);

            long remaining = state.activePlayerCount();
            long dropSeq = state.nextSequence();
            events.add(new PlayerDroppedEvent(UUID.randomUUID().toString(), state.getGameId(), dropSeq,
                    cmd.timestamp(), cmd.playerId(), false, penalty, remaining));

            if (remaining <= 1) {
                PlayerState winner = state.getPlayers().stream()
                        .filter(p -> p.getStatus() == PlayerStatus.ACTIVE)
                        .findFirst()
                        .orElse(player);
                return finishGameWithSingleRemainingPlayer(state, winner, cmd.timestamp(), events);
            }
        } else {
            // Auto-discard if player drew but timed out on discard
            if (turn.getPhase() == TurnPhase.AWAITING_DISCARD && player.getHandSize() == 14) {
                // Discard the card that was drawn
                String drawnId = turn.getDrawnCardInstanceId();
                if (drawnId != null && player.hasCard(drawnId)) {
                    CardInstance autoDiscarded = player.removeCard(drawnId);
                    state.addToDiscardPile(autoDiscarded);
                    long discardSeq = state.nextSequence();
                    events.add(new CardDiscardedEvent(UUID.randomUUID().toString(), state.getGameId(),
                            discardSeq, cmd.timestamp(), cmd.playerId(), autoDiscarded, player.getHandSize()));
                }
            }
        }

        // Advance turn
        Optional<PlayerState> nextOpt = state.nextActivePlayer(cmd.playerId());
        if (nextOpt.isPresent()) {
            PlayerState nextPlayer = nextOpt.get();
            TurnState nextTurn = TurnState.startTurn(turn.getTurnNumber() + 1, nextPlayer.getPlayerId(),
                    cmd.timestamp(), DEFAULT_TURN_TIMEOUT_SECONDS);
            state.setTurnState(nextTurn);

            long turnSeq = state.nextSequence();
            events.add(new TurnChangedEvent(UUID.randomUUID().toString(), state.getGameId(), turnSeq,
                    cmd.timestamp(), cmd.playerId(), nextPlayer.getPlayerId(), nextTurn.getTurnNumber(), nextTurn.getTurnDeadline()));
        }

        return EngineResult.success(state, events);
    }

    private EngineResult finishGameWithSingleRemainingPlayer(GameState state, PlayerState winner,
                                                             Instant timestamp, List<GameEvent> events) {
        state.setStatus(GameStatus.COMPLETED);
        state.setWinnerPlayerId(winner.getPlayerId());
        winner.setScore(0);
        if (!winner.getHandSnapshot().isEmpty()) {
            state.setWinningGroups(List.of(com.rummy.engine.rules.CardGroup.of(winner.getHandSnapshot())));
        }

        Map<String, Integer> scoreMap = new HashMap<>();
        for (PlayerState p : state.getPlayers()) {
            scoreMap.put(p.getPlayerId(), p.getScore());
        }

        long finishSeq = state.nextSequence();
        String finishEventId = UUID.randomUUID().toString();
        events.add(new GameFinishedEvent(finishEventId, state.getGameId(), finishSeq, timestamp,
                winner.getPlayerId(), scoreMap));

        return EngineResult.success(state, events);
    }
}
