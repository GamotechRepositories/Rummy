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

    /**
     * Evaluates a hand (after draw: 14 cards for 13-card rummy, 22 cards for 21-card rummy)
     * to check if a valid declaration can be made.
     * Searches all candidate finish cards and partitions the remaining cards.
     */
    public static Optional<EvaluationResult> findWinningDeclaration(List<CardInstance> hand, Card cutJoker, RummyRules rules) {
        int targetCards = rules != null ? rules.getCardsPerPlayer() : 13;
        int expectedHand = targetCards + 1;
        if (hand == null || hand.size() != expectedHand) {
            return Optional.empty();
        }

        // Try each card as the finish card
        for (int i = 0; i < hand.size(); i++) {
            CardInstance finishCandidate = hand.get(i);
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

        // Recursive backtracking to find a combination of non-overlapping candidate melds that covers all cards
        List<CardGroup> selected = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();

        if (backtrackFindPartition(candidateMelds, 0, cards.size(), selected, usedIds, cutJoker, rules, new int[]{0})) {
            return Optional.of(new ArrayList<>(selected));
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
        // Greedily find best non-overlapping meld subset that maximizes card count and points
        List<CardGroup> bestMelds = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();

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

        List<CardInstance> deadwood = new ArrayList<>();
        int deadwoodPoints = 0;
        for (CardInstance c : hand) {
            if (!usedIds.contains(c.getInstanceId())) {
                deadwood.add(c);
                deadwoodPoints += c.getCard().points(cutJoker);
            }
        }

        return new EvaluationResult(false, null, bestMelds, deadwood, deadwoodPoints);
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
     */
    public static List<CardGroup> findAllCandidateMelds(List<CardInstance> cards, Card cutJoker) {
        List<CardGroup> melds = new ArrayList<>();
        int n = cards.size();

        // Check all 3-card combinations
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    CardGroup group = CardGroup.of(cards.get(i), cards.get(j), cards.get(k));
                    GroupType type = classifyCandidateGroup(group, cutJoker);
                    if (type != GroupType.INVALID) {
                        melds.add(group);
                    }
                }
            }
        }

        // Check all 4-card combinations
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    for (int m = k + 1; m < n; m++) {
                        CardGroup group = CardGroup.of(cards.get(i), cards.get(j), cards.get(k), cards.get(m));
                        GroupType type = classifyCandidateGroup(group, cutJoker);
                        if (type != GroupType.INVALID) {
                            melds.add(group);
                        }
                    }
                }
            }
        }

        // Prioritize pure sequences first, then impure sequences, then sets
        melds.sort((g1, g2) -> {
            GroupType t1 = classifyCandidateGroup(g1, cutJoker);
            GroupType t2 = classifyCandidateGroup(g2, cutJoker);
            int p1 = (t1 == GroupType.PURE_SEQUENCE ? 3 : (t1 == GroupType.IMPURE_SEQUENCE ? 2 : 1));
            int p2 = (t2 == GroupType.PURE_SEQUENCE ? 3 : (t2 == GroupType.IMPURE_SEQUENCE ? 2 : 1));
            return Integer.compare(p2, p1);
        });

        return melds;
    }
}
