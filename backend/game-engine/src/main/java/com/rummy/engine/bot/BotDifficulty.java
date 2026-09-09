package com.rummy.engine.bot;

/**
 * AI Bot player difficulty levels.
 */
public enum BotDifficulty {
    /**
     * Basic legal moves, simple deadwood discard, small randomness.
     */
    EASY,

    /**
     * Hand evaluation, pure sequence prioritization, smart deadwood minimization.
     */
    MEDIUM,

    /**
     * Optimal combinatorial meld search, discard risk awareness, fastest declaration.
     */
    HARD
}
