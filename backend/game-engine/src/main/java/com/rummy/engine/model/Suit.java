package com.rummy.engine.model;

/**
 * Standard card suits used in Rummy decks.
 */
public enum Suit {
    HEARTS("♥", Color.RED, "Hearts"),
    DIAMONDS("♦", Color.RED, "Diamonds"),
    CLUBS("♣", Color.BLACK, "Clubs"),
    SPADES("♠", Color.BLACK, "Spades");

    public enum Color {
        RED,
        BLACK
    }

    private final String symbol;
    private final Color color;
    private final String displayName;

    Suit(String symbol, Color color, String displayName) {
        this.symbol = symbol;
        this.color = color;
        this.displayName = displayName;
    }

    public String getSymbol() {
        return symbol;
    }

    public Color getColor() {
        return color;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isRed() {
        return color == Color.RED;
    }

    public boolean isBlack() {
        return color == Color.BLACK;
    }

    @Override
    public String toString() {
        return symbol;
    }
}
