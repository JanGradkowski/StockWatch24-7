package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoricalHarmonicFormationServiceTest {

    @Test
    void reconstructsHistoricalDetailFromConfirmedGeometryAndMeasuresForwardOutcome() {
        CandleRepository candles = mock(CandleRepository.class);
        CandleCompletionService completion = mock(CandleCompletionService.class);
        HarmonicPatternDetectionService detector = new HarmonicPatternDetectionService(
                new HarmonicPatternDetectionService.Rules(.04, .08, .10, 0.0, 1, 40));
        HistoricalHarmonicFormationService service =
                new HistoricalHarmonicFormationService(candles, completion, detector);
        List<Candle> history = history();
        when(completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY)).thenReturn(Long.MAX_VALUE);
        when(candles.findBySymbolAndTimeIntervalOrderByTimestampAsc("MSFT", "1d"))
                .thenReturn(history);

        HistoricalHarmonicFormationService.HistoricalHarmonicDetail detail = service.findDetail(
                "msft", "1d", HarmonicPatternType.GARTLEY, 6 * 86_400L);

        assertThat(detail.pattern()).isEqualTo(HarmonicPatternType.GARTLEY);
        assertThat(detail.tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(detail.endpointLabel()).isEqualTo("D");
        assertThat(detail.endpointPrice()).isEqualTo(121.4);
        assertThat(detail.confirmationTimestamp()).isEqualTo(7 * 86_400L);
        assertThat(detail.confirmationClose()).isEqualTo(130.0);
        assertThat(detail.points()).extracting(HistoricalHarmonicFormationService.PointView::label)
                .containsExactly("X", "A", "B", "C", "D");
        assertThat(detail.measurements()).containsKeys("B_XA", "AD_XA", "CD_AB");
        assertThat(detail.qualityScore()).isBetween(1, 100);
        assertThat(detail.scoreSections()).isNotEmpty();
        assertThat(detail.result().available()).isTrue();
        assertThat(detail.result().availableForwardCandles()).isEqualTo(10);
        assertThat(detail.result().tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(detail.result().signalClose()).isEqualTo(130.0);
        assertThat(detail.result().outcomeLabel()).isEqualTo("Best close-based return");
        assertThat(detail.result().bestActionLabel()).isEqualTo("Best sell close");
        assertThat(detail.result().points()).hasSize(11);
        assertThat(detail.result().points().getLast().directionalPriceDifference()).isEqualTo(20.0);
    }

    private List<Candle> history() {
        List<Candle> candles = new ArrayList<>(List.of(
                candle(1, 110, 111, 109),
                candle(2, 100.5, 101, 100),
                candle(3, 199.5, 200, 199),
                candle(4, 138.7, 139, 138.2),
                candle(5, 182.7, 183.2, 182),
                candle(6, 122, 123, 121.4),
                candle(7, 130, 131, 129)
        ));
        for (int day = 8; day <= 17; day++) {
            double close = 130 + (day - 7) * 2.0;
            candles.add(candle(day, close, close + 1, close - 1));
        }
        return List.copyOf(candles);
    }

    private Candle candle(int day, double close, double high, double low) {
        return new Candle("MSFT", "1d", day * 86_400L, close, high, low, close, 1_000L);
    }
}
