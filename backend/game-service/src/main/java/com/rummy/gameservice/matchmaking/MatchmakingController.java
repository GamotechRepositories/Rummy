package com.rummy.gameservice.matchmaking;

import com.rummy.gameservice.operator.MockOperatorController;
import com.rummy.gameservice.operator.OperatorRegistry;
import com.rummy.gameservice.operator.OperatorWalletException;
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

    static final String TEST_POOL = "test";

    private final MatchmakingService matchmakingService;
    private final WalletService walletService;
    private final OperatorRegistry operators;

    public MatchmakingController(
            MatchmakingService matchmakingService,
            @Autowired(required = false) WalletService walletService,
            @Autowired(required = false) OperatorRegistry operators) {
        this.matchmakingService = Objects.requireNonNull(matchmakingService);
        this.walletService = walletService;
        this.operators = operators;
    }

    @PostMapping("/join")
    public ResponseEntity<?> joinQueue(HttpServletRequest httpRequest, @RequestBody MatchmakingRequest request) {
        request.setPlayerId(AuthenticatedPlayer.resolve(httpRequest, request.getPlayerId()));
        request.setPool(isTestPlayer(request.getPlayerId()) ? TEST_POOL : null);

        Optional<String> ruleset = StakeTiers.offeredRuleset(request.getRulesetId());
        if (ruleset.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "INVALID_REQUEST",
                    "message", "This game variant is not offered"
            ));
        }
        if (!StakeTiers.isOffered(ruleset.get(), request.getStakeTier(), request.getMaxPlayers())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "INVALID_REQUEST",
                    "message", "Choose one of the offered tables: stakes " + StakeTiers.stakesFor(ruleset.get())
                            + ", " + StakeTiers.tableSizesLabel(ruleset.get())
            ));
        }
        request.setRulesetId(ruleset.get());

        try {
            MatchmakingTicket ticket = matchmakingService.enqueue(request);
            return ResponseEntity.ok(MatchmakingResponse.fromTicket(ticket));
        } catch (InsufficientBalanceException e) {
            return insufficientBalance(request);
        } catch (OperatorWalletException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "5")
                    .body(Map.of(
                            "success", false,
                            "error", "WALLET_UNAVAILABLE",
                            "message", "Your wallet could not be charged right now. Please try again in a moment."
                    ));
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

    private boolean isTestPlayer(String playerId) {
        return operators != null && operators.player(playerId)
                .map(ref -> MockOperatorController.OPERATOR_ID.equals(ref.operatorId()))
                .orElse(false);
    }

    private Optional<MatchmakingTicket> ownedTicket(String ticketId, String playerId) {
        return matchmakingService.getTicket(ticketId)
                .filter(ticket -> playerId.equals(ticket.getPlayerId()));
    }

    private ResponseEntity<?> insufficientBalance(MatchmakingRequest request) {
        BigDecimal required = BigDecimal.valueOf(request.getStakeTier());
        BigDecimal balance = walletService != null ? walletService.balanceOf(request.getPlayerId()) : BigDecimal.ZERO;
        return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "INSUFFICIENT_BALANCE",
                "message", "Insufficient balance. You have ₹" + balance + " but this table needs ₹" + required + ". Add cash from your account.",
                "currentBalance", balance,
                "requiredStake", required
        ));
    }
}
