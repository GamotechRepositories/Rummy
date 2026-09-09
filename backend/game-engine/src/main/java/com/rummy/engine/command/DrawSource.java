package com.rummy.engine.command;

/**
 * Source from which a player draws a card on their turn.
 */
public enum DrawSource {
    /**
     * Draw from the hidden closed deck.
     */
    CLOSED_DECK,

    /**
     * Draw the top face-up card from the open discard pile.
     */
    DISCARD_PILE
}
