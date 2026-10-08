package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.CardInstance;

import java.util.List;

/**
 * Section R20: 13-Card Deals Rummy (DEALS_RUMMY).
 * Played for a fixed number of deals (typically 2, 3, or 6 deals).
 * Every player starts with equal chips; at the end of each deal, the winner takes chips from losers.
 */
public final class DealsRummyRules implements RummyRules {

    public static final String RULESET_ID = "DEALS_RUMMY";
    public static final String RULESET_VERSION = "1.0.0";

    private final String rulesetId;
    private final int totalDeals;
    private final int firstDropPenalty;
    private final int middleDropPenalty;
    private final int autoDropPenalty;
    private final int wrongDeclarationPenalty;
    private final int maximumPenalty;

    public DealsRummyRules() {
        this(RULESET_ID, 2, 20, 40, 40, 80, 80);
    }

    public DealsRummyRules(int totalDeals) {
        this("DEALS_" + totalDeals, totalDeals, 20, 40, 40, 80, 80);
    }

    public DealsRummyRules(String rulesetId, int totalDeals) {
        this(rulesetId, totalDeals, 20, 40, 40, 80, 80);
    }

    public DealsRummyRules(String rulesetId,
                           int totalDeals,
                           int firstDropPenalty,
                           int middleDropPenalty,
                           int autoDropPenalty,
                           int wrongDeclarationPenalty,
                           int maximumPenalty) {
        this.rulesetId = rulesetId != null ? rulesetId : RULESET_ID;
        this.totalDeals = totalDeals;
        this.firstDropPenalty = firstDropPenalty;
        this.middleDropPenalty = middleDropPenalty;
        this.autoDropPenalty = autoDropPenalty;
        this.wrongDeclarationPenalty = wrongDeclarationPenalty;
        this.maximumPenalty = maximumPenalty;
    }

    public DealsRummyRules(int totalDeals,
                           int firstDropPenalty,
                           int middleDropPenalty,
                           int autoDropPenalty,
                           int wrongDeclarationPenalty,
                           int maximumPenalty) {
        this(RULESET_ID, totalDeals, firstDropPenalty, middleDropPenalty, autoDropPenalty, wrongDeclarationPenalty, maximumPenalty);
    }

    @Override
    public String getRulesetId() {
        return rulesetId;
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
    public int getTotalDeals() {
        return totalDeals;
    }

    @Override
    public boolean isDealsGame() {
        return true;
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

    @Override
    public int scoreLosingHand(List<CardInstance> hand, Card cutJoker) {
        return maximumPenalty;
    }
}
