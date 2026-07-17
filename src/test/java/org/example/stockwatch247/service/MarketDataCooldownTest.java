package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataCooldownTest {

    @Test
    void forcedRefreshReusesRecentSuccessfulSyncForTheSameSymbolAndInterval() {
        AtomicLong now = new AtomicLong(1_800_000_000L);
        TestContext context = context(now, List.of(bar(1, 100, 105, 99, 104)), List.of());

        MarketDataService.CandleSyncResult first = context.service()
                .syncCandles("AAPL", "1d", null, true);
        now.addAndGet(10L);
        MarketDataService.CandleSyncResult second = context.service()
                .syncCandles("AAPL", "1d", null, true);

        assertThat(first.source()).isEqualTo(MarketDataService.CandleSource.TWELVE_DATA);
        assertThat(first.candlesSynced()).isEqualTo(1);
        assertThat(second.source()).isEqualTo(MarketDataService.CandleSource.CACHE);
        assertThat(second.candlesSynced()).isZero();
        verify(context.twelveDataService(), times(1)).getTimeSeries("AAPL", "1day", 1000);
        verify(context.candleRepository(), times(1))
                .findBySymbolAndTimeIntervalAndTimestampIn(anyString(), anyString(), anyCollection());
        verify(context.candleRepository(), times(1)).saveAll(any());
    }

    @Test
    void refreshAfterCooldownFetchesAgainButDoesNotPersistUnchangedCandles() {
        AtomicLong now = new AtomicLong(1_800_000_000L);
        MarketDataBar providerBar = bar(1, 100, 105, 99, 104);
        Candle existing = candle(providerBar);
        TestContext context = context(now, List.of(providerBar), List.of(existing));
        when(context.candleRepository().findBySymbolAndTimeIntervalAndTimestampIn(
                anyString(), anyString(), anyCollection()))
                .thenReturn(List.of(), List.of(existing));

        MarketDataService.CandleSyncResult first = context.service()
                .syncCandles("AAPL", "1d", null, true);
        now.addAndGet(599L);
        MarketDataService.CandleSyncResult withinCooldown = context.service()
                .syncCandles("AAPL", "1d", null, true);
        now.addAndGet(2L);
        MarketDataService.CandleSyncResult afterCooldown = context.service()
                .syncCandles("AAPL", "1d", null, true);

        assertThat(first.candlesSynced()).isEqualTo(1);
        assertThat(withinCooldown.source()).isEqualTo(MarketDataService.CandleSource.CACHE);
        assertThat(afterCooldown.source()).isEqualTo(MarketDataService.CandleSource.TWELVE_DATA);
        assertThat(afterCooldown.candlesSynced()).isZero();
        verify(context.twelveDataService(), times(2)).getTimeSeries("AAPL", "1day", 1000);
        verify(context.candleRepository(), times(1)).saveAll(any());
    }

    @Test
    void bulkPersistenceWritesOnlyNewOrChangedCandles() {
        AtomicLong now = new AtomicLong(1_800_000_000L);
        MarketDataBar unchangedBar = bar(1, 100, 105, 99, 104);
        MarketDataBar changedBar = bar(2, 104, 110, 103, 109);
        MarketDataBar newBar = bar(3, 109, 112, 108, 111);
        Candle unchanged = candle(unchangedBar);
        Candle changed = candle(bar(2, 104, 108, 102, 106));
        TestContext context = context(
                now,
                List.of(unchangedBar, changedBar, newBar),
                List.of(unchanged, changed));
        List<Candle> persisted = new ArrayList<>();
        when(context.candleRepository().saveAll(any())).thenAnswer(invocation -> {
            Iterable<Candle> candles = invocation.getArgument(0);
            candles.forEach(persisted::add);
            return persisted;
        });

        MarketDataService.CandleSyncResult result = context.service()
                .syncCandles("AAPL", "1d", null, true);

        assertThat(result.candlesSynced()).isEqualTo(2);
        assertThat(persisted)
                .extracting(Candle::getTimestamp)
                .containsExactly(2L * 86_400L, 3L * 86_400L);
        assertThat(changed.getClosePrice()).isEqualTo(109.0);
    }

    @Test
    void recentStoredCandleDoesNotPreventProviderFallbackWhenNoRecentSyncExists() {
        AtomicLong now = new AtomicLong(1_800_000_000L);
        MarketDataBar existingBar = new MarketDataBar(
                "SAP.DE",
                now.get() - 3_600L,
                100,
                105,
                99,
                104,
                1_000L);
        Candle existing = new Candle(
                "SAP.DE",
                "1d",
                existingBar.timestamp(),
                existingBar.open(),
                existingBar.high(),
                existingBar.low(),
                existingBar.close(),
                existingBar.volume());

        CandleRepository candleRepository = mock(CandleRepository.class);
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        TwelveDataService twelveDataService = mock(TwelveDataService.class);
        YahooFinanceService yahooFinanceService = mock(YahooFinanceService.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("SAP.DE");
        asset.setCompanyName("SAP SE");
        asset.setExchange("XETRA");
        asset.setCurrency("EUR");

        when(twelveDataService.getTimeSeries("SAP.DE", "1day", 1000))
                .thenThrow(new IllegalStateException("plan does not include this market"));
        when(yahooFinanceService.getTimeSeries("SAP.DE", "1d", 1000))
                .thenReturn(List.of(existingBar));
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("SAP.DE")).thenReturn(Optional.of(asset));
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampIn(anyString(), anyString(), anyCollection()))
                .thenReturn(List.of(existing));

        MarketDataService service = new MarketDataService(
                candleRepository,
                stockAssetRepository,
                twelveDataService,
                yahooFinanceService,
                new InMemoryMarketDataSyncCoordinator(now::get),
                mock(MarketDataHistoryStateStore.class),
                60,
                600,
                3_600,
                180);

        MarketDataService.CandleSyncResult result = service.syncCandles("SAP.DE", "1d", null, false);

        assertThat(result.source()).isEqualTo(MarketDataService.CandleSource.YAHOO_FINANCE);
        assertThat(result.candlesSynced()).isZero();
        verify(twelveDataService).getTimeSeries("SAP.DE", "1day", 1000);
        verify(yahooFinanceService).getTimeSeries("SAP.DE", "1d", 1000);
        verify(candleRepository, never()).findTop1BySymbolAndTimeIntervalOrderByTimestampDesc(anyString(), anyString());
    }

    private TestContext context(AtomicLong now,
                                List<MarketDataBar> providerBars,
                                List<Candle> existingCandles) {
        CandleRepository candleRepository = mock(CandleRepository.class);
        StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        TwelveDataService twelveDataService = mock(TwelveDataService.class);
        YahooFinanceService yahooFinanceService = mock(YahooFinanceService.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("AAPL");
        asset.setCompanyName("Apple Inc.");
        asset.setExchange("NASDAQ");
        asset.setCurrency("USD");

        when(twelveDataService.normalizeSymbol(anyString()))
                .thenAnswer(invocation -> invocation.<String>getArgument(0).trim().toUpperCase());
        when(twelveDataService.getTimeSeries("AAPL", "1day", 1000)).thenReturn(providerBars);
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("AAPL")).thenReturn(Optional.of(asset));
        when(candleRepository.findTop1BySymbolAndTimeIntervalOrderByTimestampDesc("AAPL", "1d"))
                .thenReturn(List.of());
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampIn(
                anyString(), anyString(), anyCollection())).thenReturn(existingCandles);

        MarketDataService service = new MarketDataService(
                candleRepository,
                stockAssetRepository,
                twelveDataService,
                yahooFinanceService,
                new InMemoryMarketDataSyncCoordinator(now::get),
                mock(MarketDataHistoryStateStore.class),
                60,
                600,
                3_600,
                180);
        return new TestContext(service, candleRepository, twelveDataService);
    }

    private MarketDataBar bar(int day, double open, double high, double low, double close) {
        return new MarketDataBar("AAPL", day * 86_400L, open, high, low, close, 1_000L);
    }

    private Candle candle(MarketDataBar bar) {
        return new Candle(
                "AAPL",
                "1d",
                bar.timestamp(),
                bar.open(),
                bar.high(),
                bar.low(),
                bar.close(),
                bar.volume());
    }

    private record TestContext(MarketDataService service,
                               CandleRepository candleRepository,
                               TwelveDataService twelveDataService) {
    }
}
