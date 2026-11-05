package org.example.stockwatch247.service;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A scheduled check waits for candle downloads only so long; the provider is still asked once. */
class MarketDataDownloadWaitTest {

    @Test
    void aCheckStopsWaitingAtItsLimitAndTheDownloadFinishesInTheBackground() throws Exception {
        var release = new CountDownLatch(1);
        var requests = new AtomicInteger();
        var context = context(() -> {
            requests.incrementAndGet();
            release.await(10, TimeUnit.SECONDS);
        });

        long started = System.nanoTime();
        MarketDataService.CandleSyncResult first;
        MarketDataService.CandleSyncResult second;
        try (var wait = MarketDataService.waitForDownloadsAtMost(Duration.ofMillis(150))) {
            first = context.service().syncCandlesForAnalysis("AAPL", "1d", 300);
            // The check's wait is spent: a second download of the same series joins the first without waiting.
            second = context.service().syncCandlesForAnalysis("AAPL", "1d", 300);
        }
        long waitedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(first.source()).isEqualTo(MarketDataService.CandleSource.DOWNLOAD_CONTINUES);
        assertThat(second.source()).isEqualTo(MarketDataService.CandleSource.DOWNLOAD_CONTINUES);
        assertThat(first.successful()).isTrue();
        assertThat(waitedMillis).isLessThan(2_000);

        release.countDown();
        // The download the check stopped waiting for stores its candles, and was the only request.
        verify(context.candles(), timeout(5_000)).saveAll(any());
        assertThat(requests).hasValue(1);
    }

    @Test
    void outsideACheckASyncWaitsForTheProviderAsBefore() throws Exception {
        var context = context(() -> Thread.sleep(300));

        var result = context.service().syncCandlesForAnalysis("AAPL", "1d", 300);

        assertThat(result.source()).isEqualTo(MarketDataService.CandleSource.TWELVE_DATA);
        assertThat(result.candlesSynced()).isEqualTo(1);
    }

    @Test
    void aPrefetchWaitsForItsDownloadAndACheckArrivingMeanwhileJoinsIt() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requests = new AtomicInteger();
        var context = context(() -> {
            requests.incrementAndGet();
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
        });

        try (var lane = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var prefetch = lane.submit(() -> {
                try (var priority = MarketDataProviderRequestBudget.background()) {
                    return context.service().prefetchForAnalysis("AAPL", "1d", 300);
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            MarketDataService.CandleSyncResult check;
            try (var wait = MarketDataService.waitForDownloadsAtMost(Duration.ofSeconds(5))) {
                release.countDown();
                check = context.service().syncCandlesForAnalysis("AAPL", "1d", 300);
            }
            assertThat(prefetch.get(5, TimeUnit.SECONDS).source()).isEqualTo(MarketDataService.CandleSource.TWELVE_DATA);
            assertThat(check.source()).isIn(MarketDataService.CandleSource.TWELVE_DATA, MarketDataService.CandleSource.CACHE);
        }
        assertThat(requests).hasValue(1);
    }

    @FunctionalInterface
    private interface Provider {
        void respond() throws Exception;
    }

    private Context context(Provider provider) {
        CandleRepository candles = mock(CandleRepository.class);
        StockAssetRepository assets = mock(StockAssetRepository.class);
        TwelveDataService twelveData = mock(TwelveDataService.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("AAPL");
        asset.setCompanyName("Apple Inc.");
        asset.setExchange("NASDAQ");
        asset.setCurrency("USD");
        when(twelveData.normalizeSymbol(anyString())).thenAnswer(call -> call.<String>getArgument(0).trim().toUpperCase());
        when(twelveData.getTimeSeries(eq("AAPL"), eq("1day"), anyInt())).thenAnswer(call -> {
            provider.respond();
            return List.of(new MarketDataBar("AAPL", 86_400L, 100, 105, 99, 104, 1_000L));
        });
        when(assets.findByTickerSymbolIgnoreCase("AAPL")).thenReturn(Optional.of(asset));
        when(candles.findBySymbolAndTimeIntervalAndTimestampIn(anyString(), anyString(), anyCollection())).thenReturn(List.of());
        MarketDataService service = new MarketDataService(candles, assets, twelveData, mock(YahooFinanceService.class),
                new InMemoryMarketDataSyncCoordinator(() -> 1_800_000_000L), mock(MarketDataHistoryStateStore.class),
                60, 600, 3_600, 180);
        return new Context(service, candles);
    }

    private record Context(MarketDataService service, CandleRepository candles) { }
}
