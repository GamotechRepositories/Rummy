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
        return ResponseEntity.ok(Map.of(
                "playerId", playerId,
                "freePlayBalance", acc.getFreePlayBalance(),
                "realMoneyBalance", acc.getRealMoneyBalance(),
                "currency", acc.getCurrency(),
                "isRealMoneyEnabled", acc.isRealMoneyEnabled(),
                "complianceNotice", "Virtual Free-Play Mode active"
        ));
    }

    @PostMapping("/claim-daily")
    public ResponseEntity<Map<String, Object>> claimDaily(@RequestParam String playerId) {
        try {
            WalletTransactionDocument tx = walletService.claimDailyFreeTokens(playerId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "tokensClaimed", tx.getAmount(),
                    "newBalance", tx.getBalanceAfter(),
                    "message", "Successfully claimed 500 free-play tokens!"
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
        String key = "FAUCET_" + playerId + "_" + UUID.randomUUID().toString();
        WalletTransactionDocument tx = walletService.credit(
                playerId, amount, "PROMOTIONAL_CREDIT", key, null, "Test token faucet credit", null
        );
        return ResponseEntity.ok(Map.of(
                "success", true,
                "credited", tx.getAmount(),
                "newBalance", tx.getBalanceAfter()
        ));
    }
}
