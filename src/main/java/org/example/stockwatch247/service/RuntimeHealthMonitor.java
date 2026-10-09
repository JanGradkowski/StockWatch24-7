package org.example.stockwatch247.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class RuntimeHealthMonitor {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RuntimeHealthMonitor.class);
    private Instant previous = Instant.now();
    @Scheduled(fixedDelay = 30000)
    public synchronized void checkPause() {
        Instant now = Instant.now();
        long gap = java.time.Duration.between(previous, now).toSeconds();
        previous = now;
        if (gap > 90 || gap < 0) log.warn(
                "Process timing interruption: gapSeconds={}; elapsed job durations include this gap; expired leases must be reclaimed", gap);
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void checkJit() {
        if (java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:TieredStopAtLevel=1"))
            log.warn("Analysis runtime uses TieredStopAtLevel=1; select StockWatch full JIT for sustained processing");
    }
}
