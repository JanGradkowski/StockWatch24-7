package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.HarmonicPatternDetectionService;
import org.example.stockwatch247.service.LivePricingService;
import org.example.stockwatch247.service.MarketDataService;
import org.example.stockwatch247.service.TechnicalIndicatorEnrichmentService;
import org.example.stockwatch247.service.TwelveDataService;
import org.example.stockwatch247.service.YahooFinanceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChartControllerHarmonicPatternTest {

    @Test
    void returnsConfirmedFormationsFromTheRequestedHistoryBoundary() {
        CandleRepository candles = mock(CandleRepository.class);
        ChartController controller = controller(candles);
        controller.configureHarmonicPatterns(new HarmonicPatternDetectionService(
                new HarmonicPatternDetectionService.Rules(.04, .08, .10, 0.0, 1, 40)));
        long start = 1_700_000_000L;
        List<Candle> history = gartleyCandles(start);
        when(candles.findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                "MSFT", "1d", start)).thenReturn(history);

        ChartController.HarmonicHistoryOverlay overlay =
                controller.getHistoricalHarmonicFormations("msft", "1d", start);

        assertThat(overlay.interval()).isEqualTo("1d");
        assertThat(overlay.fromTimestamp()).isEqualTo(start);
        assertThat(overlay.ruleVersion()).isEqualTo(HarmonicPatternDetectionService.RULE_VERSION);
        assertThat(overlay.formations()).singleElement().satisfies(formation -> {
            assertThat(formation.pattern()).isEqualTo(HarmonicPatternType.GARTLEY);
            assertThat(formation.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(formation.points()).extracting(point -> point.label())
                    .containsExactly("X", "A", "B", "C", "D");
            assertThat(formation.confirmationTimestamp()).isEqualTo(start + 6 * 86_400L);
        });
        verify(candles).findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                "MSFT", "1d", start);
        verify(candles).findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                org.mockito.ArgumentMatchers.eq("MSFT"), org.mockito.ArgumentMatchers.eq("1d"),
                org.mockito.ArgumentMatchers.eq(start), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsIntervalsOutsideTheTrackedDailyWeeklyMonthlySet() {
        ChartController controller = controller(mock(CandleRepository.class));

        assertThatThrownBy(() -> controller.getHistoricalHarmonicFormations("MSFT", "1h", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesEarlierWarmupCandlesForAFormationCrossingTheVisibleBoundary() {
        CandleRepository candles = mock(CandleRepository.class);
        ChartController controller = controller(candles);
        controller.configureHarmonicPatterns(new HarmonicPatternDetectionService(
                new HarmonicPatternDetectionService.Rules(.04, .08, .10, 0.0, 1, 40)));
        long start = 1_700_000_000L;
        long boundary = start + 2 * 86_400L;
        List<Candle> history = gartleyCandles(start);
        when(candles.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                org.mockito.ArgumentMatchers.eq("MSFT"), org.mockito.ArgumentMatchers.eq("1d"),
                org.mockito.ArgumentMatchers.eq(boundary), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(history.get(1), history.get(0)));
        when(candles.findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                "MSFT", "1d", boundary)).thenReturn(history.subList(2, history.size()));

        ChartController.HarmonicHistoryOverlay overlay =
                controller.getHistoricalHarmonicFormations("MSFT", "1d", boundary);

        assertThat(overlay.formations()).singleElement().satisfies(formation -> {
            assertThat(formation.pattern()).isEqualTo(HarmonicPatternType.GARTLEY);
            assertThat(formation.points().getFirst().timestamp()).isLessThan(boundary);
            assertThat(formation.points().getLast().timestamp()).isGreaterThanOrEqualTo(boundary);
        });
    }

    private ChartController controller(CandleRepository candles) {
        return new ChartController(
                candles,
                mock(LivePricingService.class),
                mock(MarketDataService.class),
                mock(StockAssetRepository.class),
                mock(TwelveDataService.class),
                mock(YahooFinanceService.class),
                new TechnicalIndicatorEnrichmentService(),
                new ElliottWaveDetectionService());
    }

    private List<Candle> gartleyCandles(long start) {
        long day = 86_400L;
        return List.of(
                candle(start, 110, 111, 109),
                candle(start + day, 100.5, 101, 100),
                candle(start + 2 * day, 199.5, 200, 199),
                candle(start + 3 * day, 138.7, 139, 138.2),
                candle(start + 4 * day, 182.7, 183.2, 182),
                candle(start + 5 * day, 122, 123, 121.4),
                candle(start + 6 * day, 130, 131, 129)
        );
    }

    private Candle candle(long timestamp, double close, double high, double low) {
        return new Candle("MSFT", "1d", timestamp, close, high, low, close, 1_000L);
    }
}
