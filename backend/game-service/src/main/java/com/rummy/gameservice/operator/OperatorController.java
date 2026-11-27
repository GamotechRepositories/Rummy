package com.rummy.gameservice.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.gameservice.wallet.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Endpoints for operators and launched players.
 * <ul>
 *   <li>{@code POST /api/operator/launch}: server-to-server direct operator launch (HMAC signed).</li>
 *   <li>{@code POST /api/operator/session}: exchanges either an Operations launch payload ({@code token},
 *       {@code partner_code}, {@code gid}, etc.) or a single-use mock/direct {@code code} for a Rummy JWT session.</li>
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
    private WalletService walletService;

    @Autowired
    public OperatorController(OperatorRegistry registry, OperatorLaunchService launches, ObjectMapper objectMapper) {
        this.registry = Objects.requireNonNull(registry);
        this.launches = Objects.requireNonNull(launches);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Autowired(required = false)
    public void setWalletService(WalletService walletService) {
        this.walletService = walletService;
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
        String gameCode;
        try {
            JsonNode json = objectMapper.readTree(body);
            externalId = json.path("playerId").asText(null);
            displayName = json.path("displayName").asText(null);
            gameCode = json.path("gameCode").asText(null);
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Body must be JSON");
        }
        try {
            OperatorLaunchService.Launch launch = launches.launch(operatorId, externalId, displayName, gameCode);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("launchUrl", launch.launchUrl());
            response.put("expiresInSeconds", launch.expiresInSeconds());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
        }
    }

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> session(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "INVALID_LAUNCH",
                    "This game link has expired or was already used. Open the game again from your account.");
        }

        String token = str(body, "token");
        String partnerCode = str(body, "partner_code", "partnerCode");
        String code = str(body, "code");

        // Operations HTTP 302 boot sends ?token=...&partner_code=...&gid=...
        if (token != null || partnerCode != null) {
            OperatorLaunchService.OperationsLaunchRequest req = new OperatorLaunchService.OperationsLaunchRequest(
                    token,
                    partnerCode,
                    str(body, "gid"),
                    str(body, "game_code", "gameCode"),
                    str(body, "username"),
                    str(body, "user_id", "userId"),
                    str(body, "currency"),
                    str(body, "lang"),
                    str(body, "extsess"),
                    str(body, "close_url", "closeUrl")
            );
            OperatorLaunchService.OperationsRedeemResult result = launches.redeemOperations(req);
            if (!result.ok()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("error", result.error() != null ? result.error() : "INVALID_LAUNCH");
                if (result.errorCode() != null) {
                    err.put("errorCode", result.errorCode());
                }
                err.put("message", result.message() != null ? result.message() : "Session validation failed.");
                if (result.closeUrl() != null) {
                    err.put("closeUrl", result.closeUrl());
                }
                HttpStatus status = "OPERATIONS_UNAVAILABLE".equals(result.error())
                        || "OPERATIONS_NOT_CONFIGURED".equals(result.error())
                        ? HttpStatus.SERVICE_UNAVAILABLE
                        : HttpStatus.UNAUTHORIZED;
                return ResponseEntity.status(status).body(err);
            }
            OperatorLaunchService.Session session = result.session();
            if (walletService != null && session.balance() != null) {
                walletService.recordOperatorBalance(session.playerId(), session.balance());
            }
            return ResponseEntity.ok(sessionPayload(session));
        }

        // Single-use ?launch=<code> exchange (mock operator / direct operator launch)
        return launches.redeem(code)
                .<ResponseEntity<Map<String, Object>>>map(session -> ResponseEntity.ok(sessionPayload(session)))
                .orElseGet(() -> error(HttpStatus.UNAUTHORIZED, "INVALID_LAUNCH",
                        "This game link has expired or was already used. Open the game again from your account."));
    }

    private static Map<String, Object> sessionPayload(OperatorLaunchService.Session session) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", session.token());
        out.put("playerId", session.playerId());
        out.put("displayName", session.displayName());
        out.put("tokenType", "Bearer");
        out.put("expiresInSeconds", session.expiresInSeconds());
        if (session.balance() != null) {
            out.put("balance", session.balance());
        }
        if (session.partnerCode() != null) {
            out.put("partnerCode", session.partnerCode());
        }
        if (session.gid() != null) {
            out.put("gid", session.gid());
        }
        if (session.gameCode() != null) {
            out.put("gameCode", session.gameCode());
        }
        out.put("currency", session.currency() != null ? session.currency() : "INR");
        out.put("lang", session.lang() != null ? session.lang() : "en");
        if (session.closeUrl() != null) {
            out.put("closeUrl", session.closeUrl());
        }
        return out;
    }

    private static String str(Map<String, Object> body, String... keys) {
        for (String key : keys) {
            Object val = body.get(key);
            if (val != null) {
                String s = String.valueOf(val).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", code, "message", message));
    }

    /** Shared 401 SESSION_INVALID payload when Operations rejects an expired/invalid session (ERR-0011..0013). */
    public static ResponseEntity<Map<String, Object>> sessionInvalidResponse(
            OperatorLaunchService launches, String playerId, OperatorWalletException e) {
        String closeUrl = launches != null
                ? launches.getProviderSession(playerId).map(ProviderSessionDocument::getCloseUrl).orElse(null)
                : null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", "SESSION_INVALID");
        if (e != null && e.getErrorCode() != null) {
            body.put("errorCode", e.getErrorCode());
        }
        body.put("message", "Your game session has expired. Please return to the lobby and launch the game again.");
        if (closeUrl != null) {
            body.put("closeUrl", closeUrl);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }
}
