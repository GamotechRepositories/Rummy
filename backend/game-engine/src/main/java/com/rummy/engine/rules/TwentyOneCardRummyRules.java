package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Production ruleset implementation for 21-Card Indian Rummy (RUMMY_21).
 * Features: 3 decks (156 cards + jokers), 21 cards per player, minimum 3 pure sequences (or Tunnelas).
 */
public final class TwentyOneCardRummyRules implements RummyRules {

    public static final String RULESET_ID = "RUMMY_21";
    public static final String RULESET_VERSION = "1.0.0";

    private final int firstDropPenalty;
    private final int middleDropPenalty;
    private final int autoDropPenalty;
    private final int wrongDeclarationPenalty;
    private final int maximumPenalty;

    public TwentyOneCardRummyRules() {
        this(30, 60, 60, 120, 120);
    }

    public TwentyOneCardRummyRules(int firstDropPenalty,
                                  int middleDropPenalty,
                                  int autoDropPenalty,
                                  int wrongDeclarationPenalty,
                                  int maximumPenalty) {
        this.firstDropPenalty = firstDropPenalty;
        this.middleDropPenalty = middleDropPenalty;
        this.autoDropPenalty = autoDropPenalty;
        this.wrongDeclarationPenalty = wrongDeclarationPenalty;
        this.maximumPenalty = maximumPenalty;
    }

    @Override
    public String getRulesetId() {
        return RULESET_ID;
    }

    @Override
    public String getRulesetVersion() {
        return RULESET_VERSION;
    }

    @Override
    public int getCardsPerPlayer() {
        return 21;
    }

    @Override
    public int getMinPlayers() {
        return 2;
    }

    @Override
    public int getMaxPlayers() {
        return 6;
    }

    @Override
    public int getDeckCount() {
        return 3;
    }

    @Override
    public int getPrintedJokersPerDeck() {
        return 2;
    }

    @Override
    public int getMinimumSequences() {
        return 3;
    }

    @Override
    public int getRequiredPureSequences() {
        return 3;
    }

    @Override
    public int getFirstDropPenalty() {
        return firstDropPenalty;
    }

    @Override
    public int getMiddleDropPenalty() {
        return middleDropPenalty;
    }

    @Override
    public int getAutoDropPenalty() {
        return autoDropPenalty;
    }

    @Override
    public int getWrongDeclarationPenalty() {
        return wrongDeclarationPenalty;
    }

    @Override
    public int getMaximumPenalty() {
        return maximumPenalty;
    }

    /**
     * In 21-Card Rummy, a Tunnela (3 identical cards of the exact same rank and suit
     * from different decks, or 3 printed jokers) is considered a valid Pure Sequence.
     */
    public static boolean isTunnela(CardGroup group) {
        if (group == null || group.size() != 3) {
            return false;
        }
        List<CardInstance> cards = group.getCards();
        boolean allPrintedJokers = cards.stream().allMatch(CardInstance::isPrintedJoker);
        if (allPrintedJokers) {
            return true;
        }
        if (cards.stream().anyMatch(CardInstance::isPrintedJoker)) {
            return false;
        }
        Card first = cards.get(0).getCard();
        return cards.stream().allMatch(ci ->
                ci.getCard().suit() == first.suit() && ci.getCard().rank() == first.rank()
        );
    }

    public static GroupType classifyGroup(CardGroup group, Card cutJoker) {
        if (isTunnela(group)) {
            return GroupType.PURE_SEQUENCE;
        }
        return DeclarationValidator.classifyGroup(group, cutJoker);
    }

    @Override
    public DeclarationResult validateDeclaration(List<CardGroup> groups, Card cutJoker) {
        if (groups == null || groups.isEmpty()) {
            return DeclarationResult.invalid(0, 0, 0, 0,
                    List.of("Declaration contains no card groups"), Collections.emptyList());
        }

        int pureCount = 0;
        int impureCount = 0;
        int setsCount = 0;
        int invalidCount = 0;
        int totalCards = 0;
        List<String> errors = new ArrayList<>();
        List<DeclarationResult.GroupClassification> classifications = new ArrayList<>();

        for (int i = 0; i < groups.size(); i++) {
            CardGroup group = groups.get(i);
            totalCards += group.size();
            GroupType type = classifyGroup(group, cutJoker);
            classifications.add(new DeclarationResult.GroupClassification(group, type));

            switch (type) {
                case PURE_SEQUENCE -> pureCount++;
                case IMPURE_SEQUENCE -> impureCount++;
                case SET -> setsCount++;
                case INVALID -> {
                    invalidCount++;
                    errors.add("Group " + (i + 1) + " is invalid");
                }
            }
        }

        if (totalCards != 21) {
            errors.add("21-Card Rummy requires exactly 21 cards arranged in groups (found " + totalCards + ")");
        }

        if (pureCount < 3) {
            errors.add("21-Card Rummy requires at least 3 pure sequences or tunnelas (found " + pureCount + ")");
        }

        if (errors.isEmpty() && invalidCount == 0) {
            return DeclarationResult.valid(pureCount, impureCount, setsCount, classifications);
        } else {
            return DeclarationResult.invalid(pureCount, impureCount, setsCount, invalidCount, errors, classifications);
        }
    }

    @Override
    public int calculateLosingScore(List<CardGroup> groups, Card cutJoker) {
        if (groups == null || groups.isEmpty()) {
            return 0;
        }

        int pureCount = 0;
        int impureCount = 0;
        List<CardGroup> pureSequences = new ArrayList<>();
        List<CardGroup> validMelds = new ArrayList<>();
        List<CardGroup> invalidGroups = new ArrayList<>();

        for (CardGroup group : groups) {
            GroupType type = classifyGroup(group, cutJoker);
            switch (type) {
                case PURE_SEQUENCE -> {
                    pureCount++;
                    pureSequences.add(group);
                    validMelds.add(group);
                }
                case IMPURE_SEQUENCE -> {
                    impureCount++;
                    validMelds.add(group);
                }
                case SET -> validMelds.add(group);
                case INVALID -> invalidGroups.add(group);
            }
        }

        // 21-Card Rummy Scoring Rule:
        // CASE 1: No pure sequences -> all cards count (capped at maximumPenalty)
        if (pureCount == 0) {
            int total = 0;
            for (CardGroup group : groups) {
                total += group.calculatePoints(cutJoker);
            }
            return Math.min(total, maximumPenalty);
        }

        // CASE 2: Less than 3 pure sequences (e.g. 1 or 2 pure sequences/tunnelas)
        // ONLY pure sequences are exempt (0 points);
        // All other cards (even if in valid sets or impure sequences) count towards penalty!
        if (pureCount < 3) {
            int total = 0;
            for (CardGroup group : groups) {
                if (!pureSequences.contains(group)) {
                    total += group.calculatePoints(cutJoker);
                }
            }
            return Math.min(total, maximumPenalty);
        }

        // CASE 3: At least 3 pure sequences
        // All valid pure sequences, impure sequences, and sets are exempt (0 points);
        // Only cards in invalid groups count towards penalty.
        int total = 0;
        for (CardGroup group : invalidGroups) {
            total += group.calculatePoints(cutJoker);
        }
        return Math.min(total, maximumPenalty);
    }
}
