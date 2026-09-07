package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ElliottTradePlanPolicyTest {

    @Test
    void createsBullishWaveThreePlanFromValidatedWaveTwoWithoutChangingTheCount() {
        ElliottTradePlanPolicy.TradePlan plan = ElliottTradePlanPolicy.calculate(
                ElliottSignalStage.WAVE_II_END, "BULLISH", TradeSignal.BUY,
                104.0, bullishCycle(), 2.0, TimeInterval.DAILY).orElseThrow();

        assertThat(plan.structuralStopPrice()).isEqualTo(100.0);
        assertThat(plan.stopLossPrice()).isEqualTo(99.8);
        assertThat(plan.targetMidpoint()).isEqualTo(120.18);
        assertThat(plan.targetZoneLow()).isCloseTo(118.3773, within(.000001));
        assertThat(plan.targetTriggerPrice()).isEqualTo(plan.targetZoneLow());
        assertThat(plan.requiredRewardRiskRatio()).isEqualTo(2.0);
        assertThat(plan.actualRewardRiskRatio()).isGreaterThan(2.0);
        assertThat(plan.actionable()).isTrue();
        assertThat(plan.hardInvalidationPrice()).isEqualTo(100.0);
        assertThat(plan.hardInvalidationSide()).isEqualTo(ElliottTradePlanPolicy.BoundarySide.BELOW);
    }

    @Test
    void mirrorsWaveTwoPlanForBearishCycleAndUsesWeeklyOneToThreeRequirement() {
        ElliottTradePlanPolicy.TradePlan plan = ElliottTradePlanPolicy.calculate(
                ElliottSignalStage.WAVE_II_END, "BEARISH", TradeSignal.SELL,
                96.0, bearishCycle(), 2.0, TimeInterval.WEEKLY).orElseThrow();

        assertThat(plan.structuralStopPrice()).isEqualTo(100.0);
        assertThat(plan.stopLossPrice()).isEqualTo(100.2);
        assertThat(plan.targetMidpoint()).isEqualTo(79.82);
        assertThat(plan.targetTriggerPrice()).isEqualTo(plan.targetZoneHigh());
        assertThat(plan.requiredRewardRiskRatio()).isEqualTo(3.0);
        assertThat(plan.actualRewardRiskRatio()).isGreaterThan(3.0);
        assertThat(plan.actionable()).isTrue();
        assertThat(plan.hardInvalidationSide()).isEqualTo(ElliottTradePlanPolicy.BoundarySide.ABOVE);
    }

    @Test
    void usesLogarithmicBearishProjectionOnlyAfterBothArithmeticWaveThreeTargetsReachZero() {
        List<ElliottWaveDetectionService.ElliottWavePoint> mara = List.of(
                point("0", 16.43), point("I", 8.68), point("II", 12.32));

        ElliottTradePlanPolicy.TradePlan plan = ElliottTradePlanPolicy.calculate(
                ElliottSignalStage.WAVE_II_END, "BEARISH", TradeSignal.SELL,
                10.6635, mara, 1.0, TimeInterval.DAILY).orElseThrow();

        double expectedMidpoint = 12.32 * Math.pow(8.68 / 16.43, 1.618);
        assertThat(12.32 - Math.abs(8.68 - 16.43) * 1.618).isLessThanOrEqualTo(0.0);
        assertThat(12.32 - Math.abs(8.68 - 16.43) * 2.618).isLessThanOrEqualTo(0.0);
        assertThat(plan.targetMidpoint()).isCloseTo(expectedMidpoint, within(.000001));
        assertThat(plan.targetTriggerPrice()).isCloseTo(expectedMidpoint * 1.015, within(.000001));
        assertThat(plan.targetMidpoint()).isPositive();
        assertThat(plan.targetBasis()).contains("Logarithmic Wave III", "both arithmetic extensions");
        assertThat(plan.actionable()).isFalse();
        assertThat(plan.qualification()).startsWith("Projection only:");
    }

    @Test
    void supportsEveryAlreadyDetectedStageWithTextbookStructuralAnchors() {
        ElliottTradePlanPolicy.TradePlan waveFour = plan(ElliottSignalStage.WAVE_III_END, TradeSignal.SELL, 126.0);
        ElliottTradePlanPolicy.TradePlan waveFive = plan(ElliottSignalStage.WAVE_IV_END, TradeSignal.BUY, 118.0);
        ElliottTradePlanPolicy.TradePlan correction = plan(ElliottSignalStage.WAVE_V_END, TradeSignal.SELL, 130.0);
        ElliottTradePlanPolicy.TradePlan nextCycle = plan(ElliottSignalStage.CORRECTION_END, TradeSignal.BUY, 115.0);

        assertThat(waveFour.structuralStopPrice()).isEqualTo(126.0);
        assertThat(waveFour.targetBasis()).contains("38.2% retracement of Wave III");
        assertThat(waveFive.structuralStopPrice()).isEqualTo(118.0);
        assertThat(waveFive.hardInvalidationPrice()).isEqualTo(110.0);
        assertThat(correction.structuralStopPrice()).isEqualTo(130.0);
        assertThat(correction.targetBasis()).contains("previous Wave IV");
        assertThat(nextCycle.structuralStopPrice()).isEqualTo(115.0);
        assertThat(nextCycle.targetBasis()).contains("prior Wave V");
    }

    @Test
    void labelsAValidFibonacciProjectionNonActionableWhenMinimumRiskRewardIsNotMet() {
        ElliottTradePlanPolicy.TradePlan plan = ElliottTradePlanPolicy.calculate(
                ElliottSignalStage.WAVE_II_END, "BULLISH", TradeSignal.BUY,
                118.0, bullishCycle(), 2.0, TimeInterval.DAILY).orElseThrow();

        assertThat(plan.actionable()).isFalse();
        assertThat(plan.actualRewardRiskRatio()).isLessThan(2.0);
        assertThat(plan.qualification()).startsWith("Projection only:");
    }

    @Test
    void rejectsMissingStructureInsteadOfInventingWavePoints() {
        assertThat(ElliottTradePlanPolicy.calculate(
                ElliottSignalStage.WAVE_IV_END, "BULLISH", TradeSignal.BUY,
                118.0, bullishCycle().subList(0, 3), 2.0, TimeInterval.DAILY)).isEmpty();
    }

    private ElliottTradePlanPolicy.TradePlan plan(
            ElliottSignalStage stage, TradeSignal move, double entry) {
        return ElliottTradePlanPolicy.calculate(
                stage, "BULLISH", move, entry, bullishCycle(), 2.0, TimeInterval.DAILY)
                .orElseThrow();
    }

    private List<ElliottWaveDetectionService.ElliottWavePoint> bullishCycle() {
        return List.of(
                point("0", 100), point("I", 110), point("II", 104),
                point("III", 126), point("IV", 118), point("V", 130),
                point("A", 122), point("B", 127), point("C", 115));
    }

    private List<ElliottWaveDetectionService.ElliottWavePoint> bearishCycle() {
        return List.of(
                point("0", 100), point("I", 90), point("II", 96),
                point("III", 74), point("IV", 82), point("V", 70),
                point("A", 78), point("B", 73), point("C", 85));
    }

    private ElliottWaveDetectionService.ElliottWavePoint point(String label, double price) {
        return new ElliottWaveDetectionService.ElliottWavePoint(label, (long) label.hashCode(), price, "LOW");
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
