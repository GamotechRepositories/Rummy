package com.rummy.gameservice.wallet;

import com.rummy.gameservice.persistence.document.WalletAccountDocument;
import com.rummy.gameservice.persistence.document.WalletTransactionDocument;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST API for player wallet operations, token balance queries, and daily complimentary token claims.
 */
@RestController
@RequestMapping("/api/wallet")
@CrossOrigin(origins = "*")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = Objects.requireNonNull(walletService);
    }

    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@RequestParam String playerId) {
        WalletAccountDocument acc = walletService.getOrCreateWallet(playerId);
        BigDecimal total = acc.getFreePlayBalance();
        BigDecimal deposit = total.multiply(BigDecimal.valueOf(0.6)).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal winnings = total.subtract(deposit).setScale(2, java.math.RoundingMode.HALF_UP);
        return ResponseEntity.ok(Map.of(
                "playerId", playerId,
                "freePlayBalance", total,
                "totalBalance", total,
                "depositBalance", deposit,
                "winningsBalance", winnings,
                "currency", "INR",
                "isRealMoneyEnabled", true,
                "complianceNotice", "Real Cash Account (INR)"
        ));
    }

    @PostMapping("/deposit")
    public ResponseEntity<Map<String, Object>> depositCash(@RequestParam String playerId,
                                                           @RequestParam BigDecimal amount,
                                                           @RequestParam(defaultValue = "UPI") String method) {
        try {
            WalletTransactionDocument tx = walletService.depositCash(playerId, amount, method);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "amount", tx.getAmount(),
                    "newBalance", tx.getBalanceAfter(),
                    "transactionId", tx.getIdempotencyKey(),
                    "message", "₹ " + amount + " deposited successfully via " + method
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/withdraw")
    public ResponseEntity<Map<String, Object>> withdrawCash(@RequestParam String playerId,
                                                            @RequestParam BigDecimal amount,
                                                            @RequestParam(defaultValue = "UPI") String method,
                                                            @RequestParam(defaultValue = "Verified Bank Account") String destination) {
        try {
            WalletTransactionDocument tx = walletService.withdrawCash(playerId, amount, method, destination);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "amount", tx.getAmount(),
                    "newBalance", tx.getBalanceAfter(),
                    "transactionId", tx.getIdempotencyKey(),
                    "message", "Withdrawal request of ₹ " + amount + " processed to " + destination
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/claim-daily")
    public ResponseEntity<Map<String, Object>> claimDaily(@RequestParam String playerId) {
        try {
            WalletTransactionDocument tx = walletService.claimDailyFreeTokens(playerId);
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
    public ResponseEntity<List<WalletTransactionDocument>> getTransactions(@RequestParam String playerId) {
        return ResponseEntity.ok(walletService.getTransactions(playerId));
    }

    @PostMapping("/faucet")
    public ResponseEntity<Map<String, Object>> freeFaucet(@RequestParam String playerId,
                                                          @RequestParam(defaultValue = "1000") BigDecimal amount) {
        String key = "CASH_ADD_" + playerId + "_" + UUID.randomUUID().toString();
        WalletTransactionDocument tx = walletService.credit(
                playerId, amount, "CASH_DEPOSIT", key, null, "Instant Cash Deposit via UPI", null
        );
        return ResponseEntity.ok(Map.of(
                "success", true,
                "credited", tx.getAmount(),
                "newBalance", tx.getBalanceAfter()
        ));
    }
}
