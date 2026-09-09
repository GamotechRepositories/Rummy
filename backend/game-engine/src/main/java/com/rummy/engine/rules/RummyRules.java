package com.rummy.engine.rules;

import com.rummy.engine.model.Card;
import com.rummy.engine.model.PlayerState;

import java.util.List;

/**
 * Common interface for all Rummy game variants supported by the platform.
 */
public interface RummyRules {

    String getRulesetId();

    String getRulesetVersion();

    int getCardsPerPlayer();

    int getMinPlayers();

    int getMaxPlayers();

    int getDeckCount();

    int getPrintedJokersPerDeck();

    int getMinimumSequences();

    int getRequiredPureSequences();

    int getFirstDropPenalty();

    int getMiddleDropPenalty();

    int getAutoDropPenalty();

    int getWrongDeclarationPenalty();

    int getMaximumPenalty();

    /**
     * Validate a player's declared hand.
     */
    DeclarationResult validateDeclaration(List<CardGroup> groups, Card cutJoker);

    /**
     * Calculate losing penalty points for an undeclared hand.
     */
    int calculateLosingScore(List<CardGroup> groups, Card cutJoker);
}
