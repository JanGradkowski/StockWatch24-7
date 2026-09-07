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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataProviderBudgetFallbackTest {

    @Test
    void exhaustedLocalBudgetSkipsTwelveDataAndUsesYahoo() {
        CandleRepository candles = mock(CandleRepository.class);
        StockAssetRepository assets = mock(StockAssetRepository.class);
        TwelveDataService twelve = mock(TwelveDataService.class);
        YahooFinanceService yahoo = mock(YahooFinanceService.class);
        MarketDataProviderRequestBudget budget = mock(MarketDataProviderRequestBudget.class);
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("AAPL");
        when(candles.countBySymbolAndTimeInterval("AAPL", "1d")).thenReturn(600L);
        when(candles.findBySymbolAndTimeIntervalAndTimestampIn(
                org.mockito.ArgumentMatchers.eq("AAPL"),
                org.mockito.ArgumentMatchers.eq("1d"), anyCollection())).thenReturn(List.of());
        when(assets.findByTickerSymbolIgnoreCase("AAPL")).thenReturn(Optional.of(asset));
        org.mockito.Mockito.doThrow(new MarketDataProviderRequestBudget.BudgetUnavailableException(
                        "The local Twelve Data minute request budget is exhausted; using fallback data."))
                .when(twelve).getTimeSeries(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
        when(yahoo.getTimeSeries("AAPL", "1d", 10)).thenReturn(List.of(
                new MarketDataBar("AAPL", 1_800_000_000L, 100, 102, 99, 101, 1_000L)));
        MarketDataService service = new MarketDataService(
                candles, assets, twelve, yahoo,
                new InMemoryMarketDataSyncCoordinator(() -> 1_800_000_000L),
                mock(MarketDataHistoryStateStore.class), budget,
                60, 600, 3_600, 180);

        MarketDataService.CandleSyncResult result =
                service.syncCandlesForAnalysis("AAPL", "1d", 519);

        assertThat(result.source()).isEqualTo(MarketDataService.CandleSource.YAHOO_FINANCE);
        verify(twelve).getTimeSeries(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
        verify(yahoo).getTimeSeries("AAPL", "1d", 10);
    }
}

