package com.rummy.gameservice.matchmaking;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Admin management endpoints for AI bot risk controls and kill switch.
 */
@RestController
@RequestMapping("/api/admin/bot-budget")
public class BotAdminController {

    private final BotBudgetService botBudgetService;

    @Autowired
    public BotAdminController(BotBudgetService botBudgetService) {
        this.botBudgetService = botBudgetService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(botBudgetService.getStatus());
    }

    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggleBotEnabled() {
        boolean newState = !botBudgetService.isBotEnabled();
        botBudgetService.setBotEnabled(newState);
        return ResponseEntity.ok(botBudgetService.getStatus());
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> body) {
        if (body.containsKey("botEnabled")) {
            botBudgetService.setBotEnabled(Boolean.TRUE.equals(body.get("botEnabled")));
        }
        if (body.containsKey("dailyLossCap")) {
            botBudgetService.setDailyLossCap(new BigDecimal(body.get("dailyLossCap").toString()));
        }
        if (Boolean.TRUE.equals(body.get("resetTodayLoss"))) {
            botBudgetService.resetTodayLoss();
        }
        return ResponseEntity.ok(botBudgetService.getStatus());
    }
}
