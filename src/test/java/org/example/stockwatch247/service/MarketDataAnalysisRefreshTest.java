package org.example.stockwatch247.service;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataAnalysisRefreshTest {

    @Test
    void coldAnalysisRequestsOnlyItsRequiredWarmupHistory() {
        Fixture fixture = fixture(0);
        when(fixture.twelve().getTimeSeries("AAPL", "1day", 519))
                .thenReturn(List.of(bar()));

        MarketDataService.CandleSyncResult result = fixture.service()
                .syncCandlesForAnalysis("AAPL", "1d", 519);

        assertThat(result.candlesSynced()).isEqualTo(1);
        verify(fixture.twelve()).getTimeSeries("AAPL", "1day", 519);
    }

    @Test
    void warmedAnalysisRequestsOnlyTheLatestDelta() {
        Fixture fixture = fixture(600);
        when(fixture.twelve().getTimeSeries("AAPL", "1day", 10))
                .thenReturn(List.of(bar()));

        fixture.service().syncCandlesForAnalysis("AAPL", "1d", 519);

        verify(fixture.twelve()).getTimeSeries("AAPL", "1day", 10);
    }

    private Fixture fixture(long cachedCandles) {
        CandleRepository candles = mock(CandleRepository.class);
        StockAssetRepository assets = mock(StockAssetRepository.class);
        TwelveDataService twelve = mock(TwelveDataService.class);
        YahooFinanceService yahoo = mock(YahooFinanceService.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("AAPL");
        when(candles.countBySymbolAndTimeInterval("AAPL", "1d")).thenReturn(cachedCandles);
        when(candles.findBySymbolAndTimeIntervalAndTimestampIn(
                org.mockito.ArgumentMatchers.eq("AAPL"),
                org.mockito.ArgumentMatchers.eq("1d"), anyCollection())).thenReturn(List.of());
        when(assets.findByTickerSymbolIgnoreCase("AAPL")).thenReturn(Optional.of(asset));
        MarketDataService service = new MarketDataService(
                candles, assets, twelve, yahoo,
                new InMemoryMarketDataSyncCoordinator(() -> 1_800_000_000L),
                mock(MarketDataHistoryStateStore.class), 60, 600, 3_600, 180);
        return new Fixture(service, twelve);
    }

    private MarketDataBar bar() {
        return new MarketDataBar("AAPL", 1_800_000_000L, 100, 102, 99, 101, 1_000L);
    }

    private record Fixture(MarketDataService service, TwelveDataService twelve) { }
}
