package com.rummy.gameservice.matchmaking;

import com.rummy.gameservice.persistence.document.WalletAccountDocument;
import com.rummy.gameservice.wallet.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 18: Matchmaking REST Controller.
 */
@RestController
@RequestMapping("/api/matchmaking")
@CrossOrigin(origins = "*")
public class MatchmakingController {

    private final MatchmakingService matchmakingService;
    private final WalletService walletService;

    public MatchmakingController(
            MatchmakingService matchmakingService,
            @Autowired(required = false) WalletService walletService) {
        this.matchmakingService = Objects.requireNonNull(matchmakingService);
        this.walletService = walletService;
    }

    @PostMapping("/join")
    public ResponseEntity<?> joinQueue(@RequestBody MatchmakingRequest request) {
        // Validate player has sufficient tokens to cover the stake tier
        if (walletService != null && request.getStakeTier() > 0) {
            WalletAccountDocument wallet = walletService.getOrCreateWallet(request.getPlayerId());
            BigDecimal required = BigDecimal.valueOf(request.getStakeTier());
            if (wallet.getFreePlayBalance().compareTo(required) < 0) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "INSUFFICIENT_BALANCE",
                        "message", "Insufficient token balance! You have " + wallet.getFreePlayBalance() + " tokens, but need " + required + " tokens to enter this table.",
                        "currentBalance", wallet.getFreePlayBalance(),
                        "requiredStake", required
                ));
            }
        }

        MatchmakingTicket ticket = matchmakingService.enqueue(request);
        return ResponseEntity.ok(MatchmakingResponse.fromTicket(ticket));
    }

    @GetMapping("/ticket/{ticketId}")
    public ResponseEntity<MatchmakingResponse> getTicketStatus(@PathVariable String ticketId) {
        return matchmakingService.getTicket(ticketId)
                .map(ticket -> ResponseEntity.ok(MatchmakingResponse.fromTicket(ticket)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/cancel/{ticketId}")
    public ResponseEntity<Map<String, Object>> cancelTicket(@PathVariable String ticketId) {
        boolean cancelled = matchmakingService.cancelTicket(ticketId);
        return ResponseEntity.ok(Map.of(
                "ticketId", ticketId,
                "cancelled", cancelled
        ));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getQueueStats() {
        return ResponseEntity.ok(Map.of(
                "queuedPlayers", matchmakingService.getQueuedPlayerCount()
        ));
    }
}
