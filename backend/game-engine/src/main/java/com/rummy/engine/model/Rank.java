package com.rummy.engine.model;

/**
 * Standard card ranks with scoring point values and sequence ordering.
 */
public enum Rank {
    ACE("A", 1, 10, "Ace"),
    TWO("2", 2, 2, "Two"),
    THREE("3", 3, 3, "Three"),
    FOUR("4", 4, 4, "Four"),
    FIVE("5", 5, 5, "Five"),
    SIX("6", 6, 6, "Six"),
    SEVEN("7", 7, 7, "Seven"),
    EIGHT("8", 8, 8, "Eight"),
    NINE("9", 9, 9, "Nine"),
    TEN("10", 10, 10, "Ten"),
    JACK("J", 11, 10, "Jack"),
    QUEEN("Q", 12, 10, "Queen"),
    KING("K", 13, 10, "King");

    private final String symbol;
    private final int order;
    private final int defaultPoints;
    private final String displayName;

    Rank(String symbol, int order, int defaultPoints, String displayName) {
        this.symbol = symbol;
        this.order = order;
        this.defaultPoints = defaultPoints;
        this.displayName = displayName;
    }

    public String getSymbol() {
        return symbol;
    }

    /**
     * Standard order where ACE is 1 and KING is 13.
     */
    public int getOrder() {
        return order;
    }

    /**
     * Order when Ace is evaluated as high (14), useful for Q-K-A runs.
     */
    public int getAceHighOrder() {
        return this == ACE ? 14 : order;
    }

    /**
     * Default penalty point value in 13-card Indian Rummy:
     * - Face cards (J, Q, K) and Ace = 10
     * - 10 = 10
     * - 2 through 9 = face value
     */
    public int getDefaultPoints() {
        return defaultPoints;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Find next higher rank in cyclical order (A follows K).
     */
    public Rank next() {
        return switch (this) {
            case ACE -> TWO;
            case TWO -> THREE;
            case THREE -> FOUR;
            case FOUR -> FIVE;
            case FIVE -> SIX;
            case SIX -> SEVEN;
            case SEVEN -> EIGHT;
            case EIGHT -> NINE;
            case NINE -> TEN;
            case TEN -> JACK;
            case JACK -> QUEEN;
            case QUEEN -> KING;
            case KING -> ACE;
        };
    }

    /**
     * Find previous lower rank in cyclical order (K precedes A).
     */
    public Rank previous() {
        return switch (this) {
            case ACE -> KING;
            case TWO -> ACE;
            case THREE -> TWO;
            case FOUR -> THREE;
            case FIVE -> FOUR;
            case SIX -> FIVE;
            case SEVEN -> SIX;
            case EIGHT -> SEVEN;
            case NINE -> EIGHT;
            case TEN -> NINE;
            case JACK -> TEN;
            case QUEEN -> JACK;
            case KING -> QUEEN;
        };
    }

    @Override
    public String toString() {
        return symbol;
    }
}
