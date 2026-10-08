package com.rummy.engine.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;
import com.rummy.engine.rules.CardGroup;

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
    private List<CardGroup> winningGroups;
    private Instant showdownDeadline;
    private final Map<String, List<CardGroup>> submittedMelds;
    private int dealNumber = 1;
    private int dealerSeatIndex = 0;
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
        this.winningGroups = null;
        this.showdownDeadline = null;
        this.submittedMelds = new HashMap<>();
        this.dealNumber = 1;
        this.createdAt = Instant.now();
        this.finishedAt = null;
    }

    /** Restores a captured state verbatim. See {@link GameStateSnapshots}. */
    GameState(String gameId, String tableId, String rulesetId, String rulesetVersion,
              List<PlayerState> players, Deck deck, List<CardInstance> discardPile,
              CardInstance cutJoker, CardInstance finishCard, TurnState turnState,
              long sequence, GameStatus status, String winnerPlayerId, List<CardGroup> winningGroups,
              int dealNumber, int dealerSeatIndex, Instant createdAt, Instant finishedAt,
              Instant showdownDeadline, Map<String, List<CardGroup>> submittedMelds) {
        this.gameId = Objects.requireNonNull(gameId);
        this.tableId = Objects.requireNonNull(tableId);
        this.rulesetId = Objects.requireNonNull(rulesetId);
        this.rulesetVersion = Objects.requireNonNull(rulesetVersion);
        this.players = new ArrayList<>(players);
        this.deck = Objects.requireNonNull(deck);
        this.discardPile = new ArrayList<>(discardPile);
        this.cutJoker = cutJoker;
        this.finishCard = finishCard;
        this.turnState = turnState;
        this.sequence = sequence;
        this.status = Objects.requireNonNull(status);
        this.winnerPlayerId = winnerPlayerId;
        this.winningGroups = winningGroups != null ? new ArrayList<>(winningGroups) : null;
        this.showdownDeadline = showdownDeadline;
        this.submittedMelds = submittedMelds != null ? new HashMap<>(submittedMelds) : new HashMap<>();
        this.dealNumber = dealNumber;
        this.dealerSeatIndex = dealerSeatIndex;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.finishedAt = finishedAt;
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

    public void addPlayer(PlayerState player) {
        Objects.requireNonNull(player, "player must not be null");
        this.players.add(player);
    }

    /** Waiting-room only. Removes a seated player so the seat can be reused. */
    public boolean removePlayer(String playerId) {
        return players.removeIf(p -> p.getPlayerId().equals(playerId));
    }

    public CardInstance topDiscard() {
        if (discardPile.isEmpty()) {
            return null;
        }
        return discardPile.get(discardPile.size() - 1);
    }

    /**
     * Whether the current player may take the open card. Jokers can never be taken from the open pile,
     * except the very first open card of the deal, which only the first player may take.
     */
    public boolean isTopDiscardPickable() {
        CardInstance top = topDiscard();
        if (top == null) {
            return false;
        }
        boolean joker = top.isPrintedJoker()
                || (cutJoker != null && top.getCard().isWildJoker(cutJoker.getCard()));
        if (!joker) {
            return true;
        }
        return turnState != null && turnState.getTurnNumber() == 1 && discardPile.size() == 1;
    }

    public void addToDiscardPile(CardInstance card) {
        Objects.requireNonNull(card, "card must not be null");
        discardPile.add(card);
    }

    public void clearDiscardPile() {
        discardPile.clear();
    }

    /**
     * Reset table for another deal while keeping seated players.
     * Non-eliminated players are prepared for a new deal; eliminated players retain their ELIMINATED status.
     */
    public void prepareForNewDeal(Deck freshDeck) {
        Objects.requireNonNull(freshDeck, "freshDeck must not be null");
        this.deck = freshDeck;
        this.discardPile.clear();
        this.cutJoker = null;
        this.finishCard = null;
        this.turnState = null;
        this.winnerPlayerId = null;
        this.winningGroups = null;
        this.showdownDeadline = null;
        this.submittedMelds.clear();
        this.finishedAt = null;
        this.status = GameStatus.WAITING_FOR_PLAYERS;
        rotateDealer();
        for (PlayerState player : players) {
            if (player.getStatus() != PlayerStatus.ELIMINATED) {
                player.prepareForNewDeal();
            }
        }
    }

    public int getDealerSeatIndex() {
        return dealerSeatIndex;
    }

    public void setDealerSeatIndex(int dealerSeatIndex) {
        this.dealerSeatIndex = dealerSeatIndex;
    }

    public void rotateDealer() {
        List<PlayerState> active = players.stream()
                .filter(p -> p.getStatus() != PlayerStatus.ELIMINATED)
                .sorted(Comparator.comparingInt(PlayerState::getSeatIndex))
                .toList();
        if (!active.isEmpty()) {
            int current = this.dealerSeatIndex;
            PlayerState next = active.stream()
                    .filter(p -> p.getSeatIndex() > current)
                    .findFirst()
                    .orElse(active.get(0));
            this.dealerSeatIndex = next.getSeatIndex();
        }
    }

    public int getDealNumber() {
        return dealNumber;
    }

    public void setDealNumber(int dealNumber) {
        this.dealNumber = dealNumber;
    }

    public void incrementDealNumber() {
        this.dealNumber++;
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

    public List<CardGroup> getWinningGroups() {
        return winningGroups != null ? Collections.unmodifiableList(winningGroups) : null;
    }

    public void setWinningGroups(List<CardGroup> winningGroups) {
        this.winningGroups = winningGroups != null ? new ArrayList<>(winningGroups) : null;
    }

    public Instant getShowdownDeadline() {
        return showdownDeadline;
    }

    public void setShowdownDeadline(Instant showdownDeadline) {
        this.showdownDeadline = showdownDeadline;
    }

    public Map<String, List<CardGroup>> getSubmittedMelds() {
        return Collections.unmodifiableMap(submittedMelds);
    }

    public void addSubmittedMeld(String playerId, List<CardGroup> groups) {
        this.submittedMelds.put(playerId, new ArrayList<>(groups));
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
