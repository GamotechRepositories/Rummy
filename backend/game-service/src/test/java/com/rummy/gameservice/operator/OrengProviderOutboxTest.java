package com.rummy.gameservice.operator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rummy.gameservice.persistence.document.WalletTransactionDocument;
import com.rummy.gameservice.security.JwtService;
import com.rummy.gameservice.wallet.InsufficientBalanceException;
import com.rummy.gameservice.wallet.WalletService;
import in.oreng.gamesdk.ProviderSDKException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrengProviderOutboxTest {

    private static final class StubOrengProvider extends OrengProviderService {
        boolean configured = true;
        RuntimeException nextDebitError;
        RuntimeException nextCreditError;
        JsonObject debitResponse;
        JsonObject creditResponse;
        String lastCreditToken;
        final AtomicInteger creditCalls = new AtomicInteger();

        StubOrengProvider() {
            super("https://api.example.com/api/v1", "", "RUMMY");
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public Optional<BigDecimal> getBalance(String sessionToken) {
            return Optional.empty();
        }

        @Override
        public Optional<JsonObject> debit(String sessionToken, BigDecimal amount, String transactionId, String roundId, String tableId) {
            if (nextDebitError != null) {
                RuntimeException ex = nextDebitError;
                nextDebitError = null;
                throw ex;
            }
            return Optional.ofNullable(debitResponse);
        }

        @Override
        public Optional<JsonObject> credit(String sessionToken, BigDecimal amount, String transactionId, String roundId, String tableId) {
            creditCalls.incrementAndGet();
            lastCreditToken = sessionToken;
            if (nextCreditError != null) {
                RuntimeException ex = nextCreditError;
                nextCreditError = null;
                throw ex;
            }
            return Optional.ofNullable(creditResponse);
        }
    }

    @Test
    @DisplayName("extractBalance handles both top-level and nested data.balance JSON payloads")
    void extractBalanceParsesFormats() {
        JsonObject direct = JsonParser.parseString("{\"balance\": 512.50}").getAsJsonObject();
        JsonObject nested = JsonParser.parseString("{\"data\": {\"balance\": \"840.25\"}}").getAsJsonObject();

        assertThat(OrengProviderService.extractBalance(direct)).contains(new BigDecimal("512.50"));
        assertThat(OrengProviderService.extractBalance(nested)).contains(new BigDecimal("840.25"));
        assertThat(OrengProviderService.extractBalance(null)).isEmpty();
    }

    @Test
    @DisplayName("Failed Oreng credit queues PROVIDER_CREDIT in outbox and retries to completion with balance sync")
    void failedProviderCreditIsQueuedAndRetried() {
        OperatorRegistry registry = OperatorRegistry.inMemory();
        OperatorRegistry.PlayerRef player = registry.registerPlayer("AAKDA-001", "EXT_99");
        JwtService jwt = new JwtService("SecretSigningKeyForTestingPurposesMustBeAtLeast32BytesLong!", 1);
        OperatorLaunchService launches = new OperatorLaunchService(registry, jwt, null, "http://localhost:5173", 120, 12);
        launches.saveProviderSessionToken(player.playerId(), "AAKDA-001", "POINTS", "sess_tok_live_1");

        StubOrengProvider oreng = new StubOrengProvider();
        oreng.nextCreditError = new ProviderSDKException("Temporary gateway timeout", 502, null);

        OperatorWalletOutbox outbox = new OperatorWalletOutbox(null);
        outbox.setOrengProvider(oreng);
        outbox.setOperatorLaunches(launches);

        WalletService wallet = new WalletService();
        wallet.setOperatorWallet(registry, null, outbox);
        wallet.setOrengProvider(oreng);
        wallet.setOperatorLaunches(launches);

        WalletTransactionDocument queuedTx = wallet.credit(
                player.playerId(),
                new BigDecimal("85.00"),
                "GAME_WIN",
                "WIN_G_RETRY_1",
                "G_RETRY_1",
                "Points win payout",
                null
        );

        assertThat(queuedTx.getMetadata()).containsEntry("operatorStatus", "QUEUED");
        assertThat(outbox.isPending(OperatorWalletOutbox.Kind.PROVIDER_CREDIT, "WIN_G_RETRY_1")).isTrue();
        assertThat(oreng.creditCalls.get()).isEqualTo(1);

        // Provider recovers and returns updated balance
        oreng.creditResponse = JsonParser.parseString("{\"data\": {\"balance\": \"1085.00\"}}").getAsJsonObject();
        int delivered = outbox.deliverDue(Instant.now().plusSeconds(10));

        assertThat(delivered).isEqualTo(1);
        assertThat(oreng.creditCalls.get()).isEqualTo(2);
        assertThat(oreng.lastCreditToken).isEqualTo("sess_tok_live_1");
        assertThat(outbox.isPending(OperatorWalletOutbox.Kind.PROVIDER_CREDIT, "WIN_G_RETRY_1")).isFalse();
        assertThat(wallet.getOrCreateWallet(player.playerId()).getBalance()).isEqualByComparingTo("1085.00");
    }

    @Test
    @DisplayName("Oreng debit insufficient funds (402 or null-status message) maps to InsufficientBalanceException")
    void providerInsufficientBalanceMapsCleanly() {
        OperatorRegistry registry = OperatorRegistry.inMemory();
        OperatorRegistry.PlayerRef player = registry.registerPlayer("AAKDA-001", "EXT_LOW");
        JwtService jwt = new JwtService("SecretSigningKeyForTestingPurposesMustBeAtLeast32BytesLong!", 1);
        OperatorLaunchService launches = new OperatorLaunchService(registry, jwt, null, "http://localhost:5173", 120, 12);
        launches.saveProviderSessionToken(player.playerId(), "AAKDA-001", "POOL", "sess_tok_low");

        StubOrengProvider oreng = new StubOrengProvider();
        OperatorWalletOutbox outbox = new OperatorWalletOutbox(null);

        WalletService wallet = new WalletService();
        wallet.setOperatorWallet(registry, null, outbox);
        wallet.setOrengProvider(oreng);
        wallet.setOperatorLaunches(launches);

        oreng.nextDebitError = new ProviderSDKException("Insufficient funds", null, null);
        assertThatThrownBy(() -> wallet.debit(player.playerId(), new BigDecimal("50.00"), "GAME_ENTRY", "DEBIT_LOW_1", "G1", "Entry", null))
                .isInstanceOf(InsufficientBalanceException.class);

        oreng.nextDebitError = new ProviderSDKException("Payment required", 402, null);
        assertThatThrownBy(() -> wallet.debit(player.playerId(), new BigDecimal("50.00"), "GAME_ENTRY", "DEBIT_LOW_2", "G1", "Entry", null))
                .isInstanceOf(InsufficientBalanceException.class);
    }
}
