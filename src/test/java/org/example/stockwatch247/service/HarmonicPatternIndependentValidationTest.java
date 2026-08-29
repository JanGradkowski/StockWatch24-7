package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.Direction;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.HarmonicFormation;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.HarmonicPivot;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.PivotType;
import org.example.stockwatch247.service.HarmonicPatternDetectionService.Rules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A second, deterministic validation group that does not use the real-market
 * reference-audit candidates. It generates unseen geometries from an independent
 * ratio oracle, exercises affine price invariance and both directions, then sends
 * separate OHLC fixtures through the complete candle-to-pivot detection path.
 */
class HarmonicPatternIndependentValidationTest {
    private static final double TOLERANCE = .03;
    private static final int GENERATED_CASES_PER_PATTERN_AND_DIRECTION = 100;
    private static final int PIPELINE_CASES_PER_PATTERN_AND_DIRECTION = 10;
    private static final long RANDOM_SEED = 0x247A11CE5L;

    private static final Map<HarmonicPatternType, double[]> CANONICAL = canonicalGeometries();
    private static final Map<HarmonicPatternType, double[]> INVALID = invalidGeometries();

    private final HarmonicPatternDetectionService detector = new HarmonicPatternDetectionService(
            new Rules(.03, .03, .10, .005, 2, 250));

    @Test
    void classifiesTwelveHundredUnseenOracleGeneratedGeometries() {
        Random random = new Random(RANDOM_SEED);
        int evaluated = 0;

        for (HarmonicPatternType expected : HarmonicPatternType.values()) {
            List<double[]> generated = generateUniqueValidGeometries(expected, random);
            assertThat(generated)
                    .as("independent valid geometries for %s", expected)
                    .hasSize(GENERATED_CASES_PER_PATTERN_AND_DIRECTION);

            for (double[] geometry : generated) {
                for (boolean bearish : List.of(false, true)) {
                    double[] transformed = transform(geometry, bearish, random);
                    HarmonicFormation actual = detector.classify(pivots(transformed, bearish)).orElseThrow();
                    assertThat(actual.pattern())
                            .as("classification for %s geometry %s", expected, List.of(transformed))
                            .isEqualTo(expected);
                    assertThat(actual.direction()).isEqualTo(bearish ? Direction.BEARISH : Direction.BULLISH);
                    evaluated++;
                }
            }
        }

        assertThat(evaluated).isEqualTo(1_200);
    }

    @Test
    void detectsOneHundredTwentyFixturesThroughTheCompleteOhlcPipeline() {
        Random random = new Random(RANDOM_SEED ^ 0x5EEDL);
        int evaluated = 0;

        for (Map.Entry<HarmonicPatternType, double[]> entry : CANONICAL.entrySet()) {
            for (boolean bearish : List.of(false, true)) {
                for (int sample = 0; sample < PIPELINE_CASES_PER_PATTERN_AND_DIRECTION; sample++) {
                    double[] transformed = transform(entry.getValue(), bearish, random);
                    List<HarmonicFormation> actual = detector.detectHistorical(
                            candlesFromGeometry(transformed, bearish, sample));

                    assertThat(actual)
                            .as("full OHLC pipeline for %s %s sample %s",
                                    entry.getKey(), bearish ? "bearish" : "bullish", sample)
                            .anySatisfy(formation -> {
                                assertThat(formation.pattern()).isEqualTo(entry.getKey());
                                assertThat(formation.direction())
                                        .isEqualTo(bearish ? Direction.BEARISH : Direction.BULLISH);
                            });
                    evaluated++;
                }
            }
        }

        assertThat(evaluated).isEqualTo(120);
    }

    @Test
    void rejectsSixHundredAffineVariantsOutsideHardPatternBoundaries() {
        Random random = new Random(RANDOM_SEED ^ 0xBADL);
        int evaluated = 0;

        for (Map.Entry<HarmonicPatternType, double[]> entry : INVALID.entrySet()) {
            assertThat(referenceLabels(entry.getValue()))
                    .as("independent oracle must reject the adversarial %s fixture", entry.getKey())
                    .doesNotContain(entry.getKey());
            for (int sample = 0; sample < 50; sample++) {
                for (boolean bearish : List.of(false, true)) {
                    double[] transformed = transform(entry.getValue(), bearish, random);
                    detector.classify(pivots(transformed, bearish)).ifPresent(formation ->
                            assertThat(formation.pattern())
                                    .as("hard-boundary rejection for %s", entry.getKey())
                                    .isNotEqualTo(entry.getKey()));
                    evaluated++;
                }
            }
        }

        assertThat(evaluated).isEqualTo(600);
    }

    private List<double[]> generateUniqueValidGeometries(HarmonicPatternType expected, Random random) {
        double[] seed = CANONICAL.get(expected);
        Set<String> seen = new LinkedHashSet<>();
        List<double[]> result = new ArrayList<>();
        for (int attempt = 0;
             attempt < 100_000 && result.size() < GENERATED_CASES_PER_PATTERN_AND_DIRECTION;
             attempt++) {
            double[] candidate = seed.clone();
            candidate[2] += random.nextGaussian() * .22;
            candidate[3] += random.nextGaussian() * .22;
            candidate[4] += random.nextGaussian() * .22;
            if (!referenceLabels(candidate).equals(Set.of(expected))) continue;
            String key = String.format(java.util.Locale.ROOT, "%.5f:%.5f:%.5f",
                    candidate[2], candidate[3], candidate[4]);
            if (seen.add(key)) result.add(candidate);
        }
        return List.copyOf(result);
    }

    private Set<HarmonicPatternType> referenceLabels(double[] p) {
        Set<HarmonicPatternType> labels = new LinkedHashSet<>();
        double xa = distance(p[0], p[1]);
        double ab = distance(p[1], p[2]);
        double bc = distance(p[2], p[3]);
        double cd = distance(p[3], p[4]);
        double xc = distance(p[0], p[3]);
        double bXa = ratio(ab, xa);
        double cAb = ratio(bc, ab);
        double cdBc = ratio(cd, bc);
        double cdAb = ratio(cd, ab);
        double adXa = ratio(distance(p[1], p[4]), xa);
        boolean alternating = p[1] > p[0] && p[2] < p[1] && p[3] > p[2] && p[4] < p[3];
        if (!alternating) return Set.of();
        boolean inside = p[3] <= p[1] && p[4] > p[0] && p[4] < p[1];
        boolean outside = p[3] <= p[1] && p[4] < p[0];

        if (inside && near(bXa, .618) && within(cAb, .382, .886)
                && within(cdBc, 1.13, 1.618) && alternative(cdAb, 1.0, 1.27)
                && near(adXa, .786)) labels.add(HarmonicPatternType.GARTLEY);
        if (inside && within(bXa, .382, .50) && within(cAb, .382, .886)
                && within(cdBc, 1.618, 2.618) && alternative(cdAb, 1.0, 1.27)
                && near(adXa, .886)) labels.add(HarmonicPatternType.BAT);
        if (outside && near(bXa, .786) && within(cAb, .382, .886)
                && within(cdBc, 1.618, 2.24) && alternative(cdAb, 1.0, 1.27)
                && near(adXa, 1.27)) labels.add(HarmonicPatternType.BUTTERFLY);
        if (outside && within(bXa, .382, .618) && within(cAb, .382, .886)
                && within(cdBc, 2.618, 3.618) && near(adXa, 1.618)
                && Math.abs(cdAb - 1.0) >= .10) labels.add(HarmonicPatternType.CRAB);

        double sharkA0x = bXa;
        double sharkAbXa = cAb;
        double sharkBcAb = cdBc;
        double sharkCompletion = adXa;
        boolean sharkOrder = p[3] > p[1] && p[4] < p[2] && p[4] <= p[1];
        if (sharkOrder && within(sharkA0x, .32, .618)
                && within(sharkAbXa, 1.13, 1.618) && within(sharkBcAb, 1.618, 2.24)
                && alternative(sharkCompletion, .886, 1.13)) labels.add(HarmonicPatternType.SHARK);

        double xcXa = ratio(xc, xa);
        double cdXc = ratio(cd, xc);
        boolean cypherOrder = p[3] > p[1] && p[4] < p[2];
        if (cypherOrder && within(bXa, .382, .618)
                && xcXa >= 1.272 * (1.0 - TOLERANCE) && xcXa <= 1.414
                && near(cdXc, .786)) labels.add(HarmonicPatternType.CYPHER);
        return Set.copyOf(labels);
    }

    private List<HarmonicPivot> pivots(double[] prices, boolean bearish) {
        List<HarmonicPivot> result = new ArrayList<>();
        for (int index = 0; index < prices.length; index++) {
            PivotType bullishType = index % 2 == 0 ? PivotType.LOW : PivotType.HIGH;
            result.add(new HarmonicPivot(index, 1_700_000_000L + index * 86_400L, prices[index],
                    bearish ? opposite(bullishType) : bullishType,
                    1_700_000_000L + (index + 2L) * 86_400L));
        }
        return List.copyOf(result);
    }

    private List<Candle> candlesFromGeometry(double[] prices, boolean bearish, int sample) {
        int firstPivot = 3;
        int spacing = 5;
        int[] pivotIndices = new int[prices.length];
        for (int index = 0; index < prices.length; index++) {
            pivotIndices[index] = firstPivot + index * spacing;
        }
        int candleCount = pivotIndices[pivotIndices.length - 1] + 3;
        double[] centers = new double[candleCount];
        double firstMove = Math.abs(prices[1] - prices[0]);
        double lastMove = Math.abs(prices[4] - prices[3]);
        double before = prices[0] + (bearish ? -1.0 : 1.0) * firstMove * .12;
        double after = prices[4] + (bearish ? -1.0 : 1.0) * lastMove * .12;
        interpolate(centers, 0, before, pivotIndices[0], prices[0]);
        for (int index = 0; index < prices.length - 1; index++) {
            interpolate(centers, pivotIndices[index], prices[index],
                    pivotIndices[index + 1], prices[index + 1]);
        }
        interpolate(centers, pivotIndices[4], prices[4], candleCount - 1, after);

        double minimumLeg = Double.POSITIVE_INFINITY;
        for (int index = 1; index < prices.length; index++) {
            minimumLeg = Math.min(minimumLeg, Math.abs(prices[index] - prices[index - 1]));
        }
        double spread = Math.max(minimumLeg * .001, .000001);
        long start = 1_650_000_000L + sample * 100_000L;
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < centers.length; index++) {
            int pivotPosition = pivotPosition(pivotIndices, index);
            double high = centers[index] + spread;
            double low = centers[index] - spread;
            double close = centers[index];
            if (pivotPosition >= 0) {
                boolean bullishLow = pivotPosition % 2 == 0;
                boolean lowPivot = bearish ? !bullishLow : bullishLow;
                if (lowPivot) {
                    low = prices[pivotPosition];
                    high = prices[pivotPosition] + spread * 2.0;
                    close = prices[pivotPosition] + spread;
                } else {
                    high = prices[pivotPosition];
                    low = prices[pivotPosition] - spread * 2.0;
                    close = prices[pivotPosition] - spread;
                }
            }
            candles.add(new Candle("VALIDATION", "1d", start + index * 86_400L,
                    close, high, low, close, 1_000L));
        }
        return List.copyOf(candles);
    }

    private void interpolate(double[] values, int fromIndex, double fromValue, int toIndex, double toValue) {
        for (int index = fromIndex; index <= toIndex; index++) {
            double progress = (double) (index - fromIndex) / (toIndex - fromIndex);
            values[index] = fromValue + (toValue - fromValue) * progress;
        }
    }

    private int pivotPosition(int[] pivotIndices, int candleIndex) {
        for (int index = 0; index < pivotIndices.length; index++) {
            if (pivotIndices[index] == candleIndex) return index;
        }
        return -1;
    }

    private double[] transform(double[] source, boolean bearish, Random random) {
        double scale = Math.exp(-2.0 + random.nextDouble() * 6.0);
        double minimum = java.util.Arrays.stream(source).min().orElseThrow();
        double maximum = java.util.Arrays.stream(source).max().orElseThrow();
        double floor = .25 + random.nextDouble() * 500.0;
        double[] transformed = new double[source.length];
        for (int index = 0; index < source.length; index++) {
            double normalized = bearish ? maximum + minimum - source[index] : source[index];
            transformed[index] = floor + scale * (normalized - minimum + 10.0);
        }
        return transformed;
    }

    private boolean near(double value, double target) {
        return value >= target * (1.0 - TOLERANCE) && value <= target * (1.0 + TOLERANCE);
    }

    private boolean within(double value, double minimum, double maximum) {
        return value >= minimum * (1.0 - TOLERANCE)
                && value <= maximum * (1.0 + TOLERANCE);
    }

    private boolean alternative(double value, double first, double second) {
        return near(value, first) || near(value, second);
    }

    private double distance(double first, double second) {
        return Math.abs(second - first);
    }

    private double ratio(double numerator, double denominator) {
        return denominator <= 0.0 ? Double.NaN : numerator / denominator;
    }

    private PivotType opposite(PivotType type) {
        return type == PivotType.LOW ? PivotType.HIGH : PivotType.LOW;
    }

    private static Map<HarmonicPatternType, double[]> canonicalGeometries() {
        Map<HarmonicPatternType, double[]> result = new EnumMap<>(HarmonicPatternType.class);
        result.put(HarmonicPatternType.GARTLEY, new double[]{100, 200, 138.2, 183.2, 121.4});
        result.put(HarmonicPatternType.BAT, new double[]{100, 200, 150, 174.9, 111.4});
        result.put(HarmonicPatternType.BUTTERFLY, new double[]{100, 200, 121.4, 172.622, 73});
        result.put(HarmonicPatternType.CRAB, new double[]{100, 200, 150, 194, 38.2});
        result.put(HarmonicPatternType.SHARK, new double[]{100, 200, 150, 210, 111.4});
        result.put(HarmonicPatternType.CYPHER, new double[]{100, 200, 150, 227.2, 127.2});
        return Map.copyOf(result);
    }

    private static Map<HarmonicPatternType, double[]> invalidGeometries() {
        Map<HarmonicPatternType, double[]> result = new EnumMap<>(HarmonicPatternType.class);
        result.put(HarmonicPatternType.GARTLEY, new double[]{100, 200, 130, 183.2, 121.4});
        result.put(HarmonicPatternType.BAT, new double[]{100, 200, 150, 174.9, 121.4});
        result.put(HarmonicPatternType.BUTTERFLY, new double[]{100, 200, 121.4, 158.422, 58.6});
        result.put(HarmonicPatternType.CRAB, new double[]{100, 200, 150, 194, 50});
        result.put(HarmonicPatternType.SHARK, new double[]{100, 200, 150, 210, 70});
        result.put(HarmonicPatternType.CYPHER, new double[]{100, 200, 150, 241.5, 130.3});
        return Map.copyOf(result);
    }
}
