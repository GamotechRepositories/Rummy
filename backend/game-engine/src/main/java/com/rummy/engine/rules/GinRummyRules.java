package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Production ruleset implementation for Gin Rummy (GIN_RUMMY).
 * Features: 2 players, 10 cards, 1 standard 52-card deck (no jokers), deadwood scoring, Knock (<=10) and Gin (0 deadwood).
 */
public final class GinRummyRules implements RummyRules {

    public static final String RULESET_ID = "GIN_RUMMY";
    public static final String RULESET_VERSION = "1.0.0";

    public static final int KNOCK_DEADWOOD_THRESHOLD = 10;
    public static final int GIN_BONUS = 25;
    public static final int UNDERCUT_BONUS = 25;

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
        return 10;
    }

    @Override
    public int getMinPlayers() {
        return 2;
    }

    @Override
    public int getMaxPlayers() {
        return 2;
    }

    @Override
    public int getDeckCount() {
        return 1;
    }

    @Override
    public int getPrintedJokersPerDeck() {
        return 0;
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
        return 10;
    }

    @Override
    public int getMiddleDropPenalty() {
        return 20;
    }

    @Override
    public int getAutoDropPenalty() {
        return 20;
    }

    @Override
    public int getWrongDeclarationPenalty() {
        return 50;
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
        List<DeclarationResult.GroupClassification> classifications = new ArrayList<>();

        for (CardGroup group : groups) {
            GroupType type = DeclarationValidator.classifyGroup(group, null);
            classifications.add(new DeclarationResult.GroupClassification(group, type));
            switch (type) {
                case PURE_SEQUENCE -> pure++;
                case IMPURE_SEQUENCE -> impure++;
                case SET -> sets++;
                case INVALID -> invalid++;
            }
        }

        int deadwood = calculateDeadwood(groups);
        if (deadwood <= KNOCK_DEADWOOD_THRESHOLD) {
            return DeclarationResult.valid(pure, impure, sets, classifications);
        }
        return DeclarationResult.invalid(
                pure, impure, sets, invalid,
                List.of("Deadwood score (" + deadwood + ") exceeds knock threshold of " + KNOCK_DEADWOOD_THRESHOLD),
                classifications
        );
    }

    @Override
    public int calculateLosingScore(List<CardGroup> groups, Card cutJoker) {
        return calculateDeadwood(groups);
    }

    /**
     * Calculates total deadwood (point value of cards not in valid sets or runs).
     */
    public int calculateDeadwood(List<CardGroup> groups) {
        int deadwood = 0;
        for (CardGroup group : groups) {
            GroupType type = DeclarationValidator.classifyGroup(group, null);
            if (type == GroupType.INVALID) {
                for (CardInstance card : group.getCards()) {
                    deadwood += getCardPointValue(card.getCard());
                }
            }
        }
        return deadwood;
    }

    public int getCardPointValue(Card card) {
        if (card.isPrintedJoker()) return 0;
        return switch (card.rank()) {
            case ACE -> 1;
            case TWO -> 2;
            case THREE -> 3;
            case FOUR -> 4;
            case FIVE -> 5;
            case SIX -> 6;
            case SEVEN -> 7;
            case EIGHT -> 8;
            case NINE -> 9;
            case TEN, JACK, QUEEN, KING -> 10;
        };
    }
}
