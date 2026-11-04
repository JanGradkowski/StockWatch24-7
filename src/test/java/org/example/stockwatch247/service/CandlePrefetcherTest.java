package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandlePrefetcherTest {

    @Test
    void downloadsTheNextQueuedChecksOnceEachAndCarriesOnPastAFailure() {
        var jobs = mock(AlertCheckJobStore.class);
        var market = mock(MarketDataService.class);
        Instant run = Instant.parse("2026-11-02T23:00:00Z");
        var first = new AlertCheckJobStore.AlertCheckJob(1, "AAPL", TimeInterval.DAILY, run, 0);
        var second = new AlertCheckJobStore.AlertCheckJob(2, "MSFT", TimeInterval.DAILY, run, 0);
        var third = new AlertCheckJobStore.AlertCheckJob(3, "KO", TimeInterval.WEEKLY, run, 0);
        when(jobs.upcoming(3)).thenReturn(List.of(first, second), List.of(first, second, third));
        when(market.prefetchForAnalysis(eq("AAPL"), eq("1d"), anyInt())).thenThrow(new IllegalStateException("provider down"));
        var prefetcher = new CandlePrefetcher(jobs, market, new TechnicalIndicatorEnrichmentService(), true, 3);

        assertThat(prefetcher.prefetchNext()).isEqualTo(1);
        assertThat(prefetcher.prefetchNext()).isEqualTo(1);

        verify(market, times(1)).prefetchForAnalysis(eq("AAPL"), eq("1d"), anyInt());
        verify(market, times(1)).prefetchForAnalysis(eq("MSFT"), eq("1d"), anyInt());
        verify(market, times(1)).prefetchForAnalysis(eq("KO"), eq("1wk"), anyInt());
    }
}
