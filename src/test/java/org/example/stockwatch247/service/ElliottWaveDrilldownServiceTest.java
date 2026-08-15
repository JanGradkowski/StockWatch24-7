package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElliottWaveDrilldownServiceTest {
    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final CandleCompletionService completionService = mock(CandleCompletionService.class);
    private final TechnicalIndicatorEnrichmentService enrichmentService =
            mock(TechnicalIndicatorEnrichmentService.class);
    private final ElliottWaveDetectionService detectionService = mock(ElliottWaveDetectionService.class);
    private final ElliottWaveDrilldownService service = new ElliottWaveDrilldownService(
            marketDataService, completionService, enrichmentService, detectionService);

    @Test
    void mapsWeeklyParentsToDailyCandlesAndKeepsTheHistoricalAsOfCutoff() {
        long parentStart = timestamp(2024, 1, 1);
        long parentEnd = timestamp(2024, 1, 15);
        long asOfExclusive = timestamp(2024, 1, 22);
        List<Candle> pageCandles = new ArrayList<>();
        for (int day = 1; day <= 23; day++) {
            pageCandles.add(candle(timestamp(2024, 1, day), 100.0 + day));
        }
        List<EnrichedCandle> enriched = pageCandles.stream()
                .filter(candle -> candle.getTimestamp() >= parentStart
                        && candle.getTimestamp() < asOfExclusive)
                .map(this::enriched)
                .toList();
        when(completionService.firstIncompleteCandleTimestamp(TimeInterval.DAILY))
                .thenReturn(timestamp(2026, 1, 1));
        when(marketDataService.loadCandlePage("TEST", "1d", asOfExclusive, 1_000))
                .thenReturn(new MarketDataService.CandlePage(
                        pageCandles, null, false, MarketDataService.CandleSource.CACHE, null));
        when(enrichmentService.enrich(anyList(), eq(enriched.size()), eq(TimeInterval.DAILY)))
                .thenReturn(enriched);
        when(detectionService.findSubdivision(anyList(), eq("III"), eq(101.0), eq(115.0)))
                .thenReturn(Optional.of(new ElliottWaveDetectionService.ElliottSubdivision(
                        "Motive 1-2-3-4-5",
                        84,
                        true,
                        List.of(
                                point("", parentStart, 101.0, "LOW"),
                                point("i", timestamp(2024, 1, 4), 108.0, "HIGH"),
                                point("ii", timestamp(2024, 1, 7), 104.0, "LOW"),
                                point("iii", timestamp(2024, 1, 10), 116.0, "HIGH"),
                                point("iv", timestamp(2024, 1, 12), 109.0, "LOW"),
                                point("v", parentEnd, 115.0, "HIGH")),
                        List.of("Validated."),
                        List.of())));

        ElliottWaveDrilldownService.DrilldownView view = service.drillDown(
                "test", "1wk", "III", parentStart, parentEnd, 101.0, 115.0, asOfExclusive);

        assertThat(view.available()).isTrue();
        assertThat(view.interval()).isEqualTo("1d");
        assertThat(view.candles()).allMatch(candle -> candle.timestamp() < asOfExclusive);
        assertThat(view.candles()).noneMatch(candle -> candle.timestamp() == timestamp(2024, 1, 23));
        ArgumentCaptor<List<Candle>> candleCaptor = ArgumentCaptor.forClass(List.class);
        verify(enrichmentService).enrich(candleCaptor.capture(), eq(enriched.size()), eq(TimeInterval.DAILY));
        assertThat(candleCaptor.getValue()).allMatch(candle -> candle.getTimestamp() < asOfExclusive);
    }

    @Test
    void refusesAParentWaveThatWouldCrossTheHistoricalAsOfBoundary() {
        long parentStart = timestamp(2024, 1, 1);
        long parentEnd = timestamp(2024, 1, 15);
        when(completionService.firstIncompleteCandleTimestamp(TimeInterval.DAILY))
                .thenReturn(timestamp(2026, 1, 1));

        ElliottWaveDrilldownService.DrilldownView view = service.drillDown(
                "TEST", "1wk", "III", parentStart, parentEnd,
                100.0, 120.0, timestamp(2024, 1, 20));

        assertThat(view.available()).isFalse();
        assertThat(view.unavailableReason()).contains("as-of boundary");
        verify(marketDataService, never()).loadCandlePage(
                eq("TEST"), eq("1d"), anyLong(), eq(1_000));
    }

    private Candle candle(long timestamp, double close) {
        return new Candle("TEST", "1d", timestamp,
                close - 0.5, close + 1.0, close - 1.0, close, 1_000L);
    }

    private EnrichedCandle enriched(Candle candle) {
        return new EnrichedCandle(candle.getTimestamp(), candle.getOpenPrice(), candle.getHighPrice(),
                candle.getLowPrice(), candle.getClosePrice(), 1_000.0, 900.0,
                55.0, candle.getClosePrice(), Double.NaN, Double.NaN, Double.NaN, 2.0);
    }

    private ElliottWaveDetectionService.ElliottWavePoint point(
            String label, long timestamp, double price, String type) {
        return new ElliottWaveDetectionService.ElliottWavePoint(label, timestamp, price, type);
    }

    private long timestamp(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

}
