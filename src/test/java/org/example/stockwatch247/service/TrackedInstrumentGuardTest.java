package org.example.stockwatch247.service;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackedInstrumentGuardTest {
    private final StockAssetRepository assets = mock(StockAssetRepository.class);
    private final CandleRepository candles = mock(CandleRepository.class);
    private final YahooFinanceService yahoo = mock(YahooFinanceService.class);
    private final TwelveDataService twelveData = mock(TwelveDataService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC);

    private TrackedInstrumentGuard guard(boolean required) {
        return new TrackedInstrumentGuard(mock(JdbcTemplate.class), assets, candles, yahoo, twelveData,
                clock, required, 300, 500);
    }

    private StockAsset asset(String symbol) {
        StockAsset asset = new StockAsset();
        asset.setId(7L);
        asset.setTickerSymbol(symbol);
        when(assets.findById(7L)).thenReturn(Optional.of(asset));
        when(assets.save(any())).thenAnswer(call -> call.getArgument(0));
        return asset;
    }

    @Test
    void listingWithoutDataAnywhereIsRejectedWithAReadableMessage() {
        StockAsset asset = asset("QQQQQ1");
        when(yahoo.getTimeSeries(anyString(), anyString(), anyInt())).thenThrow(new IllegalStateException("No data"));
        when(twelveData.getTimeSeries(anyString(), anyString(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> guard(true).requireMarketData(asset))
                .isInstanceOf(UserInputException.class)
                .hasMessageContaining("No market data was found for QQQQQ1");
        verify(assets, never()).save(any());
    }

    @Test
    void verificationUsesYahooBeforeTheSharedTwelveDataBudget() {
        StockAsset asset = asset("AAPL");
        when(yahoo.getTimeSeries("AAPL", "1d", 5))
                .thenReturn(List.of(new MarketDataBar("AAPL", 1_700_000_000L, 1, 2, 0.5, 1.5, 10)));

        StockAsset verified = guard(true).requireMarketData(asset);

        assertThat(verified.getMarketDataVerifiedAt()).isEqualTo(clock.instant());
        verify(twelveData, never()).getTimeSeries(anyString(), anyString(), anyInt());
    }

    @Test
    void providerOutageIsReportedAsTemporaryNotAsAnUnknownTicker() {
        StockAsset asset = asset("MSFT");
        when(yahoo.getTimeSeries(anyString(), anyString(), anyInt()))
                .thenThrow(new YahooFinanceService.ProviderUnavailableException("HTTP 429", null));
        when(twelveData.getTimeSeries(anyString(), anyString(), anyInt()))
                .thenThrow(new MarketDataProviderRequestBudget.BudgetUnavailableException("exhausted"));

        assertThatThrownBy(() -> guard(true).requireMarketData(asset))
                .isInstanceOf(MarketDataUnavailableException.class);
    }

    @Test
    void alreadyVerifiedOrDisabledVerificationMakesNoProviderCall() {
        StockAsset asset = asset("NVDA");
        asset.setMarketDataVerifiedAt(Instant.EPOCH);
        guard(true).requireMarketData(asset);
        guard(false).requireMarketData(asset("TSLA"));
        verify(yahoo, never()).getTimeSeries(anyString(), anyString(), anyInt());
        verify(twelveData, never()).getTimeSeries(anyString(), anyString(), anyInt());
    }
}
