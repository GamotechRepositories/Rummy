package com.rummy.gameservice.wallet;

import com.mongodb.MongoException;
import com.rummy.gameservice.operator.OperatorRegistry;
import com.rummy.gameservice.operator.OperatorWalletClient;
import com.rummy.gameservice.operator.OperatorWalletException;
import com.rummy.gameservice.operator.OperatorWalletOutbox;
import com.rummy.gameservice.persistence.document.WalletAccountDocument;
import com.rummy.gameservice.persistence.document.WalletTransactionDocument;
import com.rummy.gameservice.persistence.repository.WalletAccountRepository;
import com.rummy.gameservice.persistence.repository.WalletTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MongoDB Wallet & Double-Entry Financial Ledger Service.
 *
 * <p>Concurrency: every balance change is a read-modify-write guarded by the account's
 * {@code @Version} field and retried on conflict, so concurrent updates from any number of
 * nodes cannot lose money. When a {@link MongoTransactionManager} is configured the balance
 * update and the ledger insert commit atomically.</p>
 *
 * <p>Idempotency: the unique index on {@code idempotencyKey} is the source of truth.</p>
 *
 * <p>The in-memory store exists only for unit tests (no repositories). With MongoDB
 * configured, a database failure surfaces as an exception — money is never kept in memory.</p>
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private static final int MAX_WRITE_ATTEMPTS = 8;
    private static final int TRANSACTION_HISTORY_LIMIT = 100;
    private static final int SETTLEMENT_CACHE_SIZE = 10_000;

    public static final String PLATFORM_TREASURY = "PLATFORM_TREASURY";
    public static final BigDecimal DEFAULT_RAKE_RATE = new BigDecimal("0.15"); // 15% Platform Commission

    private final WalletAccountRepository accountRepository;
    private final WalletTransactionRepository transactionRepository;
    private final TransactionTemplate transactionTemplate;

    private OperatorRegistry operators;
    private OperatorWalletClient operatorWallet;
    private OperatorWalletOutbox outbox;

    private final Map<String, WalletAccountDocument> memoryAccounts = new ConcurrentHashMap<>();
    private final Map<String, WalletTransactionDocument> memoryTransactions = new ConcurrentHashMap<>();
    private final Object memoryLock = new Object();

    private final Map<String, GameSettlementResult> settlementCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, GameSettlementResult> eldest) {
                    return size() > SETTLEMENT_CACHE_SIZE;
                }
            });

    @Autowired
    public WalletService(@Autowired(required = false) WalletAccountRepository accountRepository,
                         @Autowired(required = false) WalletTransactionRepository transactionRepository,
                         ObjectProvider<MongoTransactionManager> transactionManager) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        MongoTransactionManager txManager = transactionManager != null ? transactionManager.getIfAvailable() : null;
        this.transactionTemplate = txManager != null ? new TransactionTemplate(txManager) : null;
        if (isPersistent() && transactionTemplate == null) {
            log.warn("[Wallet] MongoDB transactions are disabled. Balance and ledger writes are not atomic; "
                    + "set rummy.mongo.transactions.enabled=true on a replica set (Atlas) for production.");
        }
    }

    public WalletService(WalletAccountRepository accountRepository, WalletTransactionRepository transactionRepository) {
        this(accountRepository, transactionRepository, null);
    }

    public WalletService() {
        this(null, null, null);
    }

    /** Players launched by an operator are paid through the operator's wallet API. */
    @Autowired(required = false)
    public void setOperatorWallet(OperatorRegistry operators, OperatorWalletClient operatorWallet, OperatorWalletOutbox outbox) {
        this.operators = operators;
        this.operatorWallet = operatorWallet;
        this.outbox = outbox;
        outbox.setBalanceListener(this::recordOperatorBalance);
    }

    private boolean isPersistent() {
        return accountRepository != null && transactionRepository != null;
    }

    private Optional<OperatorRegistry.PlayerRef> operatorPlayer(String playerId) {
        return operators != null && operatorWallet != null ? operators.player(playerId) : Optional.empty();
    }

    /**
     * The player's spendable INR balance: live from the operator for operator players (falling back to
     * the last known balance if the operator cannot be reached), else this platform's ledger balance.
     */
    public BigDecimal balanceOf(String playerId) {
        Optional<OperatorRegistry.PlayerRef> player = operatorPlayer(playerId);
        if (player.isPresent()) {
            try {
                OperatorWalletClient.Reply reply = operatorWallet.balance(player.get());
                if (reply.ok() && reply.balance() != null) {
                    recordOperatorBalance(playerId, reply.balance());
                    return reply.balance();
                }
                log.warn("[Wallet] Operator {} answered {} to a balance request", player.get().operatorId(), reply.status());
            } catch (OperatorWalletException e) {
                log.warn("[Wallet] Balance from operator {} unavailable: {}", player.get().operatorId(), e.getMessage());
            }
        }
        return getOrCreateWallet(playerId).getBalance();
    }

    /**
     * Retrieves or lazily provisions a player's wallet account (opening balance zero).
     */
    public WalletAccountDocument getOrCreateWallet(String playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!isPersistent()) {
            return memoryAccounts.computeIfAbsent(playerId, WalletAccountDocument::new);
        }
        Optional<WalletAccountDocument> existing = accountRepository.findByPlayerId(playerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return accountRepository.insert(new WalletAccountDocument(playerId));
        } catch (DuplicateKeyException raced) {
            return accountRepository.findByPlayerId(playerId)
                    .orElseThrow(() -> new IllegalStateException("Wallet for " + playerId + " vanished after duplicate insert"));
        }
    }

    /**
     * Atomically credits a player's wallet with idempotency guarantee.
     */
    public WalletTransactionDocument credit(String playerId, BigDecimal amount, String type,
                                            String idempotencyKey, String gameId,
                                            String description, Map<String, Object> meta) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be strictly positive");
        }
        return applyChange(playerId, amount, type, idempotencyKey, gameId, description, meta);
    }

    /**
     * Atomically debits a player's wallet with balance check and idempotency guarantee.
     */
    public WalletTransactionDocument debit(String playerId, BigDecimal amount, String type,
                                           String idempotencyKey, String gameId,
                                           String description, Map<String, Object> meta) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debit amount must be strictly positive");
        }
        return applyChange(playerId, amount.negate(), type, idempotencyKey, gameId, description, meta);
    }

    private WalletTransactionDocument applyChange(String playerId, BigDecimal delta, String type,
                                                  String idempotencyKey, String gameId,
                                                  String description, Map<String, Object> meta) {
        Objects.requireNonNull(playerId, "playerId");
        String key = idempotencyKey != null ? idempotencyKey : type + "_" + UUID.randomUUID();

        Optional<OperatorRegistry.PlayerRef> operatorPlayer = operatorPlayer(playerId);
        if (operatorPlayer.isPresent()) {
            return applyOperatorChange(operatorPlayer.get(), delta, type, key, gameId, description, meta);
        }

        if (!isPersistent()) {
            return applyChangeInMemory(playerId, delta, type, key, gameId, description, meta);
        }

        Optional<WalletTransactionDocument> existing = transactionRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            log.info("[Wallet] Transaction with idempotencyKey={} already processed, returning existing record", key);
            return existing.get();
        }

        getOrCreateWallet(playerId);

        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            try {
                WalletTransactionDocument tx = runAtomically(() ->
                        writeChange(playerId, delta, type, key, gameId, description, meta));
                log.info("[Wallet] {} {} for player={} (balance: {} -> {}) [idempotencyKey={}]",
                        delta.signum() >= 0 ? "Credited" : "Debited", delta.abs(), playerId,
                        tx.getBalanceBefore(), tx.getBalanceAfter(), key);
                return tx;
            } catch (TransientDataAccessException e) {
                // Includes OptimisticLockingFailureException (version conflict).
                backoff(attempt);
            } catch (UncategorizedMongoDbException e) {
                if (!isTransientTransactionError(e)) {
                    throw e;
                }
                backoff(attempt);
            } catch (DuplicateKeyException e) {
                return transactionRepository.findByIdempotencyKey(key).orElseThrow(() -> e);
            }
        }
        throw new IllegalStateException("Wallet for player " + playerId + " is busy, please retry");
    }

    private WalletTransactionDocument writeChange(String playerId, BigDecimal delta, String type, String key,
                                                  String gameId, String description, Map<String, Object> meta) {
        WalletAccountDocument account = accountRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalStateException("Wallet not found for " + playerId));
        BigDecimal before = account.getBalance();
        BigDecimal after = before.add(delta);
        if (after.signum() < 0 && !PLATFORM_TREASURY.equals(playerId)) {
            throw insufficientBalance(playerId, before, delta.negate());
        }
        account.setBalance(after);
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);

        WalletTransactionDocument tx = new WalletTransactionDocument(
                key, playerId, gameId, type, delta.abs(), before, after, account.getCurrency(), description, meta);
        try {
            return transactionRepository.insert(tx);
        } catch (DuplicateKeyException e) {
            if (transactionTemplate == null) {
                revertBalance(playerId, delta);
            }
            throw e;
        }
    }

    /** Non-transactional fallback: undo a balance change whose ledger insert lost an idempotency race. */
    private void revertBalance(String playerId, BigDecimal delta) {
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            try {
                WalletAccountDocument account = accountRepository.findByPlayerId(playerId).orElseThrow();
                account.setBalance(account.getBalance().subtract(delta));
                account.setUpdatedAt(Instant.now());
                accountRepository.save(account);
                return;
            } catch (OptimisticLockingFailureException e) {
                backoff(attempt);
            }
        }
        log.error("[Wallet] RECONCILE REQUIRED: could not revert duplicate change of {} for player {}", delta, playerId);
    }

    private <T> T runAtomically(java.util.function.Supplier<T> work) {
        if (transactionTemplate == null) {
            return work.get();
        }
        return transactionTemplate.execute(status -> work.get());
    }

    private WalletTransactionDocument applyChangeInMemory(String playerId, BigDecimal delta, String type, String key,
                                                          String gameId, String description, Map<String, Object> meta) {
        synchronized (memoryLock) {
            WalletTransactionDocument existing = memoryTransactions.get(key);
            if (existing != null) {
                return existing;
            }
            WalletAccountDocument account = memoryAccounts.computeIfAbsent(playerId, WalletAccountDocument::new);
            BigDecimal before = account.getBalance();
            BigDecimal after = before.add(delta);
            if (after.signum() < 0 && !PLATFORM_TREASURY.equals(playerId)) {
                throw insufficientBalance(playerId, before, delta.negate());
            }
            account.setBalance(after);
            account.setUpdatedAt(Instant.now());
            WalletTransactionDocument tx = new WalletTransactionDocument(
                    key, playerId, gameId, type, delta.abs(), before, after, account.getCurrency(), description, meta);
            memoryTransactions.put(key, tx);
            return tx;
        }
    }

    private static InsufficientBalanceException insufficientBalance(String playerId, BigDecimal balance, BigDecimal required) {
        return new InsufficientBalanceException("Insufficient balance for player " + playerId
                + ". Balance: ₹" + (balance != null ? balance : "?") + ", Required: ₹" + required);
    }

    /**
     * Debits or credits an operator player through the operator's wallet, then mirrors it in our ledger.
     * A debit is final only once the operator confirms it; one whose outcome is unknown is rolled back
     * (queued) and refused here. A credit the operator cannot take right now is queued and retried with
     * the same transaction id until it lands, so winnings and refunds are never lost.
     */
    private WalletTransactionDocument applyOperatorChange(OperatorRegistry.PlayerRef player, BigDecimal delta, String type,
                                                          String key, String gameId, String description,
                                                          Map<String, Object> meta) {
        Optional<WalletTransactionDocument> existing = findTransaction(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        BigDecimal amount = delta.abs();
        OperatorWalletClient.Reply reply;
        if (delta.signum() < 0) {
            try {
                reply = operatorWallet.debit(player, key, amount, gameId, type, description);
            } catch (OperatorWalletException e) {
                if (e.isUncertain()) {
                    outbox.enqueue(OperatorWalletOutbox.Kind.ROLLBACK, player, key, amount, gameId, type, description, e.getMessage());
                }
                throw new OperatorWalletException("Wallet unavailable, please try again: " + e.getMessage(), false);
            }
            if (reply.status() == OperatorWalletClient.Status.INSUFFICIENT_FUNDS) {
                throw insufficientBalance(player.playerId(), reply.balance(), amount);
            }
            if (!reply.ok()) {
                throw new OperatorWalletException("Operator refused the debit: " + reply.status(), false);
            }
        } else {
            try {
                reply = operatorWallet.credit(player, key, amount, gameId, type, description);
            } catch (OperatorWalletException e) {
                outbox.enqueue(OperatorWalletOutbox.Kind.CREDIT, player, key, amount, gameId, type, description, e.getMessage());
                return recordOperatorTransaction(player, delta, type, key, gameId, description, meta, null);
            }
            if (!reply.ok()) {
                outbox.enqueue(OperatorWalletOutbox.Kind.CREDIT, player, key, amount, gameId, type, description,
                        "operator answered " + reply.status());
                return recordOperatorTransaction(player, delta, type, key, gameId, description, meta, null);
            }
        }
        try {
            return recordOperatorTransaction(player, delta, type, key, gameId, description, meta, reply);
        } catch (RuntimeException e) {
            if (delta.signum() < 0) {
                // The player was charged but we cannot record it, so the caller will treat the debit as failed.
                outbox.enqueue(OperatorWalletOutbox.Kind.ROLLBACK, player, key, amount, gameId, type, description,
                        "ledger write failed: " + e.getMessage());
            }
            throw e;
        }
    }

    /**
     * Mirrors an operator wallet movement in our ledger. {@code confirmed} null means the credit is queued:
     * the balance is left as last known and the entry is marked so.
     */
    private WalletTransactionDocument recordOperatorTransaction(OperatorRegistry.PlayerRef player, BigDecimal delta, String type,
                                                                String key, String gameId, String description,
                                                                Map<String, Object> meta, OperatorWalletClient.Reply confirmed) {
        Map<String, Object> fullMeta = new HashMap<>(meta != null ? meta : Map.of());
        fullMeta.put("operatorId", player.operatorId());
        fullMeta.put("operatorStatus", confirmed != null ? "CONFIRMED" : "QUEUED");
        BigDecimal reported = confirmed != null ? confirmed.balance() : null;
        String playerId = player.playerId();
        if (!isPersistent()) {
            synchronized (memoryLock) {
                WalletTransactionDocument existing = memoryTransactions.get(key);
                if (existing != null) {
                    return existing;
                }
                WalletAccountDocument account = memoryAccounts.computeIfAbsent(playerId, WalletAccountDocument::new);
                WalletTransactionDocument tx = mirror(account, playerId, delta, type, key, gameId, description, fullMeta, reported, confirmed != null);
                memoryTransactions.put(key, tx);
                return tx;
            }
        }
        getOrCreateWallet(playerId);
        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            try {
                return runAtomically(() -> {
                    WalletAccountDocument account = accountRepository.findByPlayerId(playerId).orElseThrow();
                    WalletTransactionDocument tx = mirror(account, playerId, delta, type, key, gameId, description, fullMeta, reported, confirmed != null);
                    accountRepository.save(account);
                    return transactionRepository.insert(tx);
                });
            } catch (TransientDataAccessException e) {
                backoff(attempt);
            } catch (UncategorizedMongoDbException e) {
                if (!isTransientTransactionError(e)) {
                    throw e;
                }
                backoff(attempt);
            } catch (DuplicateKeyException e) {
                return transactionRepository.findByIdempotencyKey(key).orElseThrow(() -> e);
            }
        }
        throw new IllegalStateException("Wallet for player " + playerId + " is busy, please retry");
    }

    private static WalletTransactionDocument mirror(WalletAccountDocument account, String playerId, BigDecimal delta, String type,
                                                    String key, String gameId, String description, Map<String, Object> meta,
                                                    BigDecimal reported, boolean confirmed) {
        BigDecimal after = reported != null ? reported : confirmed ? account.getBalance().add(delta) : account.getBalance();
        BigDecimal before = confirmed ? after.subtract(delta) : after;
        account.setBalance(after);
        account.setUpdatedAt(Instant.now());
        return new WalletTransactionDocument(key, playerId, gameId, type, delta.abs(), before, after,
                WalletAccountDocument.CURRENCY, description, meta);
    }

    /** Updates the last known operator balance of a player (best effort). */
    private void recordOperatorBalance(String playerId, BigDecimal balance) {
        if (!isPersistent()) {
            memoryAccounts.computeIfAbsent(playerId, WalletAccountDocument::new).setBalance(balance);
            return;
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                WalletAccountDocument account = getOrCreateWallet(playerId);
                if (balance.equals(account.getBalance())) {
                    return;
                }
                account.setBalance(balance);
                account.setUpdatedAt(Instant.now());
                accountRepository.save(account);
                return;
            } catch (OptimisticLockingFailureException e) {
                backoff(attempt);
            } catch (RuntimeException e) {
                log.debug("[Wallet] Could not store operator balance for {}: {}", playerId, e.getMessage());
                return;
            }
        }
    }

    private Optional<WalletTransactionDocument> findTransaction(String key) {
        if (!isPersistent()) {
            return Optional.ofNullable(memoryTransactions.get(key));
        }
        return transactionRepository.findByIdempotencyKey(key);
    }

    private static boolean isTransientTransactionError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof MongoException me && me.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }
        }
        return false;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(2, 10L * attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying wallet write", ie);
        }
    }

    /**
     * Production-ready P2P Settlement Engine with Variant-aware Rake Calculation (Supreme Court & Legal Skill Gaming compliant):
     * 1. POINTS RUMMY:
     *    - Point value = stakeTier / 80 (cap).
     *    - Each loser loses: min(penaltyPoints * pointValue, stakeTier).
     *    - Any unlost stake (stakeTier - loss) is refunded to the human loser as GAME_REFUND.
     *    - Platform charges 15% Rake on total collected loser losses -> PLATFORM_TREASURY.
     *    - Winner receives: their initial stake back + Net Winner Prize (85% of collected losses).
     * 2. POOL & DEALS RUMMY:
     *    - Fixed entry fee = stakeTier.
     *    - Total Gross Pot = stakeTier * totalPlayers.
     *    - Platform charges 15% Rake on total pot -> PLATFORM_TREASURY.
     *    - Winner receives Net Prize (85% of gross pot) -> GAME_WIN.
     *
     * Every payout uses a deterministic idempotency key derived from gameId, so re-running a
     * settlement (retry, another node) never pays twice.
     */
    public GameSettlementResult settleMatch(String gameId,
                                            String tableId,
                                            String rulesetId,
                                            BigDecimal stakeTier,
                                            String winnerPlayerId,
                                            Map<String, Integer> finalScores,
                                            List<String> allPlayerIds) {
        return settleMatch(gameId, tableId, rulesetId, stakeTier, winnerPlayerId, finalScores, allPlayerIds, Collections.emptyMap());
    }

    public GameSettlementResult settleMatch(String gameId,
                                            String tableId,
                                            String rulesetId,
                                            BigDecimal stakeTier,
                                            String winnerPlayerId,
                                            Map<String, Integer> finalScores,
                                            List<String> allPlayerIds,
                                            Map<String, Integer> rejoinCounts) {
        return settleMatch(gameId, tableId, rulesetId, stakeTier, winnerPlayerId, finalScores, allPlayerIds, rejoinCounts, null);
    }

    /**
     * @param splitDrops pool prize split agreed by the remaining players: drops left per sharing player
     *                   (see {@link PoolSplit}); null or empty for a single winner
     */
    public GameSettlementResult settleMatch(String gameId,
                                            String tableId,
                                            String rulesetId,
                                            BigDecimal stakeTier,
                                            String winnerPlayerId,
                                            Map<String, Integer> finalScores,
                                            List<String> allPlayerIds,
                                            Map<String, Integer> rejoinCounts,
                                            Map<String, Integer> splitDrops) {
        if (stakeTier == null || stakeTier.compareTo(BigDecimal.ZERO) <= 0 || winnerPlayerId == null) {
            log.warn("[Wallet] Skipping settlement: invalid stakeTier ({}) or null winner", stakeTier);
            return null;
        }

        GameSettlementResult cached = settlementCache.get(gameId);
        if (cached != null) {
            log.info("[Wallet] Match gameId={} already settled, returning cached result", gameId);
            return cached;
        }

        List<String> players = (allPlayerIds != null && !allPlayerIds.isEmpty())
                ? new ArrayList<>(allPlayerIds)
                : (finalScores != null ? new ArrayList<>(finalScores.keySet()) : new ArrayList<>(List.of(winnerPlayerId)));

        if (!players.contains(winnerPlayerId)) {
            players.add(winnerPlayerId);
        }

        Map<String, Integer> scores = finalScores != null ? finalScores : Map.of();
        String rId = (rulesetId != null) ? rulesetId.toUpperCase() : "POINTS_13";
        boolean isRummy21 = rId.contains("21") || rId.equals("RUMMY_21");
        boolean isPoints13 = rId.contains("POINT") || rId.equals("POINTS_13");
        boolean isPointsBased = isPoints13 || isRummy21;

        // 21-Card Indian Rummy cap is 120 penalty points (30 first drop, 60 middle drop); 13-Card is 80 penalty points
        int maxPenaltyCap = isRummy21 ? 120 : 80;

        Map<String, GameSettlementResult.PlayerSettlementDetail> details = new LinkedHashMap<>();
        List<String> failedPayouts = new ArrayList<>();
        BigDecimal totalGrossPot = BigDecimal.ZERO;

        if (isPointsBased) {
            // Points Rummy (80 cap for 13 cards, 120 cap for 21 cards). Point value = stakeTier / maxPenaltyCap.
            BigDecimal pointValue = stakeTier.divide(BigDecimal.valueOf(maxPenaltyCap), 4, java.math.RoundingMode.HALF_UP);

            for (String pId : players) {
                if (pId.equals(winnerPlayerId)) {
                    continue;
                }
                int penalty = scores.getOrDefault(pId, maxPenaltyCap);
                penalty = Math.min(maxPenaltyCap, Math.max(0, penalty));

                BigDecimal loss = pointValue.multiply(BigDecimal.valueOf(penalty)).setScale(2, java.math.RoundingMode.HALF_UP);
                loss = loss.min(stakeTier);
                BigDecimal refund = stakeTier.subtract(loss).setScale(2, java.math.RoundingMode.HALF_UP);
                totalGrossPot = totalGrossPot.add(loss);

                // If bot player lost, debit house loss from platform treasury (Method 2: Gross Accounting)
                if (pId.startsWith("BOT_")) {
                    if (loss.compareTo(BigDecimal.ZERO) > 0) {
                        String botLossKey = "BOT_LOSS_" + gameId + "_" + pId;
                        try {
                            debit(PLATFORM_TREASURY, loss, "BOT_HOUSE_LOSS", botLossKey, gameId,
                                    "House Bot loss (" + penalty + " pts) for " + gameId + " (" + rId + ")",
                                    Map.of("gameId", gameId, "botId", pId, "penalty", penalty, "loss", loss, "variant", rId));
                        } catch (Exception e) {
                            log.error("[Wallet] Failed to debit house bot loss {} for bot {} in gameId={}: {}",
                                    loss, pId, gameId, e.getMessage());
                            failedPayouts.add(botLossKey);
                        }
                    }
                } else if (refund.compareTo(BigDecimal.ZERO) > 0) {
                    // If human player dropped early, refund their unlost stake
                    String refundKey = "REFUND_" + gameId + "_" + pId;
                    try {
                        credit(pId, refund, "GAME_REFUND", refundKey, gameId,
                                "Unlost stake refund (" + penalty + " pts) for " + gameId + " (" + rId + ")",
                                Map.of("gameId", gameId, "penalty", penalty, "refund", refund, "variant", rId));
                    } catch (Exception e) {
                        log.error("[Wallet] Failed to credit refund {} to {} for gameId={}: {}",
                                refund, pId, gameId, e.getMessage());
                        failedPayouts.add(refundKey);
                    }
                }

                details.put(pId, new GameSettlementResult.PlayerSettlementDetail(
                        pId, false, penalty, stakeTier, loss, refund, BigDecimal.ZERO, loss.negate()
                ));
            }
        } else {
            // Pool Rummy (POOL_101, POOL_201) / Deals Rummy (DEALS_RUMMY): Fixed entry fee tournament
            // All participating players contribute their entry fee + rejoin fees to the total gross prize pot
            for (String pId : players) {
                int rejoins = (rejoinCounts != null) ? rejoinCounts.getOrDefault(pId, 0) : 0;
                BigDecimal playerContribution = stakeTier.multiply(BigDecimal.valueOf(1 + rejoins));
                totalGrossPot = totalGrossPot.add(playerContribution);

                // If bot player participated, debit their tournament stake from platform treasury (House Stake)
                if (pId.startsWith("BOT_") && playerContribution.compareTo(BigDecimal.ZERO) > 0) {
                    String botLossKey = "BOT_LOSS_" + gameId + "_" + pId;
                    try {
                        debit(PLATFORM_TREASURY, playerContribution, "BOT_HOUSE_LOSS", botLossKey, gameId,
                                "House Bot tournament stake for " + gameId + " (" + rId + ")",
                                Map.of("gameId", gameId, "botId", pId, "contribution", playerContribution, "variant", rId));
                    } catch (Exception e) {
                        log.error("[Wallet] Failed to debit house bot tournament stake {} for bot {} in gameId={}: {}",
                                playerContribution, pId, gameId, e.getMessage());
                        failedPayouts.add(botLossKey);
                    }
                }

                if (pId.equals(winnerPlayerId) || (splitDrops != null && splitDrops.containsKey(pId))) {
                    continue;
                }
                int penalty = scores.getOrDefault(pId, 80);

                details.put(pId, new GameSettlementResult.PlayerSettlementDetail(
                        pId, false, penalty, stakeTier, playerContribution, BigDecimal.ZERO, BigDecimal.ZERO, playerContribution.negate()
                ));
            }
        }

        // Platform Rake (15%)
        BigDecimal platformRake = totalGrossPot.multiply(DEFAULT_RAKE_RATE).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal netWinnerPrize = totalGrossPot.subtract(platformRake).setScale(2, java.math.RoundingMode.HALF_UP);

        // Record Platform Treasury Rake
        if (platformRake.compareTo(BigDecimal.ZERO) > 0) {
            String rakeKey = "RAKE_" + gameId;
            try {
                credit(PLATFORM_TREASURY, platformRake, "PLATFORM_RAKE", rakeKey, gameId,
                        "Platform commission rake (15%) for " + gameId + " (" + rId + ")",
                        Map.of("gameId", gameId, "tableId", tableId != null ? tableId : "", "grossPot", totalGrossPot, "rake", platformRake, "variant", rId));
            } catch (Exception e) {
                log.error("[Wallet] Failed to credit platform rake for gameId={}: {}", gameId, e.getMessage());
                failedPayouts.add(rakeKey);
            }
        }

        Map<String, BigDecimal> splitPayouts = null;
        if (!isPointsBased && splitDrops != null && !splitDrops.isEmpty()) {
            splitPayouts = PoolSplit.payouts(new LinkedHashMap<>(splitDrops), stakeTier, netWinnerPrize).orElse(null);
            if (splitPayouts == null) {
                log.error("[Wallet] Agreed split of {} cannot be paid from prize {}; paying {} the whole prize",
                        gameId, netWinnerPrize, winnerPlayerId);
                for (String pId : splitDrops.keySet()) {
                    if (!pId.equals(winnerPlayerId)) {
                        int rejoins = (rejoinCounts != null) ? rejoinCounts.getOrDefault(pId, 0) : 0;
                        BigDecimal paid = stakeTier.multiply(BigDecimal.valueOf(1 + rejoins));
                        details.put(pId, new GameSettlementResult.PlayerSettlementDetail(
                                pId, false, scores.getOrDefault(pId, 0), stakeTier, paid, BigDecimal.ZERO, BigDecimal.ZERO, paid.negate()));
                    }
                }
            }
        }

        if (splitPayouts != null) {
            for (Map.Entry<String, BigDecimal> share : splitPayouts.entrySet()) {
                String pId = share.getKey();
                int rejoins = (rejoinCounts != null) ? rejoinCounts.getOrDefault(pId, 0) : 0;
                BigDecimal paid = stakeTier.multiply(BigDecimal.valueOf(1 + rejoins));
                String account = pId.startsWith("BOT_") ? PLATFORM_TREASURY : pId;
                String key = (pId.startsWith("BOT_") ? "BOT_WIN_" : "WIN_") + gameId + "_" + pId;
                try {
                    credit(account, share.getValue(), pId.startsWith("BOT_") ? "BOT_HOUSE_WIN" : "GAME_WIN", key, gameId,
                            "Prize split payout for " + gameId + " (" + rulesetId + ")",
                            Map.of("gameId", gameId, "grossPot", totalGrossPot, "rake", platformRake, "netPrize", netWinnerPrize,
                                    "split", true, "dropsLeft", splitDrops.getOrDefault(pId, 0)));
                } catch (Exception e) {
                    log.error("[Wallet] Failed to credit split share {} to {} for gameId={}: {}", share.getValue(), pId, gameId, e.getMessage());
                    failedPayouts.add(key);
                }
                details.put(pId, new GameSettlementResult.PlayerSettlementDetail(
                        pId, true, scores.getOrDefault(pId, 0), stakeTier, BigDecimal.ZERO, BigDecimal.ZERO,
                        share.getValue(), share.getValue().subtract(paid)));
            }
            if (!failedPayouts.isEmpty()) {
                throw new IllegalStateException("Settlement of " + gameId + " incomplete, retrying: " + failedPayouts);
            }
            GameSettlementResult result = new GameSettlementResult(
                    gameId, tableId, rulesetId, winnerPlayerId, stakeTier, totalGrossPot,
                    DEFAULT_RAKE_RATE, platformRake, netWinnerPrize, details, splitPayouts
            );
            settlementCache.put(gameId, result);
            log.info("[Wallet] Settlement complete for gameId={} (prize split): GrossPot={}, PlatformRake={}, Shares={}",
                    gameId, totalGrossPot, platformRake, splitPayouts);
            return result;
        }

        // Credit Winner
        int winnerRejoins = (rejoinCounts != null) ? rejoinCounts.getOrDefault(winnerPlayerId, 0) : 0;
        BigDecimal winnerTotalPaid = isPointsBased ? stakeTier : stakeTier.multiply(BigDecimal.valueOf(1 + winnerRejoins));
        boolean isBotWinner = winnerPlayerId.startsWith("BOT_");
        BigDecimal winnerCreditAmount;
        if (isBotWinner) {
            winnerCreditAmount = netWinnerPrize;
        } else {
            winnerCreditAmount = isPointsBased ? stakeTier.add(netWinnerPrize) : netWinnerPrize;
        }
        BigDecimal winnerNetDelta = isPointsBased ? netWinnerPrize : netWinnerPrize.subtract(winnerTotalPaid);

        if (!winnerPlayerId.startsWith("BOT_")) {
            String winKey = "WIN_" + gameId + "_" + winnerPlayerId;
            try {
                credit(winnerPlayerId, winnerCreditAmount, "GAME_WIN", winKey, gameId,
                        "Winner prize payout for " + gameId + " (" + rulesetId + ")",
                        Map.of("gameId", gameId, "grossPot", totalGrossPot, "rake", platformRake, "netPrize", netWinnerPrize));
            } catch (Exception e) {
                log.error("[Wallet] Failed to credit winner {} amount {} for gameId={}: {}",
                        winnerPlayerId, winnerCreditAmount, gameId, e.getMessage());
                failedPayouts.add(winKey);
            }
        } else {
            // Bot win goes to house treasury
            String botWinKey = "BOT_WIN_" + gameId;
            try {
                credit(PLATFORM_TREASURY, winnerCreditAmount, "BOT_HOUSE_WIN", botWinKey, gameId,
                        "House Bot win payout for " + gameId,
                        Map.of("gameId", gameId, "botId", winnerPlayerId, "amount", winnerCreditAmount));
            } catch (Exception e) {
                log.error("[Wallet] Failed to credit house bot win for gameId={}: {}", gameId, e.getMessage());
                failedPayouts.add(botWinKey);
            }
        }

        if (!failedPayouts.isEmpty()) {
            throw new IllegalStateException("Settlement of " + gameId + " incomplete, retrying: " + failedPayouts);
        }

        details.put(winnerPlayerId, new GameSettlementResult.PlayerSettlementDetail(
                winnerPlayerId, true, 0, stakeTier, BigDecimal.ZERO, BigDecimal.ZERO, netWinnerPrize, winnerNetDelta
        ));

        GameSettlementResult result = new GameSettlementResult(
                gameId, tableId, rulesetId, winnerPlayerId, stakeTier, totalGrossPot,
                DEFAULT_RAKE_RATE, platformRake, netWinnerPrize, details, null
        );

        settlementCache.put(gameId, result);
        log.info("[Wallet] Settlement complete for gameId={}: GrossPot={}, PlatformRake={}, WinnerPrize={}, WinnerNetDelta={}",
                gameId, totalGrossPot, platformRake, netWinnerPrize, winnerNetDelta);

        return result;
    }

    public GameSettlementResult getSettlement(String gameId) {
        return settlementCache.get(gameId);
    }

    public BigDecimal getPlatformTreasuryBalance() {
        return getOrCreateWallet(PLATFORM_TREASURY).getBalance();
    }

    public boolean hasTransaction(String idempotencyKey) {
        if (isPersistent()) {
            return transactionRepository.existsByIdempotencyKey(idempotencyKey);
        }
        return memoryTransactions.containsKey(idempotencyKey);
    }

    /** Most recent ledger entries for a player (capped). */
    public List<WalletTransactionDocument> getTransactions(String playerId) {
        if (isPersistent()) {
            return transactionRepository.findByPlayerIdOrderByCreatedAtDesc(playerId, PageRequest.of(0, TRANSACTION_HISTORY_LIMIT));
        }
        return memoryTransactions.values().stream()
                .filter(tx -> Objects.equals(tx.getPlayerId(), playerId))
                .sorted(Comparator.comparing(WalletTransactionDocument::getCreatedAt).reversed())
                .limit(TRANSACTION_HISTORY_LIMIT)
                .toList();
    }
}
