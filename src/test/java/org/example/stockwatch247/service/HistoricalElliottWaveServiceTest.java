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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalElliottWaveServiceTest {

    @Test
    void reconstructsConfirmedHistoricalWaveAndTenCloseSellResult() {
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
        long week = 7 * 86_400L;
        List<Double> closes = List.of(
                80.0, 88.0, 84.0, 96.0, 90.0, 105.0, 100.0,
                96.0, 98.0, 95.0, 90.0, 91.0, 92.0, 93.0, 94.0, 97.0, 99.0
        );
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < closes.size(); index++) {
            double close = closes.get(index);
            candles.add(new Candle("MARA", "1wk", (index + 1L) * week,
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
        when(completionService.firstIncompleteCandleTimestamp(TimeInterval.WEEKLY))
                .thenReturn(Long.MAX_VALUE);
        when(candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc("MARA", "1wk"))
                .thenReturn(candles);
        when(enrichmentService.enrichForElliott(candles, candles.size(), TimeInterval.WEEKLY))
                .thenReturn(enriched);
        when(detectionService.findHistoricalWaveStructures(enriched)).thenReturn(List.of(structure));
        when(detectionService.lifecycleCycleKey(structure)).thenReturn(Optional.of(cycleKey));

        HistoricalElliottWaveService.HistoricalElliottWaveDetail detail = service.findDetail(
                "MARA",
                "1wk",
                ElliottSignalStage.WAVE_V_END,
                6 * week,
                cycleKey
        );

        assertThat(detail.status()).isEqualTo("CONFIRMED");
        assertThat(detail.tradeSignal()).isEqualTo(TradeSignal.SELL);
        assertThat(detail.confirmationTimestamp()).isEqualTo(7 * week);
        assertThat(detail.result().available()).isTrue();
        assertThat(detail.result().bestDirectionalReturnPercent()).isEqualTo(10.0);
        assertThat(detail.result().windowEndDirectionalReturnPercent()).isEqualTo(1.0);
        assertThat(detail.result().points()).hasSize(11);
        assertThat(detail.result().points().getFirst().candleNumber()).isZero();
        assertThat(detail.endpointPeriodLabel()).isNotBlank();
        assertThat(detail.candles()).isNotEmpty();
        assertThat(detail.points()).hasSize(6);
    }
}
