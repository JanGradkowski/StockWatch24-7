package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ElliottCorrectionSuffixTest {
    private final ElliottWaveDetectionService optimized = new ElliottWaveDetectionService();
    private final ElliottWaveDetectionService reference = new ElliottWaveDetectionService() {
        @Override boolean reuseCorrectionSuffixes() { return false; }
    };

    @Test void preservesFullPrimaryResultsAcrossRevisionsAndChildCoverage() {
        var fixtures = new ElliottWaveDetectionServiceTest();
        var context = new ElliottWaveDetectionService.DevelopingScanContext();
        for (var input : List.of(fixtures.completeNestedImpulseWithNestedZigzagCorrection(),
                fixtures.completeNestedImpulseWithNestedFlatCorrection(), fixtures.completeNestedImpulseWithTriangularWaveB(),
                randomCandles(13), randomCandles(81))) {
            for (var children : List.of(List.<EnrichedCandle>of(), input, input.subList(input.size() / 2, input.size()))) {
                for (var window : List.of(input.subList(0, input.size() - 1), input,
                        input.subList(0, input.size() - 3), input.subList(4, input.size()))) {
                    assertThat(optimized.findDevelopingImpulses(window, children, context))
                            .isEqualTo(reference.findDevelopingImpulses(window, children));
                }
            }
        }
    }

    @Test void compactConfluencePreservesFirstGeometryAndCausalAvailability() {
        var fixtures = new ElliottWaveDetectionServiceTest();
        for (var input : List.of(fixtures.completeNestedImpulseWithNestedZigzagCorrection(),
                fixtures.completeNestedImpulseWithNestedFlatCorrection(), fixtures.completeNestedImpulseWithTriangularWaveB(),
                randomCandles(13), randomCandles(81))) {
            for (int end = Math.max(34, input.size() / 2); end <= input.size(); end += 3) {
                var current = input.subList(0, end);
                for (var previous : List.of(List.<EnrichedCandle>of(), input.subList(0, end - 1))) {
                    assertThat(optimized.newConfluenceObservations(current, previous,
                            new ElliottWaveDetectionService.DevelopingScanContext()))
                            .isEqualTo(reference.newConfluenceObservations(current, previous,
                                    new ElliottWaveDetectionService.DevelopingScanContext()));
                }
            }
        }
    }

    @Test void ruleChangesCannotReuseAnotherRulesetSuffixes() {
        var fixtures = new ElliottWaveDetectionServiceTest();
        var context = new ElliottWaveDetectionService.DevelopingScanContext();
        for (var rules : List.of(ElliottWaveDetectionService.DetectionRules.factory(),
                ElliottWaveDetectionService.DetectionRules.factory().withRequireWaveOneShortest(true),
                ElliottWaveDetectionService.DetectionRules.factory().withAllowWaveOneLongest(false))) {
            var configured = optimized.configured(rules);
            org.springframework.test.util.ReflectionTestUtils.setField(reference, "detectionRules", rules);
            for (var input : List.of(fixtures.completeNestedImpulseWithNestedZigzagCorrection(),
                    fixtures.completeNestedImpulseWithNestedFlatCorrection())) {
                assertThat(configured.findDevelopingImpulses(input, List.of(), context))
                        .isEqualTo(reference.findDevelopingImpulses(input));
            }
        }
    }

    @Test void confidenceBucketsPreserveRoundedRankingAndEncounterOrderTies() throws Exception {
        var rankingType = Class.forName(ElliottWaveDetectionService.class.getName() + "$CorrectionRanking");
        var constructor = rankingType.getDeclaredConstructor();
        constructor.setAccessible(true);
        var ranking = constructor.newInstance();
        var pivotType = Class.forName(ElliottWaveDetectionService.class.getName() + "$PivotType");
        var pivotConstructor = Class.forName(ElliottWaveDetectionService.class.getName() + "$Pivot")
                .getDeclaredConstructor(int.class, pivotType, double.class);
        pivotConstructor.setAccessible(true);
        var first = new ArrayList<Object>();
        var second = new ArrayList<Object>();
        for (int index = 0; index < 9; index++) {
            first.add(pivotConstructor.newInstance(index, pivotType.getEnumConstants()[index % 2], 100.0));
            second.add(pivotConstructor.newInstance(index + 20, pivotType.getEnumConstants()[index % 2], 100.0));
        }
        var accept = rankingType.getDeclaredMethod("accept", List.class, boolean.class, int.class, int.class, long.class);
        accept.setAccessible(true);
        accept.invoke(ranking, first, false, 1100, 200, 100L);
        accept.invoke(ranking, second, false, 1100, 201, 100L);
        Object result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(ranking, "result");
        List<?> buckets = org.springframework.test.util.ReflectionTestUtils.invokeMethod(result, "best");
        for (int prefixSum = 0; prefixSum <= 500; prefixSum++) {
            var expected = Math.round((prefixSum + 201) / 8.0) > Math.round((prefixSum + 200) / 8.0)
                    ? second.subList(6, 9) : first.subList(6, 9);
            assertThat(buckets.get(prefixSum % 8)).isEqualTo(expected);
        }
        assertThat((Integer) org.springframework.test.util.ReflectionTestUtils.invokeMethod(result, "count")).isEqualTo(2);
    }

    private List<EnrichedCandle> randomCandles(long seed) {
        var random = new Random(seed);
        var candles = new ArrayList<EnrichedCandle>();
        double price = 100;
        for (int index = 0; index < 180; index++) {
            double open = price;
            price = Math.max(20, price + random.nextGaussian() * 4);
            candles.add(new EnrichedCandle(1_600_000_000L + index * 86400L, open,
                    Math.max(open, price) + .5, Math.min(open, price) - .5, price,
                    1000, 1000, 50, price, price, price - 10, price + 10, 3));
        }
        return candles;
    }
}
