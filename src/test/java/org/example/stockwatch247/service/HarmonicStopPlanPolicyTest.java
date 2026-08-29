package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HarmonicStopPlanPolicyTest {

    @Test
    void calculatesEverySupportedBullishStructuralLevelAndHalfPercentBuffer() {
        assertBullish(HarmonicPatternType.GARTLEY, 100.0);
        assertBullish(HarmonicPatternType.BAT, 100.0);
        assertBullish(HarmonicPatternType.CYPHER, 100.0);
        assertBullish(HarmonicPatternType.BUTTERFLY, 91.72);
        assertBullish(HarmonicPatternType.CRAB, 80.0);
        assertBullish(HarmonicPatternType.SHARK, 94.6);
    }

    @Test
    void mirrorsEverySupportedStopForBearishFormations() {
        assertBearish(HarmonicPatternType.GARTLEY, 100.0);
        assertBearish(HarmonicPatternType.BAT, 100.0);
        assertBearish(HarmonicPatternType.CYPHER, 100.0);
        assertBearish(HarmonicPatternType.BUTTERFLY, 108.28);
        assertBearish(HarmonicPatternType.CRAB, 120.0);
        assertBearish(HarmonicPatternType.SHARK, 105.4);
    }

    @Test
    void usesConfirmationCloseAsEntryAndKeepsPrzEndpointSeparate() {
        HarmonicPatternDetectionService.HarmonicFormation formation =
                formation(HarmonicPatternType.BUTTERFLY, TradeSignal.BUY);

        HarmonicStopPlanPolicy.StopPlan plan = HarmonicStopPlanPolicy.calculate(
                formation, 95.25).orElseThrow();

        assertThat(plan.entryPrice()).isEqualTo(95.25);
        assertThat(formation.points().getLast().price()).isNotEqualTo(plan.entryPrice());
        assertThat(plan.basis()).isEqualTo("1.414 XA extension");
        assertThat(plan.formula()).contains("1.414");
    }

    @Test
    void rejectsAPlanWhenTheCalculatedStopIsNotOnTheLossSideOfEntry() {
        assertThat(HarmonicStopPlanPolicy.calculate(
                formation(HarmonicPatternType.CRAB, TradeSignal.BUY), 75.0)).isEmpty();
        assertThat(HarmonicStopPlanPolicy.calculate(
                formation(HarmonicPatternType.CRAB, TradeSignal.SELL), 125.0)).isEmpty();
    }

    private void assertBullish(HarmonicPatternType pattern, double expectedStructural) {
        double entry = pattern == HarmonicPatternType.CRAB ? 85.0 : 105.0;
        HarmonicStopPlanPolicy.StopPlan plan = HarmonicStopPlanPolicy.calculate(
                formation(pattern, TradeSignal.BUY), entry).orElseThrow();
        assertThat(plan.structuralInvalidationPrice()).isCloseTo(expectedStructural, within(1e-9));
        assertThat(plan.bufferPercent()).isEqualTo(.5);
        assertThat(plan.bufferAmount()).isCloseTo(expectedStructural * .005, within(1e-9));
        assertThat(plan.stopLossPrice()).isCloseTo(expectedStructural * .995, within(1e-9));
        assertThat(plan.stopLossPrice()).isLessThan(plan.entryPrice());
    }

    private void assertBearish(HarmonicPatternType pattern, double expectedStructural) {
        double entry = pattern == HarmonicPatternType.CRAB ? 115.0 : 95.0;
        HarmonicStopPlanPolicy.StopPlan plan = HarmonicStopPlanPolicy.calculate(
                formation(pattern, TradeSignal.SELL), entry).orElseThrow();
        assertThat(plan.structuralInvalidationPrice()).isCloseTo(expectedStructural, within(1e-9));
        assertThat(plan.bufferPercent()).isEqualTo(.5);
        assertThat(plan.bufferAmount()).isCloseTo(expectedStructural * .005, within(1e-9));
        assertThat(plan.stopLossPrice()).isCloseTo(expectedStructural * 1.005, within(1e-9));
        assertThat(plan.stopLossPrice()).isGreaterThan(plan.entryPrice());
    }

    private HarmonicPatternDetectionService.HarmonicFormation formation(
            HarmonicPatternType pattern, TradeSignal signal) {
        boolean buy = signal == TradeSignal.BUY;
        String[] labels = pattern == HarmonicPatternType.SHARK
                ? new String[]{"0", "X", "A", "B", "C"}
                : new String[]{"X", "A", "B", "C", "D"};
        double[] prices;
        if (pattern == HarmonicPatternType.SHARK) {
            prices = buy
                    ? new double[]{100, 120, 108, 124, 99}
                    : new double[]{100, 80, 92, 76, 101};
        } else {
            prices = buy
                    ? new double[]{100, 120, 110, 115, 104}
                    : new double[]{100, 80, 90, 85, 96};
        }
        List<HarmonicPatternDetectionService.HarmonicPoint> points =
                java.util.stream.IntStream.range(0, labels.length)
                        .mapToObj(index -> new HarmonicPatternDetectionService.HarmonicPoint(
                                labels[index], index + 1L, prices[index],
                                index % 2 == 0
                                        ? HarmonicPatternDetectionService.PivotType.LOW
                                        : HarmonicPatternDetectionService.PivotType.HIGH))
                        .toList();
        return new HarmonicPatternDetectionService.HarmonicFormation(
                pattern, signal,
                buy ? HarmonicPatternDetectionService.Direction.BULLISH
                        : HarmonicPatternDetectionService.Direction.BEARISH,
                points, 10L, 90, 0.0, new LinkedHashMap<>(), List.of(),
                HarmonicPatternDetectionService.RULE_VERSION);
    }
}
