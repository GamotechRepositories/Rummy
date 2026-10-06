package com.rummy.gameservice.admin;

import com.rummy.gameservice.lifecycle.GracefulDrainLifecycle;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * REST controller providing administrative oversight, diagnostics, and compliance reporting.
 * Requires the X-Admin-Key header (enforced by ApiAuthFilter).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminMetricsService adminMetricsService;
    private final GracefulDrainLifecycle drainLifecycle;

    public AdminController(AdminMetricsService adminMetricsService, GracefulDrainLifecycle drainLifecycle) {
        this.adminMetricsService = Objects.requireNonNull(adminMetricsService);
        this.drainLifecycle = Objects.requireNonNull(drainLifecycle);
    }

    @GetMapping("/diagnostics")
    public ResponseEntity<Map<String, Object>> getDiagnostics() {
        return ResponseEntity.ok(adminMetricsService.getSystemDiagnostics());
    }

    /**
     * Takes this node out of rotation (readiness down, no new tables) while live games finish.
     * Hits only the pod the request lands on; in Kubernetes prefer a rolling restart, which drains via SIGTERM.
     */
    @PostMapping("/drain")
    public ResponseEntity<Map<String, Object>> setDrainMode(@RequestParam(defaultValue = "true") boolean drain) {
        if (drain) {
            drainLifecycle.beginDrain("admin request");
        } else {
            drainLifecycle.cancelDrain();
        }
        Map<String, Object> diag = adminMetricsService.getSystemDiagnostics();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "serverInstanceId", diag.get("serverInstanceId"),
                "isDraining", drain,
                "liveTables", diag.get("liveTables"),
                "message", drain ? "Node is draining: no new tables; live games will finish." : "Drain cancelled."
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
