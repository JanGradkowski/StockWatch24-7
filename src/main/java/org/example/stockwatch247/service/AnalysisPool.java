package org.example.stockwatch247.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The CPU threads a check runs its independent scans on, at the same time as its own thread works through the
 * rest. One pool for all checks; by default one thread fewer than the machine has cores.
 */
@Component
public class AnalysisPool {
    private final ExecutorService executor;

    public AnalysisPool(@Value("${analysis.pool-threads:0}") int threads) {
        int size = threads > 0 ? threads : Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        executor = Executors.newFixedThreadPool(size, Thread.ofPlatform().daemon(true).name("analysis-", 0).factory());
    }

    public Future<?> submit(Runnable work) {
        return executor.submit(work);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
