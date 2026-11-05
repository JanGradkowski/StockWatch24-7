package org.example.stockwatch247.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The CPU threads a check runs its independent scans on, at the same time as its own thread works through the
 * rest, and that a large Elliott count spreads its starting points over. One pool for all checks; by default one
 * thread fewer than the machine has cores.
 */
@Component
public class AnalysisPool {
    private final ExecutorService executor;
    private final int size;
    private final java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();

    public AnalysisPool(@Value("${analysis.pool-threads:0}") int threads,
                        @Value("${analysis.parallel-seeds:true}") boolean parallelSeeds) {
        size = threads > 0 ? threads : Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        executor = Executors.newFixedThreadPool(size, Thread.ofPlatform().daemon(true).name("analysis-", 0).factory());
        if (parallelSeeds) ElliottWaveDetectionService.runSeedsOn(executor, this::seedHelpers);
    }

    /** A check started (see {@link #seedHelpers}). */
    void checkStarted() {
        checks.incrementAndGet();
    }

    void checkEnded() {
        checks.decrementAndGet();
    }

    /**
     * Helper threads one Elliott count may take now: the pool's threads shared among the checks running. A check
     * running alone gets nearly all of them, to finish fast; many at once (a market-wide run) get a few each,
     * because a count spread wide spends much of its threads' time waiting on each other.
     */
    int seedHelpers() {
        return Math.max(0, size / Math.max(1, checks.get()) - 1);
    }

    /** Whether other checks are running beside the caller's. */
    boolean checksSideBySide() {
        return checks.get() > 1;
    }

    public Future<?> submit(Runnable work) {
        return executor.submit(work);
    }

    @PreDestroy
    void stop() {
        ElliottWaveDetectionService.stopRunningSeedsOn(executor);
        executor.shutdownNow();
    }
}
