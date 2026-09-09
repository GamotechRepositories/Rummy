package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Production ruleset implementation for Kalooki / Kaluki Rummy (KALOOKI).
 * Features: Contract-based melds with 2 decks and 4 jokers. Jokers in hand score 50 penalty points.
 */
public final class KalookiRules implements RummyRules {

    public static final String RULESET_ID = "KALOOKI";
    public static final String RULESET_VERSION = "1.0.0";

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
        return 13;
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
        return 2;
    }

    @Override
    public int getMinimumSequences() {
        return 1;
    }

    @Override
    public int getRequiredPureSequences() {
        return 0;
    }

    @Override
    public int getFirstDropPenalty() {
        return 25;
    }

    @Override
    public int getMiddleDropPenalty() {
        return 50;
    }

    @Override
    public int getAutoDropPenalty() {
        return 50;
    }

    @Override
    public int getWrongDeclarationPenalty() {
        return 100;
    }

    @Override
    public int getMaximumPenalty() {
        return 150;
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
                    errors.add("Hand contains invalid card group");
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
        int penalty = 0;
        for (CardGroup group : groups) {
            if (DeclarationValidator.classifyGroup(group, cutJoker) == GroupType.INVALID) {
                for (CardInstance card : group.getCards()) {
                    if (card.isPrintedJoker()) {
                        penalty += 50; // Joker penalty in Kalooki
                    } else {
                        penalty += switch (card.getCard().rank()) {
                            case ACE -> 15;
                            case TEN, JACK, QUEEN, KING -> 10;
                            case TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE -> card.getCard().rank().getDefaultPoints();
                        };
                    }
                }
            }
        }
        return Math.min(penalty, getMaximumPenalty());
    }
}
