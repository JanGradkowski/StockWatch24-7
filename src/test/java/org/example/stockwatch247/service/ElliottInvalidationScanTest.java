package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ElliottInvalidationScanTest {
    private static final long DAY = 86_400L;

    @Test
    void aBreakOnASkippedDayInvalidatesTheCountOnThatDayEvenAfterARecovery() {
        // Wave II low recorded on day 0; origin at 100. Day 2 trades through the origin, day 3 recovers.
        List<EnrichedCandle> candles = List.of(candle(1, 104, 106, 103, 105), candle(2, 103, 104, 99, 101),
                candle(3, 101, 108, 100.5, 107));
        Candle latest = new Candle("ELL", "1d", 3 * DAY, 101.0, 108.0, 100.5, 107.0, 1_000L);

        ScheduledAlertService.Breach breach = ScheduledAlertService.firstBreach(
                candles, latest, 0, true, 100.0, null);

        assertThat(breach).isNotNull();
        assertThat(breach.timestamp()).isEqualTo(2 * DAY);
        assertThat(breach.close()).isEqualTo(101.0);
        assertThat(breach.origin()).isTrue();
    }

    @Test
    void candlesUpToTheLastWavePointAndAnIntactStructureDoNotCount() {
        List<EnrichedCandle> candles = List.of(candle(1, 104, 106, 99, 105), candle(2, 105, 107, 103, 106));
        Candle latest = new Candle("ELL", "1d", 2 * DAY, 105.0, 107.0, 103.0, 106.0, 1_000L);

        assertThat(ScheduledAlertService.firstBreach(candles, latest, DAY, true, 100.0, null)).isNull();
    }

    @Test
    void waveFourEnteringWaveOneTerritoryIsAnOverlapBreach() {
        List<EnrichedCandle> candles = List.of(candle(5, 130, 131, 118, 121), candle(6, 121, 125, 120, 124));
        Candle latest = new Candle("ELL", "1d", 6 * DAY, 121.0, 125.0, 120.0, 124.0, 1_000L);

        ScheduledAlertService.Breach breach = ScheduledAlertService.firstBreach(
                candles, latest, 4 * DAY, true, 100.0, 120.0);

        assertThat(breach.timestamp()).isEqualTo(5 * DAY);
        assertThat(breach.origin()).isFalse();
    }

    private static EnrichedCandle candle(int day, double open, double high, double low, double close) {
        return new EnrichedCandle(day * DAY, open, high, low, close, 1_000,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
