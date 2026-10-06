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

    private final String playerId;
    private final String displayName;
    private final BotDifficulty difficulty;
    private final Random random;
    private final Set<Card> opponentPicks;
    private final Map<String, Set<Card>> opponentPicksByPlayer;
    private int botTurnCountInDeal;
    
    // Hand State Caching
    private String lastHandState = "";
    private HandEvaluator.EvaluationResult cachedDeadwood;

    public BotPlayerAgent(String playerId, String displayName, BotDifficulty difficulty) {
        this.playerId = Objects.requireNonNull(playerId, "playerId must not be null");
        this.displayName = displayName != null ? displayName : "Bot_" + playerId;
        this.difficulty = difficulty != null ? difficulty : BotDifficulty.MEDIUM;
        this.random = new Random();
        this.opponentPicks = java.util.concurrent.ConcurrentHashMap.newKeySet();
        this.opponentPicksByPlayer = new java.util.concurrent.ConcurrentHashMap<>();
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
     * Resets the memory of opponent picks at the start of each new deal.
     */
    public void resetDealMemory() {
        opponentPicks.clear();
        opponentPicksByPlayer.clear();
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

            // EMOTIONAL PLAY / TILT: If close to elimination, bot might just drop out of fear if no pure sequence
            if (!view.hasTakenFirstTurn() && difficulty != BotDifficulty.EASY && view.eliminationThreshold() > 0) {
                if (view.viewerCumulativeScore() >= view.eliminationThreshold() * 0.8) {
                    // Very close to elimination. If no pure seq, high chance to just drop immediately (Tilt)
                    if (!HandEvaluator.hasPureSequence(view.hand(), cutCard) && random.nextDouble() < 0.6) {
                        return new DropCommand(cmdId, view.gameId(), playerId, now);
                    }
                }
            }

            // 1. Strategic First Drop check on turn 1 before drawing
            if (!view.hasTakenFirstTurn() && difficulty != BotDifficulty.EASY) {
                if (HandEvaluator.shouldTakeFirstDrop(view.hand(), cutCard, rules)) {
                    return new DropCommand(cmdId, view.gameId(), playerId, now);
                }
            }

            // 2. Strategic Middle Drop check on turn 2 or 3 (Hard difficulty)
            if (view.hasTakenFirstTurn() && difficulty == BotDifficulty.HARD && botTurnCountInDeal >= 2 && botTurnCountInDeal <= 3) {
                if (HandEvaluator.shouldTakeMiddleDrop(view.hand(), cutCard, rules, view.viewerCumulativeScore(), view.eliminationThreshold())) {
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

                    // 3. If bot already has a pure sequence, it is safe to pick cards that form impure sequences or sets
                    boolean hasPure = HandEvaluator.hasPureSequence(view.hand(), cutCard);
                    if (hasPure && HandEvaluator.doesCardImproveHand(topDiscard, view.hand(), cutCard)) {
                        return new DrawCommand(cmdId, view.gameId(), playerId, DrawSource.DISCARD_PILE, now);
                    }
                }
            }
        }

        return new DrawCommand(cmdId, view.gameId(), playerId, DrawSource.CLOSED_DECK, now);
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
     * Calculates how desirable a card is to discard.
     * Incorporates Card Counting (dead card awareness in discard history) and Next-Player Downstream Defense.
     * Includes PRO LOGIC: Dynamic Risk Assessment and Baiting/Trapping.
     */
    private int calculateDiscardDesirability(CardInstance c, PlayerGameView view, Card cutCard, String nextPlayerId, int deckCount) {
        int rawPts = c.getCard().points(cutCard);
        int connScore = HandEvaluator.calculateConnectorScore(c, view.hand(), cutCard, view.discardHistory(), deckCount);
        int dangerScore;
        if (difficulty == BotDifficulty.EASY) {
            dangerScore = 0;
        } else if (!opponentPicksByPlayer.isEmpty() && nextPlayerId != null) {
            dangerScore = HandEvaluator.calculateOpponentDangerScore(c, opponentPicksByPlayer, nextPlayerId);
        } else {
            dangerScore = HandEvaluator.calculateOpponentDangerScore(c, opponentPicks);
        }

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

        // TRAPPING / BAITING LOGIC
        // A pro player sometimes discards a card near their required sequence to trick opponents (Baiting).
        int trapBonus = 0;
        if (difficulty == BotDifficulty.HARD && dangerScore == 0 && connScore > 0 && connScore < 18) {
            // 20% chance to use this card as bait if it's not our best connector but still related to our hand.
            // Makes the bot highly unpredictable and human-like.
            if (random.nextDouble() < 0.20) {
                trapBonus = 25; // Artificially make this card highly desirable to discard as a trap
            }
        }

        return (int) (rawPts * pointsWeight) - connScore - (int) (dangerScore * dangerWeight) + trapBonus;
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
            thinkMillis = ThreadLocalRandom.current().nextLong(2000, 4000);
        } else {
            thinkMillis = ThreadLocalRandom.current().nextLong(3000, 6000);
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
