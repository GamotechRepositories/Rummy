package com.rummy.engine.bot;

import com.rummy.engine.command.*;
import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.model.PlayerGameView;
import com.rummy.engine.model.TurnPhase;
import com.rummy.engine.rules.RummyRules;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Production AI Bot Player implementing zero-knowledge fair play.
 * Generates valid GameCommands based strictly on the player's private PlayerGameView.
 */
public final class BotPlayerAgent implements PlayerAgent {

    public static final String BOT_VERSION = "1.0.0";

    /** Chance of playing one more turn on a hand it would drop, so drops do not land on a fixed beat. */
    static final double DROP_HESITATION = 0.10;
    /** Least improvement (in {@link HandSolver#cost} points) for which the bot shows its hand by taking the open card. */
    static final int OPEN_CARD_MIN_GAIN = 8;

    private final String playerId;
    private final String displayName;
    private final BotDifficulty difficulty;
    private final Random random;
    private final Set<Card> opponentPicks;
    private final Map<String, Set<Card>> opponentPicksByPlayer;
    private final Map<String, Set<Card>> opponentDiscardsByPlayer;
    private int botTurnCountInDeal;
    
    // Hand State Caching
    private String lastHandState = "";
    private HandEvaluator.EvaluationResult cachedDeadwood;

    public BotPlayerAgent(String playerId, String displayName, BotDifficulty difficulty) {
        this(playerId, displayName, difficulty, new Random());
    }

    BotPlayerAgent(String playerId, String displayName, BotDifficulty difficulty, Random random) {
        this.playerId = Objects.requireNonNull(playerId, "playerId must not be null");
        this.displayName = displayName != null ? displayName : "Bot_" + playerId;
        this.difficulty = difficulty != null ? difficulty : BotDifficulty.MEDIUM;
        this.random = random;
        this.opponentPicks = java.util.concurrent.ConcurrentHashMap.newKeySet();
        this.opponentPicksByPlayer = new java.util.concurrent.ConcurrentHashMap<>();
        this.opponentDiscardsByPlayer = new java.util.concurrent.ConcurrentHashMap<>();
        this.botTurnCountInDeal = 0;
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

    /**
     * Records a card picked by an opponent from the open discard pile.
     */
    public void recordOpponentPick(Card card) {
        if (card != null && !card.isPrintedJoker()) {
            opponentPicks.add(card);
        }
    }

    /**
     * Records a card picked by a specific opponent player.
     * Enables downstream-specific defense against the next player clockwise.
     */
    public void recordOpponentPick(String opponentPlayerId, Card card) {
        if (card != null && !card.isPrintedJoker()) {
            opponentPicks.add(card);
            if (opponentPlayerId != null) {
                opponentPicksByPlayer.computeIfAbsent(opponentPlayerId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(card);
            }
        }
    }

    /**
     * Records a card an opponent threw away. Cards near what a player discards are ones they are not
     * collecting, so they are safer to hand that player.
     */
    public void recordOpponentDiscard(String opponentPlayerId, Card card) {
        if (opponentPlayerId != null && card != null && !card.isPrintedJoker()) {
            opponentDiscardsByPlayer.computeIfAbsent(opponentPlayerId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(card);
        }
    }

    /**
     * Resets the memory of opponent picks at the start of each new deal.
     */
    public void resetDealMemory() {
        opponentPicks.clear();
        opponentPicksByPlayer.clear();
        opponentDiscardsByPlayer.clear();
        botTurnCountInDeal = 0;
        lastHandState = "";
        cachedDeadwood = null;
    }

    public Set<Card> getOpponentPicks() {
        return Collections.unmodifiableSet(opponentPicks);
    }

    public Map<String, Set<Card>> getOpponentPicksByPlayer() {
        return Collections.unmodifiableMap(opponentPicksByPlayer);
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
            botTurnCountInDeal++;

            if (view.hasTakenFirstTurn() && difficulty != BotDifficulty.EASY) {
                int activeOpponents = (int) view.opponents().stream()
                        .filter(o -> o.status() == com.rummy.engine.model.PlayerStatus.ACTIVE)
                        .count();
                if (HandEvaluator.shouldTakeMiddleDrop(view.hand(), cutCard, rules, view.viewerCumulativeScore(),
                        view.eliminationThreshold(), botTurnCountInDeal, activeOpponents)
                        && random.nextDouble() >= DROP_HESITATION) {
                    return new DropCommand(cmdId, view.gameId(), playerId, now);
                }
            }

            return decideDraw(view, cutCard, rules, cmdId, now);
        } else if (view.turnPhase() == TurnPhase.AWAITING_DISCARD) {
            return decideDiscardOrDeclare(view, cutCard, rules, cmdId, now);
        }

        throw new IllegalStateException("Unhandled turn phase for bot: " + view.turnPhase());
    }

    private GameCommand decideDraw(PlayerGameView view, Card cutCard, RummyRules rules, String cmdId, Instant now) {
        CardInstance topDiscard = view.topDiscard();

        if (topDiscard != null) {
            boolean isTopJoker = topDiscard.isPrintedJoker() || (cutCard != null && topDiscard.getCard().isWildJoker(cutCard));
            if (isTopJoker && view.topDiscardPickable()) {
                return new DrawCommand(cmdId, view.gameId(), playerId, DrawSource.DISCARD_PILE, now);
            }
            if (!isTopJoker) {
                // INTENTIONAL BLUNDER: Medium bot has 8% chance to ignore a good open card
                boolean blunder = (difficulty == BotDifficulty.MEDIUM && random.nextDouble() < 0.08);

                if (!blunder) {
                    // 1. Instant win declaration check
                    List<CardInstance> potentialHand = new ArrayList<>(view.hand());
                    potentialHand.add(topDiscard);
                    if (HandEvaluator.findWinningDeclaration(potentialHand, cutCard, rules).isPresent()) {
                        return new DrawCommand(cmdId, view.gameId(), playerId, DrawSource.DISCARD_PILE, now);
                    }

                    // 2. Pure Sequence priority: If card creates a natural Pure Sequence, always pick it!
                    boolean formsPure = HandEvaluator.doesCardFormPureSequence(topDiscard, view.hand());
                    if (formsPure) {
                        return new DrawCommand(cmdId, view.gameId(), playerId, DrawSource.DISCARD_PILE, now);
                    }

                    // 3. With a pure sequence in hand, take open cards that genuinely bring the hand closer to declaring
                    boolean hasPure = HandEvaluator.hasPureSequence(view.hand(), cutCard);
                    if (hasPure && takingOpenCardPays(potentialHand, topDiscard, cutCard)) {
                        return new DrawCommand(cmdId, view.gameId(), playerId, DrawSource.DISCARD_PILE, now);
                    }
                }
            }
        }

        return new DrawCommand(cmdId, view.gameId(), playerId, DrawSource.CLOSED_DECK, now);
    }

    /**
     * Whether the hand after taking the open card (the last card of {@code handWithOpenCard}) and making its
     * best discard is clearly better than the hand now.
     */
    private boolean takingOpenCardPays(List<CardInstance> handWithOpenCard, CardInstance openCard, Card cutCard) {
        int openIndex = handWithOpenCard.size() - 1;
        if (!HandSolver.fits(handWithOpenCard)) {
            return HandEvaluator.doesCardImproveHand(openCard, handWithOpenCard.subList(0, openIndex), cutCard);
        }
        HandSolver solver = new HandSolver(handWithOpenCard, cutCard);
        int now = solver.cost(solver.without(openIndex));
        int bestAfter = Integer.MAX_VALUE;
        for (int i = 0; i < handWithOpenCard.size(); i++) {
            if (i != openIndex) {
                bestAfter = Math.min(bestAfter, solver.cost(solver.without(i)));
            }
        }
        return bestAfter <= now - OPEN_CARD_MIN_GAIN;
    }

    private GameCommand decideDiscardOrDeclare(PlayerGameView view, Card cutCard, RummyRules rules, String cmdId, Instant now) {
        List<CardInstance> hand = view.hand();
        String forbiddenCardId = (view.isDrawnFromDiscard() && view.drawnCardInstanceId() != null)
                ? view.drawnCardInstanceId()
                : null;

        // 1. Check if 14 cards contain a winning declaration
        Optional<HandEvaluator.EvaluationResult> winning = HandEvaluator.findWinningDeclaration(hand, cutCard, rules, forbiddenCardId);
        if (winning.isPresent()) {
            HandEvaluator.EvaluationResult win = winning.get();
            return new DeclareCommand(cmdId, view.gameId(), playerId, win.finishCard().getInstanceId(), win.meldedGroups(), now);
        }

        // 2. Identify the next active player clockwise to enforce downstream defensive blocking
        String nextPlayerId = findNextActiveOpponentId(view);
        int deckCount = rules != null ? rules.getDeckCount() : 2;

        if (difficulty != BotDifficulty.EASY && HandSolver.fits(hand)) {
            CardInstance best = chooseDiscard(view, cutCard, forbiddenCardId, nextPlayerId, deckCount);
            if (best != null) {
                return new DiscardCommand(cmdId, view.gameId(), playerId, best.getInstanceId(), now);
            }
        }

        // 3. Select optimal discard by finding the best card to release (lowest value to us, safest against opponents)
        HandEvaluator.EvaluationResult eval = getCachedDeadwood(view, cutCard);
        List<CardInstance> deadwood = eval.deadwoodCards();

        CardInstance cardToDiscard;
        List<CardInstance> eligibleDeadwood = deadwood.stream()
                .filter(c -> forbiddenCardId == null || !c.getInstanceId().equals(forbiddenCardId))
                .toList();

        if (!eligibleDeadwood.isEmpty()) {
            // Sort deadwood by discard desirability descending (highest score is discarded first)
            List<CardInstance> sortedDeadwood = new ArrayList<>(eligibleDeadwood);
            sortedDeadwood.sort((c1, c2) -> {
                int score1 = calculateDiscardDesirability(c1, view, cutCard, nextPlayerId, deckCount);
                int score2 = calculateDiscardDesirability(c2, view, cutCard, nextPlayerId, deckCount);
                return Integer.compare(score2, score1);
            });

            if (difficulty == BotDifficulty.EASY && sortedDeadwood.size() > 1 && random.nextDouble() < 0.25) {
                // Easy bot occasionally picks a random deadwood card
                cardToDiscard = sortedDeadwood.get(random.nextInt(sortedDeadwood.size()));
            } else {
                cardToDiscard = sortedDeadwood.get(0);
            }
        } else {
            // If all deadwood cards were somehow the forbidden card or cards are in melds,
            // pick a card with the highest discard desirability that isn't a joker and not forbidden
            cardToDiscard = hand.stream()
                    .filter(c -> forbiddenCardId == null || !c.getInstanceId().equals(forbiddenCardId))
                    .filter(c -> !c.isPrintedJoker() && !c.getCard().isWildJoker(cutCard))
                    .max(Comparator.comparingInt(c -> calculateDiscardDesirability(c, view, cutCard, nextPlayerId, deckCount)))
                    .orElseGet(() -> hand.stream()
                            .filter(c -> forbiddenCardId == null || !c.getInstanceId().equals(forbiddenCardId))
                            .findFirst()
                            .orElse(hand.get(hand.size() - 1)));
        }

        return new DiscardCommand(cmdId, view.gameId(), playerId, cardToDiscard.getInstanceId(), now);
    }

    /**
     * Picks the discard that leaves the closest hand to a declaration ({@link HandSolver#cost}), counting
     * also the connections the card would still offer and how much it would help the opponents. Jokers and
     * the card just taken from the open pile are kept. Returns null if nothing may be discarded.
     */
    private CardInstance chooseDiscard(PlayerGameView view, Card cutCard, String forbiddenCardId, String nextPlayerId, int deckCount) {
        List<CardInstance> hand = view.hand();
        HandSolver solver = new HandSolver(hand, cutCard);
        double dangerWeight = 0.3;
        int limit = view.eliminationThreshold();
        if (difficulty == BotDifficulty.HARD && limit > 0) {
            int score = view.viewerCumulativeScore();
            if (score >= limit * 0.75) {
                dangerWeight = 0.15;
            } else if (score <= limit * 0.25) {
                dangerWeight = 0.4;
            }
        }
        CardInstance best = null;
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < hand.size(); i++) {
            CardInstance c = hand.get(i);
            if (c.getInstanceId().equals(forbiddenCardId) || c.isPrintedJoker() || c.getCard().isWildJoker(cutCard)) {
                continue;
            }
            double cost = solver.cost(solver.without(i))
                    + HandEvaluator.calculateConnectorScore(c, hand, cutCard, view.discardHistory(), deckCount)
                    + dangerWeight * dangerScore(c, nextPlayerId);
            if (cost < bestCost) {
                bestCost = cost;
                best = c;
            }
        }
        return best;
    }

    /**
     * How much discarding {@code c} would help opponents, from the open cards they took. Halved against the
     * next player when they have thrown away a card of the same rank or a near card of the same suit.
     */
    private int dangerScore(CardInstance c, String nextPlayerId) {
        int danger = !opponentPicksByPlayer.isEmpty() && nextPlayerId != null
                ? HandEvaluator.calculateOpponentDangerScore(c, opponentPicksByPlayer, nextPlayerId)
                : HandEvaluator.calculateOpponentDangerScore(c, opponentPicks);
        if (danger > 0 && nextPlayerId != null && nextPlayerRejected(c.getCard(), opponentDiscardsByPlayer.get(nextPlayerId))) {
            danger /= 2;
        }
        return danger;
    }

    private static boolean nextPlayerRejected(Card card, Set<Card> discards) {
        if (discards == null) {
            return false;
        }
        for (Card d : discards) {
            if (d.rank() == card.rank()) {
                return true;
            }
            if (d.suit() == card.suit()) {
                int gapLow = Math.abs(d.rank().getOrder() - card.rank().getOrder());
                int gapHigh = Math.abs(d.rank().getAceHighOrder() - card.rank().getAceHighOrder());
                if (Math.min(gapLow, gapHigh) <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Calculates how desirable a card is to discard.
     * Incorporates Card Counting (dead card awareness in discard history) and Next-Player Downstream Defense.
     */
    private int calculateDiscardDesirability(CardInstance c, PlayerGameView view, Card cutCard, String nextPlayerId, int deckCount) {
        int rawPts = c.getCard().points(cutCard);
        int connScore = HandEvaluator.calculateConnectorScore(c, view.hand(), cutCard, view.discardHistory(), deckCount);
        int dangerScore = difficulty == BotDifficulty.EASY ? 0 : dangerScore(c, nextPlayerId);

        // DYNAMIC RISK ASSESSMENT
        // Base weights: Points (want to throw), Connector (want to keep), Danger (bad to throw)
        double pointsWeight = 2.0;
        double dangerWeight = 3.0;

        if (difficulty == BotDifficulty.HARD) {
            int score = view.viewerCumulativeScore();
            int limit = view.eliminationThreshold();
            if (limit > 0) {
                if (score >= limit * 0.75) {
                    // Critical danger of elimination. Getting caught with points is fatal.
                    // Must discard high point cards even if slightly dangerous.
                    pointsWeight = 3.5; 
                    dangerWeight = 2.0; 
                } else if (score <= limit * 0.25) {
                    // Safe zone. Can afford to hold high points if it means starving opponents.
                    pointsWeight = 1.0;
                    dangerWeight = 4.0;
                }
            }
        }

        return (int) (rawPts * pointsWeight) - connScore - (int) (dangerScore * dangerWeight);
    }

    /**
     * Finds the immediate next active opponent sitting clockwise from the viewer.
     * Discarded cards are directly offered to this player first, requiring highest defensive caution.
     */
    private String findNextActiveOpponentId(PlayerGameView view) {
        int viewerSeat = view.viewerSeatIndex();
        List<PlayerGameView.OpponentView> activeOpponents = view.opponents().stream()
                .filter(o -> o.status() == com.rummy.engine.model.PlayerStatus.ACTIVE)
                .toList();
        if (activeOpponents.isEmpty()) {
            return null;
        }

        for (int step = 1; step < 6; step++) {
            int targetSeat = (viewerSeat + step) % 6;
            for (PlayerGameView.OpponentView opp : activeOpponents) {
                if (opp.seatIndex() == targetSeat) {
                    return opp.playerId();
                }
            }
        }
        return activeOpponents.get(0).playerId();
    }

    /**
     * Gets or calculates deadwood. Uses caching to avoid recalculation if hand hasn't changed.
     */
    private HandEvaluator.EvaluationResult getCachedDeadwood(PlayerGameView view, Card cutCard) {
        List<String> ids = new ArrayList<>();
        for (CardInstance c : view.hand()) ids.add(c.getInstanceId());
        Collections.sort(ids);
        String currentState = String.join(",", ids);
        
        if (!currentState.equals(lastHandState) || cachedDeadwood == null) {
            lastHandState = currentState;
            cachedDeadwood = HandEvaluator.evaluateDeadwood(view.hand(), cutCard);
        }
        return cachedDeadwood;
    }
    /**
     * Computes a human-like delay for the bot's turn action.
     */
    public long getThinkTimeMillis(PlayerGameView view, RummyRules rules, int totalPlayers) {
        long thinkMillis;
        TurnPhase phase = view.turnPhase();

        // Base think time based on phase
        if (phase == TurnPhase.AWAITING_DRAW) {
            thinkMillis = ThreadLocalRandom.current().nextLong(1000, 2500);
        } else {
            thinkMillis = ThreadLocalRandom.current().nextLong(1500, 3500);
        }

        // Difficulty modifiers
        if (difficulty == BotDifficulty.HARD) {
            // Hard bot thinks slightly faster because it is a "pro"
            thinkMillis = (long) (thinkMillis * 0.85);
        } else if (difficulty == BotDifficulty.EASY) {
            // Easy bot is a beginner, takes longer
            thinkMillis = (long) (thinkMillis * 1.3);
        }

        // Initial Sorting Delay on Turn 1
        if (!view.hasTakenFirstTurn() && phase == TurnPhase.AWAITING_DRAW) {
            int cardsPerPlayer = rules != null ? rules.getCardsPerPlayer() : 13;
            int totalCards = totalPlayers * cardsPerPlayer;
            // Deal animation duration roughly
            long dealDurationMs = Math.max(0, totalCards - 1) * 120L + 460L + 280L;
            // Human needs extra 3-5 seconds to arrange cards after deal
            long initialArrangementTime = ThreadLocalRandom.current().nextLong(3000, 5000);
            thinkMillis += dealDurationMs + initialArrangementTime;
        }

        // Emotional Tilt / High Pressure check (takes longer to decide if critical)
        int score = view.viewerCumulativeScore();
        int limit = view.eliminationThreshold();
        if (limit > 0 && score >= limit * 0.75) {
            // High pressure, takes extra time to think
            thinkMillis += ThreadLocalRandom.current().nextLong(1500, 3000);
        }

        return thinkMillis;
    }
}
