package com.rummy.engine.rules;

import com.rummy.engine.model.Card;

import java.util.List;

/**
 * Production ruleset implementation for Indian 13-Card Points Rummy (POINTS_13).
 */
public final class PointsRummyRules implements RummyRules {

    public static final String RULESET_ID = "POINTS_13";
    public static final String RULESET_VERSION = "1.0.0";

    private final int firstDropPenalty;
    private final int middleDropPenalty;
    private final int autoDropPenalty;
    private final int wrongDeclarationPenalty;
    private final int maximumPenalty;

    public PointsRummyRules() {
        this(20, 40, 40, 80, 80);
    }

    public PointsRummyRules(int firstDropPenalty,
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
        return 1;
    }

    @Override
    public int getMinimumSequences() {
        return 2;
    }

    @Override
    public int getRequiredPureSequences() {
        return 1;
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

    @Override
    public DeclarationResult validateDeclaration(List<CardGroup> groups, Card cutJoker) {
        return DeclarationValidator.validate(groups, cutJoker);
    }

    @Override
    public int calculateLosingScore(List<CardGroup> groups, Card cutJoker) {
        return ScoreCalculator.calculateHandPoints(groups, cutJoker, maximumPenalty);
    }
}
