package com.rummy.engine.rules;

import com.rummy.engine.model.Card;

import java.util.List;

/**
 * Section R17: 13-Card Pool 101 Rummy (POOL_101).
 * Players accumulate points across deals until reaching 101 (elimination).
 * First drop = 20, Middle drop = 40, Wrong declare / Full count = 80.
 */
public final class Pool101Rules implements RummyRules {

    public static final String RULESET_ID = "POOL_101";
    public static final String RULESET_VERSION = "1.0.0";
    public static final int ELIMINATION_THRESHOLD = 101;

    private final int firstDropPenalty;
    private final int middleDropPenalty;
    private final int autoDropPenalty;
    private final int wrongDeclarationPenalty;
    private final int maximumPenalty;

    public Pool101Rules() {
        this(20, 40, 40, 80, 80);
    }

    public Pool101Rules(int firstDropPenalty,
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

    public int getEliminationThreshold() {
        return ELIMINATION_THRESHOLD;
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
