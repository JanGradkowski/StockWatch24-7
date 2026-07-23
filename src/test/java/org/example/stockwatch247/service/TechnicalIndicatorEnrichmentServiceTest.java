package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TechnicalIndicatorEnrichmentServiceTest {

    @Test
    void usesAFullSma200WarmupBeforeReturningTheLatestSignalWindow() {
        TechnicalIndicatorEnrichmentService service = new TechnicalIndicatorEnrichmentService();
        int signalCandles = 100;
        List<Candle> history = IntStream.rangeClosed(1, service.requiredInputCandles(signalCandles))
                .mapToObj(this::candle)
                .toList();

        List<EnrichedCandle> enriched = service.enrich(history, signalCandles);

        assertThat(service.requiredInputCandles(signalCandles)).isEqualTo(299);
        assertThat(enriched).hasSize(signalCandles);
        assertThat(enriched.getFirst().timestamp()).isEqualTo(200L * 86_400L);
        assertThat(enriched).allSatisfy(candle -> assertThat(candle.sma200()).isFinite());
        assertThat(enriched.getFirst().sma200()).isCloseTo(100.5, within(0.000_000_1));
        assertThat(enriched.getLast().sma200()).isCloseTo(199.5, within(0.000_000_1));
    }

    private Candle candle(int day) {
        double close = day;
        return new Candle(
                "VST",
                "1d",
                day * 86_400L,
                close - 0.5,
                close + 1.0,
                close - 1.0,
                close,
                1_000L
        );
    }
}
