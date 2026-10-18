package org.example.stockwatch247.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/** Fixed capacity across known queues; at most four tasks run at once, one per queue. */
@Component
public class BackgroundJobDispatcher {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BackgroundJobDispatcher.class);
    @org.springframework.beans.factory.annotation.Autowired
    private InfrastructureRecovery recovery = new InfrastructureRecovery();
    private final Map<String, Semaphore> slots = Map.of("alerts", new Semaphore(1), "insider", new Semaphore(1),
            "email", new Semaphore(1), "imports", new Semaphore(1), "custom-alerts", new Semaphore(1));
    private final ExecutorService workers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("durable-worker-", 0).factory());
    public void submit(String queue, Runnable work) {
        if (!recovery.ready()) return;
        Semaphore slot = slots.get(queue);
        if (slot == null) throw new IllegalArgumentException("Unknown work queue.");
        if (!slot.tryAcquire()) return;
        try { workers.execute(() -> {
            try { work.run(); }
            catch (RuntimeException failure) {
                if (!recovery.failed(failure, queue))
                    log.error("Background work failed: queue={} failureType={}", queue, failure.getClass().getSimpleName());
            } finally { slot.release(); }
        }); }
        catch (RuntimeException e) { slot.release(); throw e; }
    }
    @PreDestroy void stop() { workers.shutdownNow(); }
}
