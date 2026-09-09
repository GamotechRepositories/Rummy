package com.rummy.engine.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

/**
 * Server-authoritative in-memory state of a Rummy table.
 * Contains the complete, ground-truth game state including all private hands,
 * the closed deck, and the open discard pile.
 */
public final class GameState implements Serializable {

    private final String gameId;
    private final String tableId;
    private final String rulesetId;
    private final String rulesetVersion;
    private final List<PlayerState> players;

    private Deck deck;
    private final List<CardInstance> discardPile;
    private CardInstance cutJoker;
    private CardInstance finishCard;
    private TurnState turnState;
    private long sequence;
    private GameStatus status;
    private String winnerPlayerId;
    private final Instant createdAt;
    private Instant finishedAt;

    public GameState(String gameId,
                     String tableId,
                     String rulesetId,
                     String rulesetVersion,
                     List<PlayerState> players,
                     Deck deck) {
        this.gameId = Objects.requireNonNull(gameId, "gameId must not be null");
        this.tableId = Objects.requireNonNull(tableId, "tableId must not be null");
        this.rulesetId = Objects.requireNonNull(rulesetId, "rulesetId must not be null");
        this.rulesetVersion = Objects.requireNonNull(rulesetVersion, "rulesetVersion must not be null");
        this.players = new ArrayList<>(Objects.requireNonNull(players, "players must not be null"));
        this.deck = Objects.requireNonNull(deck, "deck must not be null");
        this.discardPile = new ArrayList<>();
        this.cutJoker = null;
        this.finishCard = null;
        this.turnState = null;
        this.sequence = 0L;
        this.status = GameStatus.WAITING_FOR_PLAYERS;
        this.winnerPlayerId = null;
        this.createdAt = Instant.now();
        this.finishedAt = null;
    }

    public Optional<PlayerState> getPlayer(String playerId) {
        return players.stream().filter(p -> p.getPlayerId().equals(playerId)).findFirst();
    }

    public PlayerState requirePlayer(String playerId) {
        return getPlayer(playerId)
                .orElseThrow(() -> new NoSuchElementException("Player not found in game: " + playerId));
    }

    public Optional<PlayerState> getPlayerBySeat(int seatIndex) {
        return players.stream().filter(p -> p.getSeatIndex() == seatIndex).findFirst();
    }

    public CardInstance topDiscard() {
        if (discardPile.isEmpty()) {
            return null;
        }
        return discardPile.get(discardPile.size() - 1);
    }

    public void addToDiscardPile(CardInstance card) {
        Objects.requireNonNull(card, "card must not be null");
        discardPile.add(card);
    }

    public CardInstance takeTopDiscard() {
        if (discardPile.isEmpty()) {
            throw new NoSuchElementException("Discard pile is empty");
        }
        return discardPile.remove(discardPile.size() - 1);
    }

    /**
     * Determines next active player in clockwise seat order.
     */
    public Optional<PlayerState> nextActivePlayer(String currentPlayerId) {
        List<PlayerState> sortedPlayers = players.stream()
                .sorted(Comparator.comparingInt(PlayerState::getSeatIndex))
                .toList();

        int currentIndex = -1;
        for (int i = 0; i < sortedPlayers.size(); i++) {
            if (sortedPlayers.get(i).getPlayerId().equals(currentPlayerId)) {
                currentIndex = i;
                break;
            }
        }

        int total = sortedPlayers.size();
        for (int offset = 1; offset < total; offset++) {
            int nextIdx = (currentIndex + offset) % total;
            PlayerState candidate = sortedPlayers.get(nextIdx);
            if (candidate.getStatus() == PlayerStatus.ACTIVE) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Active non-dropped, non-eliminated players count.
     */
    public long activePlayerCount() {
        return players.stream().filter(p -> p.getStatus() == PlayerStatus.ACTIVE).count();
    }

    public long nextSequence() {
        return ++this.sequence;
    }

    public String getGameId() {
        return gameId;
    }

    public String getTableId() {
        return tableId;
    }

    public String getRulesetId() {
        return rulesetId;
    }

    public String getRulesetVersion() {
        return rulesetVersion;
    }

    public List<PlayerState> getPlayers() {
        return Collections.unmodifiableList(players);
    }

    public Deck getDeck() {
        return deck;
    }

    public void setDeck(Deck deck) {
        this.deck = Objects.requireNonNull(deck);
    }

    public List<CardInstance> getDiscardPile() {
        return Collections.unmodifiableList(discardPile);
    }

    public CardInstance getCutJoker() {
        return cutJoker;
    }

    public void setCutJoker(CardInstance cutJoker) {
        this.cutJoker = cutJoker;
    }

    public CardInstance getFinishCard() {
        return finishCard;
    }

    public void setFinishCard(CardInstance finishCard) {
        this.finishCard = finishCard;
    }

    public TurnState getTurnState() {
        return turnState;
    }

    public void setTurnState(TurnState turnState) {
        this.turnState = turnState;
    }

    public long getSequence() {
        return sequence;
    }

    public GameStatus getStatus() {
        return status;
    }

    public void setStatus(GameStatus status) {
        this.status = Objects.requireNonNull(status);
        if (status == GameStatus.COMPLETED || status == GameStatus.ABORTED) {
            this.finishedAt = Instant.now();
        }
    }

    public String getWinnerPlayerId() {
        return winnerPlayerId;
    }

    public void setWinnerPlayerId(String winnerPlayerId) {
        this.winnerPlayerId = winnerPlayerId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    @Override
    public String toString() {
        return "GameState[id=" + gameId + ", status=" + status + ", players=" + players.size() + ", seq=" + sequence + "]";
    }
}
