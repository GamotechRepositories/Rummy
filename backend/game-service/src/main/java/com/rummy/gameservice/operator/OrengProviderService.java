package com.rummy.gameservice.operator;

import com.google.gson.JsonObject;
import in.oreng.gamesdk.ProviderGameServerSDK;
import in.oreng.gamesdk.ProviderSDKException;
import in.oreng.gamesdk.WalletOperationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Oreng / Gamotech Provider Game Server SDK integration.
 * Connects directly to the aggregator / provider gateway (e.g. api.dpbossking.com).
 */
@Service
public class OrengProviderService {

    private static final Logger log = LoggerFactory.getLogger(OrengProviderService.class);

    private final String apiUrl;
    private final String gameServerKey;
    private final String gameCode;
    private final ProviderGameServerSDK sdk;

    public OrengProviderService(
            @Value("${rummy.provider.api-url:${PROVIDER_API_URL:https://api.dpbossking.com/api/v1}}") String apiUrl,
            @Value("${rummy.provider.game-server-api-key:${GAME_SERVER_API_KEY:}}") String gameServerKey,
            @Value("${rummy.provider.game-code:${GAME_CODE:RUMMY}}") String gameCode) {
        this.apiUrl = apiUrl != null && !apiUrl.isBlank() ? apiUrl : "https://api.dpbossking.com/api/v1";
        this.gameServerKey = gameServerKey != null ? gameServerKey.trim() : "";
        this.gameCode = gameCode != null ? gameCode.trim() : "RUMMY";

        if (!this.gameServerKey.isBlank()) {
            this.sdk = new ProviderGameServerSDK(this.apiUrl, this.gameServerKey);
            log.info("[OrengProvider] Initialized ProviderGameServerSDK for apiUrl={}, gameCode={}", this.apiUrl, this.gameCode);
        } else {
            this.sdk = null;
            log.info("[OrengProvider] GAME_SERVER_API_KEY not configured. ProviderGameServerSDK in standby.");
        }
    }

    public boolean isConfigured() {
        return sdk != null;
    }

    public String getGameCode() {
        return gameCode;
    }

    public Optional<JsonObject> getPlayerProfile(String sessionToken) {
        if (sdk == null || sessionToken == null || sessionToken.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonObject profile = sdk.getPlayerProfile(sessionToken);
            return Optional.ofNullable(profile);
        } catch (ProviderSDKException e) {
            log.warn("[OrengProvider] getPlayerProfile failed for token: {} (status: {})", e.getMessage(), e.getStatus());
            return Optional.empty();
        } catch (Exception e) {
            log.error("[OrengProvider] getPlayerProfile error: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<BigDecimal> getBalance(String sessionToken) {
        if (sdk == null || sessionToken == null || sessionToken.isBlank()) {
            return Optional.empty();
        }
        try {
            return extractBalance(sdk.getBalance(sessionToken));
        } catch (ProviderSDKException e) {
            log.warn("[OrengProvider] getBalance failed: {} (status: {})", e.getMessage(), e.getStatus());
            return Optional.empty();
        } catch (Exception e) {
            log.error("[OrengProvider] getBalance unexpected error: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Extracts a balance field from either top-level {@code balance} or nested {@code data.balance}. */
    public static Optional<BigDecimal> extractBalance(JsonObject resp) {
        if (resp == null) {
            return Optional.empty();
        }
        try {
            if (resp.has("balance") && !resp.get("balance").isJsonNull()) {
                return Optional.of(new BigDecimal(resp.get("balance").getAsString()));
            }
            if (resp.has("data") && resp.get("data").isJsonObject()) {
                JsonObject data = resp.getAsJsonObject("data");
                if (data.has("balance") && !data.get("balance").isJsonNull()) {
                    return Optional.of(new BigDecimal(data.get("balance").getAsString()));
                }
            }
        } catch (Exception ignored) {
            // Unparseable optional balance field; operation status itself remains valid.
        }
        return Optional.empty();
    }

    public Optional<JsonObject> debit(String sessionToken, BigDecimal amount, String transactionId, String roundId, String tableId) {
        if (sdk == null) {
            return Optional.empty();
        }
        try {
            WalletOperationRequest req = WalletOperationRequest.builder()
                    .sessionToken(sessionToken)
                    .amount(amount)
                    .transactionId(transactionId)
                    .roundId(roundId != null ? roundId : "")
                    .tableId(tableId != null ? tableId : "")
                    .build();
            JsonObject result = sdk.debit(req);
            log.info("[OrengProvider] Debit successful for txId={}, amount={}", transactionId, amount);
            return Optional.ofNullable(result);
        } catch (ProviderSDKException e) {
            log.warn("[OrengProvider] Debit failed for txId={}: {} (status: {})", transactionId, e.getMessage(), e.getStatus());
            throw e;
        }
    }

    public Optional<JsonObject> credit(String sessionToken, BigDecimal amount, String transactionId, String roundId, String tableId) {
        if (sdk == null) {
            return Optional.empty();
        }
        try {
            WalletOperationRequest req = WalletOperationRequest.builder()
                    .sessionToken(sessionToken)
                    .amount(amount)
                    .transactionId(transactionId)
                    .roundId(roundId != null ? roundId : "")
                    .tableId(tableId != null ? tableId : "")
                    .build();
            JsonObject result = sdk.credit(req);
            log.info("[OrengProvider] Credit successful for txId={}, amount={}", transactionId, amount);
            return Optional.ofNullable(result);
        } catch (ProviderSDKException e) {
            log.warn("[OrengProvider] Credit failed for txId={}: {} (status: {})", transactionId, e.getMessage(), e.getStatus());
            throw e;
        }
    }
}
