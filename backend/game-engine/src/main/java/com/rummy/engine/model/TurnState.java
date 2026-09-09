package com.rummy.engine.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Encapsulates the active turn state of a table actor.
 */
public final class TurnState implements Serializable {

    private final int turnNumber;
    private final String currentPlayerId;
    private final TurnPhase phase;
    private final Instant turnStartedAt;
    private final Instant turnDeadline;
    private final String drawnCardInstanceId;
    private final boolean drawnFromDiscard;

    public TurnState(int turnNumber,
                     String currentPlayerId,
                     TurnPhase phase,
                     Instant turnStartedAt,
                     Instant turnDeadline,
                     String drawnCardInstanceId,
                     boolean drawnFromDiscard) {
        this.turnNumber = turnNumber;
        this.currentPlayerId = Objects.requireNonNull(currentPlayerId, "currentPlayerId must not be null");
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
        this.turnStartedAt = Objects.requireNonNull(turnStartedAt, "turnStartedAt must not be null");
        this.turnDeadline = Objects.requireNonNull(turnDeadline, "turnDeadline must not be null");
        this.drawnCardInstanceId = drawnCardInstanceId;
        this.drawnFromDiscard = drawnFromDiscard;
    }

    /**
     * Start a new turn for a player awaiting draw.
     */
    public static TurnState startTurn(int turnNumber, String playerId, Instant now, long turnDurationSeconds) {
        Instant deadline = now.plusSeconds(turnDurationSeconds);
        return new TurnState(turnNumber, playerId, TurnPhase.AWAITING_DRAW, now, deadline, null, false);
    }

    /**
     * Transition turn to AWAITING_DISCARD after drawing a card.
     */
    public TurnState withCardDrawn(String cardInstanceId, boolean fromDiscard) {
        if (this.phase != TurnPhase.AWAITING_DRAW) {
            throw new IllegalStateException("Cannot draw card when phase is " + this.phase);
        }
        return new TurnState(this.turnNumber, this.currentPlayerId, TurnPhase.AWAITING_DISCARD,
                this.turnStartedAt, this.turnDeadline, cardInstanceId, fromDiscard);
    }

    /**
     * Transition turn to AWAITING_DECLARE_VALIDATION.
     */
    public TurnState withDeclarationInitiated() {
        if (this.phase != TurnPhase.AWAITING_DISCARD) {
            throw new IllegalStateException("Cannot initiate declare when phase is " + this.phase);
        }
        return new TurnState(this.turnNumber, this.currentPlayerId, TurnPhase.AWAITING_DECLARE_VALIDATION,
                this.turnStartedAt, this.turnDeadline, this.drawnCardInstanceId, this.drawnFromDiscard);
    }

    /**
     * Transition to turn COMPLETED.
     */
    public TurnState withCompleted() {
        return new TurnState(this.turnNumber, this.currentPlayerId, TurnPhase.COMPLETED,
                this.turnStartedAt, this.turnDeadline, this.drawnCardInstanceId, this.drawnFromDiscard);
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(turnDeadline);
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public String getCurrentPlayerId() {
        return currentPlayerId;
    }

    public TurnPhase getPhase() {
        return phase;
    }

    public Instant getTurnStartedAt() {
        return turnStartedAt;
    }

    public Instant getTurnDeadline() {
        return turnDeadline;
    }

    public String getDrawnCardInstanceId() {
        return drawnCardInstanceId;
    }

    public boolean isDrawnFromDiscard() {
        return drawnFromDiscard;
    }

    @Override
    public String toString() {
        return "TurnState[turn=" + turnNumber + ", player=" + currentPlayerId + ", phase=" + phase + "]";
    }
}
