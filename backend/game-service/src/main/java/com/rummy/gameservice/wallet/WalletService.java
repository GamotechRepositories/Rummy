package com.rummy.gameservice.wallet;

import com.mongodb.MongoException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

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
    private static final BigDecimal DAILY_BONUS_AMOUNT = BigDecimal.valueOf(500);

    public static final String PLATFORM_TREASURY = "PLATFORM_TREASURY";
    public static final BigDecimal DEFAULT_RAKE_RATE = new BigDecimal("0.15"); // 15% Platform Commission

    private final WalletAccountRepository accountRepository;
    private final WalletTransactionRepository transactionRepository;
    private final TransactionTemplate transactionTemplate;

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

    private boolean isPersistent() {
        return accountRepository != null && transactionRepository != null;
    }

    /**
     * Retrieves or lazily provisions a wallet account for a player with initial complimentary tokens.
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
        return applyChange(playerId, amount, type, idempotencyKey, gameId, description, meta, null);
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
        return applyChange(playerId, amount.negate(), type, idempotencyKey, gameId, description, meta, null);
    }

    /**
     * Daily Free Token Bonus Claim (500 complimentary tokens per 24-hour cycle).
     * The 24h check and the claim timestamp are written in the same versioned update as the credit.
     */
    public WalletTransactionDocument claimDailyFreeTokens(String playerId) {
        Instant now = Instant.now();
        String idempotencyKey = "DAILY_BONUS_" + playerId + "_" + now.toEpochMilli();
        return applyChange(playerId, DAILY_BONUS_AMOUNT, "PROMOTIONAL_CREDIT", idempotencyKey, null,
                "Daily complimentary login bonus tokens", Map.of("claimedAt", now.toString()),
                account -> {
                    if (account.getLastDailyClaimAt() != null) {
                        long hoursElapsed = Duration.between(account.getLastDailyClaimAt(), now).toHours();
                        if (hoursElapsed < 24) {
                            throw new IllegalStateException("Daily bonus already claimed. Next claim available in "
                                    + (24 - hoursElapsed) + " hours.");
                        }
                    }
                    account.setLastDailyClaimAt(now);
                });
    }

    private WalletTransactionDocument applyChange(String playerId, BigDecimal delta, String type,
                                                  String idempotencyKey, String gameId,
                                                  String description, Map<String, Object> meta,
                                                  Consumer<WalletAccountDocument> accountRule) {
        Objects.requireNonNull(playerId, "playerId");
        String key = idempotencyKey != null ? idempotencyKey : type + "_" + UUID.randomUUID();

        if (!isPersistent()) {
            return applyChangeInMemory(playerId, delta, type, key, gameId, description, meta, accountRule);
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
                        writeChange(playerId, delta, type, key, gameId, description, meta, accountRule));
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
                                                  String gameId, String description, Map<String, Object> meta,
                                                  Consumer<WalletAccountDocument> accountRule) {
        WalletAccountDocument account = accountRepository.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalStateException("Wallet not found for " + playerId));
        BigDecimal before = account.getFreePlayBalance();
        BigDecimal after = before.add(delta);
        if (after.signum() < 0) {
            throw insufficientBalance(playerId, before, delta.negate());
        }
        if (accountRule != null) {
            accountRule.accept(account);
        }
        account.setFreePlayBalance(after);
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
                account.setFreePlayBalance(account.getFreePlayBalance().subtract(delta));
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
                                                          String gameId, String description, Map<String, Object> meta,
                                                          Consumer<WalletAccountDocument> accountRule) {
        synchronized (memoryLock) {
            WalletTransactionDocument existing = memoryTransactions.get(key);
            if (existing != null) {
                return existing;
            }
            WalletAccountDocument account = memoryAccounts.computeIfAbsent(playerId, WalletAccountDocument::new);
            BigDecimal before = account.getFreePlayBalance();
            BigDecimal after = before.add(delta);
            if (after.signum() < 0) {
                throw insufficientBalance(playerId, before, delta.negate());
            }
            if (accountRule != null) {
                accountRule.accept(account);
            }
            account.setFreePlayBalance(after);
            account.setUpdatedAt(Instant.now());
            WalletTransactionDocument tx = new WalletTransactionDocument(
                    key, playerId, gameId, type, delta.abs(), before, after, account.getCurrency(), description, meta);
            memoryTransactions.put(key, tx);
            return tx;
        }
    }

    private static InsufficientBalanceException insufficientBalance(String playerId, BigDecimal balance, BigDecimal required) {
        return new InsufficientBalanceException("Insufficient free-play token balance for player " + playerId
                + ". Balance: " + balance + ", Required: " + required);
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

                // If human player dropped early, refund their unlost stake
                if (!pId.startsWith("BOT_") && refund.compareTo(BigDecimal.ZERO) > 0) {
                    String refundKey = "REFUND_" + gameId + "_" + pId;
                    try {
                        credit(pId, refund, "GAME_REFUND", refundKey, gameId,
                                "Unlost stake refund (" + penalty + " pts) for " + gameId + " (" + rId + ")",
                                Map.of("gameId", gameId, "penalty", penalty, "refund", refund, "variant", rId));
                    } catch (Exception e) {
                        log.error("[Wallet] RECONCILE REQUIRED: failed to credit refund {} to {} for gameId={}: {}",
                                refund, pId, gameId, e.getMessage());
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
                if (pId.equals(winnerPlayerId)) {
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
                log.error("[Wallet] RECONCILE REQUIRED: failed to credit platform rake for gameId={}: {}", gameId, e.getMessage());
            }
        }

        // Credit Winner
        int winnerRejoins = (rejoinCounts != null) ? rejoinCounts.getOrDefault(winnerPlayerId, 0) : 0;
        BigDecimal winnerTotalPaid = isPointsBased ? stakeTier : stakeTier.multiply(BigDecimal.valueOf(1 + winnerRejoins));
        BigDecimal winnerCreditAmount = isPointsBased ? stakeTier.add(netWinnerPrize) : netWinnerPrize;
        BigDecimal winnerNetDelta = isPointsBased ? netWinnerPrize : netWinnerPrize.subtract(winnerTotalPaid);

        if (!winnerPlayerId.startsWith("BOT_")) {
            String winKey = "WIN_" + gameId + "_" + winnerPlayerId;
            try {
                credit(winnerPlayerId, winnerCreditAmount, "GAME_WIN", winKey, gameId,
                        "Winner prize payout for " + gameId + " (" + rulesetId + ")",
                        Map.of("gameId", gameId, "grossPot", totalGrossPot, "rake", platformRake, "netPrize", netWinnerPrize));
            } catch (Exception e) {
                log.error("[Wallet] RECONCILE REQUIRED: failed to credit winner {} amount {} for gameId={}: {}",
                        winnerPlayerId, winnerCreditAmount, gameId, e.getMessage());
            }
        } else {
            // Bot win goes to house treasury
            String botWinKey = "BOT_WIN_" + gameId;
            try {
                credit(PLATFORM_TREASURY, winnerCreditAmount, "BOT_HOUSE_WIN", botWinKey, gameId,
                        "House Bot win payout for " + gameId,
                        Map.of("gameId", gameId, "botId", winnerPlayerId, "amount", winnerCreditAmount));
            } catch (Exception e) {
                log.error("[Wallet] RECONCILE REQUIRED: failed to credit house bot win for gameId={}: {}", gameId, e.getMessage());
            }
        }

        details.put(winnerPlayerId, new GameSettlementResult.PlayerSettlementDetail(
                winnerPlayerId, true, 0, stakeTier, BigDecimal.ZERO, BigDecimal.ZERO, netWinnerPrize, winnerNetDelta
        ));

        GameSettlementResult result = new GameSettlementResult(
                gameId, tableId, rulesetId, winnerPlayerId, stakeTier, totalGrossPot,
                DEFAULT_RAKE_RATE, platformRake, netWinnerPrize, details
        );

        settlementCache.put(gameId, result);
        log.info("[Wallet] Settlement complete for gameId={}: GrossPot={}, PlatformRake={}, WinnerPrize={}, WinnerNetDelta={}",
                gameId, totalGrossPot, platformRake, netWinnerPrize, winnerNetDelta);

        return result;
    }

    /**
     * Settles a finished table match: debits loser stakes and credits winner with net pot (legacy support).
     */
    public void settleGame(String gameId, String winnerPlayerId, List<String> loserPlayerIds, BigDecimal stake) {
        if (stake.compareTo(BigDecimal.ZERO) <= 0 || winnerPlayerId == null) return;

        BigDecimal totalPot = BigDecimal.ZERO;

        for (String loserId : loserPlayerIds) {
            if (!loserId.startsWith("BOT_")) {
                String debitKey = "DEBIT_" + gameId + "_" + loserId;
                try {
                    debit(loserId, stake, "GAME_ENTRY", debitKey, gameId, "Game entry stake for " + gameId, null);
                    totalPot = totalPot.add(stake);
                } catch (Exception e) {
                    log.warn("[Wallet] Failed to debit loser {}: {}", loserId, e.getMessage());
                }
            } else {
                totalPot = totalPot.add(stake);
            }
        }

        if (!winnerPlayerId.startsWith("BOT_") && totalPot.compareTo(BigDecimal.ZERO) > 0) {
            String creditKey = "WIN_" + gameId + "_" + winnerPlayerId;
            credit(winnerPlayerId, totalPot, "GAME_WIN", creditKey, gameId, "Winner prize payout for " + gameId,
                    Map.of("gameId", gameId, "pot", totalPot));
        }
    }

    public GameSettlementResult getSettlement(String gameId) {
        return settlementCache.get(gameId);
    }

    public BigDecimal getPlatformTreasuryBalance() {
        return getOrCreateWallet(PLATFORM_TREASURY).getFreePlayBalance();
    }

    /**
     * Real-Money Deposit into player wallet. Must only be called after a payment gateway
     * has confirmed the payment; the controller gates this behind a configuration flag.
     */
    public WalletTransactionDocument depositCash(String playerId, BigDecimal amount, String method) {
        String key = "DEP_" + playerId + "_" + UUID.randomUUID();
        return credit(playerId, amount, "CASH_DEPOSIT", key, null,
                "Real Cash Deposit via " + (method != null ? method : "UPI"),
                Map.of("method", method != null ? method : "UPI", "timestamp", Instant.now().toString()));
    }

    /**
     * Real-Money Withdrawal to player's verified Bank / UPI ID.
     */
    public WalletTransactionDocument withdrawCash(String playerId, BigDecimal amount, String method, String destination) {
        String key = "WTH_" + playerId + "_" + UUID.randomUUID();
        return debit(playerId, amount, "CASH_WITHDRAWAL", key, null,
                "Real Cash Withdrawal to " + (destination != null ? destination : "Bank Account"),
                Map.of("method", method != null ? method : "UPI_PAYOUT", "destination", destination != null ? destination : ""));
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
