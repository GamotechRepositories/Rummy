package com.rummy.gameservice.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Endpoints for operators and launched players. See OPERATOR_INTEGRATION.md.
 * <ul>
 *   <li>{@code POST /api/operator/launch}: server-to-server, signed with the operator secret.</li>
 *   <li>{@code POST /api/operator/session}: the game client exchanges a launch code for a session.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/operator")
public class OperatorController {

    private static final Logger log = LoggerFactory.getLogger(OperatorController.class);
    private static final int MAX_BODY_BYTES = 4096;

    private final OperatorRegistry registry;
    private final OperatorLaunchService launches;
    private final ObjectMapper objectMapper;

    public OperatorController(OperatorRegistry registry, OperatorLaunchService launches, ObjectMapper objectMapper) {
        this.registry = Objects.requireNonNull(registry);
        this.launches = Objects.requireNonNull(launches);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @PostMapping("/launch")
    public ResponseEntity<Map<String, Object>> launch(HttpServletRequest request,
                                                      @RequestBody(required = false) byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0 || rawBody.length > MAX_BODY_BYTES) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "JSON body required");
        }
        String body = new String(rawBody, StandardCharsets.UTF_8);
        String operatorId = request.getHeader(RequestSignature.OPERATOR_HEADER);
        Optional<OperatorDocument> operator = registry.findEnabled(operatorId);
        if (operator.isEmpty() || !RequestSignature.verify(operator.get().getSecret(),
                request.getHeader(RequestSignature.TIMESTAMP_HEADER), body,
                request.getHeader(RequestSignature.SIGNATURE_HEADER), Instant.now())) {
            log.warn("[Operator] Rejected launch request: bad operator or signature (operator header {})", operatorId);
            return error(HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE", "Unknown operator or invalid signature");
        }
        String externalId;
        String displayName;
        try {
            JsonNode json = objectMapper.readTree(body);
            externalId = json.path("playerId").asText(null);
            displayName = json.path("displayName").asText(null);
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Body must be JSON");
        }
        try {
            OperatorLaunchService.Launch launch = launches.launch(operatorId, externalId, displayName);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("launchUrl", launch.launchUrl());
            response.put("expiresInSeconds", launch.expiresInSeconds());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
        }
    }

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> session(@RequestBody(required = false) Map<String, String> body) {
        String code = body != null ? body.get("code") : null;
        return launches.redeem(code)
                .<ResponseEntity<Map<String, Object>>>map(session -> ResponseEntity.ok(Map.of(
                        "token", session.token(),
                        "playerId", session.playerId(),
                        "displayName", session.displayName(),
                        "tokenType", "Bearer",
                        "expiresInSeconds", session.expiresInSeconds()
                )))
                .orElseGet(() -> error(HttpStatus.UNAUTHORIZED, "INVALID_LAUNCH",
                        "This game link has expired or was already used. Open the game again from your account."));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", code, "message", message));
    }
}
