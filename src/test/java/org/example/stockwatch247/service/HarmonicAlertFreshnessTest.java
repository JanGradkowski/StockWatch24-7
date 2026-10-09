package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Harmonic alerts are sent only while the formation is recent and its trade is still possible. */
class HarmonicAlertFreshnessTest {
    private static final long START = 1_700_000_000L;
    private static final long DAY = 86_400L;
    private final HarmonicPatternDetectionService detector = new HarmonicPatternDetectionService(
            new HarmonicPatternDetectionService.Rules(.04, .08, .10, 0.0, 1, 40));

    @Test
    void aRecentFormationWithPriceBetweenStopAndTargetCanAlert() {
        List<Candle> candles = gartley();
        var formation = gartleyIn(candles);

        assertThat(HarmonicPatternDetectionService.endpointAge(formation, candles)).isEqualTo(1);
        assertThat(HarmonicPatternDetectionService.stillActionable(formation, candles, TimeInterval.DAILY, 5)).isTrue();
    }

    @Test
    void aFormationCompletedMoreThanFiveDailyCandlesAgoIsTooLate() {
        List<Candle> candles = new ArrayList<>(gartley());
        var formation = gartleyIn(candles);
        for (int day = 7; day <= 12; day++) candles.add(candle(START + day * DAY, 131, 132, 129));

        assertThat(HarmonicPatternDetectionService.endpointAge(formation, candles)).isEqualTo(7);
        assertThat(HarmonicPatternDetectionService.stillActionable(formation, candles, TimeInterval.DAILY, 5)).isFalse();
    }

    @Test
    void aFormationWhosePriceAlreadyPassedTheFirstTargetIsNotAlerted() {
        List<Candle> candles = new ArrayList<>(gartley());
        var formation = gartleyIn(candles);
        candles.add(candle(START + 7 * DAY, 170, 171, 160));

        assertThat(HarmonicPatternDetectionService.stillActionable(formation, candles, TimeInterval.DAILY, 5)).isFalse();
    }

    @Test
    void alertDetectionUsesNarrowerPivotsThanTheHistoryView() {
        assertThat(detector.forAlerts()).isNotSameAs(detector);
        assertThat(HarmonicPatternDetectionService.ALERT_MAXIMUM_PIVOT_WINDOW).isLessThanOrEqualTo(8);
        assertThat(HarmonicPatternDetectionService.defaultAlertEndpointAge(TimeInterval.DAILY)).isEqualTo(5);
        assertThat(HarmonicPatternDetectionService.defaultAlertEndpointAge(TimeInterval.WEEKLY)).isEqualTo(3);
        assertThat(HarmonicPatternDetectionService.defaultAlertEndpointAge(TimeInterval.MONTHLY)).isEqualTo(2);
    }

    private HarmonicPatternDetectionService.HarmonicFormation gartleyIn(List<Candle> candles) {
        return detector.detectHistorical(candles).stream()
                .filter(formation -> formation.pattern() == HarmonicPatternType.GARTLEY)
                .findFirst().orElseThrow();
    }

    private static List<Candle> gartley() {
        return List.of(
                candle(START, 110, 111, 109),
                candle(START + DAY, 100.5, 101, 100),
                candle(START + 2 * DAY, 199.5, 200, 199),
                candle(START + 3 * DAY, 138.7, 139, 138.2),
                candle(START + 4 * DAY, 182.7, 183.2, 182),
                candle(START + 5 * DAY, 122, 123, 121.4),
                candle(START + 6 * DAY, 130, 131, 129));
    }

    private static Candle candle(long timestamp, double close, double high, double low) {
        return new Candle("MSFT", "1d", timestamp, close, high, low, close, 1_000L);
    }
}
