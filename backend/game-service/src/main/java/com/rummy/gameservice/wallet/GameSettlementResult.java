package com.rummy.gameservice.wallet;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Production-ready financial settlement record for completed Rummy tables.
 * Follows Supreme Court of India P2P Skill Gaming guidelines and real-world commercial standards
 * (RummyCircle, Junglee Rummy, A23):
 * - Platform charges a strictly transparent Rake / Commission (e.g. 15%).
 * - Winner receives the net prize pool (Gross Pot - Platform Rake).
 * - Platform revenue is audited in PLATFORM_TREASURY ledger.
 */
public record GameSettlementResult(
        String gameId,
        String tableId,
        String rulesetId,
        String winnerPlayerId,
        BigDecimal stakeTier,
        BigDecimal totalGrossPot,
        BigDecimal platformRakeRate,   // e.g. 0.15 (15%)
        BigDecimal platformRakeAmount, // e.g. 30.00
        BigDecimal netWinnerPrize,     // e.g. 170.00
        Map<String, PlayerSettlementDetail> playerDetails
) implements Serializable {

    public record PlayerSettlementDetail(
            String playerId,
            boolean isWinner,
            int penaltyPoints,
            BigDecimal initialStake,
            BigDecimal lossAmount,
            BigDecimal refundAmount,
            BigDecimal winAmount,
            BigDecimal netWalletDelta      // e.g. +170.00 for winner, -25.00 for dropped loser, etc.
    ) implements Serializable {}
}
