package org.example.stockwatch247.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WatchlistImportWorker {
    private final WatchlistImportService imports;
    private final boolean enabled;
    public WatchlistImportWorker(WatchlistImportService imports,@Value("${watchlists.imports.worker-enabled:true}") boolean enabled) {
        this.imports=imports;this.enabled=enabled;
    }
    @Scheduled(fixedDelayString="${watchlists.imports.worker-delay-ms:1000}",initialDelayString="${watchlists.imports.initial-delay-ms:10000}")
    public void run() {
        if(!enabled) return;
        for(int i=0;i<10;i++) {
            var work=imports.claim(); if(work.isEmpty()) return;
            try { imports.process(work.get()); } catch(RuntimeException error) { imports.fail(work.get(),error); }
        }
    }
}
