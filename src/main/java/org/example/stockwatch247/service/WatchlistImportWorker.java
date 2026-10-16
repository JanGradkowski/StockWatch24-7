package org.example.stockwatch247.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Imports make provider calls, so the timer only hands a batch to the background workers; it never
 * runs one on the shared scheduler threads that also serve email, recovery and cleanup timers.
 */
@Component
public class WatchlistImportWorker {
    @org.springframework.beans.factory.annotation.Autowired
    private InfrastructureRecovery recovery = new InfrastructureRecovery();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BackgroundJobDispatcher dispatcher;
    private final WatchlistImportService imports;
    private final boolean enabled;
    public WatchlistImportWorker(WatchlistImportService imports,@Value("${watchlists.imports.worker-enabled:true}") boolean enabled) {
        this.imports=imports;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${watchlists.imports.worker-delay-ms:1000}",initialDelayString="${watchlists.imports.initial-delay-ms:10000}")
    public void run() {
        if(!enabled || !recovery.ready()) return;
        if (dispatcher == null) processBatch();
        else dispatcher.submit("imports", this::processBatch);
    }

    /** Up to ten queued imports, one after another. */
    public void processBatch() {
        try {
        for(int i=0;i<10;i++) {
            var work=imports.claim(); if(work.isEmpty()) return;
            try { imports.process(work.get()); } catch(RuntimeException error) {
                if (InfrastructureRecovery.isDatabaseUnavailable(error)) throw error;
                imports.fail(work.get(),error);
            }
        }
        } catch (RuntimeException failure) { if (!recovery.failed(failure, "watchlist-import")) throw failure; }
    }
}
