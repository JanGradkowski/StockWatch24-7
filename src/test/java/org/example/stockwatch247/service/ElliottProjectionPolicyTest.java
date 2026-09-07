package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ElliottProjectionPolicyTest {
    private static final long DAY = 86_400L;

    @Test
    void waveTwoEndCreatesThreeBullishMotiveAlternatesWithTextbookTargets() {
        List<ElliottProjectionPolicy.Scenario> scenarios = ElliottProjectionPolicy.generate(
                ElliottSignalStage.WAVE_II_END, "BULLISH", TradeSignal.BUY,
                2 * DAY, 105.0, points("BULLISH"), candles(12), TimeInterval.DAILY);

        assertThat(scenarios).hasSize(3);
        assertThat(scenarios).extracting(ElliottProjectionPolicy.Scenario::key)
                .containsExactly("STANDARD_WAVE_III", "EXTENDED_WAVE_III", "CONSERVATIVE_WAVE_III");
        assertThat(scenarios.getFirst().targetMidpoint()).isCloseTo(121.18,
                org.assertj.core.data.Offset.offset(.0001));
        assertThat(scenarios.getFirst().hardInvalidationPrice()).isEqualTo(100.0);
        assertThat(scenarios.getFirst().path()).hasSize(
                scenarios.getFirst().maximumCandles() + 1);
        assertThat(scenarios.getFirst().path().getFirst().price()).isEqualTo(105.0);
        assertThat(scenarios.getFirst().path().getLast().price())
                .isEqualTo(scenarios.getFirst().targetMidpoint());
    }

    @Test
    void waveThreeEndCreatesFlatZigzagAndTriangleWithDistinctDiscreditLevels() {
        List<ElliottProjectionPolicy.Scenario> scenarios = ElliottProjectionPolicy.generate(
                ElliottSignalStage.WAVE_III_END, "BULLISH", TradeSignal.SELL,
                5 * DAY, 125.0, points("BULLISH"), candles(12), TimeInterval.DAILY);

        assertThat(scenarios).extracting(ElliottProjectionPolicy.Scenario::key)
                .containsExactly("SHALLOW_FLAT_WAVE_IV", "ZIGZAG_WAVE_IV", "TRIANGLE_WAVE_IV");
        assertThat(scenarios).allSatisfy(scenario -> {
            assertThat(scenario.hardInvalidationPrice()).isEqualTo(110.0);
            assertThat(scenario.scenarioInvalidationPrice()).isNotNull();
            assertThat(scenario.minimumCandles()).isLessThan(scenario.maximumCandles());
        });
    }

    @Test
    void bearishTargetsMirrorBelowTheEndpointAndRemainPositive() {
        List<ElliottProjectionPolicy.Scenario> scenarios = ElliottProjectionPolicy.generate(
                ElliottSignalStage.WAVE_II_END, "BEARISH", TradeSignal.SELL,
                2 * DAY, 195.0, points("BEARISH"), candles(12), TimeInterval.DAILY);

        assertThat(scenarios).hasSize(3);
        assertThat(scenarios).allSatisfy(scenario -> {
            assertThat(scenario.targetMidpoint()).isBetween(0.0, 195.0);
            assertThat(scenario.path().getLast().price()).isEqualTo(scenario.targetMidpoint());
        });
    }

    private List<ElliottWaveDetectionService.ElliottWavePoint> points(String direction) {
        boolean bullish = "BULLISH".equals(direction);
        double origin = bullish ? 100 : 200;
        double one = bullish ? 110 : 190;
        double two = bullish ? 105 : 195;
        double three = bullish ? 125 : 175;
        return List.of(
                point("", 0, origin), point("I", 1, one), point("II", 2, two),
                point("III", 5, three), point("IV", 7, bullish ? 118 : 182),
                point("V", 9, bullish ? 132 : 168), point("A", 10, bullish ? 124 : 176),
                point("B", 11, bullish ? 129 : 171), point("C", 12, bullish ? 116 : 184));
    }

    private ElliottWaveDetectionService.ElliottWavePoint point(String label, long day, double price) {
        return new ElliottWaveDetectionService.ElliottWavePoint(
                label, day * DAY, price, label.equals("I") || label.equals("III")
                || label.equals("V") || label.equals("B") ? "HIGH" : "LOW");
    }

    private List<Candle> candles(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new Candle("TEST", "1d", index * DAY,
                        100, 102, 98, 100.0, 1_000L))
                .toList();
    }
}
