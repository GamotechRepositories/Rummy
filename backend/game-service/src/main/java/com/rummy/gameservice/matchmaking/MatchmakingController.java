package com.rummy.gameservice.matchmaking;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    public MatchmakingController(MatchmakingService matchmakingService) {
        this.matchmakingService = Objects.requireNonNull(matchmakingService);
    }

    @PostMapping("/join")
    public ResponseEntity<MatchmakingResponse> joinQueue(@RequestBody MatchmakingRequest request) {
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
