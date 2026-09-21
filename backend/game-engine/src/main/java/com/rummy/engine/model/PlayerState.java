package com.rummy.engine.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

/**
 * Server-authoritative state of an individual player at a table.
 */
public final class PlayerState implements Serializable {

    private final String playerId;
    private final String displayName;
    private final int seatIndex;
    private final boolean isBot;
    private final List<CardInstance> hand;
    private List<CardInstance> lastHand;

    private PlayerStatus status;
    private int score;
    private int cumulativeScore;
    private long chipBalance;
    private boolean hasDeclared;
    private boolean hasDropped;
    private int consecutiveMissedTurns;
    private int turnsCompleted;
    private Instant lastActionAt;

    public PlayerState(String playerId, String displayName, int seatIndex, boolean isBot) {
        this.playerId = Objects.requireNonNull(playerId, "playerId must not be null");
        this.displayName = displayName != null ? displayName : playerId;
        this.seatIndex = seatIndex;
        this.isBot = isBot;
        this.hand = new ArrayList<>();
        this.lastHand = new ArrayList<>();
        this.status = PlayerStatus.WAITING;
        this.score = 0;
        this.cumulativeScore = 0;
        this.chipBalance = 0;
        this.hasDeclared = false;
        this.hasDropped = false;
        this.consecutiveMissedTurns = 0;
        this.turnsCompleted = 0;
        this.lastActionAt = Instant.now();
    }

    /**
     * Add a dealt or drawn card to player hand.
     */
    public void addCard(CardInstance card) {
        Objects.requireNonNull(card, "card must not be null");
        if (hasCard(card.getInstanceId())) {
            throw new IllegalArgumentException("Card instance already in hand: " + card.getInstanceId());
        }
        this.hand.add(card);
        this.lastActionAt = Instant.now();
    }

    /**
     * Add multiple cards (e.g. initial 13 cards deal).
     */
    public void addCards(Collection<CardInstance> cards) {
        Objects.requireNonNull(cards, "cards must not be null");
        for (CardInstance card : cards) {
            addCard(card);
        }
    }

    /**
     * Remove a card from hand (e.g. discard).
     *
     * @param instanceId physical card instance ID
     * @return the removed CardInstance
     */
    public CardInstance removeCard(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Iterator<CardInstance> it = hand.iterator();
        while (it.hasNext()) {
            CardInstance card = it.next();
            if (card.getInstanceId().equals(instanceId)) {
                it.remove();
                this.lastActionAt = Instant.now();
                return card;
            }
        }
        throw new NoSuchElementException("Card instance not found in player hand: " + instanceId);
    }

    public boolean hasCard(String instanceId) {
        for (CardInstance card : hand) {
            if (card.getInstanceId().equals(instanceId)) {
                return true;
            }
        }
        return false;
    }

    public void incrementMissedTurns() {
        this.consecutiveMissedTurns++;
        this.lastActionAt = Instant.now();
    }

    public void resetMissedTurns() {
        this.consecutiveMissedTurns = 0;
        this.lastActionAt = Instant.now();
    }

    public void recordTurnCompleted() {
        this.turnsCompleted++;
        this.resetMissedTurns();
    }

    public boolean hasTakenFirstTurn() {
        return this.turnsCompleted > 0;
    }

    public void markDropped(int penaltyPoints) {
        this.status = PlayerStatus.DROPPED;
        this.hasDropped = true;
        this.score = penaltyPoints;
        this.cumulativeScore += penaltyPoints;
        this.lastHand = new ArrayList<>(this.hand);
        this.hand.clear();
        this.lastActionAt = Instant.now();
    }

    public void markDeclared() {
        this.status = PlayerStatus.DECLARED;
        this.hasDeclared = true;
        this.lastActionAt = Instant.now();
    }

    /**
     * Reset deal-specific fields so the same seated player can play another hand.
     * Keeps seat, identity, chips, and cumulative score.
     */
    public void prepareForNewDeal() {
        this.hand.clear();
        this.lastHand.clear();
        this.status = PlayerStatus.READY;
        this.score = 0;
        this.hasDeclared = false;
        this.hasDropped = false;
        this.consecutiveMissedTurns = 0;
        this.turnsCompleted = 0;
        this.lastActionAt = Instant.now();
    }

    /**
     * Retrieves the player hand for post-game showdown / review.
     * For active players, returns their current hand; for dropped players,
     * returns the hand held at the moment of dropping.
     */
    public List<CardInstance> getShowdownHand() {
        if (!hand.isEmpty()) {
            return Collections.unmodifiableList(hand);
        }
        return Collections.unmodifiableList(lastHand);
    }

    public void markEliminated() {
        this.status = PlayerStatus.ELIMINATED;
        this.lastActionAt = Instant.now();
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getSeatIndex() {
        return seatIndex;
    }

    public boolean isBot() {
        return isBot;
    }

    public List<CardInstance> getHandSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(hand));
    }

    public int getHandSize() {
        return hand.size();
    }

    public PlayerStatus getStatus() {
        return status;
    }

    public void setStatus(PlayerStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public int getCumulativeScore() {
        return cumulativeScore;
    }

    public void addCumulativeScore(int points) {
        this.cumulativeScore += points;
    }

    public long getChipBalance() {
        return chipBalance;
    }

    public void setChipBalance(long chipBalance) {
        this.chipBalance = chipBalance;
    }

    public boolean isHasDeclared() {
        return hasDeclared;
    }

    public boolean isHasDropped() {
        return hasDropped;
    }

    public int getConsecutiveMissedTurns() {
        return consecutiveMissedTurns;
    }

    public int getTurnsCompleted() {
        return turnsCompleted;
    }

    public Instant getLastActionAt() {
        return lastActionAt;
    }

    @Override
    public String toString() {
        return "PlayerState[id=" + playerId + ", seat=" + seatIndex + ", status=" + status + ", cards=" + hand.size() + "]";
    }
}
