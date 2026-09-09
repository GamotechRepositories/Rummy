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
     * Evaluates a 14-card hand (after draw) to check if a valid declaration can be made.
     * Searches all candidate finish cards and partitions the remaining 13 cards.
     */
    public static Optional<EvaluationResult> findWinningDeclaration(List<CardInstance> hand14, Card cutJoker, RummyRules rules) {
        if (hand14 == null || hand14.size() != 14) {
            return Optional.empty();
        }

        // Try each card as the finish card
        for (int i = 0; i < hand14.size(); i++) {
            CardInstance finishCandidate = hand14.get(i);
            List<CardInstance> remaining13 = new ArrayList<>(hand14);
            remaining13.remove(i);

            Optional<List<CardGroup>> winningGroups = searchValid13CardPartition(remaining13, cutJoker, rules);
            if (winningGroups.isPresent()) {
                return Optional.of(new EvaluationResult(true, finishCandidate, winningGroups.get(), Collections.emptyList(), 0));
            }
        }

        return Optional.empty();
    }

    /**
     * Searches for a valid partition of 13 cards that satisfies the ruleset declaration requirements.
     */
    public static Optional<List<CardGroup>> searchValid13CardPartition(List<CardInstance> cards13, Card cutJoker, RummyRules rules) {
        List<CardGroup> candidateMelds = findAllCandidateMelds(cards13, cutJoker);

        // Recursive backtracking to find a combination of non-overlapping candidate melds that covers all 13 cards
        List<CardGroup> selected = new ArrayList<>();
        Set<String> usedIds = new HashSet<>();

        if (backtrackFindPartition(candidateMelds, 0, cards13.size(), selected, usedIds, cutJoker, rules)) {
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
                                                  RummyRules rules) {
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

            if (backtrackFindPartition(candidates, i + 1, targetCardCount, selected, usedIds, cutJoker, rules)) {
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
                GroupType type = DeclarationValidator.classifyGroup(trio, cutJoker);
                if (type != GroupType.INVALID) {
                    return true;
                }
            }
        }

        return false;
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
                    GroupType type = DeclarationValidator.classifyGroup(group, cutJoker);
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
                        GroupType type = DeclarationValidator.classifyGroup(group, cutJoker);
                        if (type != GroupType.INVALID) {
                            melds.add(group);
                        }
                    }
                }
            }
        }

        // Prioritize pure sequences first, then impure sequences, then sets
        melds.sort((g1, g2) -> {
            GroupType t1 = DeclarationValidator.classifyGroup(g1, cutJoker);
            GroupType t2 = DeclarationValidator.classifyGroup(g2, cutJoker);
            int p1 = (t1 == GroupType.PURE_SEQUENCE ? 3 : (t1 == GroupType.IMPURE_SEQUENCE ? 2 : 1));
            int p2 = (t2 == GroupType.PURE_SEQUENCE ? 3 : (t2 == GroupType.IMPURE_SEQUENCE ? 2 : 1));
            return Integer.compare(p2, p1);
        });

        return melds;
    }
}
