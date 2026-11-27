package com.rummy.gameservice.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-to-server client for the Operations internal wallet API:
 * <ul>
 *   <li>{@code POST /api/v1/wallet/balance} (2051 — initial/authoritative balance)</li>
 *   <li>{@code POST /api/v1/wallet/credit-transfer} (2052 — bet, win, settlement, and ttype=2 refund)</li>
 * </ul>
 * Every request is authenticated with {@code X-Internal-Key}. Retries on transient 5xx / timeout /
 * ERR-0020 / ERR-0021 always reuse the exact same {@code tranid}.
 */
@Service
public class OperationsWalletClient {

    private static final Logger log = LoggerFactory.getLogger(OperationsWalletClient.class);

    public static final String INTERNAL_KEY_HEADER = "X-Internal-Key";
    public static final String BALANCE_PATH = "/api/v1/wallet/balance";
    public static final String CREDIT_TRANSFER_PATH = "/api/v1/wallet/credit-transfer";

    public static final int TTYPE_PLAY = 1;
    public static final int TTYPE_REFUND = 2;
    public static final String GAME_TYPE_SPIN = "spin";
    public static final String GAME_TYPE_BONUS = "bonus";
    public static final String GAME_TYPE_REFUND = "refund";

    private static final int MAX_TRANSFER_ATTEMPTS = 3;
    private static final Set<String> SESSION_INVALID_CODES = Set.of("ERR-0011", "ERR-0012", "ERR-0013");
    private static final Set<String> RETRYABLE_ERROR_CODES = Set.of("ERR-0020", "ERR-0021");
    private static final Pattern ERR_CODE_PATTERN = Pattern.compile("\\b(ERR-\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_TEXT_PATTERN = Pattern.compile("^[+-]?\\d+(?:\\.\\d+)?$");

    public record TransferRequest(
            String token,
            String partnerCode,
            String userId,
            String gid,
            BigDecimal bet,
            BigDecimal win,
            String gameType,
            String tranid,
            int ttype,
            String roundid
    ) {}

    public record Reply(
            boolean ok,
            BigDecimal balance,
            String rawMessage,
            String errorCode,
            boolean insufficientFunds,
            boolean sessionInvalid
    ) {
        public static Reply success(BigDecimal balance, String rawMessage) {
            return new Reply(true, balance, rawMessage, null, false, false);
        }

        public static Reply failure(String errorCode, String rawMessage, boolean insufficientFunds, boolean sessionInvalid) {
            return new Reply(false, null, rawMessage, errorCode, insufficientFunds, sessionInvalid);
        }
    }

    private final String apiUrl;
    private final String internalServiceKey;
    private final String defaultGid;
    private final ObjectMapper json;
    private final Duration timeout;
    private volatile HttpClient http;
    private volatile long retryDelayBaseMs = 250L;

    @Autowired
    public OperationsWalletClient(
            @Value("${rummy.operations.api-url:${OPERATIONS_API_URL:https://operations.dpbossking.com}}") String apiUrl,
            @Value("${rummy.operations.internal-service-key:${INTERNAL_SERVICE_KEY:}}") String internalServiceKey,
            @Value("${rummy.operations.default-gid:${OPERATIONS_DEFAULT_GID:}}") String defaultGid,
            @Value("${rummy.operations.wallet-timeout-ms:5000}") long timeoutMs,
            ObjectMapper json) {
        this.apiUrl = normalizeBaseUrl(apiUrl);
        this.internalServiceKey = internalServiceKey != null ? internalServiceKey.trim() : "";
        this.defaultGid = defaultGid != null ? defaultGid.trim() : "";
        this.timeout = Duration.ofMillis(Math.max(500L, timeoutMs));
        this.json = Objects.requireNonNull(json);
        if (isConfigured()) {
            log.info("[OperationsWallet] Initialized for apiUrl={}", this.apiUrl);
        } else {
            log.info("[OperationsWallet] INTERNAL_SERVICE_KEY not configured. Operations wallet in standby.");
        }
    }

    private HttpClient httpClient() {
        HttpClient client = this.http;
        if (client == null) {
            synchronized (this) {
                client = this.http;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofMillis(Math.min(this.timeout.toMillis(), 3000L)))
                            .executor(Executors.newVirtualThreadPerTaskExecutor())
                            .build();
                    this.http = client;
                }
            }
        }
        return client;
    }

    public OperationsWalletClient(String apiUrl, String internalServiceKey, String defaultGid, long timeoutMs) {
        this(apiUrl, internalServiceKey, defaultGid, timeoutMs, new ObjectMapper());
    }

    public boolean isConfigured() {
        return !internalServiceKey.isBlank() && !apiUrl.isBlank();
    }

    public String getApiUrl() {
        return apiUrl;
    }

    void setRetryDelayBaseMs(long retryDelayBaseMs) {
        this.retryDelayBaseMs = Math.max(0L, retryDelayBaseMs);
    }

    /** Calls 2051 initial/live balance endpoint: {@code POST /api/v1/wallet/balance}. */
    public Reply getBalance(String token, String partnerCode) {
        if (!isConfigured()) {
            throw new OperatorWalletException("Operations INTERNAL_SERVICE_KEY is not configured", false);
        }
        if ((token == null || token.isBlank()) && (partnerCode == null || partnerCode.isBlank())) {
            throw new OperatorWalletException("token or partner_code is required for balance", false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (token != null && !token.isBlank()) {
            body.put("token", token.trim());
        }
        if (partnerCode != null && !partnerCode.isBlank()) {
            body.put("partner_code", partnerCode.trim().toUpperCase(Locale.ROOT));
        }
        String payload = encodeJson(body);
        TransportResponse response = executePost(BALANCE_PATH, payload, "balance");
        return parseReply("balance", response);
    }

    /**
     * Calls 2052 transfer endpoint: {@code POST /api/v1/wallet/credit-transfer}.
     * Retries transient network errors, HTTP 5xx, and ERR-0020/ERR-0021 using the exact same {@code tranid}.
     */
    public Reply creditTransfer(TransferRequest req) {
        if (!isConfigured()) {
            throw new OperatorWalletException("Operations INTERNAL_SERVICE_KEY is not configured", false);
        }
        Objects.requireNonNull(req, "req");
        if (req.tranid() == null || req.tranid().isBlank()) {
            throw new IllegalArgumentException("tranid is required");
        }
        Map<String, Object> body = buildTransferBody(req);
        String payload = encodeJson(body);

        for (int attempt = 1; attempt <= MAX_TRANSFER_ATTEMPTS; attempt++) {
            try {
                TransportResponse response = executePost(CREDIT_TRANSFER_PATH, payload, "credit-transfer");
                return parseReply("credit-transfer", response);
            } catch (OperatorWalletException e) {
                if (!e.isUncertain() || attempt >= MAX_TRANSFER_ATTEMPTS) {
                    throw e;
                }
                log.warn("[OperationsWallet] credit-transfer tranid={} transient failure (attempt {}/{}): {}; retrying with SAME tranid",
                        req.tranid(), attempt, MAX_TRANSFER_ATTEMPTS, e.getMessage());
                sleepBeforeRetry(attempt);
            }
        }
        throw new OperatorWalletException("Operations credit-transfer failed after retries", true);
    }

    private Map<String, Object> buildTransferBody(TransferRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (req.token() != null && !req.token().isBlank()) {
            body.put("token", req.token().trim());
        }
        if (req.partnerCode() != null && !req.partnerCode().isBlank()) {
            body.put("partner_code", req.partnerCode().trim().toUpperCase(Locale.ROOT));
        }
        if (req.userId() != null && !req.userId().isBlank()) {
            body.put("user_id", req.userId().trim());
        }
        Object gidVal = parseGidValue(req.gid(), defaultGid);
        if (gidVal != null) {
            body.put("gid", gidVal);
        }
        body.put("bet", formatJsonNumber(req.bet()));
        body.put("win", formatJsonNumber(req.win()));
        body.put("game_type", req.gameType() != null && !req.gameType().isBlank() ? req.gameType().trim() : GAME_TYPE_SPIN);
        body.put("tranid", req.tranid().trim());
        body.put("ttype", req.ttype());
        String effectiveRound = (req.roundid() != null && !req.roundid().isBlank()) ? req.roundid().trim() : req.tranid().trim();
        body.put("roundid", effectiveRound);
        return body;
    }

    protected record TransportResponse(int statusCode, String body) {}

    protected TransportResponse postJson(String path, String payload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header(INTERNAL_KEY_HEADER, internalServiceKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return new TransportResponse(response.statusCode(), response.body());
    }

    private TransportResponse executePost(String path, String payload, String action) {
        try {
            TransportResponse response = postJson(path, payload);
            if (response.statusCode() >= 500 || response.statusCode() == 408 || response.statusCode() == 429) {
                throw new OperatorWalletException(
                        "Operations " + action + " HTTP " + response.statusCode(), true);
            }
            return response;
        } catch (ConnectException e) {
            throw new OperatorWalletException(
                    "Operations " + action + " connection refused: " + e.getMessage(), false);
        } catch (IOException e) {
            throw new OperatorWalletException(
                    "Operations " + action + " I/O error: " + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperatorWalletException(
                    "Operations " + action + " interrupted", true);
        }
    }

    private Reply parseReply(String action, TransportResponse response) {
        String rawBody = response.body() != null ? response.body() : "";
        JsonNode node;
        try {
            node = rawBody.isBlank() ? null : json.readTree(rawBody);
        } catch (IOException e) {
            node = null;
        }

        String errorCode = extractErrorCode(node, rawBody);
        String rawMessage = extractRawMessage(node, rawBody);

        if (errorCode != null && RETRYABLE_ERROR_CODES.contains(errorCode.toUpperCase(Locale.ROOT))) {
            throw new OperatorWalletException(
                    "Operations " + action + " returned " + errorCode + (rawMessage != null ? ": " + rawMessage : ""),
                    true, false, errorCode);
        }

        boolean httpOk = response.statusCode() >= 200 && response.statusCode() < 300;
        Boolean statusFlag = null;
        if (node != null && node.has("status") && !node.get("status").isNull()) {
            JsonNode statusNode = node.get("status");
            if (statusNode.isBoolean()) {
                statusFlag = statusNode.asBoolean();
            } else if (statusNode.isTextual()) {
                String s = statusNode.asText().trim().toLowerCase(Locale.ROOT);
                if ("true".equals(s) || "ok".equals(s) || "success".equals(s)) {
                    statusFlag = true;
                } else if ("false".equals(s) || "failed".equals(s) || "error".equals(s)) {
                    statusFlag = false;
                }
            }
        }

        if (httpOk && Boolean.TRUE.equals(statusFlag)) {
            BigDecimal balance = parseBalance(node).orElse(null);
            return Reply.success(balance, rawMessage);
        }
        // Some internal gateways omit `status` and return 200 with numeric `balance` / `message`
        if (httpOk && statusFlag == null && errorCode == null) {
            Optional<BigDecimal> bal = parseBalance(node);
            if (bal.isPresent()) {
                return Reply.success(bal.get(), rawMessage);
            }
        }

        boolean sessionInvalid = response.statusCode() == 401
                || response.statusCode() == 403
                || (errorCode != null && SESSION_INVALID_CODES.contains(errorCode.toUpperCase(Locale.ROOT)))
                || isSessionExpiredMessage(rawMessage);

        boolean insufficient = !sessionInvalid && (
                response.statusCode() == 402
                        || isInsufficientFundsMessage(rawMessage)
                        || isInsufficientFundsMessage(errorCode));

        return Reply.failure(errorCode, rawMessage != null ? rawMessage : "HTTP " + response.statusCode(),
                insufficient, sessionInvalid);
    }

    /** Extracts the authoritative Float balance from {@code message} or fallback {@code balance} / {@code data.balance}. */
    public static Optional<BigDecimal> parseBalance(JsonNode node) {
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        Optional<BigDecimal> fromMsg = asDecimal(node.get("message"));
        if (fromMsg.isPresent()) {
            return fromMsg;
        }
        Optional<BigDecimal> fromBal = asDecimal(node.get("balance"));
        if (fromBal.isPresent()) {
            return fromBal;
        }
        JsonNode data = node.get("data");
        if (data != null && data.isObject()) {
            Optional<BigDecimal> fromDataBal = asDecimal(data.get("balance"));
            if (fromDataBal.isPresent()) {
                return fromDataBal;
            }
            return asDecimal(data.get("message"));
        }
        return Optional.empty();
    }

    private static Optional<BigDecimal> asDecimal(JsonNode n) {
        if (n == null || n.isNull()) {
            return Optional.empty();
        }
        if (n.isNumber()) {
            return Optional.of(n.decimalValue());
        }
        if (n.isTextual()) {
            String trimmed = n.asText().trim();
            if (NUMERIC_TEXT_PATTERN.matcher(trimmed).matches()) {
                try {
                    return Optional.of(new BigDecimal(trimmed));
                } catch (NumberFormatException ignored) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static String extractRawMessage(JsonNode node, String rawBody) {
        if (node != null && node.isObject()) {
            for (String field : new String[]{"message", "error", "msg", "detail"}) {
                JsonNode n = node.get(field);
                if (n != null && !n.isNull()) {
                    return n.isTextual() ? n.asText() : n.toString();
                }
            }
        }
        return rawBody != null && !rawBody.isBlank() ? rawBody.trim() : null;
    }

    private static String extractErrorCode(JsonNode node, String rawBody) {
        if (node != null && node.isObject()) {
            for (String field : new String[]{"errorCode", "error_code", "code"}) {
                JsonNode n = node.get(field);
                if (n != null && !n.isNull() && !n.asText().isBlank()) {
                    return n.asText().trim().toUpperCase(Locale.ROOT);
                }
            }
        }
        if (rawBody != null && !rawBody.isBlank()) {
            Matcher m = ERR_CODE_PATTERN.matcher(rawBody);
            if (m.find()) {
                return m.group(1).toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static boolean isSessionExpiredMessage(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("session expired")
                || lower.contains("invalid session")
                || lower.contains("invalid token")
                || lower.contains("token expired")
                || lower.contains("session closed");
    }

    private static boolean isInsufficientFundsMessage(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("insufficient") || lower.contains("not enough") || lower.contains("low balance");
    }

    static Object parseGidValue(String rawGid, String defaultGid) {
        String candidate = (rawGid != null && !rawGid.isBlank()) ? rawGid.trim()
                : (defaultGid != null && !defaultGid.isBlank() ? defaultGid.trim() : null);
        if (candidate == null) {
            return null;
        }
        if (candidate.matches("\\d{1,18}")) {
            try {
                return Long.parseLong(candidate);
            } catch (NumberFormatException ignored) {
                return candidate;
            }
        }
        return candidate;
    }

    private static BigDecimal formatJsonNumber(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        BigDecimal stripped = scaled.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.UNNECESSARY) : stripped;
    }

    private String encodeJson(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body);
        } catch (IOException e) {
            throw new OperatorWalletException("Could not encode Operations request: " + e.getMessage(), false);
        }
    }

    private void sleepBeforeRetry(int attempt) {
        long delay = retryDelayBaseMs * attempt;
        if (delay <= 0L) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperatorWalletException("Interrupted while retrying Operations call", true);
        }
    }

    private static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "https://operations.dpbossking.com";
        }
        String trimmed = raw.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
