package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Production ruleset implementation for 500 Rummy (RUMMY_500).
 * Features: Melded cards give positive points, remaining unmelded cards in hand give negative points. Race to 500 points.
 */
public final class Rummy500Rules implements RummyRules {

    public static final String RULESET_ID = "RUMMY_500";
    public static final String RULESET_VERSION = "1.0.0";
    public static final int TARGET_SCORE = 500;

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
        return 7; // 7 cards for 3-4 players, 13 for 2 players
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
        return 2;
    }

    @Override
    public int getPrintedJokersPerDeck() {
        return 1;
    }

    @Override
    public int getMinimumSequences() {
        return 0;
    }

    @Override
    public int getRequiredPureSequences() {
        return 0;
    }

    @Override
    public int getFirstDropPenalty() {
        return 20;
    }

    @Override
    public int getMiddleDropPenalty() {
        return 40;
    }

    @Override
    public int getAutoDropPenalty() {
        return 40;
    }

    @Override
    public int getWrongDeclarationPenalty() {
        return 80;
    }

    @Override
    public int getMaximumPenalty() {
        return 100;
    }

    @Override
    public DeclarationResult validateDeclaration(List<CardGroup> groups, Card cutJoker) {
        if (groups == null || groups.isEmpty()) {
            return DeclarationResult.invalid(0, 0, 0, 0, List.of("No card groups"), Collections.emptyList());
        }

        int pure = 0;
        int impure = 0;
        int sets = 0;
        int invalid = 0;
        List<String> errors = new ArrayList<>();
        List<DeclarationResult.GroupClassification> classifications = new ArrayList<>();

        for (CardGroup group : groups) {
            GroupType type = DeclarationValidator.classifyGroup(group, cutJoker);
            classifications.add(new DeclarationResult.GroupClassification(group, type));
            switch (type) {
                case PURE_SEQUENCE -> pure++;
                case IMPURE_SEQUENCE -> impure++;
                case SET -> sets++;
                case INVALID -> {
                    invalid++;
                    errors.add("Contains invalid group in declaration");
                }
            }
        }

        if (invalid == 0) {
            return DeclarationResult.valid(pure, impure, sets, classifications);
        }
        return DeclarationResult.invalid(pure, impure, sets, invalid, errors, classifications);
    }

    @Override
    public int calculateLosingScore(List<CardGroup> groups, Card cutJoker) {
        return calculateUnmeldedPoints(groups, cutJoker);
    }

    public int calculateMeldedPoints(List<CardGroup> groups, Card cutJoker) {
        int score = 0;
        for (CardGroup group : groups) {
            if (DeclarationValidator.classifyGroup(group, cutJoker) != GroupType.INVALID) {
                for (CardInstance card : group.getCards()) {
                    score += getCardPoints(card.getCard());
                }
            }
        }
        return score;
    }

    public int calculateUnmeldedPoints(List<CardGroup> groups, Card cutJoker) {
        int penalty = 0;
        for (CardGroup group : groups) {
            if (DeclarationValidator.classifyGroup(group, cutJoker) == GroupType.INVALID) {
                for (CardInstance card : group.getCards()) {
                    penalty += getCardPoints(card.getCard());
                }
            }
        }
        return penalty;
    }

    public int getCardPoints(Card card) {
        if (card.isPrintedJoker()) return 0;
        return switch (card.rank()) {
            case ACE -> 15;
            case TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE -> 5;
            case TEN, JACK, QUEEN, KING -> 10;
        };
    }
}
