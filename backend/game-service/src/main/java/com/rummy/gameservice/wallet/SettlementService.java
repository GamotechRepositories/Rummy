package com.rummy.gameservice.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rummy.gameservice.cluster.ClusterNodeService;
import com.rummy.gameservice.persistence.document.SettlementJobDocument;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Pays out finished matches off the table's thread, so a slow wallet database never stalls a game.
 *
 * <p>Each payout is first recorded as a durable job, then run on a bounded worker pool:
 * lock the stakes ({@link StakeEscrowService#beginSettlement}), pay ({@link WalletService#settleMatch},
 * idempotent per game), release the stakes. A failed payout is retried with backoff; a payout whose
 * node died is taken over by a surviving node. The table is told the result through a {@link Listener}.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    /** The first attempt normally finishes long before this; only then may the retry scan pick a job up. */
    private static final Duration FIRST_RETRY_AFTER = Duration.ofSeconds(60);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    public record Job(String gameId, String tableId, String rulesetId, long stakeTier, String winnerId,
                      Map<String, Integer> finalScores, List<String> playerIds, Map<String, Integer> rejoinCounts) {
    }

    /** Called on the settlement thread once the payout is final. */
    public interface Listener {
        void settled(GameSettlementResult result);

        /** The payout was refused (the match's stakes were refunded or taken over by another node). */
        void cancelled();
    }

    private static final Listener NO_LISTENER = new Listener() {
        @Override
        public void settled(GameSettlementResult result) {
        }

        @Override
        public void cancelled() {
        }
    };

    private final WalletService wallet;
    private final SettlementJobStore jobs;
    private final ClusterNodeService cluster;
    private final ObjectMapper json;
    private final ExecutorService executor;
    private final boolean inline;
    private final Semaphore permits;
    private StakeEscrowService escrows;

    @Autowired
    public SettlementService(WalletService wallet,
                             SettlementJobStore jobs,
                             @Autowired(required = false) ClusterNodeService cluster,
                             ObjectMapper json,
                             @Value("${rummy.settlement.max-concurrent:32}") int maxConcurrent) {
        this(wallet, jobs, cluster, json, maxConcurrent, false);
    }

    private SettlementService(WalletService wallet, SettlementJobStore jobs, ClusterNodeService cluster,
                              ObjectMapper json, int maxConcurrent, boolean inline) {
        this.wallet = Objects.requireNonNull(wallet);
        this.jobs = Objects.requireNonNull(jobs);
        this.cluster = cluster;
        this.json = json;
        this.inline = inline;
        this.executor = inline ? null : Executors.newVirtualThreadPerTaskExecutor();
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
    }

    /** Runs payouts synchronously on the caller's thread with an in-memory job queue (tests, no Spring). */
    public static SettlementService inline(WalletService wallet) {
        return new SettlementService(wallet, SettlementJobStore.inMemory(), null, new ObjectMapper(), 1, true);
    }

    @Autowired(required = false)
    public void setEscrows(StakeEscrowService escrows) {
        this.escrows = escrows;
    }

    /** Queues the payout of a finished match. Returns immediately (unless inline). */
    public void submit(Job job, Listener listener) {
        Runnable work = () -> {
            try {
                jobs.insert(job.gameId(), json.writeValueAsString(job), FIRST_RETRY_AFTER);
            } catch (Exception e) {
                log.error("[Settlement] Could not record payout job for game {}: {}", job.gameId(), e.getMessage());
            }
            attempt(job, 0, listener);
        };
        if (inline) {
            work.run();
        } else {
            executor.execute(work);
        }
    }

    private void attempt(Job job, int previousAttempts, Listener listener) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            if (escrows != null && !escrows.beginSettlement(job.gameId())) {
                jobs.markFinal(job.gameId(), SettlementJobStore.Status.CANCELLED);
                listener.cancelled();
                return;
            }
            GameSettlementResult result = wallet.settleMatch(job.gameId(), job.tableId(), job.rulesetId(),
                    BigDecimal.valueOf(job.stakeTier()), job.winnerId(), job.finalScores(), job.playerIds(),
                    job.rejoinCounts());
            if (escrows != null) {
                escrows.completeSettlement(job.gameId());
            }
            jobs.markFinal(job.gameId(), SettlementJobStore.Status.DONE);
            if (result != null) {
                log.info("[Settlement] Paid game {}: pot={}, rake={}, netWinnerPrize={}", job.gameId(),
                        result.totalGrossPot(), result.platformRakeAmount(), result.netWinnerPrize());
            }
            listener.settled(result);
        } catch (Exception e) {
            int attempts = previousAttempts + 1;
            long backoffSeconds = Math.min(MAX_BACKOFF.toSeconds(), 5L << Math.min(attempts, 10));
            log.error("[Settlement] Payout of game {} failed (attempt {}), retrying in {}s: {}",
                    job.gameId(), attempts, backoffSeconds, e.getMessage(), e);
            try {
                jobs.markRetry(job.gameId(), attempts, Instant.now().plusSeconds(backoffSeconds));
            } catch (Exception markError) {
                log.error("[Settlement] RECONCILE REQUIRED: could not schedule retry for game {}: {}", job.gameId(), markError.getMessage());
            }
        } finally {
            permits.release();
        }
    }

    /** Retries failed payouts and takes over payouts left behind by dead nodes. */
    @Scheduled(fixedDelayString = "${rummy.settlement.retry-interval-ms:10000}",
            initialDelayString = "${rummy.settlement.retry-initial-delay-ms:30000}")
    public int retryDue() {
        int started = 0;
        try {
            for (SettlementJobDocument doc : jobs.due(200)) {
                String holder = doc.getHolderNode();
                if (!jobs.nodeId().equals(holder)) {
                    if (cluster == null || cluster.isAlive(holder) || !jobs.claim(doc.getGameId(), holder)) {
                        continue;
                    }
                    if (escrows != null) {
                        escrows.adoptForSettlement(doc.getGameId(), holder);
                    }
                    log.warn("[Settlement] Took over payout of game {} from dead node {}", doc.getGameId(), holder);
                }
                Job job = json.readValue(doc.getPayload(), Job.class);
                int attempts = doc.getAttempts();
                // Push the next attempt out first, so a slow run is not picked up again by the next scan.
                jobs.markRetry(job.gameId(), attempts, Instant.now().plus(FIRST_RETRY_AFTER));
                if (inline) {
                    attempt(job, attempts, NO_LISTENER);
                } else {
                    executor.execute(() -> attempt(job, attempts, NO_LISTENER));
                }
                started++;
            }
        } catch (Exception e) {
            log.error("[Settlement] Retry scan failed: {}", e.getMessage(), e);
        }
        return started;
    }

    /** Lets queued payouts finish before the process exits. */
    @PreDestroy
    public void shutdown() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                log.warn("[Settlement] Payouts still running at shutdown; they will be retried from their jobs");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
