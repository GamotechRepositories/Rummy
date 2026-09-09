package com.rummy.engine.model;

/**
 * Table and game lifecycle states.
 */
public enum GameStatus {
    /**
     * Waiting for minimum required players to join and ready up.
     */
    WAITING_FOR_PLAYERS,

    /**
     * Cards are being shuffled and dealt to seated players.
     */
    DEALING,

    /**
     * Game is actively running; players are taking turns.
     */
    IN_PROGRESS,

    /**
     * A player has triggered a declaration attempt; server is validating.
     */
    DECLARING,

    /**
     * Hand or game has concluded; scores and payouts are being calculated.
     */
    SETTLING,

    /**
     * Game has completed and all outcomes are finalized.
     */
    COMPLETED,

    /**
     * Game was cancelled or aborted due to timeout, failure, or admin action.
     */
    ABORTED
}
