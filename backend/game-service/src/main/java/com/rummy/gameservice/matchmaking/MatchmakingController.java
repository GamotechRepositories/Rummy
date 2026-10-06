package com.rummy.gameservice.matchmaking;

import com.rummy.gameservice.persistence.document.WalletAccountDocument;
import com.rummy.gameservice.security.AuthenticatedPlayer;
import com.rummy.gameservice.wallet.InsufficientBalanceException;
import com.rummy.gameservice.wallet.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 18: Matchmaking REST Controller.
 */
@RestController
@RequestMapping("/api/matchmaking")
public class MatchmakingController {

    private static final int MAX_STAKE_TIER = 100_000;

    private final MatchmakingService matchmakingService;
    private final WalletService walletService;

    public MatchmakingController(
            MatchmakingService matchmakingService,
            @Autowired(required = false) WalletService walletService) {
        this.matchmakingService = Objects.requireNonNull(matchmakingService);
        this.walletService = walletService;
    }

    @PostMapping("/join")
    public ResponseEntity<?> joinQueue(HttpServletRequest httpRequest, @RequestBody MatchmakingRequest request) {
        request.setPlayerId(AuthenticatedPlayer.resolve(httpRequest, request.getPlayerId()));

        if (request.getStakeTier() < 0 || request.getStakeTier() > MAX_STAKE_TIER
                || request.getMaxPlayers() < 2 || request.getMaxPlayers() > 6) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "INVALID_REQUEST",
                    "message", "stakeTier must be 0-" + MAX_STAKE_TIER + " and maxPlayers 2-6"
            ));
        }

        try {
            MatchmakingTicket ticket = matchmakingService.enqueue(request);
            return ResponseEntity.ok(MatchmakingResponse.fromTicket(ticket));
        } catch (InsufficientBalanceException e) {
            return insufficientBalance(request);
        } catch (NodeDrainingException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "2")
                    .body(Map.of(
                            "success", false,
                            "error", "SERVER_DRAINING",
                            "message", e.getMessage()
                    ));
        }
    }

    @GetMapping("/ticket/{ticketId}")
    public ResponseEntity<MatchmakingResponse> getTicketStatus(HttpServletRequest httpRequest, @PathVariable String ticketId) {
        String caller = AuthenticatedPlayer.resolve(httpRequest, null);
        return ownedTicket(ticketId, caller)
                .map(ticket -> ResponseEntity.ok(MatchmakingResponse.fromTicket(ticket)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/cancel/{ticketId}")
    public ResponseEntity<Map<String, Object>> cancelTicket(HttpServletRequest httpRequest, @PathVariable String ticketId) {
        String caller = AuthenticatedPlayer.resolve(httpRequest, null);
        boolean cancelled = ownedTicket(ticketId, caller).isPresent() && matchmakingService.cancelTicket(ticketId);
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

    private Optional<MatchmakingTicket> ownedTicket(String ticketId, String playerId) {
        return matchmakingService.getTicket(ticketId)
                .filter(ticket -> playerId.equals(ticket.getPlayerId()));
    }

    private ResponseEntity<?> insufficientBalance(MatchmakingRequest request) {
        BigDecimal required = BigDecimal.valueOf(request.getStakeTier());
        BigDecimal balance = BigDecimal.ZERO;
        if (walletService != null) {
            WalletAccountDocument wallet = walletService.getOrCreateWallet(request.getPlayerId());
            balance = wallet.getFreePlayBalance();
        }
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "INSUFFICIENT_BALANCE",
                "message", "Insufficient token balance! You have " + balance + " tokens, but need " + required + " tokens to enter this table.",
                "currentBalance", balance,
                "requiredStake", required
        ));
    }
}
