package com.rummy.engine.rules;

/**
 * Classification of a grouped subset of cards in Rummy.
 */
public enum GroupType {
    /**
     * 3 or more consecutive cards of the exact same suit with zero joker substitutions.
     */
    PURE_SEQUENCE,

    /**
     * 3 or more consecutive cards of the same suit with 1 or more joker substitutions.
     */
    IMPURE_SEQUENCE,

    /**
     * 3 or 4 cards of the exact same rank from different suits (with optional jokers).
     */
    SET,

    /**
     * Cards do not form any valid sequence or set.
     */
    INVALID
}
