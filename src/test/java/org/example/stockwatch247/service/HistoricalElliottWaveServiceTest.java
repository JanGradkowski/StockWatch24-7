package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalElliottWaveServiceTest {

    @Test
    void reconstructsConfirmedHistoricalWaveAndTenCloseSellResult() {
        assertReconstructsConfirmedHistoricalWave("1wk", TimeInterval.WEEKLY, "Weekly", 7 * 86_400L);
    }

    @Test
    void reconstructsDailyHistoricalWaveWithDailyLabels() {
        assertReconstructsConfirmedHistoricalWave("1d", TimeInterval.DAILY, "Daily", 86_400L);
    }

    private void assertReconstructsConfirmedHistoricalWave(String interval,
                                                            TimeInterval timeInterval,
                                                            String intervalLabel,
                                                            long candleDuration) {
        CandleRepository candleRepository = mock(CandleRepository.class);
        CandleCompletionService completionService = mock(CandleCompletionService.class);
        TechnicalIndicatorEnrichmentService enrichmentService = mock(TechnicalIndicatorEnrichmentService.class);
        ElliottWaveDetectionService detectionService = mock(ElliottWaveDetectionService.class);
        HistoricalElliottWaveService service = new HistoricalElliottWaveService(
                candleRepository,
                completionService,
                enrichmentService,
                detectionService
        );
        long week = candleDuration;
        List<Double> closes = List.of(
                80.0, 88.0, 84.0, 96.0, 90.0, 105.0, 100.0,
                96.0, 98.0, 95.0, 90.0, 91.0, 92.0, 93.0, 94.0, 97.0, 99.0
        );
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < closes.size(); index++) {
            double close = closes.get(index);
            candles.add(new Candle("MARA", interval, (index + 1L) * week,
                    close, close + 1.0, close - 1.0, close, 1_000L));
        }
        List<EnrichedCandle> enriched = List.of(mock(EnrichedCandle.class));
        ElliottWaveDetectionService.ElliottWaveStructure structure =
                new ElliottWaveDetectionService.ElliottWaveStructure(
                        "BULLISH",
                        false,
                        List.of(
                                new ElliottWaveDetectionService.ElliottWavePoint("", week, 79.0, "LOW"),
                                new ElliottWaveDetectionService.ElliottWavePoint("I", 2 * week, 89.0, "HIGH"),
                                new ElliottWaveDetectionService.ElliottWavePoint("II", 3 * week, 83.0, "LOW"),
                                new ElliottWaveDetectionService.ElliottWavePoint("III", 4 * week, 97.0, "HIGH"),
                                new ElliottWaveDetectionService.ElliottWavePoint("IV", 5 * week, 89.0, "LOW"),
                                new ElliottWaveDetectionService.ElliottWavePoint("V", 6 * week, 106.0, "HIGH")
                        ),
                        7 * week,
                        92,
                        0.4,
                        false,
                        1.8,
                        0.3,
                        ElliottWaveDetectionService.ImpulseVariant.STANDARD,
                        ElliottWaveDetectionService.CorrectionVariant.NONE,
                        0.0,
                        0.0,
                        List.of()
                );
        String cycleKey = "BULLISH:1:2:3:4:5";
        when(completionService.firstIncompleteCandleTimestamp(timeInterval))
                .thenReturn(Long.MAX_VALUE);
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc("MARA", interval))
                .thenReturn(candles);
        when(enrichmentService.enrichForElliott(candles, candles.size(), timeInterval))
                .thenReturn(enriched);
        when(detectionService.findAllWaveStructures(enriched)).thenReturn(List.of(structure));
        when(detectionService.lifecycleCycleKey(structure)).thenReturn(Optional.of(cycleKey));

        HistoricalElliottWaveService.HistoricalElliottWaveDetail detail = service.findDetail(
                "MARA",
                interval,
                ElliottSignalStage.WAVE_V_END,
                6 * week,
                cycleKey
        );

        assertThat(detail.status()).isEqualTo("CONFIRMED");
        assertThat(detail.interval()).isEqualTo(interval);
        assertThat(detail.intervalLabel()).isEqualTo(intervalLabel);
        assertThat(detail.tradeSignal()).isEqualTo(TradeSignal.SELL);
        assertThat(detail.confirmationTimestamp()).isEqualTo(7 * week);
        assertThat(detail.result().available()).isTrue();
        // Measured from the next candle's 96.0 open, the first tradable price after the 100.0 confirmation close.
        assertThat(detail.result().signalClose()).isEqualTo(96.0);
        assertThat(detail.result().bestDirectionalReturnPercent()).isEqualTo(6.25);
        assertThat(detail.result().windowEndDirectionalReturnPercent()).isEqualTo(-3.125);
        assertThat(detail.result().points()).hasSize(11);
        assertThat(detail.result().points().getFirst().candleNumber()).isZero();
        assertThat(detail.endpointPeriodLabel()).isNotBlank();
        assertThat(detail.candles()).isNotEmpty();
        assertThat(detail.points()).hasSize(6);
        assertThat(detail.candidate()).isFalse();
    }

    @Test
    void reconstructsTheHighestScoredMatchingCandidateAsABuyAfterAFallingPattern() {
        long day = 86_400L;
        CandidateFixture fixture = candidateFixture(day);
        String key = "FLAT:" + day + "-" + 2 * day + "-" + 3 * day + "-" + 4 * day;
        when(fixture.detectionService().findPatternCandidates(fixture.enriched(), 4 * day, 4 * day))
                .thenReturn(List.of(
                        candidate(fixture.points(), key, 70, day),
                        candidate(fixture.points(), key, 81, day),
                        candidate(fixture.points(), "ZIGZAG:" + day + "-" + 4 * day, 92, day)));

        HistoricalElliottWaveService.HistoricalElliottWaveDetail detail =
                fixture.service().findCandidateDetail("MARA", "1d", 4 * day, key, null);

        assertThat(detail.candidate()).isTrue();
        assertThat(detail.stageLabel()).isEqualTo("Corrective flat A-B-C");
        assertThat(detail.direction()).isEqualTo("FALLING");
        assertThat(detail.tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(detail.qualityScore()).isEqualTo(81);
        assertThat(detail.status()).isEqualTo("CONFIRMED");
        assertThat(detail.confirmationTimestamp()).isEqualTo(5 * day);
        assertThat(detail.points()).hasSize(4);
        assertThat(detail.result().available()).isTrue();
        // Entry at the next 101.0 open: best close 110, window-end close 104.
        assertThat(detail.result().bestDirectionalReturnPercent()).isCloseTo(9.0 / 101.0 * 100, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(detail.result().windowEndDirectionalReturnPercent()).isCloseTo(3.0 / 101.0 * 100, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(detail.scoreSections()).extracting(HistoricalElliottWaveService.ScoreSectionView::category)
                .containsExactly("Pattern geometry", "Parent count");
        assertThat(detail.scoreSections().getFirst().details())
                .extracting(HistoricalElliottWaveService.ScoreDetailView::text)
                .containsExactly("Wave B retraces 47.1% of Wave A.");
    }

    @Test
    void rejectsACandidateKeyThatIsNoLongerDetected() {
        long day = 86_400L;
        CandidateFixture fixture = candidateFixture(day);
        when(fixture.detectionService().findPatternCandidates(fixture.enriched(), 4 * day, 4 * day))
                .thenReturn(List.of(candidate(fixture.points(), "FLAT:1-2-3-4", 81, day)));

        assertThatThrownBy(() -> fixture.service().findCandidateDetail("MARA", "1d", 4 * day, "FLAT:9-9-9-9", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer available");
    }

    private CandidateFixture candidateFixture(long day) {
        CandleRepository candleRepository = mock(CandleRepository.class);
        CandleCompletionService completionService = mock(CandleCompletionService.class);
        TechnicalIndicatorEnrichmentService enrichmentService = mock(TechnicalIndicatorEnrichmentService.class);
        ElliottWaveDetectionService detectionService = mock(ElliottWaveDetectionService.class);
        HistoricalElliottWaveService service = new HistoricalElliottWaveService(
                candleRepository, completionService, enrichmentService, detectionService);
        List<Double> closes = List.of(106.0, 91.0, 97.0, 86.0, 100.0,
                101.0, 102.0, 105.0, 103.0, 104.0, 110.0, 108.0, 107.0, 106.0, 104.0, 105.0);
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < closes.size(); index++) {
            double close = closes.get(index);
            candles.add(new Candle("MARA", "1d", (index + 1L) * day,
                    close, close + 1.0, close - 1.0, close, 1_000L));
        }
        List<EnrichedCandle> enriched = List.of(mock(EnrichedCandle.class));
        when(completionService.firstIncompleteCandleTimestamp(TimeInterval.DAILY)).thenReturn(Long.MAX_VALUE);
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc("MARA", "1d")).thenReturn(candles);
        when(enrichmentService.enrichForElliott(candles, candles.size(), TimeInterval.DAILY)).thenReturn(enriched);
        List<ElliottWaveDetectionService.ElliottWavePoint> points = List.of(
                new ElliottWaveDetectionService.ElliottWavePoint("", day, 107.0, "HIGH"),
                new ElliottWaveDetectionService.ElliottWavePoint("a", 2 * day, 90.0, "LOW"),
                new ElliottWaveDetectionService.ElliottWavePoint("b", 3 * day, 98.0, "HIGH"),
                new ElliottWaveDetectionService.ElliottWavePoint("c", 4 * day, 85.0, "LOW"));
        return new CandidateFixture(service, detectionService, enriched, points);
    }

    private ElliottWaveDetectionService.ElliottPatternCandidate candidate(
            List<ElliottWaveDetectionService.ElliottWavePoint> points, String key, int score, long day) {
        return new ElliottWaveDetectionService.ElliottPatternCandidate("Corrective flat A-B-C", "FALLING", points,
                5 * day, score, List.of("II", "IV", "B", "corrective component"), "OBSERVED_DEGREE_GEOMETRY", 1,
                List.of("a", "b", "c"), key, List.of("Wave B retraces 47.1% of Wave A."));
    }

    private record CandidateFixture(HistoricalElliottWaveService service,
                                    ElliottWaveDetectionService detectionService,
                                    List<EnrichedCandle> enriched,
                                    List<ElliottWaveDetectionService.ElliottWavePoint> points) {
    }
}
