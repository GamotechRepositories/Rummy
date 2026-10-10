package com.rummy.gameservice.wallet;

import com.rummy.gameservice.operator.OperatorDocument;
import com.rummy.gameservice.operator.OperatorRegistry;
import com.rummy.gameservice.persistence.document.WalletAccountDocument;
import com.rummy.gameservice.persistence.document.WalletTransactionDocument;
import com.rummy.gameservice.security.AuthenticatedPlayer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Player wallet API: balance and history. Money is added and withdrawn in the operator's own cashier,
 * not here. The wallet owner is always the authenticated player; a {@code playerId} parameter, when
 * sent, must match the token.
 */
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletService walletService;
    private final OperatorRegistry operators;

    @Autowired
    public WalletController(WalletService walletService, @Autowired(required = false) OperatorRegistry operators) {
        this.walletService = Objects.requireNonNull(walletService);
        this.operators = operators;
    }

    @GetMapping("/balance")
    public ResponseEntity<Map<String, Object>> getBalance(HttpServletRequest request,
                                                          @RequestParam(required = false) String playerId) {
        String owner = AuthenticatedPlayer.resolve(request, playerId);
        WalletAccountDocument account = walletService.getOrCreateWallet(owner);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("playerId", owner);
        body.put("balance", walletService.balanceOf(owner));
        body.put("depositBalance", account.getDepositBalance());
        body.put("winningsBalance", account.getWinningsBalance());
        body.put("bonusBalance", account.getBonusBalance());
        body.put("withdrawableBalance", account.getWinningsBalance());
        body.put("currency", WalletAccountDocument.CURRENCY);
        body.put("cashierUrl", cashierUrl(owner));
        return ResponseEntity.ok(body);
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
                "currency", WalletAccountDocument.CURRENCY,
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

    private String cashierUrl(String playerId) {
        if (operators == null) {
            return null;
        }
        return operators.player(playerId)
                .flatMap(ref -> operators.find(ref.operatorId()))
                .map(OperatorDocument::getCashierUrl)
                .orElse(null);
    }
}
