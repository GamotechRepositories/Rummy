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

    /**
     * Elimination threshold score (e.g. 101 for Pool 101, 201 for Pool 201).
     * 0 indicates non-elimination variant (e.g. Points Rummy).
     */
    default int getEliminationThreshold() {
        return 0;
    }

    /**
     * Whether this ruleset is a multi-deal elimination game.
     */
    default boolean isEliminationGame() {
        return getEliminationThreshold() > 0;
    }

    /**
     * Maximum cumulative score of the highest active player allowing an eliminated player to re-join.
     * Pool 101: 79. Pool 201: 174. 0 or negative indicates rejoin not allowed.
     */
    default int getRejoinMaxActiveThreshold() {
        return 0;
    }
}
