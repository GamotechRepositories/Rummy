package com.rummy.gameservice.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Calls an operator's seamless-wallet API. Every request is signed ({@link RequestSignature}); debits and
 * credits carry our transaction id, which the operator must treat as an idempotency key.
 */
@Component
public class OperatorWalletClient {

    private static final Logger log = LoggerFactory.getLogger(OperatorWalletClient.class);
    public static final String CURRENCY = "INR";

    public enum Status { OK, INSUFFICIENT_FUNDS, PLAYER_NOT_FOUND, PLAYER_BLOCKED, REJECTED }

    public record Reply(Status status, BigDecimal balance) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    private final OperatorRegistry registry;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration timeout;
    /** Operators served by this process itself; reached locally because peers may listen on other ports. */
    private final Map<String, String> localWalletUrls = new ConcurrentHashMap<>();

    @Autowired
    public OperatorWalletClient(OperatorRegistry registry,
                                ObjectMapper json,
                                @Value("${rummy.operator.wallet-timeout-ms:5000}") long timeoutMs) {
        this.registry = Objects.requireNonNull(registry);
        this.json = Objects.requireNonNull(json);
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 3000)))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public void useLocalWalletUrl(String operatorId, String walletUrl) {
        localWalletUrls.put(operatorId, walletUrl);
    }

    public Reply balance(OperatorRegistry.PlayerRef player) {
        return call(player, "balance", body(player, null, null, null, null, null));
    }

    public Reply debit(OperatorRegistry.PlayerRef player, String transactionId, BigDecimal amount,
                       String gameId, String type, String description) {
        return withOneRetry(player, "debit", body(player, transactionId, amount, gameId, type, description));
    }

    public Reply credit(OperatorRegistry.PlayerRef player, String transactionId, BigDecimal amount,
                        String gameId, String type, String description) {
        return withOneRetry(player, "credit", body(player, transactionId, amount, gameId, type, description));
    }

    /** Asks the operator to undo a debit we are unsure about. The operator answers OK whether or not it saw the debit. */
    public Reply rollback(OperatorRegistry.PlayerRef player, String debitTransactionId, BigDecimal amount, String gameId) {
        return withOneRetry(player, "rollback", body(player, debitTransactionId, amount, gameId, "ROLLBACK", "Rollback of " + debitTransactionId));
    }

    private Reply withOneRetry(OperatorRegistry.PlayerRef player, String action, Map<String, Object> body) {
        try {
            return call(player, action, body);
        } catch (OperatorWalletException first) {
            if (!first.isUncertain()) {
                throw first;
            }
            log.warn("[OperatorWallet] {} {} for {} uncertain ({}); retrying once", action, body.get("transactionId"),
                    player.operatorId(), first.getMessage());
            return call(player, action, body);
        }
    }

    private Reply call(OperatorRegistry.PlayerRef player, String action, Map<String, Object> body) {
        OperatorDocument operator = registry.find(player.operatorId())
                .orElseThrow(() -> new OperatorWalletException("unknown operator " + player.operatorId(), false));
        String walletUrl = localWalletUrls.getOrDefault(operator.getId(), operator.getWalletUrl());
        if (walletUrl == null) {
            throw new OperatorWalletException("operator " + operator.getId() + " has no wallet URL", false);
        }
        String payload;
        try {
            payload = json.writeValueAsString(body);
        } catch (IOException e) {
            throw new OperatorWalletException("could not encode request: " + e.getMessage(), false);
        }
        String timestamp = RequestSignature.timestamp(Instant.now());
        HttpRequest request = HttpRequest.newBuilder(URI.create(walletUrl + "/" + action))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header(RequestSignature.OPERATOR_HEADER, operator.getId())
                .header(RequestSignature.TIMESTAMP_HEADER, timestamp)
                .header(RequestSignature.SIGNATURE_HEADER, RequestSignature.sign(operator.getSecret(), timestamp, payload))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            throw new OperatorWalletException(action + " to " + operator.getId() + ": connection refused", false);
        } catch (IOException e) {
            throw new OperatorWalletException(action + " to " + operator.getId() + " failed: " + e, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperatorWalletException(action + " to " + operator.getId() + " interrupted", true);
        }
        if (response.statusCode() >= 500) {
            throw new OperatorWalletException(action + " to " + operator.getId() + ": HTTP " + response.statusCode(), true);
        }
        return parse(operator.getId(), action, response);
    }

    private Reply parse(String operatorId, String action, HttpResponse<String> response) {
        JsonNode node;
        try {
            node = json.readTree(response.body());
        } catch (IOException e) {
            node = null;
        }
        if (node == null || !node.hasNonNull("status")) {
            throw new OperatorWalletException(action + " to " + operatorId + ": HTTP " + response.statusCode()
                    + " without a status", response.statusCode() < 400);
        }
        Status status;
        try {
            status = Status.valueOf(node.get("status").asText().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            status = Status.REJECTED;
        }
        BigDecimal balance = null;
        if (node.hasNonNull("balance")) {
            try {
                balance = new BigDecimal(node.get("balance").asText());
            } catch (NumberFormatException e) {
                log.warn("[OperatorWallet] {} sent an unreadable balance: {}", operatorId, node.get("balance"));
            }
        }
        return new Reply(status, balance);
    }

    private static Map<String, Object> body(OperatorRegistry.PlayerRef player, String transactionId, BigDecimal amount,
                                            String gameId, String type, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (transactionId != null) body.put("transactionId", transactionId);
        body.put("playerId", player.externalId());
        if (amount != null) body.put("amount", amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
        body.put("currency", CURRENCY);
        if (gameId != null) body.put("gameId", gameId);
        if (type != null) body.put("type", type);
        if (description != null) body.put("description", description);
        return body;
    }
}
