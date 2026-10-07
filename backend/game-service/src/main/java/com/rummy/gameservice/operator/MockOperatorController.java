package com.rummy.gameservice.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * A test operator, so the game can be played without a real partner, locally or on a live server:
 * open {@code /} on the API host to launch as any test player with ₹10,000 of test money.
 *
 * <p>Balances live in MongoDB (shared by every node, kept across restarts). Test players are matched only
 * with each other, never with real-money players. When {@code rummy.operator.mock.access-key} is set, the
 * launch and cashier pages need it; {@code require-key} (on in production) refuses to start without one.
 */
@RestController
@ConditionalOnProperty(name = "rummy.operator.mock.enabled", havingValue = "true")
public class MockOperatorController {

    private static final Logger log = LoggerFactory.getLogger(MockOperatorController.class);
    public static final String OPERATOR_ID = "mock";
    static final String WALLETS = "mock_operator_wallets";
    static final String TRANSACTIONS = "mock_operator_tx";
    private static final long STARTING_BALANCE_PAISE = 1_000_000L;
    private static final long TOP_UP_PAISE = 100_000L;

    private final OperatorRegistry registry;
    private final OperatorLaunchService launches;
    private final OperatorWalletClient walletClient;
    private final MongoTemplate mongo;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String accessKey;
    private volatile String secret;

    public MockOperatorController(OperatorRegistry registry,
                                  OperatorLaunchService launches,
                                  OperatorWalletClient walletClient,
                                  MongoTemplate mongo,
                                  ObjectMapper objectMapper,
                                  @Value("${rummy.operator.mock.base-url:http://localhost:${server.port:8081}}") String baseUrl,
                                  @Value("${rummy.operator.mock.access-key:}") String accessKey,
                                  @Value("${rummy.operator.mock.require-key:false}") boolean requireKey) {
        this.accessKey = accessKey == null ? "" : accessKey.trim();
        if (requireKey && this.accessKey.length() < 12) {
            throw new IllegalStateException("The test operator is enabled but RUMMY_MOCK_OPERATOR_KEY is missing or shorter than 12 characters");
        }
        this.registry = registry;
        this.launches = launches;
        this.walletClient = walletClient;
        this.mongo = mongo;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        saveOperator();
        log.warn("[MockOperator] ENABLED - test money only{}. Open {}/",
                accessKey.isEmpty() ? "" : " (access key required)", baseUrl);
    }

    /** Re-creates the operator if its document was deleted while the server was running. */
    private void ensureRegistered() {
        if (registry.find(OPERATOR_ID).isEmpty()) {
            saveOperator();
            log.warn("[MockOperator] Operator document was missing; registered it again");
        }
    }

    private synchronized void saveOperator() {
        OperatorDocument doc = registry.find(OPERATOR_ID).orElse(null);
        Instant now = Instant.now();
        if (doc == null) {
            doc = new OperatorDocument();
            doc.setId(OPERATOR_ID);
            doc.setCreatedAt(now);
        }
        // Every node shares the stored secret, so wallet calls verify on whichever node serves them.
        if (doc.getSecret() == null || doc.getSecret().isBlank()) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            doc.setSecret(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        }
        String walletUrl = baseUrl + "/mock-operator/wallet";
        doc.setName("Test operator (test money)");
        doc.setWalletUrl(walletUrl);
        doc.setCashierUrl(baseUrl + "/");
        doc.setEnabled(true);
        doc.setUpdatedAt(now);
        registry.save(doc);
        secret = doc.getSecret();
        walletClient.useLocalWalletUrl(OPERATOR_ID, walletUrl);
    }

    @GetMapping(value = {"/", "/mock-operator", "/mock-operator/"}, produces = MediaType.TEXT_HTML_VALUE)
    public String home(@RequestParam(required = false) String key) {
        if (!keyOk(key)) {
            return page("<section class='card'><h2>Access key</h2>"
                    + "<p class='muted'>This server needs the test access key.</p>"
                    + "<form class='row' method='get' action='/'>"
                    + "<input name='key' type='password' placeholder='Test access key' required autofocus>"
                    + "<button class='btn gold'>Open</button></form>"
                    + (key != null ? "<p class='error'>That key is not right.</p>" : "")
                    + "</section>");
        }
        ensureRegistered();
        String hiddenKey = hidden("key", key);
        StringBuilder rows = new StringBuilder();
        int count = 0;
        for (Document w : mongo.find(new Query().with(Sort.by("_id")).limit(200), Document.class, WALLETS)) {
            String player = w.getString("_id");
            String safe = HtmlUtils.htmlEscape(player);
            count++;
            rows.append("<li class='player'><span class='avatar'>").append(HtmlUtils.htmlEscape(player.substring(0, 1).toUpperCase()))
                    .append("</span><span class='who'><b>").append(safe).append("</b><small>₹").append(rupees(paise(w)))
                    .append(" test money</small></span><span class='actions'>")
                    .append("<form method='post' action='/cashier/top-up'>").append(hiddenKey).append(hidden("player", player))
                    .append("<button class='btn ghost' title='Add test money'>+ ₹").append(rupees(TOP_UP_PAISE).replace(".00", ""))
                    .append("</button></form>")
                    .append("<form method='get' action='/play'>").append(hiddenKey).append(hidden("player", player))
                    .append("<button class='btn gold'>Play</button></form>")
                    .append("</span></li>");
        }
        String list = count == 0
                ? "<p class='muted empty'>No test players yet. Create one above.</p>"
                : "<ul class='players'>" + rows + "</ul>";
        return page("<section class='card'><h2>New player</h2>"
                + "<form class='grid' method='get' action='/play'>" + hiddenKey
                + "<label>Player id<input name='player' pattern='[A-Za-z0-9_.@:-]{1,64}' placeholder='Leave empty for a new one' autofocus></label>"
                + "<label>Display name<input name='name' maxlength='20' placeholder='Optional'></label>"
                + "<button class='btn gold'>Play</button></form>"
                + "<p class='muted'>Use the same player id to come back as that player. New players start with ₹" + rupees(STARTING_BALANCE_PAISE).replace(".00", "")
                + " of test money. Use a second browser or a private window to play as another player at the same table.</p></section>"
                + "<section class='card'><h2>Test players <span class='count'>" + count + "</span></h2>" + list + "</section>");
    }

    @GetMapping({"/play", "/mock-operator/play"})
    public ResponseEntity<?> play(@RequestParam(required = false) String player,
                                  @RequestParam(required = false) String name,
                                  @RequestParam(required = false) String key) {
        if (!keyOk(key)) {
            return ResponseEntity.notFound().build();
        }
        ensureRegistered();
        if (player == null || player.isBlank()) {
            player = "tester" + (1000 + new SecureRandom().nextInt(9000));
        }
        player = player.trim();
        try {
            String displayName = name != null && !name.isBlank() ? name : player;
            OperatorLaunchService.Launch launch = launches.launch(OPERATOR_ID, player, displayName);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(launch.launchUrl())).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(e.getMessage());
        }
    }

    @GetMapping({"/cashier", "/mock-operator/cashier"})
    public ResponseEntity<Void> cashier(@RequestParam(required = false) String key) {
        return redirectHome(key);
    }

    @PostMapping({"/cashier/top-up", "/mock-operator/cashier/top-up"})
    public ResponseEntity<Void> topUp(@RequestParam String player, @RequestParam(required = false) String key) {
        if (!keyOk(key)) {
            return ResponseEntity.notFound().build();
        }
        mongo.upsert(byId(player), new Update().inc("balancePaise", TOP_UP_PAISE).setOnInsert("createdAt", new Date()), WALLETS);
        return redirectHome(key);
    }

    @PostMapping("/mock-operator/wallet/{action}")
    public ResponseEntity<Map<String, Object>> wallet(@PathVariable String action, HttpServletRequest request,
                                                      @RequestBody byte[] rawBody) {
        String body = new String(rawBody, StandardCharsets.UTF_8);
        String currentSecret = secret;
        if (currentSecret == null
                || !OPERATOR_ID.equals(request.getHeader(RequestSignature.OPERATOR_HEADER))
                || !RequestSignature.verify(currentSecret, request.getHeader(RequestSignature.TIMESTAMP_HEADER), body,
                request.getHeader(RequestSignature.SIGNATURE_HEADER), Instant.now())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "REJECTED"));
        }
        JsonNode json;
        long amount;
        try {
            json = objectMapper.readTree(body);
            amount = json.hasNonNull("amount")
                    ? new BigDecimal(json.get("amount").asText()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
                    : 0L;
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "REJECTED"));
        }
        String player = json.path("playerId").asText();
        String txId = json.path("transactionId").asText(null);
        if (player.isEmpty() || amount < 0) {
            return ResponseEntity.badRequest().body(Map.of("status", "REJECTED"));
        }
        mongo.upsert(byId(player), new Update().setOnInsert("balancePaise", STARTING_BALANCE_PAISE)
                .setOnInsert("createdAt", new Date()), WALLETS);

        return switch (action) {
            case "balance" -> reply("OK", player);
            case "debit" -> debit(player, txId, amount);
            case "credit" -> credit(player, txId, amount);
            case "rollback" -> rollback(player, txId);
            default -> ResponseEntity.notFound().build();
        };
    }

    private ResponseEntity<Map<String, Object>> debit(String player, String txId, long amount) {
        if (txId == null) {
            return reply("REJECTED", player);
        }
        try {
            mongo.insert(transaction(txId, "debit", player, amount), TRANSACTIONS);
        } catch (DuplicateKeyException e) {
            Document prior = mongo.findById(txId, Document.class, TRANSACTIONS);
            boolean applied = prior != null && "debit".equals(prior.getString("kind")) && !Boolean.TRUE.equals(prior.get("rolledBack"));
            return reply(applied ? "OK" : "REJECTED", player);
        }
        Document charged = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(player).and("balancePaise").gte(amount)),
                new Update().inc("balancePaise", -amount), Document.class, WALLETS);
        if (charged == null) {
            mongo.remove(byId(txId), TRANSACTIONS);
            return reply("INSUFFICIENT_FUNDS", player);
        }
        return reply("OK", player);
    }

    private ResponseEntity<Map<String, Object>> credit(String player, String txId, long amount) {
        if (txId == null) {
            return reply("REJECTED", player);
        }
        try {
            mongo.insert(transaction(txId, "credit", player, amount), TRANSACTIONS);
        } catch (DuplicateKeyException e) {
            return reply("OK", player);
        }
        mongo.updateFirst(byId(player), new Update().inc("balancePaise", amount), WALLETS);
        return reply("OK", player);
    }

    /** Undoes a debit once; a rollback that arrives first blocks the debit from ever applying. */
    private ResponseEntity<Map<String, Object>> rollback(String player, String txId) {
        if (txId == null) {
            return reply("REJECTED", player);
        }
        Document prior = mongo.findAndModify(Query.query(Criteria.where("_id").is(txId).and("rolledBack").ne(true)),
                new Update().set("rolledBack", true), Document.class, TRANSACTIONS);
        if (prior == null) {
            try {
                mongo.insert(transaction(txId, "rollback", player, 0L).append("rolledBack", true), TRANSACTIONS);
            } catch (DuplicateKeyException ignored) {
                // Already rolled back.
            }
        } else if ("debit".equals(prior.getString("kind"))) {
            mongo.updateFirst(byId(prior.getString("player")),
                    new Update().inc("balancePaise", ((Number) prior.get("paise")).longValue()), WALLETS);
        }
        return reply("OK", player);
    }

    private ResponseEntity<Map<String, Object>> reply(String status, String player) {
        Document wallet = mongo.findById(player, Document.class, WALLETS);
        return ResponseEntity.ok(Map.of("status", status, "balance", rupees(wallet == null ? 0L : paise(wallet))));
    }

    private boolean keyOk(String key) {
        if (accessKey.isEmpty()) {
            return true;
        }
        return key != null && MessageDigest.isEqual(accessKey.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<Void> redirectHome(String key) {
        String location = "/" + (key != null && !key.isEmpty() ? "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8) : "");
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, location).build();
    }

    private static Document transaction(String txId, String kind, String player, long paise) {
        return new Document("_id", txId).append("kind", kind).append("player", player).append("paise", paise)
                .append("createdAt", new Date());
    }

    private static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(id));
    }

    private static long paise(Document wallet) {
        Object v = wallet.get("balancePaise");
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static String rupees(long paise) {
        return BigDecimal.valueOf(paise, 2).toPlainString();
    }

    private static String hidden(String name, String value) {
        return value == null ? "" : "<input type='hidden' name='" + name + "' value='" + HtmlUtils.htmlEscape(value) + "'>";
    }

    private static final String STYLE = """
            *{box-sizing:border-box}
            body{margin:0;min-height:100vh;font-family:'Segoe UI',system-ui,-apple-system,sans-serif;color:#f8fafc;
              background:radial-gradient(circle at 20% 0%,#3b1d5c 0%,transparent 45%),radial-gradient(circle at 90% 100%,#0f3d2e 0%,transparent 40%),#0b0f1a}
            main{max-width:640px;margin:0 auto;padding:40px 16px 56px}
            header{text-align:center;margin-bottom:28px}
            .crown{font-size:40px;line-height:1}
            h1{margin:6px 0 8px;font-size:28px;letter-spacing:.5px;background:linear-gradient(90deg,#fde68a,#f59e0b,#fde68a);
              -webkit-background-clip:text;background-clip:text;color:transparent}
            .badge{display:inline-block;padding:4px 12px;border-radius:999px;font-size:12px;font-weight:700;letter-spacing:1px;
              color:#fbbf24;border:1px solid rgba(251,191,36,.45);background:rgba(251,191,36,.08)}
            .card{background:rgba(17,24,39,.78);border:1px solid rgba(251,191,36,.18);border-radius:16px;padding:22px;margin-bottom:18px;
              box-shadow:0 10px 30px rgba(0,0,0,.35);backdrop-filter:blur(6px)}
            h2{margin:0 0 14px;font-size:17px;color:#fde68a;display:flex;align-items:center;gap:8px}
            .count{font-size:12px;color:#0b0f1a;background:#fbbf24;border-radius:999px;padding:1px 9px}
            .grid{display:grid;grid-template-columns:1fr 1fr auto;gap:12px;align-items:end}
            .row{display:flex;gap:12px}
            label{display:flex;flex-direction:column;gap:6px;font-size:13px;color:#cbd5e1}
            input{width:100%;padding:11px 12px;border-radius:10px;border:1px solid #334155;background:#0f172a;color:#f8fafc;font-size:15px;outline:none}
            input:focus{border-color:#fbbf24;box-shadow:0 0 0 3px rgba(251,191,36,.18)}
            .btn{padding:11px 20px;border-radius:10px;font-size:14px;font-weight:700;cursor:pointer;border:1px solid transparent;white-space:nowrap}
            .gold{color:#1f1300;background:linear-gradient(180deg,#fde68a,#f59e0b);box-shadow:0 4px 14px rgba(245,158,11,.3)}
            .gold:hover{filter:brightness(1.08)}
            .ghost{color:#fde68a;background:transparent;border-color:rgba(251,191,36,.4);padding:8px 14px}
            .ghost:hover{background:rgba(251,191,36,.1)}
            .muted{color:#94a3b8;font-size:13px;line-height:1.5;margin:14px 0 0}
            .empty{text-align:center;margin:6px 0}
            .error{color:#fca5a5;font-size:13px;margin:12px 0 0}
            .players{list-style:none;margin:0;padding:0}
            .player{display:flex;align-items:center;gap:12px;padding:12px 0;border-top:1px solid rgba(148,163,184,.12)}
            .player:first-child{border-top:0;padding-top:0}
            .avatar{width:40px;height:40px;flex:none;border-radius:50%;display:grid;place-items:center;font-weight:800;color:#1f1300;
              background:linear-gradient(135deg,#fde68a,#d97706)}
            .who{flex:1;min-width:0;display:flex;flex-direction:column}
            .who b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .who small{color:#86efac;font-size:13px;margin-top:2px}
            .actions{display:flex;gap:8px}
            .actions form{margin:0}
            .actions .gold{padding:8px 18px}
            footer{text-align:center;color:#64748b;font-size:12px;margin-top:24px}
            @media (max-width:560px){.grid{grid-template-columns:1fr}.player{flex-wrap:wrap}.actions{width:100%;justify-content:flex-end}}
            """;

    private static String page(String content) {
        return "<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Royal Rummy · Test login</title><style>" + STYLE + "</style></head><body><main>"
                + "<header><div class='crown'>👑</div><h1>Royal Rummy</h1><span class='badge'>TEST LOGIN · TEST MONEY</span></header>"
                + content
                + "<footer>Test players only play with other test players and bots. No real money is involved.</footer>"
                + "</main></body></html>";
    }
}
