package com.rummy.gameservice.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * REST controller providing administrative oversight, diagnostics, and compliance reporting.
 */
@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    private final AdminMetricsService adminMetricsService;

    public AdminController(AdminMetricsService adminMetricsService) {
        this.adminMetricsService = Objects.requireNonNull(adminMetricsService);
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> getDiagnostics() {
        return ResponseEntity.ok(adminMetricsService.getSystemDiagnostics());
    }

    @PostMapping("/drain")
    public ResponseEntity<Map<String, Object>> setDrainMode(@RequestParam(defaultValue = "true") boolean drain) {
        adminMetricsService.setDraining(drain);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "isDraining", drain,
                "activeTables", adminMetricsService.getSystemDiagnostics().get("activeTables"),
                "message", drain ? "Server entered DRAINING state. No new tables will be assigned." : "Draining disabled."
        ));
    }

    @GetMapping("/compliance/status")
    public ResponseEntity<Map<String, Object>> getComplianceStatus() {
        return ResponseEntity.ok(Map.of(
                "jurisdiction", "Global / Skill Gaming",
                "applicableAct", "Fair Play & Skill Gaming Standards",
                "operationalMode", "Free-Play Points Simulator",
                "realMoneyWageringEnabled", false,
                "virtualCurrencyOnly", true,
                "status", "OPERATIONAL",
                "timestamp", java.time.Instant.now().toString()
        ));
    }
}
