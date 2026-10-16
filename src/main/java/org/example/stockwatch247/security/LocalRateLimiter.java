package org.example.stockwatch247.security;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Fixed windows kept in this instance's memory. Used for limits that only protect this server's own
 * capacity (chart data, searches of the local index, alert edits), where a database write on every
 * request cost more than the request itself. Limits that guard accounts or a shared provider quota stay
 * in {@link RequestRateLimiter}, which every instance shares.
 */
final class LocalRateLimiter {
    private static final int PRUNE_ABOVE = 20_000;
    private static final long FORGET_AFTER_MILLIS = Duration.ofHours(1).toMillis();

    private record Window(long startedAt, int count) { }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    LocalRateLimiter() {
        this(System::currentTimeMillis);
    }

    LocalRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    boolean tryAcquire(String key, int maximumRequests, Duration window) {
        long now = clock.getAsLong();
        long length = window.toMillis();
        Window current = windows.compute(key, (ignored, previous) -> previous == null || now - previous.startedAt() >= length
                ? new Window(now, 1)
                : new Window(previous.startedAt(), previous.count() + 1));
        if (windows.size() > PRUNE_ABOVE) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= FORGET_AFTER_MILLIS);
        }
        return current.count() <= Math.max(1, maximumRequests);
    }
}
