package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Production ruleset implementation for Canasta (CANASTA).
 * Features: 2 decks + 4 jokers, Twos are wild, 7-card canastas (Natural = 500 bonus, Mixed = 300 bonus), target 5,000 points.
 */
public final class CanastaRules implements RummyRules {

    public static final String RULESET_ID = "CANASTA";
    public static final String RULESET_VERSION = "1.0.0";

    public static final int TARGET_SCORE = 5000;
    public static final int NATURAL_CANASTA_BONUS = 500;
    public static final int MIXED_CANASTA_BONUS = 300;

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
        return 11;
    }

    @Override
    public int getMinPlayers() {
        return 2;
    }

    @Override
    public int getMaxPlayers() {
        return 4;
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
        return 0;
    }

    @Override
    public int getRequiredPureSequences() {
        return 0;
    }

    @Override
    public int getFirstDropPenalty() {
        return 50;
    }

    @Override
    public int getMiddleDropPenalty() {
        return 100;
    }

    @Override
    public int getAutoDropPenalty() {
        return 100;
    }

    @Override
    public int getWrongDeclarationPenalty() {
        return 200;
    }

    @Override
    public int getMaximumPenalty() {
        return 500;
    }

    @Override
    public DeclarationResult validateDeclaration(List<CardGroup> groups, Card cutJoker) {
        if (groups == null || groups.isEmpty()) {
            return DeclarationResult.invalid(0, 0, 0, 0, List.of("No card groups"), Collections.emptyList());
        }

        boolean hasCanasta = false;
        List<DeclarationResult.GroupClassification> classifications = new ArrayList<>();
        int pure = 0;
        int impure = 0;
        int sets = 0;
        int invalid = 0;

        for (CardGroup group : groups) {
            if (group.size() >= 7) {
                hasCanasta = true;
            }
            GroupType type = DeclarationValidator.classifyGroup(group, cutJoker);
            classifications.add(new DeclarationResult.GroupClassification(group, type));
            switch (type) {
                case PURE_SEQUENCE -> pure++;
                case IMPURE_SEQUENCE -> impure++;
                case SET -> sets++;
                case INVALID -> invalid++;
            }
        }

        if (!hasCanasta) {
            return DeclarationResult.invalid(
                    pure, impure, sets, invalid,
                    List.of("Canasta declaration requires at least one 7-card Canasta meld"),
                    classifications
            );
        }

        return DeclarationResult.valid(pure, impure, sets, classifications);
    }

    @Override
    public int calculateLosingScore(List<CardGroup> groups, Card cutJoker) {
        int penalty = 0;
        for (CardGroup group : groups) {
            for (CardInstance card : group.getCards()) {
                penalty += getCanastaCardPoint(card);
            }
        }
        return Math.min(penalty, getMaximumPenalty());
    }

    public int getCanastaCardPoint(CardInstance card) {
        if (card.isPrintedJoker()) return 50;
        return switch (card.getCard().rank()) {
            case TWO, ACE -> 20;
            case EIGHT, NINE, TEN, JACK, QUEEN, KING -> 10;
            case FOUR, FIVE, SIX, SEVEN -> 5;
            case THREE -> 5;
        };
    }
}
