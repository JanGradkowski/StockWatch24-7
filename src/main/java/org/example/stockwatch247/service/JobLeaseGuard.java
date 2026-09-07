package org.example.stockwatch247.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Heartbeats use separate short transactions; the publishing transaction checks ownership again. */
@Component
public class JobLeaseGuard {
    private static final ThreadLocal<Claim> CURRENT = new ThreadLocal<>();
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(2,
            Thread.ofPlatform().daemon(true).name("job-heartbeat-", 0).factory());

    public Claim protect(BooleanSupplier renew, Duration lease) {
        Claim claim = new Claim(renew);
        CURRENT.set(claim);
        long period = Math.max(1, lease.toSeconds() / 3);
        claim.heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try { if (!renew.getAsBoolean()) claim.lost.set(true); }
            catch (RuntimeException e) { claim.lost.set(true); }
        }, period, period, TimeUnit.SECONDS);
        return claim;
    }

    public static void requireOwnership() {
        Claim claim = CURRENT.get();
        if (claim != null && (claim.lost.get() || !claim.renew.getAsBoolean()))
            throw new IllegalStateException("Job lease ownership was lost.");
    }

    @PreDestroy void stop() { heartbeats.shutdownNow(); }

    public static final class Claim implements AutoCloseable {
        private final BooleanSupplier renew;
        private final AtomicBoolean lost = new AtomicBoolean();
        private ScheduledFuture<?> heartbeat;
        private Claim(BooleanSupplier renew) { this.renew = renew; }
        @Override public void close() {
            heartbeat.cancel(false);
            CURRENT.remove();
        }
    }
}
