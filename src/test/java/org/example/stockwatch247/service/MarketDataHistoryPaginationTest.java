package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataHistoryPaginationTest {
    private CandleRepository candleRepository;
    private StockAssetRepository stockAssetRepository;
    private TwelveDataService twelveDataService;
    private YahooFinanceService yahooFinanceService;
    private MarketDataSyncCoordinator syncCoordinator;
    private MarketDataHistoryStateStore historyStateStore;
    private MarketDataService service;

    @BeforeEach
    void setUp() {
        candleRepository = mock(CandleRepository.class);
        stockAssetRepository = mock(StockAssetRepository.class);
        twelveDataService = mock(TwelveDataService.class);
        yahooFinanceService = mock(YahooFinanceService.class);
        syncCoordinator = mock(MarketDataSyncCoordinator.class);
        historyStateStore = mock(MarketDataHistoryStateStore.class);
        service = new MarketDataService(
                candleRepository, stockAssetRepository, twelveDataService, yahooFinanceService,
                syncCoordinator, historyStateStore, 0, 0, 0, 30);

        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("MSFT");
        when(stockAssetRepository.findByTickerSymbolIgnoreCase("MSFT")).thenReturn(Optional.of(asset));
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampIn(
                eq("MSFT"), eq("1wk"), anyCollection())).thenReturn(List.of());
    }

    @Test
    void backfillsAnUncachedCursorAndReturnsAnAscendingPage() {
        Candle newer = candle(900L);
        Candle older = candle(800L);
        when(historyStateStore.isEndReached("MSFT", "1wk")).thenReturn(false);
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                eq("MSFT"), eq("1wk"), eq(1_000L), any(Pageable.class)))
                .thenReturn(List.of(), List.of(newer, older));
        MarketDataSyncCoordinator.Claim claim = new MarketDataSyncCoordinator.Claim(
                "MSFT", "1wk-history", "worker-1", MarketDataSyncCoordinator.ClaimStatus.ACQUIRED);
        when(syncCoordinator.tryClaim("MSFT", "1wk-history", 0L, 30L)).thenReturn(claim);
        when(twelveDataService.getTimeSeriesBefore("MSFT", "1week", 2, 1_000L))
                .thenReturn(List.of(bar(800L), bar(900L)));

        MarketDataService.CandlePage page = service.loadCandlePage("msft", "1wk", 1_000L, 2);

        assertThat(page.candles()).extracting(Candle::getTimestamp).containsExactly(800L, 900L);
        assertThat(page.nextCursor()).isEqualTo(800L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.source()).isEqualTo(MarketDataService.CandleSource.TWELVE_DATA);
        verify(historyStateStore).recordProgress("MSFT", "1wk", 800L, false);
        verify(candleRepository).saveAll(any());
        verify(syncCoordinator).markSuccessful(claim);
    }

    @Test
    void recordsTheTrueBeginningAndStopsOfferingMorePages() {
        Candle oldest = candle(800L);
        when(historyStateStore.isEndReached("MSFT", "1wk")).thenReturn(false, true);
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                eq("MSFT"), eq("1wk"), eq(1_000L), any(Pageable.class)))
                .thenReturn(List.of(), List.of(oldest));
        MarketDataSyncCoordinator.Claim claim = new MarketDataSyncCoordinator.Claim(
                "MSFT", "1wk-history", "worker-1", MarketDataSyncCoordinator.ClaimStatus.ACQUIRED);
        when(syncCoordinator.tryClaim("MSFT", "1wk-history", 0L, 30L)).thenReturn(claim);
        when(twelveDataService.getTimeSeriesBefore("MSFT", "1week", 3, 1_000L))
                .thenReturn(List.of(bar(800L)));

        MarketDataService.CandlePage page = service.loadCandlePage("MSFT", "1wk", 1_000L, 3);

        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isEqualTo(800L);
        verify(historyStateStore).recordProgress("MSFT", "1wk", 800L, true);
    }

    @Test
    void usesYahooWhenTwelveDataReturnsOnlyAPartialHistoricalWindow() {
        List<Candle> downloaded = List.of(candle(900L), candle(800L), candle(700L));
        when(historyStateStore.isEndReached("MSFT", "1wk")).thenReturn(false);
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                eq("MSFT"), eq("1wk"), eq(1_000L), any(Pageable.class)))
                .thenReturn(List.of(), downloaded);
        MarketDataSyncCoordinator.Claim claim = new MarketDataSyncCoordinator.Claim(
                "MSFT", "1wk-history", "worker-1", MarketDataSyncCoordinator.ClaimStatus.ACQUIRED);
        when(syncCoordinator.tryClaim("MSFT", "1wk-history", 0L, 30L)).thenReturn(claim);
        when(twelveDataService.getTimeSeriesBefore("MSFT", "1week", 3, 1_000L))
                .thenReturn(List.of(bar(900L)));
        when(yahooFinanceService.getTimeSeriesBefore("MSFT", "1wk", 3, 1_000L))
                .thenReturn(List.of(bar(700L), bar(800L), bar(900L)));

        MarketDataService.CandlePage page = service.loadCandlePage("MSFT", "1wk", 1_000L, 3);

        assertThat(page.source()).isEqualTo(MarketDataService.CandleSource.YAHOO_FINANCE);
        assertThat(page.candles()).extracting(Candle::getTimestamp).containsExactly(700L, 800L, 900L);
        assertThat(page.hasMore()).isTrue();
        verify(historyStateStore).recordProgress("MSFT", "1wk", 700L, false);
    }

    @Test
    void neverCallsAProviderAfterTheBeginningWasRecorded() {
        List<Candle> cached = List.of(candle(900L), candle(800L));
        when(historyStateStore.isEndReached("MSFT", "1wk")).thenReturn(true);
        when(candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                eq("MSFT"), eq("1wk"), eq(1_000L), any(Pageable.class))).thenReturn(cached);

        MarketDataService.CandlePage page = service.loadCandlePage("MSFT", "1wk", 1_000L, 3);

        assertThat(page.candles()).extracting(Candle::getTimestamp).containsExactly(800L, 900L);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.source()).isEqualTo(MarketDataService.CandleSource.CACHE);
        verify(twelveDataService, never()).getTimeSeriesBefore(any(), any(), anyInt(), anyLong());
        verify(yahooFinanceService, never()).getTimeSeriesBefore(any(), any(), anyInt(), anyLong());
    }

    private Candle candle(long timestamp) {
        return new Candle("MSFT", "1wk", timestamp, 100, 103, 99, 102.0, 1_000L);
    }

    private MarketDataBar bar(long timestamp) {
        return new MarketDataBar("MSFT", timestamp, 100, 103, 99, 102, 1_000L);
    }
}
