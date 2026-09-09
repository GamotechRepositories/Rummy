package com.rummy.engine.bot;

import com.rummy.engine.command.*;
import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.PlayerGameView;
import com.rummy.engine.model.TurnPhase;
import com.rummy.engine.rules.RummyRules;

import java.time.Instant;
import java.util.*;

/**
 * Production AI Bot Player implementing zero-knowledge fair play.
 * Generates valid GameCommands based strictly on the player's private PlayerGameView.
 */
public final class BotPlayerAgent implements PlayerAgent {

    public static final String BOT_VERSION = "1.0.0";

    private final String playerId;
    private final String displayName;
    private final BotDifficulty difficulty;
    private final Random random;

    public BotPlayerAgent(String playerId, String displayName, BotDifficulty difficulty) {
        this.playerId = Objects.requireNonNull(playerId, "playerId must not be null");
        this.displayName = displayName != null ? displayName : "Bot_" + playerId;
        this.difficulty = difficulty != null ? difficulty : BotDifficulty.MEDIUM;
        this.random = new Random();
    }

    @Override
    public String getPlayerId() {
        return playerId;
    }

    @Override
    public boolean isBot() {
        return true;
    }

    public BotDifficulty getDifficulty() {
        return difficulty;
    }

    public String getVersion() {
        return BOT_VERSION;
    }

    @Override
    public GameCommand decideAction(PlayerGameView view, RummyRules rules) {
        Objects.requireNonNull(view, "PlayerGameView must not be null");
        Objects.requireNonNull(rules, "RummyRules must not be null");

        if (!view.isMyTurn()) {
            throw new IllegalStateException("Bot " + playerId + " cannot act when it is not its turn");
        }

        Card cutCard = view.cutJoker() != null ? view.cutJoker().getCard() : null;
        Instant now = Instant.now();
        String cmdId = UUID.randomUUID().toString();

        if (view.turnPhase() == TurnPhase.AWAITING_DRAW) {
            return decideDraw(view, cutCard, cmdId, now);
        } else if (view.turnPhase() == TurnPhase.AWAITING_DISCARD) {
            return decideDiscardOrDeclare(view, cutCard, rules, cmdId, now);
        }

        throw new IllegalStateException("Unhandled turn phase for bot: " + view.turnPhase());
    }

    private GameCommand decideDraw(PlayerGameView view, Card cutCard, String cmdId, Instant now) {
        CardInstance topDiscard = view.topDiscard();

        if (topDiscard != null) {
            boolean improves = HandEvaluator.doesCardImproveHand(topDiscard, view.hand(), cutCard);
            if (improves) {
                return new DrawCommand(cmdId, view.gameId(), playerId, DrawSource.DISCARD_PILE, now);
            }
        }

        return new DrawCommand(cmdId, view.gameId(), playerId, DrawSource.CLOSED_DECK, now);
    }

    private GameCommand decideDiscardOrDeclare(PlayerGameView view, Card cutCard, RummyRules rules, String cmdId, Instant now) {
        List<CardInstance> hand = view.hand();

        // 1. Check if 14 cards contain a winning declaration
        Optional<HandEvaluator.EvaluationResult> winning = HandEvaluator.findWinningDeclaration(hand, cutCard, rules);
        if (winning.isPresent()) {
            HandEvaluator.EvaluationResult win = winning.get();
            return new DeclareCommand(cmdId, view.gameId(), playerId, win.finishCard().getInstanceId(), win.meldedGroups(), now);
        }

        // 2. Select optimal discard by finding the worst deadwood card
        HandEvaluator.EvaluationResult eval = HandEvaluator.evaluateDeadwood(hand, cutCard);
        List<CardInstance> deadwood = eval.deadwoodCards();

        CardInstance cardToDiscard;
        if (!deadwood.isEmpty()) {
            // Sort deadwood by penalty points descending (A/K/Q/J/10 first)
            List<CardInstance> sortedDeadwood = new ArrayList<>(deadwood);
            sortedDeadwood.sort((c1, c2) -> Integer.compare(c2.getCard().points(cutCard), c1.getCard().points(cutCard)));

            if (difficulty == BotDifficulty.EASY && sortedDeadwood.size() > 1 && random.nextDouble() < 0.25) {
                // Easy bot occasionally picks a random deadwood card
                cardToDiscard = sortedDeadwood.get(random.nextInt(sortedDeadwood.size()));
            } else {
                cardToDiscard = sortedDeadwood.get(0);
            }
        } else {
            // If all cards are somehow in melds but total doesn't form full winning declaration,
            // pick a card with the highest points that isn't a joker
            cardToDiscard = hand.stream()
                    .filter(c -> !c.isPrintedJoker() && !c.getCard().isWildJoker(cutCard))
                    .max(Comparator.comparingInt(c -> c.getCard().points(cutCard)))
                    .orElseGet(() -> hand.get(hand.size() - 1));
        }

        return new DiscardCommand(cmdId, view.gameId(), playerId, cardToDiscard.getInstanceId(), now);
    }
}
