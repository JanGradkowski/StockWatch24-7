package org.example.stockwatch247.service;

import java.util.function.LongSupplier;

/** Shared, bounded outage cooldown. Successful probes reset the failure sequence. */
final class RetryBackoff {
    private final LongSupplier clock;
    private long availableAt;
    private int failures;
    RetryBackoff() { this(System::nanoTime); }
    RetryBackoff(LongSupplier clock) { this.clock = clock; }
    synchronized boolean ready() { return failures == 0 || clock.getAsLong() - availableAt >= 0; }
    synchronized boolean recovering() { return failures > 0; }
    synchronized long failed() {
        long seconds = Math.min(300, 15L << Math.min(5, failures++));
        seconds += java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 6);
        availableAt = clock.getAsLong() + seconds * 1_000_000_000L;
        return seconds;
    }
    synchronized void succeeded() { failures = 0; availableAt = 0; }
}
