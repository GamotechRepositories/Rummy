package com.rummy.gameservice.operator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Delivers operator / Operations wallet calls that must not be lost: credits (winnings, refunds),
 * failed Operations 2052 transfers, and rollbacks of uncertain debits. Each entry is retried with
 * backoff until accepted, always reusing the exact same {@code transactionId} / {@code tranid} so
 * settlements and refunds remain idempotent even across late payouts after session closure.
 */
@Service
public class OperatorWalletOutbox {

    private static final Logger log = LoggerFactory.getLogger(OperatorWalletOutbox.class);

    public enum Kind { CREDIT, ROLLBACK, OPERATIONS_TRANSFER }

    private static final String PENDING = "PENDING";
    private static final String DONE = "DONE";
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final int ALERT_AFTER_ATTEMPTS = 5;

    private final MongoTemplate mongo;
    private final OperatorWalletClient client;
    private final Map<String, OperatorOutboxDocument> memory = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, BigDecimal> balanceListener = (playerId, balance) -> { };
    private OperationsWalletClient operationsClient;
    private OperatorLaunchService operatorLaunches;

    @Autowired
    public OperatorWalletOutbox(@Autowired(required = false) MongoTemplate mongo, OperatorWalletClient client) {
        this.mongo = mongo;
        this.client = Objects.requireNonNull(client);
    }

    /** Package-private constructor for unit tests exercising Operations-only outbox paths without legacy client creation. */
    OperatorWalletOutbox(MongoTemplate mongo) {
        this.mongo = mongo;
        this.client = null;
    }

    @Autowired(required = false)
    public void setOperationsClient(OperationsWalletClient operationsClient) {
        this.operationsClient = operationsClient;
    }

    @Autowired(required = false)
    public void setOperatorLaunches(OperatorLaunchService operatorLaunches) {
        this.operatorLaunches = operatorLaunches;
    }

    /** Told the player's new authoritative balance after a queued credit lands. */
    public void setBalanceListener(BiConsumer<String, BigDecimal> listener) {
        this.balanceListener = listener != null ? listener : (playerId, balance) -> { };
    }

    /** Queues a direct-operator call; a second enqueue of the same call is ignored. */
    public void enqueue(Kind kind, OperatorRegistry.PlayerRef player, String transactionId, BigDecimal amount,
                        String gameId, String type, String description, String reason) {
        OperatorOutboxDocument doc = baseDoc(kind, player, transactionId, amount, gameId, type, description, reason);
        saveEntry(doc);
    }

    /** Queues a failed Operations 2052 {@code credit-transfer} preserving the exact {@code tranid} and session fields. */
    public void enqueueOperationsTransfer(OperatorRegistry.PlayerRef player,
                                          ProviderSessionDocument session,
                                          String tranid,
                                          BigDecimal betAmount,
                                          BigDecimal winAmount,
                                          String gameType,
                                          int ttype,
                                          String roundId,
                                          String ledgerType,
                                          String description,
                                          String reason) {
        BigDecimal totalAmount = ttype == OperationsWalletClient.TTYPE_REFUND
                ? (betAmount != null ? betAmount : BigDecimal.ZERO)
                : (winAmount != null ? winAmount : BigDecimal.ZERO);
        OperatorOutboxDocument doc = baseDoc(
                Kind.OPERATIONS_TRANSFER, player, tranid, totalAmount, roundId, ledgerType, description, reason);
        if (session != null) {
            doc.setProviderSessionToken(session.getSessionToken());
            doc.setPartnerCode(session.getPartnerCode());
            doc.setUserId(session.getUserId());
            doc.setGid(session.getGid());
        } else {
            doc.setPartnerCode(player.operatorId());
        }
        doc.setBetAmount(betAmount != null ? betAmount : BigDecimal.ZERO);
        doc.setWinAmount(winAmount != null ? winAmount : BigDecimal.ZERO);
        doc.setGameType(gameType);
        doc.setTtype(ttype);
        doc.setRoundId(roundId);
        saveEntry(doc);
    }

    private OperatorOutboxDocument baseDoc(Kind kind, OperatorRegistry.PlayerRef player, String transactionId,
                                           BigDecimal amount, String gameId, String type, String description, String reason) {
        Instant now = Instant.now();
        OperatorOutboxDocument doc = new OperatorOutboxDocument();
        doc.setId(kind.name() + ":" + transactionId);
        doc.setKind(kind.name());
        doc.setTransactionId(transactionId);
        doc.setPlayerId(player.playerId());
        doc.setOperatorId(player.operatorId());
        doc.setExternalId(player.externalId());
        doc.setAmount(amount);
        doc.setGameId(gameId);
        doc.setType(type);
        doc.setDescription(description);
        doc.setStatus(PENDING);
        doc.setNextAttemptAt(now.plusSeconds(2));
        doc.setLastError(reason);
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        return doc;
    }

    private void saveEntry(OperatorOutboxDocument doc) {
        if (mongo == null) {
            memory.putIfAbsent(doc.getId(), doc);
        } else {
            try {
                mongo.insert(doc);
            } catch (DuplicateKeyException ignored) {
                // Already queued with this tranid.
            }
        }
        log.warn("[OperatorWallet] Queued {} {} for {} ({} {}): {}", doc.getKind(), doc.getTransactionId(),
                doc.getOperatorId(), doc.getAmount(), OperatorWalletClient.CURRENCY, doc.getLastError());
    }

    public boolean isPending(Kind kind, String transactionId) {
        String id = kind.name() + ":" + transactionId;
        OperatorOutboxDocument doc = mongo == null ? memory.get(id) : mongo.findById(id, OperatorOutboxDocument.class);
        return doc != null && PENDING.equals(doc.getStatus());
    }

    @Scheduled(fixedDelayString = "${rummy.operator.outbox-interval-ms:5000}",
            initialDelayString = "${rummy.operator.outbox-interval-ms:5000}")
    public int deliverDue() {
        return deliverDue(Instant.now());
    }

    int deliverDue(Instant now) {
        int delivered = 0;
        try {
            for (OperatorOutboxDocument doc : leaseDue(now, 100)) {
                if (deliver(doc)) {
                    delivered++;
                }
            }
        } catch (Exception e) {
            log.error("[OperatorWallet] Outbox scan failed: {}", e.getMessage(), e);
        }
        return delivered;
    }

    private boolean deliver(OperatorOutboxDocument doc) {
        if (Kind.OPERATIONS_TRANSFER.name().equals(doc.getKind())) {
            return deliverOperationsTransfer(doc);
        }
        if (client == null) {
            handleFailure(doc, "OperatorWalletClient not configured");
            return false;
        }
        OperatorRegistry.PlayerRef player = new OperatorRegistry.PlayerRef(doc.getPlayerId(), doc.getOperatorId(), doc.getExternalId());
        String error;
        try {
            OperatorWalletClient.Reply reply = Kind.ROLLBACK.name().equals(doc.getKind())
                    ? client.rollback(player, doc.getTransactionId(), doc.getAmount(), doc.getGameId())
                    : client.credit(player, doc.getTransactionId(), doc.getAmount(), doc.getGameId(), doc.getType(), doc.getDescription());
            if (reply.ok()) {
                markDone(doc);
                if (reply.balance() != null) {
                    balanceListener.accept(doc.getPlayerId(), reply.balance());
                }
                log.info("[OperatorWallet] Delivered queued {} {} to {}", doc.getKind(), doc.getTransactionId(), doc.getOperatorId());
                return true;
            }
            error = "operator answered " + reply.status();
        } catch (Exception e) {
            error = e.getMessage();
        }
        handleFailure(doc, error);
        return false;
    }

    private boolean deliverOperationsTransfer(OperatorOutboxDocument doc) {
        String error;
        if (operationsClient == null || !operationsClient.isConfigured()) {
            error = "Operations wallet client not configured";
        } else {
            ProviderSessionDocument liveSession = operatorLaunches != null
                    ? operatorLaunches.getProviderSession(doc.getPlayerId()).orElse(null)
                    : null;
            String token = nonBlank(doc.getProviderSessionToken(),
                    liveSession != null ? liveSession.getSessionToken() : null);
            String partnerCode = nonBlank(doc.getPartnerCode(),
                    liveSession != null ? liveSession.getPartnerCode() : doc.getOperatorId());
            String userId = nonBlank(doc.getUserId(),
                    liveSession != null ? liveSession.getUserId() : null);
            String gid = nonBlank(doc.getGid(),
                    liveSession != null ? liveSession.getGid() : null);

            if (token == null && (partnerCode == null || userId == null)) {
                error = "Missing token and partner_code/user_id for player " + doc.getPlayerId();
            } else {
                try {
                    BigDecimal bet = doc.getBetAmount() != null ? doc.getBetAmount() : BigDecimal.ZERO;
                    BigDecimal win = doc.getWinAmount() != null ? doc.getWinAmount()
                            : (doc.getAmount() != null ? doc.getAmount() : BigDecimal.ZERO);
                    int ttype = doc.getTtype() > 0 ? doc.getTtype() : OperationsWalletClient.TTYPE_PLAY;
                    String gameType = nonBlank(doc.getGameType(),
                            ttype == OperationsWalletClient.TTYPE_REFUND
                                    ? OperationsWalletClient.GAME_TYPE_REFUND
                                    : OperationsWalletClient.GAME_TYPE_SPIN);
                    String roundId = nonBlank(doc.getRoundId(), doc.getGameId());

                    OperationsWalletClient.Reply reply = operationsClient.creditTransfer(
                            new OperationsWalletClient.TransferRequest(
                                    token, partnerCode, userId, gid, bet, win, gameType,
                                    doc.getTransactionId(), ttype, roundId));
                    if (reply.ok()) {
                        markDone(doc);
                        if (reply.balance() != null) {
                            balanceListener.accept(doc.getPlayerId(), reply.balance());
                        }
                        log.info("[OperationsWallet] Delivered queued OPERATIONS_TRANSFER tranid={} for {}",
                                doc.getTransactionId(), partnerCode);
                        return true;
                    }
                    error = "Operations refused transfer (" + reply.errorCode() + "): " + reply.rawMessage();
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }
        }
        handleFailure(doc, error);
        return false;
    }

    private static String nonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return (fallback != null && !fallback.isBlank()) ? fallback.trim() : null;
    }

    private void handleFailure(OperatorOutboxDocument doc, String error) {
        int attempts = doc.getAttempts();
        long backoff = Math.min(MAX_BACKOFF.toSeconds(), 5L << Math.min(attempts, 10));
        reschedule(doc, Instant.now().plusSeconds(backoff), error);
        if (attempts >= ALERT_AFTER_ATTEMPTS) {
            log.error("[OperatorWallet] RECONCILE REQUIRED: {} {} of {} {} to {} player {} still failing after {} attempts: {}",
                    doc.getKind(), doc.getTransactionId(), doc.getAmount(), OperatorWalletClient.CURRENCY,
                    doc.getOperatorId(), doc.getExternalId(), attempts, error);
        }
    }

    /** Due entries, each leased to this node so no other node sends it meanwhile. */
    private List<OperatorOutboxDocument> leaseDue(Instant now, int limit) {
        List<OperatorOutboxDocument> leased = new ArrayList<>();
        if (mongo == null) {
            memory.values().stream()
                    .filter(d -> PENDING.equals(d.getStatus()) && !d.getNextAttemptAt().isAfter(now))
                    .sorted(Comparator.comparing(OperatorOutboxDocument::getNextAttemptAt))
                    .limit(limit)
                    .forEach(d -> {
                        d.setAttempts(d.getAttempts() + 1);
                        d.setNextAttemptAt(now.plus(LEASE));
                        leased.add(d);
                    });
            return leased;
        }
        for (int i = 0; i < limit; i++) {
            OperatorOutboxDocument doc = mongo.findAndModify(
                    Query.query(Criteria.where("status").is(PENDING).and("nextAttemptAt").lte(now))
                            .with(Sort.by("nextAttemptAt")),
                    new Update().inc("attempts", 1).set("nextAttemptAt", now.plus(LEASE)).set("updatedAt", now),
                    FindAndModifyOptions.options().returnNew(true),
                    OperatorOutboxDocument.class);
            if (doc == null) {
                break;
            }
            leased.add(doc);
        }
        return leased;
    }

    private void markDone(OperatorOutboxDocument doc) {
        Instant now = Instant.now();
        if (mongo == null) {
            doc.setStatus(DONE);
            doc.setUpdatedAt(now);
            return;
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(doc.getId())),
                new Update().set("status", DONE).set("updatedAt", now).set("expireAt", now.plus(RETENTION)).unset("lastError"),
                OperatorOutboxDocument.class);
    }

    private void reschedule(OperatorOutboxDocument doc, Instant next, String error) {
        if (mongo == null) {
            doc.setNextAttemptAt(next);
            doc.setLastError(error);
            return;
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(doc.getId()).and("status").is(PENDING)),
                new Update().set("nextAttemptAt", next).set("lastError", error).set("updatedAt", Instant.now()),
                OperatorOutboxDocument.class);
    }
}
