package com.rummy.engine.bot;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;
import com.rummy.engine.rules.*;

import java.util.*;

/**
 * Evaluator that analyzes hands, searches for valid melds, identifies declarations,
 * and calculates deadwood cards to minimize penalty points.
 */
public final class HandEvaluator {

    public record EvaluationResult(
            boolean isWinningDeclaration,
            CardInstance finishCard,
            List<CardGroup> meldedGroups,
            List<CardInstance> deadwoodCards,
            int deadwoodPoints
    ) {}

    private HandEvaluator() {}

    private static class EvaluationContext {
        final Set<CardGroup> meldSet = new LinkedHashSet<>();
        final List<CardInstance> jokers = new ArrayList<>(24);
        final Map<com.rummy.engine.model.Suit, List<CardInstance>> bySuit = new EnumMap<>(com.rummy.engine.model.Suit.class);
        final Map<com.rummy.engine.model.Rank, List<CardInstance>> byRank = new EnumMap<>(com.rummy.engine.model.Rank.class);
        final List<CardInstance> pool = new ArrayList<>(24);
        
        final List<CardGroup> selected = new ArrayList<>(8);
        final Set<String> usedIds = new HashSet<>(24);
        final int[] steps = new int[1];
        
        final List<CardInstance> deadwood = new ArrayList<>(24);
        final List<CardGroup> bestMelds = new ArrayList<>(8);
        
        EvaluationContext() {
            for (com.rummy.engine.model.Suit s : com.rummy.engine.model.Suit.values()) {
                bySuit.put(s, new ArrayList<>(14));
            }
            for (com.rummy.engine.model.Rank r : com.rummy.engine.model.Rank.values()) {
                byRank.put(r, new ArrayList<>(14));
            }
        }
        
        void clearForMelds() {
            meldSet.clear();
            jokers.clear();
            for (List<CardInstance> list : bySuit.values()) list.clear();
            for (List<CardInstance> list : byRank.values()) list.clear();
            pool.clear();
        }

        void clearForSearch() {
            selected.clear();
            usedIds.clear();
            steps[0] = 0;
        }
        
        void clearForDeadwood() {
            usedIds.clear();
            deadwood.clear();
            bestMelds.clear();
        }
    }

    private static final ThreadLocal<EvaluationContext> CONTEXT = ThreadLocal.withInitial(EvaluationContext::new);

    /**
     * Evaluates a hand (after draw: 14 cards for 13-card rummy, 22 cards for 21-card rummy)
     * to check if a valid declaration can be made.
     * Searches all candidate finish cards and partitions the remaining cards.
     */
    public static Optional<EvaluationResult> findWinningDeclaration(List<CardInstance> hand, Card cutJoker, RummyRules rules) {
        return findWinningDeclaration(hand, cutJoker, rules, null);
    }

    /** As above, but never uses {@code forbiddenFinishId} (the card just taken from the open pile) as the finish card. */
    public static Optional<EvaluationResult> findWinningDeclaration(List<CardInstance> hand, Card cutJoker, RummyRules rules,
                                                                    String forbiddenFinishId) {
        int targetCards = rules != null ? rules.getCardsPerPlayer() : 13;
        int expectedHand = targetCards + 1;
        if (hand == null || hand.size() != expectedHand) {
            return Optional.empty();
        }

        if (rules != null && HandSolver.fits(hand)) {
            HandSolver solver = new HandSolver(hand, cutJoker);
            boolean rulesRejectedAnArrangement = false;
            for (int i = 0; i < hand.size(); i++) {
                CardInstance finishCandidate = hand.get(i);
                if (finishCandidate.getInstanceId().equals(forbiddenFinishId)) {
                    continue;
                }
                List<CardGroup> groups = solver.declaration(solver.without(i));
                if (groups == null) {
                    continue;
                }
                if (rules.validateDeclaration(groups, cutJoker).isValid()) {
                    return Optional.of(new EvaluationResult(true, finishCandidate, groups, Collections.emptyList(), 0));
                }
                rulesRejectedAnArrangement = true;
            }
            if (!rulesRejectedAnArrangement) {
                return Optional.empty();
            }
        }

        // Try each card as the finish card
        for (int i = 0; i < hand.size(); i++) {
            CardInstance finishCandidate = hand.get(i);
            if (finishCandidate.getInstanceId().equals(forbiddenFinishId)) {
                continue;
            }
            List<CardInstance> remaining = new ArrayList<>(hand);
            remaining.remove(i);

            Optional<List<CardGroup>> winningGroups = searchValidPartition(remaining, cutJoker, rules);
            if (winningGroups.isPresent()) {
                return Optional.of(new EvaluationResult(true, finishCandidate, winningGroups.get(), Collections.emptyList(), 0));
            }
        }

        return Optional.empty();
    }

    /**
     * Searches for a valid partition of cards that satisfies the ruleset declaration requirements.
     */
    public static Optional<List<CardGroup>> searchValid13CardPartition(List<CardInstance> cards13, Card cutJoker, RummyRules rules) {
        return searchValidPartition(cards13, cutJoker, rules);
    }

    public static Optional<List<CardGroup>> searchValidPartition(List<CardInstance> cards, Card cutJoker, RummyRules rules) {
        List<CardGroup> candidateMelds = findAllCandidateMelds(cards, cutJoker);

        EvaluationContext ctx = CONTEXT.get();
        ctx.clearForSearch();

        if (backtrackFindPartition(candidateMelds, 0, cards.size(), ctx.selected, ctx.usedIds, cutJoker, rules, ctx.steps)) {
            return Optional.of(new ArrayList<>(ctx.selected));
        }

        return Optional.empty();
    }

    private static boolean backtrackFindPartition(List<CardGroup> candidates,
                                                  int startIndex,
                                                  int targetCardCount,
                                                  List<CardGroup> selected,
                                                  Set<String> usedIds,
                                                  Card cutJoker,
                                                  RummyRules rules,
                                                  int[] steps) {
        if (steps[0]++ > 3000) {
            return false;
        }
        if (usedIds.size() == targetCardCount) {
            DeclarationResult decl = rules.validateDeclaration(selected, cutJoker);
            return decl.isValid();
        }

        for (int i = startIndex; i < candidates.size(); i++) {
            CardGroup group = candidates.get(i);
            boolean overlaps = false;
            for (CardInstance c : group.getCards()) {
                if (usedIds.contains(c.getInstanceId())) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                continue;
            }

            // Select
            selected.add(group);
            for (CardInstance c : group.getCards()) {
                usedIds.add(c.getInstanceId());
            }

            if (backtrackFindPartition(candidates, i + 1, targetCardCount, selected, usedIds, cutJoker, rules, steps)) {
                return true;
            }

            // Backtrack
            selected.remove(selected.size() - 1);
            for (CardInstance c : group.getCards()) {
                usedIds.remove(c.getInstanceId());
            }
        }

        return false;
    }

    /**
     * Evaluates deadwood for an incomplete hand to guide discarding.
     */
    public static EvaluationResult evaluateDeadwood(List<CardInstance> hand, Card cutJoker) {
        if (hand == null || hand.isEmpty()) {
            return new EvaluationResult(false, null, Collections.emptyList(), Collections.emptyList(), 0);
        }

        List<CardGroup> candidates = findAllCandidateMelds(hand, cutJoker);
        EvaluationContext ctx = CONTEXT.get();
        ctx.clearForDeadwood();
        List<CardGroup> bestMelds = ctx.bestMelds;
        Set<String> usedIds = ctx.usedIds;

        for (CardGroup group : candidates) {
            boolean overlaps = false;
            for (CardInstance c : group.getCards()) {
                if (usedIds.contains(c.getInstanceId())) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                bestMelds.add(group);
                for (CardInstance c : group.getCards()) {
                    usedIds.add(c.getInstanceId());
                }
            }
        }

        List<CardInstance> deadwood = ctx.deadwood;
        int deadwoodPoints = 0;
        for (CardInstance c : hand) {
            if (!usedIds.contains(c.getInstanceId())) {
                deadwood.add(c);
                deadwoodPoints += c.getCard().points(cutJoker);
            }
        }

        return new EvaluationResult(false, null, new ArrayList<>(bestMelds), new ArrayList<>(deadwood), deadwoodPoints);
    }

    /**
     * Determines whether drawing a candidate card (e.g. from the discard pile) improves the hand.
     */
    public static boolean doesCardImproveHand(CardInstance candidate, List<CardInstance> hand, Card cutJoker) {
        if (candidate == null) {
            return false;
        }

        // Jokers always improve hand
        if (candidate.isPrintedJoker() || candidate.getCard().isWildJoker(cutJoker)) {
            return true;
        }

        // Check if candidate forms a pure sequence, impure sequence, or set with cards in hand
        for (int i = 0; i < hand.size(); i++) {
            for (int j = i + 1; j < hand.size(); j++) {
                CardGroup trio = CardGroup.of(hand.get(i), hand.get(j), candidate);
                GroupType type = classifyCandidateGroup(trio, cutJoker);
                if (type != GroupType.INVALID) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Classifies a group supporting both 13-Card and 21-Card Rummy (including Tunnelas).
     */
    public static GroupType classifyCandidateGroup(CardGroup group, Card cutJoker) {
        if (TwentyOneCardRummyRules.isTunnela(group)) {
            return GroupType.PURE_SEQUENCE;
        }
        return DeclarationValidator.classifyGroup(group, cutJoker);
    }

    /**
     * Finds all valid 3 and 4 card candidate pure sequences, impure sequences, and sets in the given hand.
     * OPTIMIZED: Pre-groups cards by Suit and Rank to avoid O(N^4) brute force across completely unrelated cards.
     */
    public static List<CardGroup> findAllCandidateMelds(List<CardInstance> cards, Card cutJoker) {
        EvaluationContext ctx = CONTEXT.get();
        ctx.clearForMelds();
        Set<CardGroup> meldSet = ctx.meldSet;
        List<CardInstance> jokers = ctx.jokers;
        Map<com.rummy.engine.model.Suit, List<CardInstance>> bySuit = ctx.bySuit;
        Map<com.rummy.engine.model.Rank, List<CardInstance>> byRank = ctx.byRank;

        for (CardInstance c : cards) {
            if (c.isPrintedJoker() || c.getCard().isWildJoker(cutJoker)) {
                jokers.add(c);
            }
            if (!c.isPrintedJoker()) {
                bySuit.get(c.getCard().suit()).add(c);
                byRank.get(c.getCard().rank()).add(c);
            }
        }

        // Sequences (Pure & Impure)
        for (List<CardInstance> suitCards : bySuit.values()) {
            if (suitCards.isEmpty()) continue;
            List<CardInstance> pool = ctx.pool;
            pool.clear();
            pool.addAll(suitCards);
            for (CardInstance joker : jokers) {
                if (!pool.contains(joker)) pool.add(joker);
            }
            int m = pool.size();
            for (int i = 0; i < m; i++) {
                for (int j = i + 1; j < m; j++) {
                    for (int k = j + 1; k < m; k++) {
                        CardGroup group = CardGroup.of(pool.get(i), pool.get(j), pool.get(k));
                        GroupType type = classifyCandidateGroup(group, cutJoker);
                        if (type == GroupType.PURE_SEQUENCE || type == GroupType.IMPURE_SEQUENCE) {
                            meldSet.add(group);
                        }
                        for (int p = k + 1; p < m; p++) {
                            CardGroup group4 = CardGroup.of(pool.get(i), pool.get(j), pool.get(k), pool.get(p));
                            GroupType type4 = classifyCandidateGroup(group4, cutJoker);
                            if (type4 == GroupType.PURE_SEQUENCE || type4 == GroupType.IMPURE_SEQUENCE) {
                                meldSet.add(group4);
                            }
                        }
                    }
                }
            }
        }

        // Sets
        for (List<CardInstance> rankCards : byRank.values()) {
            if (rankCards.isEmpty()) continue;
            List<CardInstance> pool = ctx.pool;
            pool.clear();
            pool.addAll(rankCards);
            int m = pool.size();
            for (int i = 0; i < m; i++) {
                for (int j = i + 1; j < m; j++) {
                    for (int k = j + 1; k < m; k++) {
                        CardGroup group = CardGroup.of(pool.get(i), pool.get(j), pool.get(k));
                        if (classifyCandidateGroup(group, cutJoker) == GroupType.SET) {
                            meldSet.add(group);
                        }
                        for (int p = k + 1; p < m; p++) {
                            CardGroup group4 = CardGroup.of(pool.get(i), pool.get(j), pool.get(k), pool.get(p));
                            if (classifyCandidateGroup(group4, cutJoker) == GroupType.SET) {
                                meldSet.add(group4);
                            }
                        }
                    }
                }
            }
        }

        List<CardGroup> melds = new ArrayList<>(meldSet);
        melds.sort((g1, g2) -> {
            GroupType t1 = classifyCandidateGroup(g1, cutJoker);
            GroupType t2 = classifyCandidateGroup(g2, cutJoker);
            int p1 = (t1 == GroupType.PURE_SEQUENCE ? 3 : (t1 == GroupType.IMPURE_SEQUENCE ? 2 : 1));
            int p2 = (t2 == GroupType.PURE_SEQUENCE ? 3 : (t2 == GroupType.IMPURE_SEQUENCE ? 2 : 1));
            return Integer.compare(p2, p1);
        });

        return melds;
    }

    /**
     * Counts how many copies of a specific card (suit + rank) have already appeared
     * in the public discard history or the player's own hand.
     */
    public static int countKnownCopies(com.rummy.engine.model.Suit suit, com.rummy.engine.model.Rank rank, List<CardInstance> hand, List<CardInstance> discardHistory) {
        if (suit == null || rank == null) {
            return 0;
        }
        int count = 0;
        if (hand != null) {
            for (CardInstance c : hand) {
                if (!c.isPrintedJoker() && c.getCard().suit() == suit && c.getCard().rank() == rank) {
                    count++;
                }
            }
        }
        if (discardHistory != null) {
            for (CardInstance c : discardHistory) {
                if (!c.isPrintedJoker() && c.getCard().suit() == suit && c.getCard().rank() == rank) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Checks if a hand currently contains at least one valid natural Pure Sequence.
     * OPTIMIZED: Uses O(N) early-exit logic instead of generating all possible combinations.
     */
    public static boolean hasPureSequence(List<CardInstance> hand, Card cutJoker) {
        if (hand == null || hand.size() < 3) {
            return false;
        }
        Map<com.rummy.engine.model.Suit, Set<Integer>> suitsToRanks = new EnumMap<>(com.rummy.engine.model.Suit.class);
        for (CardInstance c : hand) {
            if (c.isPrintedJoker()) continue;
            suitsToRanks.computeIfAbsent(c.getCard().suit(), k -> new TreeSet<>()).add(c.getCard().rank().getOrder());
        }

        for (Set<Integer> ranks : suitsToRanks.values()) {
            if (ranks.size() < 3) continue;
            
            List<Integer> sortedRanks = new ArrayList<>(ranks);
            int consecutiveCount = 1;
            for (int i = 1; i < sortedRanks.size(); i++) {
                if (sortedRanks.get(i) == sortedRanks.get(i - 1) + 1) {
                    consecutiveCount++;
                    if (consecutiveCount >= 3) return true;
                } else {
                    consecutiveCount = 1;
                }
            }
            
            // Check Ace-High wrapping (A, K, Q) => orders 1, 12, 13
            if (ranks.contains(1) && ranks.contains(12) && ranks.contains(13)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether drawing candidate card directly creates a natural Pure Sequence with cards in hand.
     */
    public static boolean doesCardFormPureSequence(CardInstance candidate, List<CardInstance> hand) {
        if (candidate == null || candidate.isPrintedJoker() || hand == null || hand.size() < 2) {
            return false;
        }
        int n = hand.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                CardGroup trio = CardGroup.of(hand.get(i), hand.get(j), candidate);
                // null cutJoker enforces 100% natural pure sequence without wild joker substitutions
                if (classifyCandidateGroup(trio, null) == GroupType.PURE_SEQUENCE) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int calculateConnectorScore(CardInstance candidate, List<CardInstance> pool, Card cutJoker) {
        return calculateConnectorScore(candidate, pool, cutJoker, null, 2);
    }

    /**
     * Evaluates potential connection value with dead-card awareness (Card Counting).
     * If cards needed to complete a run are already dead (visible in discard history or hand),
     * connector score is heavily penalized or reduced to 0.
     */
    public static int calculateConnectorScore(CardInstance candidate, List<CardInstance> pool, Card cutJoker, List<CardInstance> discardHistory, int deckCount) {
        if (candidate == null || pool == null || candidate.isPrintedJoker() || candidate.getCard().isWildJoker(cutJoker)) {
            return 0;
        }
        Card card = candidate.getCard();
        int bestScore = 0;
        int activeDeckCount = deckCount > 0 ? deckCount : 2;

        for (CardInstance other : pool) {
            if (other == null || other.getInstanceId().equals(candidate.getInstanceId())) {
                continue;
            }
            if (other.isPrintedJoker() || other.getCard().isWildJoker(cutJoker)) {
                continue;
            }
            Card oc = other.getCard();

            // Same suit connector
            if (card.suit() == oc.suit()) {
                int dLow = Math.abs(card.rank().getOrder() - oc.rank().getOrder());
                int dHigh = Math.abs(card.rank().getAceHighOrder() - oc.rank().getAceHighOrder());
                int d = Math.min(dLow, dHigh);

                int minOrder = Math.min(card.rank().getOrder(), oc.rank().getOrder());
                int maxOrder = Math.max(card.rank().getOrder(), oc.rank().getOrder());

                if (d == 1) {
                    // Consecutive run connector (e.g. 8-9)
                    com.rummy.engine.model.Rank lower = minOrder > 1 ? com.rummy.engine.model.Rank.values()[minOrder - 2] : null;
                    com.rummy.engine.model.Rank higher = maxOrder < 13 ? com.rummy.engine.model.Rank.values()[maxOrder] : null;

                    int deadLower = lower != null ? countKnownCopies(card.suit(), lower, pool, discardHistory) : activeDeckCount;
                    int deadHigher = higher != null ? countKnownCopies(card.suit(), higher, pool, discardHistory) : activeDeckCount;

                    int outsLower = Math.max(0, activeDeckCount - deadLower);
                    int outsHigher = Math.max(0, activeDeckCount - deadHigher);
                    int totalOuts = outsLower + outsHigher;

                    if (totalOuts == 0) {
                        continue;
                    }
                    // EV Score: scale score directly by the number of outs available in the deck
                    bestScore = Math.max(bestScore, totalOuts * 5);
                } else if (d == 2) {
                    // One-gap run connector (e.g. 7-9 needs 8)
                    com.rummy.engine.model.Rank gapRank = (minOrder >= 1 && minOrder <= 12)
                            ? com.rummy.engine.model.Rank.values()[minOrder]
                            : null;
                    int deadGap = gapRank != null ? countKnownCopies(card.suit(), gapRank, pool, discardHistory) : activeDeckCount;

                    int outsGap = Math.max(0, activeDeckCount - deadGap);

                    if (outsGap == 0) {
                        continue;
                    }
                    // EV Score: scale score directly by the number of outs available in the deck
                    bestScore = Math.max(bestScore, outsGap * 5);
                }
            } else if (card.rank() == oc.rank()) {
                // Same rank pair connector (different suit)
                // In Rummy, sets require different suits. Count outs for the remaining 2 suits.
                int outs = 0;
                for (com.rummy.engine.model.Suit s : com.rummy.engine.model.Suit.values()) {
                    if (s != card.suit() && s != oc.suit()) {
                        int dead = countKnownCopies(s, card.rank(), pool, discardHistory);
                        outs += Math.max(0, activeDeckCount - dead);
                    }
                }
                if (outs == 0) {
                    continue;
                }
                // Sets are slightly less flexible than runs, use a 3.5 multiplier (e.g. 4 outs = 14 pts)
                int score = (int)(outs * 3.5);
                bestScore = Math.max(bestScore, score);
            }
        }

        return bestScore;
    }

    public static int calculateOpponentDangerScore(CardInstance candidate, Set<Card> opponentPicks) {
        if (candidate == null || opponentPicks == null || opponentPicks.isEmpty() || candidate.isPrintedJoker()) {
            return 0;
        }
        Card card = candidate.getCard();
        int maxDanger = 0;

        for (Card pick : opponentPicks) {
            if (pick == null || pick.isPrintedJoker()) {
                continue;
            }
            if (card.suit() == pick.suit()) {
                int dLow = Math.abs(card.rank().getOrder() - pick.rank().getOrder());
                int dHigh = Math.abs(card.rank().getAceHighOrder() - pick.rank().getAceHighOrder());
                int d = Math.min(dLow, dHigh);

                if (d == 1) {
                    maxDanger = Math.max(maxDanger, 40);
                } else if (d == 2) {
                    maxDanger = Math.max(maxDanger, 20);
                }
            } else if (card.rank() == pick.rank()) {
                maxDanger = Math.max(maxDanger, 25);
            }
        }

        return maxDanger;
    }

    /**
     * Evaluates defensive danger with Downstream Next-Player Targeting.
     * In Rummy, discards go directly to the next player clockwise.
     * Cards that feed the immediate next downstream player carry 2.5x higher danger.
     */
    public static int calculateOpponentDangerScore(
            CardInstance candidate,
            Map<String, Set<Card>> opponentPicksByPlayer,
            String nextPlayerId
    ) {
        if (candidate == null || opponentPicksByPlayer == null || opponentPicksByPlayer.isEmpty() || candidate.isPrintedJoker()) {
            return 0;
        }
        Card card = candidate.getCard();
        int maxDanger = 0;

        for (Map.Entry<String, Set<Card>> entry : opponentPicksByPlayer.entrySet()) {
            String oppId = entry.getKey();
            Set<Card> picks = entry.getValue();
            if (picks == null || picks.isEmpty()) {
                continue;
            }

            boolean isNextDownstream = nextPlayerId != null && nextPlayerId.equals(oppId);
            double multiplier = isNextDownstream ? 2.5 : 1.0;

            for (Card pick : picks) {
                if (pick == null || pick.isPrintedJoker()) {
                    continue;
                }
                if (card.suit() == pick.suit()) {
                    int dLow = Math.abs(card.rank().getOrder() - pick.rank().getOrder());
                    int dHigh = Math.abs(card.rank().getAceHighOrder() - pick.rank().getAceHighOrder());
                    int d = Math.min(dLow, dHigh);

                    if (d == 1) {
                        maxDanger = Math.max(maxDanger, (int) (40 * multiplier));
                    } else if (d == 2) {
                        maxDanger = Math.max(maxDanger, (int) (20 * multiplier));
                    }
                } else if (card.rank() == pick.rank()) {
                    maxDanger = Math.max(maxDanger, (int) (25 * multiplier));
                }
            }
        }

        return maxDanger;
    }

    /**
     * Whether a middle drop now costs less than playing on. Uses only the bot's own hand and public table
     * facts (its turn count in the deal and how many opponents are still in it).
     *
     * Drops are kept for hands that are as good as lost: neither a pure sequence nor a joker by turn 6 against
     * four or more opponents, or by turn 8 short-handed. In bot self-play no such hand went on to win the deal.
     * A single joker is enough to keep playing.
     *
     * @param turnsPlayed     the bot's turns in this deal, counting the current one
     * @param activeOpponents opponents still playing this deal
     */
    public static boolean shouldTakeMiddleDrop(
            List<CardInstance> hand,
            Card cutJoker,
            RummyRules rules,
            int cumulativeScore,
            int eliminationThreshold,
            int turnsPlayed,
            int activeOpponents
    ) {
        if (hand == null || rules == null) {
            return false;
        }
        int dropPenalty = rules.getMiddleDropPenalty();
        if (dropPenalty >= rules.getMaximumPenalty()) {
            return false;
        }
        if (eliminationThreshold > 0 && cumulativeScore + dropPenalty >= eliminationThreshold) {
            return false;
        }
        if (hasPureSequence(hand, cutJoker)) {
            return false;
        }
        if (hand.stream().anyMatch(c -> c.isPrintedJoker() || c.getCard().isWildJoker(cutJoker))) {
            return false;
        }
        if (rules.scoreLosingHand(hand, cutJoker) <= dropPenalty) {
            return false;
        }

        int firstDropTurn = activeOpponents >= 4 ? 6 : 8;
        return turnsPlayed >= firstDropTurn;
    }
}
