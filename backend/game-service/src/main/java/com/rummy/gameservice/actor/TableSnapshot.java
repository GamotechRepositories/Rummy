package com.rummy.gameservice.actor;

import com.rummy.engine.model.PlayerGameView;
import com.rummy.engine.model.snapshot.GameStateSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Everything a {@link TableActor} needs to carry on a dealt match on another process after its
 * host crashed: the engine state plus match-level bookkeeping (stakes, rejoins, pool/deals history).
 * Bot hand memory is not kept; restored bots simply forget opponents' earlier picks.
 */
public record TableSnapshot(
        int version,
        String tableId,
        GameStateSnapshot game,
        int stakeTier,
        int expectedPlayers,
        boolean matchmakingOwnsFill,
        List<Bot> bots,
        Map<String, Integer> rejoinCounts,
        Map<String, Integer> rejoinFeesCharged,
        List<String> voluntaryAbandoners,
        List<PlayerGameView.DealScoreRecord> dealHistory,
        List<String> lastEliminatedNames,
        String nextDealScheduledAt,
        String tournamentWinnerId,
        int effectiveTotalDeals,
        WindDown windDown) {

    public static final int CURRENT_VERSION = 1;

    public record Bot(String playerId, String displayName, String difficulty) {
    }

    public record WindDown(boolean active, int turnsSinceLastDrop, int turnsUntilNextDrop, int lastTurnCounted) {
    }

    public String gameId() {
        return game.gameId();
    }

    public List<String> humanPlayerIds() {
        return game.players().stream().filter(p -> !p.bot()).map(GameStateSnapshot.Player::playerId).toList();
    }

    /** Humans who should be taken back to this table after a restore (not those who walked away). */
    public List<String> resumablePlayerIds() {
        return humanPlayerIds().stream().filter(id -> !voluntaryAbandoners.contains(id)).toList();
    }
}
