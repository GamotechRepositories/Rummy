package com.rummy.gameservice.privateroom;

import com.rummy.gameservice.operator.OperatorWalletException;
import com.rummy.gameservice.security.AuthenticatedPlayer;
import com.rummy.gameservice.wallet.InsufficientBalanceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Player-facing REST API for Private Rooms (Create Room / Join by Code / Start / Leave).
 */
@RestController
@RequestMapping("/api/rooms")
public class PrivateRoomController {

    private final PrivateRoomService roomService;

    public PrivateRoomController(PrivateRoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping("/create")
    public ResponseEntity<?> create(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        try {
            String reqPlayerId = body.get("playerId") != null ? String.valueOf(body.get("playerId")) : null;
            String playerId = AuthenticatedPlayer.resolve(request, reqPlayerId);
            String displayName = body.get("displayName") != null ? String.valueOf(body.get("displayName")) : playerId;
            String rulesetId = body.get("rulesetId") != null ? String.valueOf(body.get("rulesetId")) : "POINTS_13";
            int maxPlayers = body.get("maxPlayers") != null ? ((Number) body.get("maxPlayers")).intValue() : 6;
            int stakeTier = body.get("stakeTier") != null ? ((Number) body.get("stakeTier")).intValue() : 0;

            return ResponseEntity.ok(roomService.createRoom(playerId, displayName, rulesetId, maxPlayers, stakeTier));
        } catch (InsufficientBalanceException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(Map.of(
                    "success", false,
                    "error", "INSUFFICIENT_BALANCE",
                    "message", "Insufficient wallet balance for this room's entry/buy-in."
            ));
        } catch (OperatorWalletException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("success", false, "message", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/join/{roomCode}")
    public ResponseEntity<?> join(
            HttpServletRequest request,
            @PathVariable String roomCode,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            String reqPlayerId = (body != null && body.get("playerId") != null) ? String.valueOf(body.get("playerId")) : null;
            String playerId = AuthenticatedPlayer.resolve(request, reqPlayerId);
            String displayName = (body != null && body.get("displayName") != null) ? String.valueOf(body.get("displayName")) : playerId;
            return ResponseEntity.ok(roomService.joinRoom(roomCode, playerId, displayName));
        } catch (InsufficientBalanceException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(Map.of(
                    "success", false,
                    "error", "INSUFFICIENT_BALANCE",
                    "message", "Insufficient wallet balance to join this private room."
            ));
        } catch (OperatorWalletException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("success", false, "message", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/{roomCode}/leave")
    public ResponseEntity<?> leave(
            HttpServletRequest request,
            @PathVariable String roomCode,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            String reqPlayerId = (body != null && body.get("playerId") != null) ? String.valueOf(body.get("playerId")) : null;
            String playerId = AuthenticatedPlayer.resolve(request, reqPlayerId);
            return ResponseEntity.ok(roomService.leaveRoom(roomCode, playerId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/{roomCode}/kick")
    public ResponseEntity<?> kick(
            HttpServletRequest request,
            @PathVariable String roomCode,
            @RequestBody Map<String, Object> body) {
        try {
            String reqPlayerId = body.get("playerId") != null ? String.valueOf(body.get("playerId")) : null;
            String hostPlayerId = AuthenticatedPlayer.resolve(request, reqPlayerId);
            String targetPlayerId = body.get("targetPlayerId") != null ? String.valueOf(body.get("targetPlayerId")) : null;
            if (targetPlayerId == null || targetPlayerId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "targetPlayerId is required"));
            }
            return ResponseEntity.ok(roomService.kickPlayer(roomCode, hostPlayerId, targetPlayerId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/{roomCode}/start")
    public ResponseEntity<?> start(
            HttpServletRequest request,
            @PathVariable String roomCode,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            String reqPlayerId = (body != null && body.get("playerId") != null) ? String.valueOf(body.get("playerId")) : null;
            String playerId = AuthenticatedPlayer.resolve(request, reqPlayerId);
            return ResponseEntity.ok(roomService.startRoom(roomCode, playerId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/my")
    public ResponseEntity<?> myRooms(HttpServletRequest request, @RequestParam(required = false) String playerId) {
        String resolved = AuthenticatedPlayer.resolve(request, playerId);
        return ResponseEntity.ok(Map.of("success", true, "rooms", roomService.listMyRooms(resolved)));
    }

    @GetMapping("/{roomCode}")
    public ResponseEntity<?> getRoom(
            HttpServletRequest request,
            @PathVariable String roomCode,
            @RequestParam(required = false) String playerId) {
        try {
            String resolved = AuthenticatedPlayer.resolve(request, playerId);
            return ResponseEntity.ok(roomService.getRoom(roomCode, resolved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
