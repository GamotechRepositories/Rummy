package com.rummy.gameservice.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.gameservice.persistence.document.WalletTransactionDocument;
import com.rummy.gameservice.security.JwtService;
import com.rummy.gameservice.wallet.InsufficientBalanceException;
import com.rummy.gameservice.wallet.WalletService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Operator seamless wallet and launch")
class OperatorWalletTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET_HOLDER = "op-test";

    /** A fake operator wallet that checks our signatures and can be told to fail. */
    private static final class FakeOperator {
        final HttpServer server;
        final Map<String, BigDecimal> balances = new HashMap<>();
        final Map<String, BigDecimal> debits = new HashMap<>();
        final Set<String> credits = new HashSet<>();
        final Set<String> rolledBack = new HashSet<>();
        final List<String> calls = new ArrayList<>();
        /** Next N calls fail with HTTP 500 before doing anything. */
        final AtomicInteger failBefore = new AtomicInteger();
        /** Next N calls are applied, then answered with HTTP 500 (we cannot tell they worked). */
        final AtomicInteger failAfter = new AtomicInteger();
        volatile String secret;

        FakeOperator() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/wallet/", this::handle);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/wallet";
        }

        private synchronized void handle(HttpExchange ex) throws IOException {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String action = ex.getRequestURI().getPath().substring("/wallet/".length());
            calls.add(action);
            if (!RequestSignature.verify(secret, ex.getRequestHeaders().getFirst(RequestSignature.TIMESTAMP_HEADER), body,
                    ex.getRequestHeaders().getFirst(RequestSignature.SIGNATURE_HEADER), Instant.now())) {
                send(ex, 401, "{\"status\":\"REJECTED\"}");
                return;
            }
            if (failBefore.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                send(ex, 500, "oops");
                return;
            }
            JsonNode json = JSON.readTree(body);
            String player = json.path("playerId").asText();
            String tx = json.path("transactionId").asText(null);
            BigDecimal amount = json.hasNonNull("amount") ? new BigDecimal(json.get("amount").asText()) : BigDecimal.ZERO;
            balances.putIfAbsent(player, new BigDecimal("500.00"));
            String status = "OK";
            switch (action) {
                case "debit" -> {
                    if (rolledBack.contains(tx)) {
                        status = "REJECTED";
                    } else if (!debits.containsKey(tx)) {
                        if (balances.get(player).compareTo(amount) < 0) {
                            status = "INSUFFICIENT_FUNDS";
                        } else {
                            balances.merge(player, amount.negate(), BigDecimal::add);
                            debits.put(tx, amount);
                        }
                    }
                }
                case "credit" -> {
                    if (credits.add(tx)) {
                        balances.merge(player, amount, BigDecimal::add);
                    }
                }
                case "rollback" -> {
                    if (rolledBack.add(tx) && debits.containsKey(tx)) {
                        balances.merge(player, debits.get(tx), BigDecimal::add);
                    }
                }
                default -> {
                }
            }
            if (failAfter.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                send(ex, 500, "oops");
                return;
            }
            send(ex, 200, "{\"status\":\"" + status + "\",\"balance\":\"" + balances.get(player).toPlainString() + "\"}");
        }

        private static void send(HttpExchange ex, int code, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }
    }

    private FakeOperator operator;
    private OperatorRegistry registry;
    private OperatorWalletOutbox outbox;
    private WalletService wallet;
    private String alice;

    @BeforeEach
    void setUp() throws IOException {
        operator = new FakeOperator();
        registry = OperatorRegistry.inMemory();
        OperatorDocument doc = registry.create(SECRET_HOLDER, "Test operator", operator.url(), "https://cashier.example.com");
        operator.secret = doc.getSecret();
        OperatorWalletClient client = new OperatorWalletClient(registry, JSON, 2000);
        outbox = new OperatorWalletOutbox(null, client);
        wallet = new WalletService();
        wallet.setOperatorWallet(registry, client, outbox);
        alice = registry.registerPlayer(SECRET_HOLDER, "alice-77").playerId();
    }

    @AfterEach
    void tearDown() {
        operator.server.stop(0);
    }

    @Test
    @DisplayName("Player ids are opaque and stable per operator")
    void opaquePlayerIds() {
        assertThat(alice).startsWith("P_").doesNotContain("alice");
        assertThat(OperatorRegistry.playerIdFor(SECRET_HOLDER, "alice-77")).isEqualTo(alice);
        assertThat(OperatorRegistry.playerIdFor("other-op", "alice-77")).isNotEqualTo(alice);
    }

    @Test
    @DisplayName("Stake and win move money in the operator wallet; our ledger mirrors it")
    void debitAndCreditGoToOperator() {
        WalletTransactionDocument stake = wallet.debit(alice, new BigDecimal("100"), "GAME_ENTRY_STAKE", "STAKE_1", null, "stake", null);
        assertThat(stake.getBalanceAfter()).isEqualByComparingTo("400");
        assertThat(operator.balances.get("alice-77")).isEqualByComparingTo("400");

        wallet.credit(alice, new BigDecimal("185"), "GAME_WIN", "WIN_G1_" + alice, "G1", "win", null);
        assertThat(operator.balances.get("alice-77")).isEqualByComparingTo("585");
        assertThat(wallet.balanceOf(alice)).isEqualByComparingTo("585");

        // Repeating a call with the same key changes nothing.
        wallet.credit(alice, new BigDecimal("185"), "GAME_WIN", "WIN_G1_" + alice, "G1", "win", null);
        assertThat(operator.balances.get("alice-77")).isEqualByComparingTo("585");
    }

    @Test
    @DisplayName("Operator says insufficient funds: the stake is refused")
    void insufficientFunds() {
        assertThatThrownBy(() -> wallet.debit(alice, new BigDecimal("900"), "GAME_ENTRY_STAKE", "STAKE_BIG", null, "stake", null))
                .isInstanceOf(InsufficientBalanceException.class);
        assertThat(wallet.hasTransaction("STAKE_BIG")).isFalse();
        assertThat(operator.balances.get("alice-77")).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("A failed debit is retried once with the same id and charged once")
    void debitRetriedOnce() {
        operator.failAfter.set(1);
        wallet.debit(alice, new BigDecimal("100"), "GAME_ENTRY_STAKE", "STAKE_R", null, "stake", null);
        assertThat(operator.balances.get("alice-77")).isEqualByComparingTo("400");
        assertThat(operator.calls).containsExactly("debit", "debit");
    }

    @Test
    @DisplayName("A debit we cannot confirm is refused and rolled back at the operator")
    void uncertainDebitRolledBack() {
        operator.failAfter.set(2);
        assertThatThrownBy(() -> wallet.debit(alice, new BigDecimal("100"), "GAME_ENTRY_STAKE", "STAKE_U", null, "stake", null))
                .isInstanceOf(OperatorWalletException.class);
        assertThat(wallet.hasTransaction("STAKE_U")).isFalse();
        assertThat(operator.balances.get("alice-77")).isEqualByComparingTo("400");
        assertThat(outbox.isPending(OperatorWalletOutbox.Kind.ROLLBACK, "STAKE_U")).isTrue();

        assertThat(outbox.deliverDue(Instant.now().plusSeconds(60))).isEqualTo(1);
        assertThat(operator.balances.get("alice-77")).isEqualByComparingTo("500");
        assertThat(outbox.isPending(OperatorWalletOutbox.Kind.ROLLBACK, "STAKE_U")).isFalse();
    }

    @Test
    @DisplayName("A win the operator cannot take now is queued and paid exactly once later")
    void failedCreditQueuedAndDelivered() {
        operator.failBefore.set(2);
        WalletTransactionDocument win = wallet.credit(alice, new BigDecimal("250"), "GAME_WIN", "WIN_G2_" + alice, "G2", "win", null);
        assertThat(win.getMetadata()).containsEntry("operatorStatus", "QUEUED");
        assertThat(operator.balances.getOrDefault("alice-77", new BigDecimal("500"))).isEqualByComparingTo("500");

        operator.failBefore.set(2);
        assertThat(outbox.deliverDue(Instant.now().plusSeconds(60))).isZero();
        assertThat(outbox.isPending(OperatorWalletOutbox.Kind.CREDIT, "WIN_G2_" + alice)).isTrue();

        assertThat(outbox.deliverDue(Instant.now().plusSeconds(3600))).isEqualTo(1);
        assertThat(operator.balances.get("alice-77")).isEqualByComparingTo("750");
        assertThat(outbox.deliverDue(Instant.now().plusSeconds(7200))).isZero();
        assertThat(operator.balances.get("alice-77")).isEqualByComparingTo("750");
    }

    @Test
    @DisplayName("Launch codes work once, carry the operator, and stop working when the operator is disabled")
    void launchCodes() {
        JwtService jwt = new JwtService("0123456789abcdef0123456789abcdef-test", 1);
        OperatorLaunchService launches = new OperatorLaunchService(registry, jwt, null, "https://play.example.com/", 120, 12);

        OperatorLaunchService.Launch launch = launches.launch(SECRET_HOLDER, "bob", "Bob <script>");
        assertThat(launch.launchUrl()).startsWith("https://play.example.com/?launch=");
        String code = launch.launchUrl().substring(launch.launchUrl().indexOf('=') + 1);

        OperatorLaunchService.Session session = launches.redeem(code).orElseThrow();
        assertThat(session.playerId()).isEqualTo(OperatorRegistry.playerIdFor(SECRET_HOLDER, "bob"));
        assertThat(session.displayName()).doesNotContain("<");
        assertThat(jwt.validateAndGetClaims(session.token()).orElseThrow().get(JwtService.OPERATOR_CLAIM, String.class))
                .isEqualTo(SECRET_HOLDER);
        assertThat(launches.redeem(code)).isEmpty();

        String second = launches.launch(SECRET_HOLDER, "bob", "Bob").launchUrl().split("=")[1];
        registry.update(SECRET_HOLDER, null, null, null, false);
        assertThat(launches.redeem(second)).isEmpty();
    }

    @Test
    @DisplayName("Request signatures: tampered body, wrong secret or stale timestamp are rejected")
    void signatures() {
        Instant now = Instant.now();
        String ts = RequestSignature.timestamp(now);
        String body = "{\"playerId\":\"bob\"}";
        String sig = RequestSignature.sign("secret", ts, body);
        assertThat(RequestSignature.verify("secret", ts, body, sig, now)).isTrue();
        assertThat(RequestSignature.verify("secret", ts, body.replace("bob", "eve"), sig, now)).isFalse();
        assertThat(RequestSignature.verify("other", ts, body, sig, now)).isFalse();
        assertThat(RequestSignature.verify("secret", ts, body, sig, now.plusSeconds(600))).isFalse();
    }
}
