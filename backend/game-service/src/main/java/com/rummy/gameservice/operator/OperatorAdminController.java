package com.rummy.gameservice.operator;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Operator management (admin key required, enforced by ApiAuthFilter). The secret is returned only
 * when an operator is created or its secret rotated; listings never include it.
 */
@RestController
@RequestMapping("/api/admin/operators")
public class OperatorAdminController {

    private final OperatorRegistry registry;

    public OperatorAdminController(OperatorRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return registry.list().stream().map(doc -> view(doc, false)).toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        try {
            OperatorDocument doc = registry.create(body.get("id"), body.get("name"), body.get("walletUrl"), body.get("cashierUrl"));
            return ResponseEntity.status(HttpStatus.CREATED).body(view(doc, true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            Object enabled = body.get("enabled");
            return registry.update(id, string(body.get("name")), string(body.get("walletUrl")), string(body.get("cashierUrl")),
                            enabled instanceof Boolean b ? b : null)
                    .map(doc -> ResponseEntity.ok(view(doc, false)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/rotate-secret")
    public ResponseEntity<Map<String, Object>> rotateSecret(@PathVariable String id) {
        return registry.rotateSecret(id)
                .map(doc -> ResponseEntity.ok(view(doc, true)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static String string(Object value) {
        return value instanceof String s ? s : null;
    }

    private static Map<String, Object> view(OperatorDocument doc, boolean includeSecret) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", doc.getId());
        view.put("name", doc.getName());
        view.put("walletUrl", doc.getWalletUrl());
        view.put("cashierUrl", doc.getCashierUrl());
        view.put("enabled", doc.isEnabled());
        view.put("createdAt", doc.getCreatedAt());
        view.put("updatedAt", doc.getUpdatedAt());
        if (includeSecret) {
            view.put("secret", doc.getSecret());
        }
        return view;
    }
}
