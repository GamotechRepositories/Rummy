package com.rummy.gameservice.tournament;

import com.rummy.gameservice.operator.OperatorWalletException;
import com.rummy.gameservice.security.AuthenticatedPlayer;
import com.rummy.gameservice.wallet.InsufficientBalanceException;
import jakarta.servlet.http.HttpServletRequest;
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

    public TournamentController(TournamentService tournamentService) {
        this.tournamentService = tournamentService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listTournaments(
            HttpServletRequest request,
            @RequestParam(required = false) String playerId,
            @RequestParam(required = false) String variant) {
        String resolvedId = AuthenticatedPlayer.resolve(request, playerId);
        List<Map<String, Object>> list = tournamentService.listPublicTournaments(resolvedId, variant);
        return ResponseEntity.ok(Map.of("success", true, "tournaments", list));
    }

    @GetMapping("/my")
    public ResponseEntity<Map<String, Object>> myTournaments(
            HttpServletRequest request,
            @RequestParam(required = false) String playerId,
            @RequestParam(required = false) String variants) {
        String resolvedId = AuthenticatedPlayer.resolve(request, playerId);
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
            return ResponseEntity.ok(tournamentService.getTournamentStatusByTable(tableId, resolvedId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/{tournamentId}/register")
    public ResponseEntity<?> register(
            HttpServletRequest request,
            @PathVariable String tournamentId,
            @RequestBody Map<String, Object> body) {
        try {
            String requestedId = body.get("playerId") != null ? String.valueOf(body.get("playerId")) : null;
            String playerId = AuthenticatedPlayer.resolve(request, requestedId);
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
            Map<String, Object> res = tournamentService.checkInPlayer(tournamentId, playerId);
            return ResponseEntity.ok(res);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }
}
