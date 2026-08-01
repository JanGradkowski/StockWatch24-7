package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.AnchoredVolumeProfileRefreshStore.CandleSnapshot;
import org.example.stockwatch247.service.AnchoredVolumeProfileRefreshStore.RefreshState;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnchoredVolumeProfileServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final long ACTIVE_DAILY_TIMESTAMP =
            Instant.parse("2026-07-30T00:00:00Z").getEpochSecond();

    @Test
    void distributesEveryCandlesVolumeAcrossPriceBinsAndBuildsValueArea() {
        AnchoredVolumeProfileService service = service(
                mock(CandleRepository.class),
                mock(MarketDataService.class),
                mock(CandleCompletionService.class),
                mock(AnchoredVolumeProfileRefreshStore.class));
        List<AnchoredVolumeProfileService.ProfileCandle> candles = List.of(
                new AnchoredVolumeProfileService.ProfileCandle(
                        1L, 10.0, 20.0, 10.0, 18.0, 100L),
                new AnchoredVolumeProfileService.ProfileCandle(
                        2L, 18.0, 22.0, 14.0, 15.0, 200L));

        var profile = service.calculate(candles);

        assertThat(profile.totalVolume()).isCloseTo(300.0, within(0.0001));
        assertThat(profile.bins()).hasSize(12);
        assertThat(profile.bins().stream().mapToDouble(
                AnchoredVolumeProfileService.ProfileBin::volume).sum())
                .isCloseTo(300.0, within(0.0001));
        assertThat(profile.bins().stream().filter(
                AnchoredVolumeProfileService.ProfileBin::pointOfControl))
                .hasSize(1);
        assertThat(profile.valueAreaHigh()).isGreaterThan(profile.valueAreaLow());
        assertThat(profile.pointOfControl())
                .isBetween(profile.valueAreaLow(), profile.valueAreaHigh());
    }

    @Test
    void cacheReuseDoesNotConsumeEitherLiveProviderRefresh() {
        CandleRepository candles = mock(CandleRepository.class);
        MarketDataService marketData = mock(MarketDataService.class);
        CandleCompletionService completion = mock(CandleCompletionService.class);
        AnchoredVolumeProfileRefreshStore refreshStore =
                mock(AnchoredVolumeProfileRefreshStore.class);
        long historicalAnchor = ACTIVE_DAILY_TIMESTAMP - 86_400L;
        when(completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY))
                .thenReturn(ACTIVE_DAILY_TIMESTAMP);
        when(refreshStore.find(7L, "AAPL", "1d", ACTIVE_DAILY_TIMESTAMP))
                .thenReturn(Optional.empty());
        when(marketData.syncCandles("AAPL", "15min", null))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE,
                        0,
                        null));
        when(candles.findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                "AAPL", "1d", historicalAnchor))
                .thenReturn(List.of(candle(
                        "1d", historicalAnchor, 10, 12, 9, 11, 500)));

        var response = service(candles, marketData, completion, refreshStore)
                .getProfile(user(), "AAPL", "1d", historicalAnchor);

        assertThat(response.liveRefreshesUsed()).isZero();
        assertThat(response.liveRefreshesRemaining()).isEqualTo(2);
        assertThat(response.liveRefreshLocked()).isFalse();
        verify(refreshStore, never()).recordProviderRefresh(
                any(Long.class),
                any(),
                any(),
                any(Long.class),
                any(Integer.class),
                any(),
                any());
    }

    @Test
    void currentDailyAnchorUsesFifteenMinuteCandles() {
        CandleRepository candles = mock(CandleRepository.class);
        MarketDataService marketData = mock(MarketDataService.class);
        CandleCompletionService completion = mock(CandleCompletionService.class);
        AnchoredVolumeProfileRefreshStore refreshStore =
                mock(AnchoredVolumeProfileRefreshStore.class);
        long firstBar = ACTIVE_DAILY_TIMESTAMP + 9L * 3_600L;
        when(completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY))
                .thenReturn(ACTIVE_DAILY_TIMESTAMP);
        when(refreshStore.find(7L, "AAPL", "1d", ACTIVE_DAILY_TIMESTAMP))
                .thenReturn(Optional.empty());
        when(marketData.syncCandles("AAPL", "15min", null))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.CACHE,
                        0,
                        null));
        when(candles.findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                "AAPL", "15min", ACTIVE_DAILY_TIMESTAMP))
                .thenReturn(List.of(
                        candle("15min", firstBar, 10, 11, 9, 10.5, 100),
                        candle("15min", firstBar + 900, 10.5, 13, 10, 12, 200)));

        var response = service(candles, marketData, completion, refreshStore)
                .getProfile(user(), "AAPL", "1d", ACTIVE_DAILY_TIMESTAMP);

        assertThat(response.calculationInterval()).isEqualTo("15min");
        assertThat(response.candlesIncluded()).isEqualTo(2);
        assertThat(response.totalVolume()).isCloseTo(300.0, within(0.0001));
        verify(marketData).syncCandles("AAPL", "15min", null);
    }

    @Test
    void secondProviderRefreshFreezesTheUsersActiveIntradaySnapshot() {
        CandleRepository candles = mock(CandleRepository.class);
        MarketDataService marketData = mock(MarketDataService.class);
        CandleCompletionService completion = mock(CandleCompletionService.class);
        AnchoredVolumeProfileRefreshStore refreshStore =
                mock(AnchoredVolumeProfileRefreshStore.class);
        long activeIntradayTimestamp = ACTIVE_DAILY_TIMESTAMP + 10L * 3_600L;
        Candle providerCandle = candle(
                "15min", activeIntradayTimestamp, 10, 12, 9, 11, 999);
        CandleSnapshot frozenSnapshot = new CandleSnapshot(
                "15min", activeIntradayTimestamp, 10, 12, 9, 11, 100L);
        RefreshState firstRefresh = new RefreshState(
                1,
                Instant.parse("2026-07-30T10:00:00Z"),
                "TWELVE_DATA",
                frozenSnapshot);
        RefreshState secondRefresh = new RefreshState(
                2,
                NOW,
                "YAHOO_FINANCE",
                frozenSnapshot);
        when(completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY))
                .thenReturn(ACTIVE_DAILY_TIMESTAMP);
        when(refreshStore.find(7L, "AAPL", "1d", ACTIVE_DAILY_TIMESTAMP))
                .thenReturn(Optional.of(firstRefresh));
        when(marketData.syncCandles("AAPL", "15min", null))
                .thenReturn(new MarketDataService.CandleSyncResult(
                        MarketDataService.CandleSource.YAHOO_FINANCE,
                        1,
                        null));
        when(candles.findTop1BySymbolAndTimeIntervalOrderByTimestampDesc("AAPL", "15min"))
                .thenReturn(List.of(providerCandle));
        when(refreshStore.recordProviderRefresh(
                eq(7L),
                eq("AAPL"),
                eq("1d"),
                eq(ACTIVE_DAILY_TIMESTAMP),
                eq(2),
                eq("YAHOO_FINANCE"),
                any(CandleSnapshot.class)))
                .thenReturn(Optional.of(secondRefresh));
        when(candles.findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                "AAPL", "15min", ACTIVE_DAILY_TIMESTAMP))
                .thenReturn(List.of(providerCandle));

        var response = service(candles, marketData, completion, refreshStore)
                .getProfile(user(), "AAPL", "1d", ACTIVE_DAILY_TIMESTAMP);

        assertThat(response.liveRefreshLocked()).isTrue();
        assertThat(response.liveRefreshesUsed()).isEqualTo(2);
        assertThat(response.totalVolume()).isCloseTo(100.0, within(0.0001));
        assertThat(response.calculationInterval()).isEqualTo("15min");
        assertThat(response.statusMessage()).contains("Live refresh limit reached");
    }

    @Test
    void lockedHistoricalProfileCombinesCompletedDailyAndFrozenIntradayCandles() {
        CandleRepository candles = mock(CandleRepository.class);
        MarketDataService marketData = mock(MarketDataService.class);
        CandleCompletionService completion = mock(CandleCompletionService.class);
        AnchoredVolumeProfileRefreshStore refreshStore =
                mock(AnchoredVolumeProfileRefreshStore.class);
        long activeIntradayTimestamp = ACTIVE_DAILY_TIMESTAMP + 10L * 3_600L;
        RefreshState locked = new RefreshState(
                2,
                NOW,
                "TWELVE_DATA",
                new CandleSnapshot(
                        "15min", activeIntradayTimestamp, 10, 12, 9, 11, 100L));
        long historicalAnchor = ACTIVE_DAILY_TIMESTAMP - 86_400L;
        when(completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY))
                .thenReturn(ACTIVE_DAILY_TIMESTAMP);
        when(refreshStore.find(7L, "AAPL", "1d", ACTIVE_DAILY_TIMESTAMP))
                .thenReturn(Optional.of(locked));
        when(candles.findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                "AAPL", "1d", historicalAnchor))
                .thenReturn(List.of(
                        candle("1d", historicalAnchor, 8, 11, 7, 10, 50),
                        candle("1d", ACTIVE_DAILY_TIMESTAMP, 10, 13, 9, 12, 999)));
        when(candles.findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                "AAPL", "15min", ACTIVE_DAILY_TIMESTAMP))
                .thenReturn(List.of(candle(
                        "15min", activeIntradayTimestamp, 10, 13, 9, 12, 999)));

        var response = service(candles, marketData, completion, refreshStore)
                .getProfile(user(), "AAPL", "1d", historicalAnchor);

        assertThat(response.totalVolume()).isCloseTo(150.0, within(0.0001));
        assertThat(response.calculationInterval()).isEqualTo("1d+15min");
        verify(marketData, never()).syncCandles(any(), any(), any());
    }

    private AnchoredVolumeProfileService service(
            CandleRepository candles,
            MarketDataService marketData,
            CandleCompletionService completion,
            AnchoredVolumeProfileRefreshStore refreshStore) {
        return new AnchoredVolumeProfileService(
                candles,
                marketData,
                completion,
                refreshStore,
                "Europe/Brussels",
                12,
                70,
                2,
                Clock.fixed(NOW, ZoneId.of("Europe/Brussels")));
    }

    private User user() {
        User user = new User();
        user.setId(7L);
        user.setEmail("profile@example.com");
        return user;
    }

    private Candle candle(
            String interval,
            long timestamp,
            double open,
            double high,
            double low,
            double close,
            long volume) {
        return new Candle("AAPL", interval, timestamp, open, high, low, close, volume);
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
