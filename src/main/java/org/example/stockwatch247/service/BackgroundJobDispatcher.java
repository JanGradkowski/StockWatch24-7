package org.example.stockwatch247.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/** Fixed capacity across known queues; at most four tasks can be submitted or running. */
@Component
public class BackgroundJobDispatcher {
    private final Map<String, Semaphore> slots = Map.of("alerts", new Semaphore(2), "insider", new Semaphore(1), "email", new Semaphore(1));
    private final ExecutorService workers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("durable-worker-", 0).factory());
    public void submit(String queue, Runnable work) {
        Semaphore slot = slots.get(queue);
        if (slot == null) throw new IllegalArgumentException("Unknown work queue.");
        if (!slot.tryAcquire()) return;
        try { workers.execute(() -> { try { work.run(); } finally { slot.release(); } }); }
        catch (RuntimeException e) { slot.release(); throw e; }
    }
    @PreDestroy void stop() { workers.shutdownNow(); }
}
