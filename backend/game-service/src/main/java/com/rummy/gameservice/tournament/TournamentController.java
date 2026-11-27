package com.rummy.gameservice.tournament;

import com.rummy.gameservice.operator.OperatorController;
import com.rummy.gameservice.operator.OperatorLaunchService;
import com.rummy.gameservice.operator.OperatorWalletException;
import com.rummy.gameservice.security.AuthenticatedPlayer;
import com.rummy.gameservice.security.VariantAccessPolicy;
import com.rummy.gameservice.wallet.InsufficientBalanceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Player-facing Tournament REST API in game-service.
 * Handles listing upcoming/live tournaments (with active table assignment)
 * and player registration with atomic Seamless Wallet entry fee debit.
 */
@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;
    private final VariantAccessPolicy variantPolicy;
    private OperatorLaunchService operatorLaunches;

    @Autowired(required = false)
    public void setOperatorLaunches(OperatorLaunchService operatorLaunches) {
        this.operatorLaunches = operatorLaunches;
    }

    public TournamentController(TournamentService tournamentService) {
        this(tournamentService, null);
    }

    @Autowired
    public TournamentController(
            TournamentService tournamentService,
            @Autowired(required = false) VariantAccessPolicy variantPolicy) {
        this.tournamentService = tournamentService;
        this.variantPolicy = variantPolicy;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listTournaments(
            HttpServletRequest request,
            @RequestParam(required = false) String playerId,
            @RequestParam(required = false) String variant) {
        String resolvedId = AuthenticatedPlayer.resolve(request, playerId);
        List<Map<String, Object>> list = tournamentService.listPublicTournaments(resolvedId, variant);
        if (variantPolicy != null) {
            VariantAccessPolicy.FilterScope scope = variantPolicy.resolveScope(request, variant);
            if (scope.restricted()) {
                list = list.stream()
                        .filter(item -> scope.allows(String.valueOf(item.getOrDefault("variant", "DEALS_3"))))
                        .toList();
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "tournaments", list));
    }

    @GetMapping("/my")
    public ResponseEntity<Map<String, Object>> myTournaments(
            HttpServletRequest request,
            @RequestParam(required = false) String playerId,
            @RequestParam(required = false) String variants) {
        String resolvedId = AuthenticatedPlayer.resolve(request, playerId);
        if (variantPolicy != null) {
            VariantAccessPolicy.FilterScope scope = variantPolicy.resolveScope(request, variants);
            if (scope.restricted() && scope.rulesets().isEmpty()) {
                return ResponseEntity.ok(Map.of("success", true, "tournaments", List.of()));
            }
            String effectiveCsv = scope.restricted() ? String.join(",", scope.rulesets()) : variants;
            return ResponseEntity.ok(Map.of("success", true, "tournaments", tournamentService.listMyTournaments(resolvedId, effectiveCsv)));
        }
        List<Map<String, Object>> list = tournamentService.listMyTournaments(resolvedId, variants);
        return ResponseEntity.ok(Map.of("success", true, "tournaments", list));
    }

    @GetMapping("/by-table/{tableId}")
    public ResponseEntity<?> byTable(
            HttpServletRequest request,
            @PathVariable String tableId,
            @RequestParam(required = false) String playerId) {
        try {
            String resolvedId = AuthenticatedPlayer.resolve(request, playerId);
            Map<String, Object> status = tournamentService.getTournamentStatusByTable(tableId, resolvedId);
            ResponseEntity<Map<String, Object>> denied = rejectIfDisallowed(
                    request, String.valueOf(status.getOrDefault("variant", "DEALS_3")));
            if (denied != null) {
                return denied;
            }
            return ResponseEntity.ok(status);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/{tournamentId}/register")
    public ResponseEntity<?> register(
            HttpServletRequest request,
            @PathVariable String tournamentId,
            @RequestBody Map<String, Object> body) {
        String playerId = null;
        try {
            String requestedId = body.get("playerId") != null ? String.valueOf(body.get("playerId")) : null;
            playerId = AuthenticatedPlayer.resolve(request, requestedId);
            ResponseEntity<Map<String, Object>> denied = rejectIfDisallowed(
                    request, tournamentService.getTournamentVariant(tournamentId).orElse(null));
            if (denied != null) {
                return denied;
            }
            String displayName = body.get("displayName") != null ? String.valueOf(body.get("displayName")) : playerId;
            String operatorId = body.get("operatorId") != null ? String.valueOf(body.get("operatorId")) : "DEFAULT";

            Map<String, Object> res = tournamentService.registerPlayer(tournamentId, playerId, displayName, operatorId);
            return ResponseEntity.ok(res);
        } catch (InsufficientBalanceException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(Map.of(
                    "success", false,
                    "error", "INSUFFICIENT_BALANCE",
                    "message", "Insufficient balance in your wallet to join this tournament."
            ));
        } catch (OperatorWalletException e) {
            if (e.isSessionInvalid()) {
                return OperatorController.sessionInvalidResponse(operatorLaunches, playerId, e);
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "success", false,
                    "error", "WALLET_ERROR",
                    "message", e.getMessage()
            ));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/{tournamentId}/checkin")
    public ResponseEntity<?> checkIn(
            HttpServletRequest request,
            @PathVariable String tournamentId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            String requestedId = (body != null && body.get("playerId") != null)
                    ? String.valueOf(body.get("playerId")) : null;
            String playerId = AuthenticatedPlayer.resolve(request, requestedId);
            ResponseEntity<Map<String, Object>> denied = rejectIfDisallowed(
                    request, tournamentService.getTournamentVariant(tournamentId).orElse(null));
            if (denied != null) {
                return denied;
            }
            Map<String, Object> res = tournamentService.checkInPlayer(tournamentId, playerId);
            return ResponseEntity.ok(res);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    private ResponseEntity<Map<String, Object>> rejectIfDisallowed(HttpServletRequest request, String trnVariant) {
        if (variantPolicy != null && trnVariant != null && !variantPolicy.isRulesetAllowed(request, trnVariant)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", "VARIANT_NOT_ALLOWED",
                    "message", "This tournament variant is not enabled on this frontend."
            ));
        }
        return null;
    }
}
