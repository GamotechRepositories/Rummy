package com.rummy.gameservice.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.gameservice.persistence.document.WalletTransactionDocument;
import com.rummy.gameservice.security.JwtService;
import com.rummy.gameservice.wallet.InsufficientBalanceException;
import com.rummy.gameservice.wallet.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsWalletClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class StubOperationsClient extends OperationsWalletClient {
        record RecordedCall(String path, JsonNode body) {}

        final List<RecordedCall> calls = new ArrayList<>();
        final List<TransportResponse> scriptedResponses = new ArrayList<>();

        StubOperationsClient() {
            super("https://operations.dpbossking.com", "gt_int_test_secret_key", "1001", 5000, JSON);
            setRetryDelayBaseMs(0L);
        }

        void enqueue(int status, String body) {
            scriptedResponses.add(new TransportResponse(status, body));
        }

        @Override
        protected TransportResponse postJson(String path, String payload) {
            try {
                calls.add(new RecordedCall(path, JSON.readTree(payload)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (scriptedResponses.isEmpty()) {
                return new TransportResponse(200, "{\"status\":true,\"message\":1000.0}");
            }
            return scriptedResponses.remove(0);
        }
    }

    @Test
    @DisplayName("2051 getBalance parses authoritative Float message and detects ERR-0011 session invalid")
    void balanceParsesFloatMessageAndSessionInvalidCodes() {
        StubOperationsClient ops = new StubOperationsClient();
        ops.enqueue(200, "{\"status\":true,\"message\":1250.75}");
        ops.enqueue(400, "{\"status\":false,\"code\":\"ERR-0011\",\"message\":\"Session expired\"}");

        OperationsWalletClient.Reply okReply = ops.getBalance("tok_abc", "alpha-test");
        assertThat(okReply.ok()).isTrue();
        assertThat(okReply.balance()).isEqualByComparingTo("1250.75");
        assertThat(ops.calls.get(0).path()).isEqualTo("/api/v1/wallet/balance");
        assertThat(ops.calls.get(0).body().get("token").asText()).isEqualTo("tok_abc");
        assertThat(ops.calls.get(0).body().get("partner_code").asText()).isEqualTo("ALPHA-TEST");

        OperationsWalletClient.Reply errReply = ops.getBalance("tok_expired", "ALPHA-TEST");
        assertThat(errReply.ok()).isFalse();
        assertThat(errReply.sessionInvalid()).isTrue();
        assertThat(errReply.errorCode()).isEqualTo("ERR-0011");
    }

    @Test
    @DisplayName("2052 creditTransfer retries HTTP 502 / ERR-0020 with the exact same tranid")
    void creditTransferRetriesWithSameTranid() {
        StubOperationsClient ops = new StubOperationsClient();
        ops.enqueue(502, "Bad Gateway ERR-0020");
        ops.enqueue(200, "{\"status\":false,\"message\":\"ERR-0021 downstream timeout\"}");
        ops.enqueue(200, "{\"status\":true,\"message\":990.5}");

        OperationsWalletClient.Reply reply = ops.creditTransfer(new OperationsWalletClient.TransferRequest(
                "tok_live", "ALPHA-TEST", "U_77", "1001",
                new BigDecimal("10.00"), BigDecimal.ZERO, "spin", "TXN_RETRY_1", 1, "RND_1"));

        assertThat(reply.ok()).isTrue();
        assertThat(reply.balance()).isEqualByComparingTo("990.5");
        assertThat(ops.calls).hasSize(3);
        for (StubOperationsClient.RecordedCall call : ops.calls) {
            assertThat(call.path()).isEqualTo("/api/v1/wallet/credit-transfer");
            assertThat(call.body().get("tranid").asText()).isEqualTo("TXN_RETRY_1");
            assertThat(call.body().get("gid").asLong()).isEqualTo(1001L);
            assertThat(call.body().get("ttype").asInt()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("OperatorLaunchService + OperatorController redeems Operations launch query params and propagates ERR-0011 + close_url")
    void launchRedeemAndSessionInvalidHandling() {
        OperatorRegistry registry = OperatorRegistry.inMemory();
        JwtService jwt = new JwtService("SecretSigningKeyForTestingPurposesMustBeAtLeast32BytesLong!", 1);
        OperatorLaunchService launches = new OperatorLaunchService(registry, jwt, null, "http://localhost:5173", 120, 12);
        StubOperationsClient ops = new StubOperationsClient();
        launches.setOperationsClient(ops);

        WalletService wallet = new WalletService();
        OperatorController controller = new OperatorController(registry, launches, JSON);
        controller.setWalletService(wallet);

        ops.enqueue(200, "{\"status\":true,\"message\":5000.0}");
        ResponseEntity<Map<String, Object>> okRes = controller.session(Map.of(
                "token", "ops_tok_1",
                "partner_code", "alpha-test",
                "gid", "1001",
                "game_code", "RUMMY",
                "username", "Ravi Kumar",
                "user_id", "EXT_42",
                "currency", "INR",
                "lang", "en",
                "extsess", "ext_ref_9",
                "close_url", "https://lobby.partner.example/exit"
        ));

        assertThat(okRes.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = okRes.getBody();
        assertThat(body).isNotNull();
        String playerId = (String) body.get("playerId");
        assertThat(playerId).isNotBlank();
        assertThat(body.get("displayName")).isEqualTo("Ravi Kumar");
        assertThat((BigDecimal) body.get("balance")).isEqualByComparingTo("5000.0");
        assertThat(body.get("closeUrl")).isEqualTo("https://lobby.partner.example/exit");
        assertThat(wallet.getOrCreateWallet(playerId).getBalance()).isEqualByComparingTo("5000.0");

        ProviderSessionDocument saved = launches.getProviderSession(playerId).orElseThrow();
        assertThat(saved.getPartnerCode()).isEqualTo("ALPHA-TEST");
        assertThat(saved.getGid()).isEqualTo("1001");
        assertThat(saved.getUserId()).isEqualTo("EXT_42");
        assertThat(saved.getExtsess()).isEqualTo("ext_ref_9");
        assertThat(saved.getCloseUrl()).isEqualTo("https://lobby.partner.example/exit");

        // Expired token must return 401 SESSION_INVALID with closeUrl preserved
        ops.enqueue(400, "{\"status\":false,\"code\":\"ERR-0012\",\"message\":\"Token expired\"}");
        ResponseEntity<Map<String, Object>> errRes = controller.session(Map.of(
                "token", "ops_tok_expired",
                "partner_code", "ALPHA-TEST",
                "close_url", "https://lobby.partner.example/exit"
        ));
        assertThat(errRes.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(errRes.getBody()).containsEntry("error", "SESSION_INVALID")
                .containsEntry("errorCode", "ERR-0012")
                .containsEntry("closeUrl", "https://lobby.partner.example/exit");
    }

    @Test
    @DisplayName("WalletService maps debits, wins, aborted ttype=2 refunds, insufficient balance, and outbox retries cleanly")
    void walletServiceAndOutboxWithOperations() {
        OperatorRegistry registry = OperatorRegistry.inMemory();
        JwtService jwt = new JwtService("SecretSigningKeyForTestingPurposesMustBeAtLeast32BytesLong!", 1);
        OperatorLaunchService launches = new OperatorLaunchService(registry, jwt, null, "http://localhost:5173", 120, 12);
        StubOperationsClient ops = new StubOperationsClient();
        launches.setOperationsClient(ops);

        OperatorWalletOutbox outbox = new OperatorWalletOutbox(null);
        outbox.setOperationsClient(ops);
        outbox.setOperatorLaunches(launches);

        WalletService wallet = new WalletService();
        wallet.setOperatorWallet(registry, null, outbox);
        wallet.setOperationsClient(ops);
        wallet.setOperatorLaunches(launches);

        ops.enqueue(200, "{\"status\":true,\"message\":1000.0}");
        var redeemed = launches.redeemOperations(new OperatorLaunchService.OperationsLaunchRequest(
                "tok_game_1", "ALPHA-TEST", "1001", "RUMMY", "Alice", "EXT_ALICE", "INR", "en", null, "https://lobby.example/home"
        ));
        assertThat(redeemed.ok()).isTrue();
        String playerId = redeemed.session().playerId();

        // 1. Entry stake debit -> ttype=1, game_type="spin", bet=100, win=0
        ops.enqueue(200, "{\"status\":true,\"message\":900.0}");
        wallet.debit(playerId, new BigDecimal("100.00"), "GAME_ENTRY_STAKE", "STAKE_TKT_1", null,
                "Stake debit", Map.of("ticketId", "TKT_1"));
        var debitBody = ops.calls.get(1).body();
        assertThat(debitBody.get("ttype").asInt()).isEqualTo(1);
        assertThat(debitBody.get("game_type").asText()).isEqualTo("spin");
        assertThat(debitBody.get("bet").decimalValue()).isEqualByComparingTo("100");
        assertThat(debitBody.get("win").decimalValue()).isEqualByComparingTo("0");
        assertThat(debitBody.get("roundid").asText()).isEqualTo("TKT_1");
        assertThat(wallet.getOrCreateWallet(playerId).getBalance()).isEqualByComparingTo("900.0");

        // 2. Abort refund -> ttype=2, game_type="refund", bet=100, win=0, same original debit roundid
        ops.enqueue(200, "{\"status\":true,\"message\":1000.0}");
        wallet.credit(playerId, new BigDecimal("100.00"), "GAME_ABORT_REFUND", "ABORT_REFUND_1", "G_1",
                "Match aborted", Map.of("escrowId", "STAKE_TKT_1"));
        var refundBody = ops.calls.get(2).body();
        assertThat(refundBody.get("ttype").asInt()).isEqualTo(2);
        assertThat(refundBody.get("game_type").asText()).isEqualTo("refund");
        assertThat(refundBody.get("bet").decimalValue()).isEqualByComparingTo("100");
        assertThat(refundBody.get("win").decimalValue()).isEqualByComparingTo("0");
        assertThat(refundBody.get("roundid").asText()).isEqualTo("TKT_1");
        assertThat(wallet.getOrCreateWallet(playerId).getBalance()).isEqualByComparingTo("1000.0");

        // 3. Points partial unlost stake refund -> ttype=1, game_type="spin", bet=0, win=60
        ops.enqueue(200, "{\"status\":true,\"message\":1060.0}");
        wallet.credit(playerId, new BigDecimal("60.00"), "GAME_REFUND", "REFUND_G2_" + playerId, "G_2",
                "Unlost stake refund", Map.of("gameId", "G_2"));
        var partialBody = ops.calls.get(3).body();
        assertThat(partialBody.get("ttype").asInt()).isEqualTo(1);
        assertThat(partialBody.get("game_type").asText()).isEqualTo("spin");
        assertThat(partialBody.get("bet").decimalValue()).isEqualByComparingTo("0");
        assertThat(partialBody.get("win").decimalValue()).isEqualByComparingTo("60");

        // 4. Failed win credit (502 x3) -> queued in outbox with exact tranid, delivered on retry with authoritative balance sync
        ops.enqueue(502, "ERR-0020");
        ops.enqueue(502, "ERR-0020");
        ops.enqueue(502, "ERR-0020");
        WalletTransactionDocument queued = wallet.credit(
                playerId, new BigDecimal("170.00"), "GAME_WIN", "WIN_G3_" + playerId, "G_3", "Win payout", null);
        assertThat(queued.getMetadata()).containsEntry("operatorStatus", "QUEUED");
        assertThat(outbox.isPending(OperatorWalletOutbox.Kind.OPERATIONS_TRANSFER, "WIN_G3_" + playerId)).isTrue();

        ops.enqueue(200, "{\"status\":true,\"message\":1230.0}");
        int delivered = outbox.deliverDue(Instant.now().plusSeconds(10));
        assertThat(delivered).isEqualTo(1);
        assertThat(outbox.isPending(OperatorWalletOutbox.Kind.OPERATIONS_TRANSFER, "WIN_G3_" + playerId)).isFalse();
        assertThat(wallet.getOrCreateWallet(playerId).getBalance()).isEqualByComparingTo("1230.0");

        // 5. Business refusal: insufficient balance -> InsufficientBalanceException
        ops.enqueue(400, "{\"status\":false,\"message\":\"Insufficient balance\"}");
        assertThatThrownBy(() -> wallet.debit(playerId, new BigDecimal("5000.00"), "GAME_ENTRY_STAKE", "STAKE_LOW", "G_LOW", "Low", null))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    @DisplayName("OperatorLaunchService rejects unsafe close_url schemes and invalid partner_code/overlong token")
    void launchSanitizesCloseUrlAndValidatesParams() {
        OperatorRegistry registry = OperatorRegistry.inMemory();
        JwtService jwt = new JwtService("SecretSigningKeyForTestingPurposesMustBeAtLeast32BytesLong!", 1);
        OperatorLaunchService launches = new OperatorLaunchService(registry, jwt, null, "http://localhost:5173", 120, 12);
        StubOperationsClient ops = new StubOperationsClient();
        launches.setOperationsClient(ops);

        ops.enqueue(200, "{\"status\":true,\"message\":100.0}");
        var xssRes = launches.redeemOperations(new OperatorLaunchService.OperationsLaunchRequest(
                "tok_xss", "ALPHA-TEST", "1001", "RUMMY", "Ravi", "EXT_1", "INR", "en", null, "javascript:alert(1)"
        ));
        assertThat(xssRes.ok()).isTrue();
        assertThat(xssRes.session().closeUrl()).isNull();

        var badPartner = launches.redeemOperations(new OperatorLaunchService.OperationsLaunchRequest(
                "tok_bad", "BAD PARTNER!", "1001", "RUMMY", "Ravi", "EXT_1", "INR", "en", null, "https://lobby.example/back"
        ));
        assertThat(badPartner.ok()).isFalse();
        assertThat(badPartner.error()).isEqualTo("INVALID_LAUNCH");
        assertThat(badPartner.closeUrl()).isEqualTo("https://lobby.example/back");
    }
}
