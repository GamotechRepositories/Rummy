package com.rummy.engine.model;

import com.rummy.engine.model.snapshot.GameStateSnapshot;
import com.rummy.engine.rules.CardGroup;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a {@link GameState} to a {@link GameStateSnapshot} and back, for crash recovery.
 * A restored state is indistinguishable from the original: same deck order, hands, discard pile,
 * turn (with its absolute deadline), scores and sequence number.
 */
public final class GameStateSnapshots {

    private static final Map<String, Suit> SUITS_BY_LETTER = new HashMap<>();
    private static final Map<String, Rank> RANKS_BY_SYMBOL = new HashMap<>();

    static {
        for (Suit suit : Suit.values()) {
            SUITS_BY_LETTER.put(suit.name().substring(0, 1), suit);
        }
        for (Rank rank : Rank.values()) {
            RANKS_BY_SYMBOL.put(rank.getSymbol(), rank);
        }
    }

    private GameStateSnapshots() {
    }

    public static GameStateSnapshot capture(GameState s) {
        List<GameStateSnapshot.Player> players = new ArrayList<>();
        for (PlayerState p : s.getPlayers()) {
            players.add(new GameStateSnapshot.Player(
                    p.getPlayerId(), p.getDisplayName(), p.getSeatIndex(), p.isBot(), p.getAvatarId(),
                    ids(p.getHandSnapshot()), ids(p.lastHandSnapshot()), p.getStatus().name(),
                    p.getScore(), p.getCumulativeScore(), p.getChipBalance(),
                    p.isHasDeclared(), p.isHasDropped(), p.getConsecutiveMissedTurns(), p.getTurnsCompleted(),
                    instant(p.getLastActionAt()), p.getExtraTimeSeconds()));
        }
        TurnState t = s.getTurnState();
        GameStateSnapshot.Turn turn = t == null ? null : new GameStateSnapshot.Turn(
                t.getTurnNumber(), t.getCurrentPlayerId(), t.getPhase().name(),
                instant(t.getTurnStartedAt()), instant(t.getTurnDeadline()),
                t.getDrawnCardInstanceId(), t.isDrawnFromDiscard(), t.isExtraTime());
        List<List<String>> winningGroups = null;
        if (s.getWinningGroups() != null) {
            winningGroups = new ArrayList<>();
            for (CardGroup g : s.getWinningGroups()) {
                winningGroups.add(ids(g.getCards()));
            }
        }
        return new GameStateSnapshot(
                GameStateSnapshot.CURRENT_VERSION,
                s.getGameId(), s.getTableId(), s.getRulesetId(), s.getRulesetVersion(),
                players,
                ids(s.getDeck().getCardsSnapshot()),
                ids(s.getDiscardPile()),
                id(s.getCutJoker()), id(s.getFinishCard()),
                turn,
                s.getSequence(), s.getStatus().name(), s.getWinnerPlayerId(), winningGroups,
                s.getDealNumber(), s.getDealerSeatIndex(),
                instant(s.getCreatedAt()), instant(s.getFinishedAt()));
    }

    public static GameState restore(GameStateSnapshot snap) {
        if (snap.version() != GameStateSnapshot.CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported snapshot version " + snap.version());
        }
        List<PlayerState> players = new ArrayList<>();
        for (GameStateSnapshot.Player p : snap.players()) {
            PlayerState player = new PlayerState(p.playerId(), p.displayName(), p.seatIndex(), p.bot(), p.avatarId());
            player.restoreProgress(cards(p.hand()), cards(p.lastHand()), PlayerStatus.valueOf(p.status()),
                    p.score(), p.cumulativeScore(), p.chipBalance(), p.declared(), p.dropped(),
                    p.consecutiveMissedTurns(), p.turnsCompleted(), p.extraTimeSeconds(), instant(p.lastActionAt()));
            players.add(player);
        }
        GameStateSnapshot.Turn t = snap.turn();
        TurnState turn = t == null ? null : new TurnState(t.turnNumber(), t.currentPlayerId(),
                TurnPhase.valueOf(t.phase()), instant(t.startedAt()), instant(t.deadline()),
                t.drawnCardInstanceId(), t.drawnFromDiscard(), t.extraTime());
        List<CardGroup> winningGroups = null;
        if (snap.winningGroups() != null) {
            winningGroups = new ArrayList<>();
            for (List<String> g : snap.winningGroups()) {
                winningGroups.add(new CardGroup(cards(g)));
            }
        }
        return new GameState(
                snap.gameId(), snap.tableId(), snap.rulesetId(), snap.rulesetVersion(),
                players,
                new Deck(cards(snap.deck()), new SecureRandom()),
                cards(snap.discardPile()),
                card(snap.cutJoker()), card(snap.finishCard()),
                turn,
                snap.sequence(), GameStatus.valueOf(snap.status()), snap.winnerPlayerId(), winningGroups,
                snap.dealNumber(), snap.dealerSeatIndex(),
                instant(snap.createdAt()), instant(snap.finishedAt()));
    }

    private static String instant(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private static Instant instant(String iso) {
        return iso != null ? Instant.parse(iso) : null;
    }

    /** Rebuilds a card from its deterministic instance id ({@code D<deck>_<suit>_<rank>} or {@code D<deck>_JOKER_<n>}). */
    static CardInstance card(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        String[] parts = instanceId.split("_");
        if (parts.length != 3 || !parts[0].startsWith("D")) {
            throw new IllegalArgumentException("Unrecognised card id " + instanceId);
        }
        int deckNumber = Integer.parseInt(parts[0].substring(1));
        if ("JOKER".equals(parts[1])) {
            return new CardInstance(instanceId, Card.printedJoker(), deckNumber);
        }
        Suit suit = SUITS_BY_LETTER.get(parts[1]);
        Rank rank = RANKS_BY_SYMBOL.get(parts[2]);
        if (suit == null || rank == null) {
            throw new IllegalArgumentException("Unrecognised card id " + instanceId);
        }
        return new CardInstance(instanceId, Card.of(suit, rank), deckNumber);
    }

    private static List<CardInstance> cards(List<String> ids) {
        List<CardInstance> cards = new ArrayList<>(ids.size());
        for (String id : ids) {
            cards.add(card(id));
        }
        return cards;
    }

    private static String id(CardInstance card) {
        return card != null ? card.getInstanceId() : null;
    }

    private static List<String> ids(List<CardInstance> cards) {
        List<String> ids = new ArrayList<>(cards.size());
        for (CardInstance c : cards) {
            ids.add(c.getInstanceId());
        }
        return ids;
    }
}
