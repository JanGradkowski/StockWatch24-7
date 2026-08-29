package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.Direction;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.HarmonicFormation;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.HarmonicPivot;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.PivotType;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.Rules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HarmonicPatternDetectionServiceTest {
    private final HarmonicPatternDetectionService detector = new HarmonicPatternDetectionService(
            new Rules(.04, .08, .10, 0.0, 1, 40));

    @Test
    void classifiesEveryBullishFormationUsingItsPrimaryBAndCompletionIdentity() {
        assertPattern(bullish(100, 200, 138.2, 183.2, 121.4), HarmonicPatternType.GARTLEY);
        assertPattern(bullish(100, 200, 150, 174.9, 111.4), HarmonicPatternType.BAT);
        assertPattern(bullish(100, 200, 121.4, 172.622, 73), HarmonicPatternType.BUTTERFLY);
        assertPattern(bullish(100, 200, 150, 194, 38.2), HarmonicPatternType.CRAB);
        assertPattern(bullish(100, 200, 144, 190, 38.2), HarmonicPatternType.CRAB);
        assertPattern(bullish(100, 200, 150, 210, 111.4), HarmonicPatternType.SHARK);
        assertPattern(bullish(100, 200, 150, 227.2, 127.2), HarmonicPatternType.CYPHER);
    }

    @Test
    void mirrorsEveryFormationForBearishGeometry() {
        assertBearish(mirror(bullish(100, 200, 138.2, 183.2, 121.4), 300), HarmonicPatternType.GARTLEY);
        assertBearish(mirror(bullish(100, 200, 150, 174.9, 111.4), 300), HarmonicPatternType.BAT);
        assertBearish(mirror(bullish(100, 200, 121.4, 172.622, 73), 300), HarmonicPatternType.BUTTERFLY);
        assertBearish(mirror(bullish(100, 200, 150, 194, 38.2), 300), HarmonicPatternType.CRAB);
        assertBearish(mirror(bullish(100, 200, 150, 210, 111.4), 300), HarmonicPatternType.SHARK);
        assertBearish(mirror(bullish(100, 200, 150, 227.2, 127.2), 300), HarmonicPatternType.CYPHER);
    }

    @Test
    void resolvesTheHalfRetracementAndPointEightEightSixCompletionAsBatNotStretchedGartley() {
        HarmonicFormation formation = detector.classify(bullish(100, 200, 150, 174.9, 111.4)).orElseThrow();

        assertThat(formation.pattern()).isEqualTo(HarmonicPatternType.BAT);
        assertThat(formation.reasons()).anySatisfy(reason -> assertThat(reason).contains("1.27 alternate"));
    }

    @Test
    void rejectsValuesBetweenTheDiscreteAbCdAlternatives() {
        assertThat(detector.classify(bullish(100, 200, 138.2, 191.543, 121.4))).isEmpty();
    }

    @Test
    void doesNotTreatTheButterflyInvalidationBoundaryAsACompletion() {
        assertThat(detector.classify(bullish(100, 200, 121.4, 158.422, 58.6))).isEmpty();
    }

    @Test
    void allowsSmallRatioToleranceButRejectsValuesOutsideIt() {
        List<HarmonicPivot> withinTolerance = bullish(100, 200, 136.04, 185.36, 121.4);
        List<HarmonicPivot> outsideTolerance = bullish(100, 200, 135.0, 186.4, 121.4);

        assertThat(detector.classify(withinTolerance)).get()
                .extracting(HarmonicFormation::pattern)
                .isEqualTo(HarmonicPatternType.GARTLEY);
        assertThat(detector.classify(outsideTolerance)).isEmpty();
    }

    @Test
    void enforcesHardDirectionAndCypherMaximumWithoutTolerance() {
        List<HarmonicPivot> wrongDirection = bullish(100, 200, 150, 140, 127.2);
        List<HarmonicPivot> cypherBeyondHardMaximum = bullish(100, 200, 150, 241.5, 130.3);

        assertThat(detector.classify(wrongDirection)).isEmpty();
        assertThat(detector.classify(cypherBeyondHardMaximum)).isEmpty();
    }

    @Test
    void usesOfficialSharkCompletionWhereBullishTerminalCIsBelowA() {
        HarmonicFormation shark = detector.classify(
                bullish(100, 200, 150, 210, 111.4)).orElseThrow();

        assertThat(shark.pattern()).isEqualTo(HarmonicPatternType.SHARK);
        assertThat(shark.points()).extracting(point -> point.label())
                .containsExactly("0", "X", "A", "B", "C");
        assertThat(shark.points().getLast().price()).isLessThan(shark.points().get(2).price());
        assertThat(shark.measurements().get("C_0X_COMPLETION")).isCloseTo(.886,
                org.assertj.core.data.Offset.offset(.000001));
        assertThat(shark.reasons()).anySatisfy(reason -> assertThat(reason)
                .startsWith("Primary B ratio +35/35:"));
        assertThat(shark.reasons()).anySatisfy(reason -> assertThat(reason)
                .startsWith("Completion ratio +45/45:"));
        assertThat(shark.reasons()).anySatisfy(reason -> assertThat(reason)
                .contains("all locked direction")
                .contains("point-order"));
    }

    @Test
    void terminalPivotIsNotReportedUntilItsRightSideConfirmationCandleExists() {
        long start = 1_700_000_000L;
        List<Candle> incomplete = pivotCandles(start, 86_400L).subList(0, 6);
        List<Candle> confirmed = pivotCandles(start, 86_400L);

        assertThat(detector.detectHistorical(incomplete)).isEmpty();
        HarmonicFormation formation = detector.detectHistorical(confirmed).getFirst();
        assertThat(formation.pattern()).isEqualTo(HarmonicPatternType.GARTLEY);
        assertThat(formation.points().getLast().timestamp()).isEqualTo(start + 5 * 86_400L);
        assertThat(formation.confirmationTimestamp()).isEqualTo(start + 6 * 86_400L);
    }

    @Test
    void findsLargerFormationWhenMinorAlternatingPivotsInterruptTheBaseScale() {
        long start = 1_700_000_000L;
        long spacing = 86_400L;
        List<Candle> candles = List.of(
                candle(start, 110, 111, 109),
                candle(start + spacing, 100.5, 101, 100),
                candle(start + 2 * spacing, 199.5, 200, 199),
                candle(start + 3 * spacing, 198.2, 198.5, 198),
                candle(start + 4 * spacing, 198.7, 199, 198.5),
                candle(start + 5 * spacing, 138.7, 139, 138.2),
                candle(start + 6 * spacing, 182.7, 183.2, 182),
                candle(start + 7 * spacing, 122, 123, 121.4),
                candle(start + 8 * spacing, 130, 131, 129));

        assertThat(detector.confirmedPivots(candles)).hasSizeGreaterThan(5);
        assertThat(detector.detectHistorical(candles))
                .extracting(HarmonicFormation::pattern)
                .contains(HarmonicPatternType.GARTLEY);
    }

    @Test
    void findsEightyCandleFormationDespiteLargeInternalCounterSwings() {
        List<Candle> candles = longNoisyGartleyCandles();

        assertThat(detector.confirmedPivots(candles)).hasSizeGreaterThan(12);
        assertThat(detector.detectHistorical(candles))
                .anySatisfy(formation -> {
                    assertThat(formation.pattern()).isEqualTo(HarmonicPatternType.GARTLEY);
                    assertThat(formation.points().getFirst().timestamp())
                            .isEqualTo(candles.get(3).getTimestamp());
                    assertThat(formation.points().getLast().timestamp())
                            .isEqualTo(candles.get(83).getTimestamp());
                });
    }

    private void assertPattern(List<HarmonicPivot> pivots, HarmonicPatternType expected) {
        HarmonicFormation formation = detector.classify(pivots).orElseThrow();
        assertThat(formation.pattern()).isEqualTo(expected);
        assertThat(formation.direction()).isEqualTo(Direction.BULLISH);
        assertThat(formation.tradeSignal()).isEqualTo(TradeSignal.BUY);
    }

    private void assertBearish(List<HarmonicPivot> pivots, HarmonicPatternType expected) {
        HarmonicFormation formation = detector.classify(pivots).orElseThrow();
        assertThat(formation.pattern()).isEqualTo(expected);
        assertThat(formation.direction()).isEqualTo(Direction.BEARISH);
        assertThat(formation.tradeSignal()).isEqualTo(TradeSignal.SELL);
    }

    private List<HarmonicPivot> bullish(double... prices) {
        List<HarmonicPivot> pivots = new ArrayList<>();
        for (int index = 0; index < prices.length; index++) {
            pivots.add(new HarmonicPivot(index, 1_000L + index * 10L, prices[index],
                    index % 2 == 0 ? PivotType.LOW : PivotType.HIGH,
                    1_005L + index * 10L));
        }
        return List.copyOf(pivots);
    }

    private List<HarmonicPivot> mirror(List<HarmonicPivot> source, double axis) {
        return source.stream()
                .map(pivot -> new HarmonicPivot(
                        pivot.index(), pivot.timestamp(), axis - pivot.price(),
                        pivot.type() == PivotType.LOW ? PivotType.HIGH : PivotType.LOW,
                        pivot.confirmationTimestamp()))
                .toList();
    }

    private List<Candle> pivotCandles(long start, long spacing) {
        return List.of(
                candle(start, 110, 111, 109),
                candle(start + spacing, 100.5, 101, 100),
                candle(start + 2 * spacing, 199.5, 200, 199),
                candle(start + 3 * spacing, 138.7, 139, 138.2),
                candle(start + 4 * spacing, 182.7, 183.2, 182),
                candle(start + 5 * spacing, 122, 123, 121.4),
                candle(start + 6 * spacing, 130, 131, 129)
        );
    }

    private List<Candle> longNoisyGartleyCandles() {
        double[] prices = {100, 200, 138.2, 183.2, 121.4};
        int[] anchors = {3, 23, 43, 63, 83};
        double[] centers = new double[87];
        interpolate(centers, 0, 112, anchors[0], prices[0], 0.0);
        for (int leg = 0; leg < 4; leg++) {
            interpolate(centers, anchors[leg], prices[leg], anchors[leg + 1], prices[leg + 1], 7.0);
        }
        interpolate(centers, anchors[4], prices[4], centers.length - 1, 133, 0.0);
        List<Candle> candles = new ArrayList<>();
        long start = 1_600_000_000L;
        for (int index = 0; index < centers.length; index++) {
            int anchor = -1;
            for (int point = 0; point < anchors.length; point++) {
                if (anchors[point] == index) anchor = point;
            }
            double high = centers[index] + .02;
            double low = centers[index] - .02;
            if (anchor >= 0) {
                if (anchor % 2 == 0) low = prices[anchor];
                else high = prices[anchor];
            }
            candles.add(candle(start + index * 86_400L, centers[index], high, low));
        }
        return List.copyOf(candles);
    }

    private void interpolate(double[] values,
                             int from,
                             double fromValue,
                             int to,
                             double toValue,
                             double amplitude) {
        double lower = Math.min(fromValue, toValue) + .05;
        double upper = Math.max(fromValue, toValue) - .05;
        for (int index = from; index <= to; index++) {
            double progress = (double) (index - from) / (to - from);
            double baseline = fromValue + (toValue - fromValue) * progress;
            double wave = amplitude * Math.sin(Math.PI * progress)
                    * Math.sin(4.0 * Math.PI * progress);
            values[index] = index == from ? fromValue : index == to ? toValue
                    : Math.clamp(baseline + wave, lower, upper);
        }
    }

    private Candle candle(long timestamp, double close, double high, double low) {
        return new Candle("TEST", "1d", timestamp, close, high, low, close, 1_000L);
    }
}
