package com.rummy.engine.model;

/**
 * Sub-states within a single player turn.
 */
public enum TurnPhase {
    /**
     * Player must draw one card from either the closed deck or top of the open discard pile.
     * Hand size is 13 cards.
     */
    AWAITING_DRAW,

    /**
     * Card has been drawn. Hand size is 14 cards.
     * Player must discard 1 card (or place 1 card in finish slot to declare).
     */
    AWAITING_DISCARD,

    /**
     * Player placed a card in finish slot and is submitting group arrangements for validation.
     */
    AWAITING_DECLARE_VALIDATION,

    /**
     * Turn action has completed; transitioning to next eligible player.
     */
    COMPLETED
}
