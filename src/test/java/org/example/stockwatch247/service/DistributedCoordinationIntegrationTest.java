package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@Transactional
class DistributedCoordinationIntegrationTest {
    @Autowired
    private RequestRateLimiter rateLimiter;

    @Autowired
    private PostgresMarketDataSyncCoordinator syncCoordinator;

    @Autowired
    private AlertCheckJobStore jobStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SharedQuoteCache quoteCache;

    @Test
    void rateLimitCounterIsAtomicAndSharedThroughPostgres() {
        String key = "integration:" + UUID.randomUUID();

        assertThat(rateLimiter.tryAcquire(key, 2, Duration.ofMinutes(1))).isTrue();
        assertThat(rateLimiter.tryAcquire(key, 2, Duration.ofMinutes(1))).isTrue();
        assertThat(rateLimiter.tryAcquire(key, 2, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void marketDataLeaseIsNonBlockingAndSuccessfulSyncStartsSharedCooldown() {
        String symbol = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        MarketDataSyncCoordinator.Claim first = syncCoordinator.tryClaim(symbol, "1d", 600, 60);
        MarketDataSyncCoordinator.Claim concurrent = syncCoordinator.tryClaim(symbol, "1d", 600, 60);

        assertThat(first.status()).isEqualTo(MarketDataSyncCoordinator.ClaimStatus.ACQUIRED);
        assertThat(concurrent.status()).isEqualTo(MarketDataSyncCoordinator.ClaimStatus.IN_PROGRESS);

        syncCoordinator.markSuccessful(first);
        MarketDataSyncCoordinator.Claim duringCooldown = syncCoordinator.tryClaim(symbol, "1d", 600, 60);
        assertThat(duringCooldown.status()).isEqualTo(MarketDataSyncCoordinator.ClaimStatus.RECENT_SUCCESS);
    }

    @Test
    void alertJobsSurviveInTheDatabaseAndCannotBeClaimedTwice() {
        String symbol = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        Instant scheduledFor = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);

        assertThat(jobStore.enqueue(symbol, TimeInterval.DAILY, scheduledFor)).isEqualTo(1);
        assertThat(jobStore.enqueue(symbol, TimeInterval.DAILY, scheduledFor)).isZero();
        assertThat(jobStore.enqueue(symbol, TimeInterval.DAILY, scheduledFor.plusSeconds(1))).isEqualTo(1);

        AlertCheckJobStore.AlertCheckJob job = jobStore.claimNextForSymbol(Duration.ofMinutes(5), symbol).orElseThrow();
        assertThat(job.symbol()).isEqualTo(symbol);
        assertThat(job.scheduledFor()).isEqualTo(scheduledFor);
        // A second app instance must not overtake an in-flight earlier run for the same symbol.
        assertThat(jobStore.claimNextForSymbol(Duration.ofMinutes(5), symbol)).isEmpty();

        jobStore.complete(job.id());
        assertThat(jobStore.enqueue(symbol, TimeInterval.DAILY, scheduledFor)).isZero();
        AlertCheckJobStore.AlertCheckJob nextJob = jobStore
                .claimNextForSymbol(Duration.ofMinutes(5), symbol)
                .orElseThrow();
        assertThat(nextJob.scheduledFor()).isEqualTo(scheduledFor.plusSeconds(1));
    }

    @Test
    void scheduleCheckpointInsertionIsIdempotentEvenWithMultipleAppInstances() {
        Instant scheduledFor = Instant.parse("2099-01-01T00:00:00Z");

        jobStore.enqueueScheduledRun(TimeInterval.DAILY, scheduledFor);

        assertThat(jobStore.enqueueScheduledRun(TimeInterval.DAILY, scheduledFor)).isZero();
        assertThat(jobStore.findLatestScheduledRun(TimeInterval.DAILY)).contains(scheduledFor);
    }

    @Test
    void processingJobIsReclaimedAfterItsCrashedWorkerLeaseExpires() {
        String symbol = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        Instant scheduledFor = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        jobStore.enqueue(symbol, TimeInterval.DAILY, scheduledFor);
        AlertCheckJobStore.AlertCheckJob abandoned = jobStore
                .claimNextForSymbol(Duration.ofMinutes(5), symbol)
                .orElseThrow();
        jdbcTemplate.update(
                "update alert_check_jobs set lease_until = current_timestamp - interval '1 second' where id = ?",
                abandoned.id());

        AlertCheckJobStore.AlertCheckJob reclaimed = jobStore
                .claimNextForSymbol(Duration.ofMinutes(5), symbol)
                .orElseThrow();

        assertThat(reclaimed.id()).isEqualTo(abandoned.id());
        assertThat(reclaimed.attempts()).isEqualTo(abandoned.attempts() + 1);
    }

    @Test
    void unfilteredWorkerClaimAcceptsItsNullSymbolParameter() {
        // The test transaction rolls back any claimed row; this exercises the exact
        // null-symbol SQL path used by the scheduled production worker.
        jobStore.claimNext(Duration.ofSeconds(1));
    }

    @Test
    void liveQuoteCacheIsSharedThroughPostgres() {
        String symbol = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        quoteCache.put(symbol, Map.of("symbol", symbol, "price", 123.45));

        assertThat(quoteCache.getFresh(symbol, 60)).hasValueSatisfying(quote -> {
            assertThat(quote.get("symbol")).isEqualTo(symbol);
            assertThat(quote.get("price")).isEqualTo(123.45);
        });
    }
}
