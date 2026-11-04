package org.example.stockwatch247.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Downloads the candles of the next queued checks before the worker reaches them, one download at a time, so a
 * check usually starts with its candles stored and its own refresh still inside the provider's cooldown (no
 * second request). It looks only a few checks ahead: a download much earlier would fall out of the cooldown
 * before its check and be made again. A failed download is left to the check, which retries as it always has.
 */
@Component
public class CandlePrefetcher {
    private static final Logger log = LoggerFactory.getLogger(CandlePrefetcher.class);

    @Autowired private InfrastructureRecovery infrastructure = new InfrastructureRecovery();
    private final AlertCheckJobStore jobs;
    private final MarketDataService marketData;
    private final TechnicalIndicatorEnrichmentService enrichment;
    private final boolean enabled;
    private final int ahead;
    /** Checks whose candles were already downloaded ahead, so each is fetched once. */
    private final Set<Long> fetched = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > 4096;
        }
    });

    public CandlePrefetcher(AlertCheckJobStore jobs, MarketDataService marketData, TechnicalIndicatorEnrichmentService enrichment,
                            @Value("${alerts.schedule.prefetch-enabled:${alerts.schedule.enabled:true}}") boolean enabled,
                            @Value("${alerts.schedule.prefetch-ahead:3}") int ahead) {
        this.jobs = jobs;
        this.marketData = marketData;
        this.enrichment = enrichment;
        this.enabled = enabled;
        this.ahead = Math.max(0, ahead);
    }

    @Scheduled(fixedDelayString = "${alerts.schedule.prefetch-delay-ms:1000}",
            initialDelayString = "${alerts.schedule.initial-delay-ms:30000}")
    public void prefetchUpcoming() {
        if (!enabled) return;
        infrastructure.run("candle-prefetch", this::prefetchNext);
    }

    /** Downloads the candles of the next queued checks not fetched yet, one after another; returns how many. */
    synchronized int prefetchNext() {
        int downloaded = 0;
        for (AlertCheckJobStore.AlertCheckJob job : jobs.upcoming(ahead)) {
            if (!fetched.add(job.id())) continue;
            try (var priority = MarketDataProviderRequestBudget.background()) {
                marketData.prefetchForAnalysis(job.symbol(), job.interval().analysisApiValue(),
                        SignalInputPreparation.elliottHistory(enrichment, job.interval()));
                downloaded++;
            } catch (RuntimeException failure) {
                if (InfrastructureRecovery.isDatabaseUnavailable(failure)) throw failure;
                log.info("Candle prefetch failed for {} {}; the check will download them itself: {}",
                        job.symbol(), job.interval(), failure.getMessage());
            }
        }
        return downloaded;
    }
}
