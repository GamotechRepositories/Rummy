package com.rummy.engine.model;

/**
 * Lifecycle status of an individual player in a table.
 */
public enum PlayerStatus {
    /**
     * Joined table and waiting for game start.
     */
    WAITING,

    /**
     * Seated and ready for game start.
     */
    READY,

    /**
     * Actively playing in the current deal.
     */
    ACTIVE,

    /**
     * Temporarily disconnected but within the reconnection grace period.
     */
    DISCONNECTED,

    /**
     * Voluntarily or automatically dropped out of the current hand.
     */
    DROPPED,

    /**
     * Declared hand; awaiting declaration verification by server.
     */
    DECLARED,

    /**
     * Eliminated (e.g. cumulative score >= 101/201 in Pool Rummy).
     */
    ELIMINATED
}
