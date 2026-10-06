package com.rummy.gameservice.wallet;

import com.rummy.gameservice.persistence.document.WalletAccountDocument;
import com.rummy.gameservice.persistence.document.WalletTransactionDocument;
import com.rummy.gameservice.security.AuthenticatedPlayer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * REST API for player wallet operations, token balance queries, and daily complimentary token claims.
 * The wallet owner is always the authenticated player; a {@code playerId} parameter, when sent,
 * must match the token.
 */
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private static final BigDecimal MAX_SINGLE_TRANSFER = BigDecimal.valueOf(100_000);

    private final WalletService walletService;
    private final boolean simulatedPaymentsEnabled;

    public WalletController(WalletService walletService,
                            @Value("${rummy.wallet.simulated-payments-enabled:false}") boolean simulatedPaymentsEnabled) {
        this.walletService = Objects.requireNonNull(walletService);
        this.simulatedPaymentsEnabled = simulatedPaymentsEnabled;
    }

    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> getBalance(HttpServletRequest request,
                                                          @RequestParam(required = false) String playerId) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        WalletAccountDocument acc = walletService.getOrCreateWallet(owner);
        BigDecimal total = acc.getFreePlayBalance();
        BigDecimal deposit = total.multiply(BigDecimal.valueOf(0.6)).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal winnings = total.subtract(deposit).setScale(2, java.math.RoundingMode.HALF_UP);
        return ResponseEntity.ok(Map.of(
                "playerId", owner,
                "freePlayBalance", total,
                "totalBalance", total,
                "depositBalance", deposit,
                "winningsBalance", winnings,
                "currency", "INR",
                "isRealMoneyEnabled", true,
                "complianceNotice", "Real Cash Account (INR)"
        ));
    }

    /**
     * Credits the wallet without any payment confirmation. Only for demos and local testing;
     * production must credit deposits from a verified payment-gateway webhook instead.
     */
    @PostMapping("/deposit")
    public ResponseEntity<Map<String, Object>> depositCash(HttpServletRequest request,
                                                           @RequestParam(required = false) String playerId,
                                                           @RequestParam BigDecimal amount,
                                                           @RequestParam(defaultValue = "UPI") String method) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        if (!simulatedPaymentsEnabled) {
            return paymentsUnavailable("Deposits are not available yet");
        }
        ResponseEntity<Map<String, Object>> invalid = validateAmount(amount);
        if (invalid != null) {
            return invalid;
        }
        try {
            WalletTransactionDocument tx = walletService.depositCash(owner, amount, method);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "amount", tx.getAmount(),
                    "newBalance", tx.getBalanceAfter(),
                    "transactionId", tx.getIdempotencyKey(),
                    "message", "₹ " + amount + " deposited successfully via " + method
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/withdraw")
    public ResponseEntity<Map<String, Object>> withdrawCash(HttpServletRequest request,
                                                            @RequestParam(required = false) String playerId,
                                                            @RequestParam BigDecimal amount,
                                                            @RequestParam(defaultValue = "UPI") String method,
                                                            @RequestParam(defaultValue = "Verified Bank Account") String destination) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        if (!simulatedPaymentsEnabled) {
            return paymentsUnavailable("Withdrawals are not available yet");
        }
        ResponseEntity<Map<String, Object>> invalid = validateAmount(amount);
        if (invalid != null) {
            return invalid;
        }
        try {
            WalletTransactionDocument tx = walletService.withdrawCash(owner, amount, method, destination);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "amount", tx.getAmount(),
                    "newBalance", tx.getBalanceAfter(),
                    "transactionId", tx.getIdempotencyKey(),
                    "message", "Withdrawal request of ₹ " + amount + " processed to " + destination
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/claim-daily")
    public ResponseEntity<Map<String, Object>> claimDaily(HttpServletRequest request,
                                                          @RequestParam(required = false) String playerId) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        try {
            WalletTransactionDocument tx = walletService.claimDailyFreeTokens(owner);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "tokensClaimed", tx.getAmount(),
                    "newBalance", tx.getBalanceAfter(),
                    "message", "Successfully claimed ₹ 500 Daily Bonus!"
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<WalletTransactionDocument>> getTransactions(HttpServletRequest request,
                                                                           @RequestParam(required = false) String playerId) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        return ResponseEntity.ok(walletService.getTransactions(owner));
    }

    /** Admin only (enforced by ApiAuthFilter). */
    @GetMapping("/platform-revenue")
    public ResponseEntity<Map<String, Object>> getPlatformRevenue() {
        BigDecimal balance = walletService.getPlatformTreasuryBalance();
        List<WalletTransactionDocument> recentTxns = walletService.getTransactions(WalletService.PLATFORM_TREASURY);
        List<WalletTransactionDocument> rakeTxns = recentTxns.stream()
                .filter(t -> "PLATFORM_RAKE".equals(t.getTransactionType()))
                .toList();
        BigDecimal recentRake = rakeTxns.stream()
                .map(WalletTransactionDocument::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ResponseEntity.ok(Map.of(
                "treasuryBalance", balance,
                "recentRakeCollected", recentRake,
                "rakeRate", "15%",
                "rakePercentage", 0.15,
                "currency", "INR",
                "treasuryPlayerId", WalletService.PLATFORM_TREASURY,
                "recentRakeTransactions", rakeTxns.stream().limit(20).toList()
        ));
    }

    @GetMapping("/settlement")
    public ResponseEntity<?> getSettlement(HttpServletRequest request, @RequestParam String gameId) {
        String caller = AuthenticatedPlayer.resolve(request, null);
        GameSettlementResult result = walletService.getSettlement(gameId);
        if (result == null || result.playerDetails() == null || !result.playerDetails().containsKey(caller)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    private static ResponseEntity<Map<String, Object>> validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_SINGLE_TRANSFER) > 0 || amount.scale() > 2) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Amount must be between 0.01 and " + MAX_SINGLE_TRANSFER + " with at most 2 decimals"
            ));
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> paymentsUnavailable(String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "success", false,
                "error", "PAYMENTS_DISABLED",
                "message", message
        ));
    }
}
