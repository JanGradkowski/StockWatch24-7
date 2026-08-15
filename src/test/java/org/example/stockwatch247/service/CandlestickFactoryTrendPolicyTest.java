package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.PriorTrendAssessment;
import org.example.stockwatch247.service.CandlePatternDetectionService.TrendDirection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandlestickFactoryTrendPolicyTest {
    private final CandlePatternDetectionService detection = new CandlePatternDetectionService();

    @Test
    void usesSymmetricSwingStructureForWeeklyAndMonthlyBuyContext() {
        List<EnrichedCandle> falling = swingSeries(35, 140.0, -0.8);

        PriorTrendAssessment weekly = detection.assessFactoryPriorTrendForLatestPattern(
                falling, 1, TimeInterval.WEEKLY, TradeSignal.BUY);
        PriorTrendAssessment monthly = detection.assessFactoryPriorTrendForLatestPattern(
                falling, 1, TimeInterval.MONTHLY, TradeSignal.BUY);

        assertThat(weekly.direction()).isEqualTo(TrendDirection.DOWN);
        assertThat(monthly.direction()).isEqualTo(TrendDirection.DOWN);
        assertThat(weekly.description()).contains("minimum swing displacement 0.25 ATR");
        assertThat(monthly.description()).contains("minimum swing displacement 0.25 ATR");
    }

    @Test
    void acceptsMatchingAdaptiveAndOriginalRulesForBothDailyDirections() {
        PriorTrendAssessment sell = detection.assessFactoryPriorTrendForLatestPattern(
                swingSeries(35, 80.0, 0.8), 1, TimeInterval.DAILY, TradeSignal.SELL);
        PriorTrendAssessment buy = detection.assessFactoryPriorTrendForLatestPattern(
                swingSeries(35, 140.0, -0.8), 1, TimeInterval.DAILY, TradeSignal.BUY);

        assertThat(sell.direction()).isEqualTo(TrendDirection.UP);
        assertThat(sell.description()).contains("structure with strong regression veto")
                .contains("minimum swing displacement 0.25 ATR");
        assertThat(buy.direction()).isEqualTo(TrendDirection.DOWN);
        assertThat(buy.description()).contains("structure with strong regression veto")
                .contains("minimum swing displacement 0.25 ATR")
                .contains("conflict-aware OR result: accepted down");
    }

    @Test
    void classifiesTinyDailyPivotDriftAsSidewaysInBothDirections() {
        PriorTrendAssessment down = detection.assessFactoryPriorTrendForLatestPattern(
                swingSeries(35, 100.0, -0.01), 1, TimeInterval.DAILY, TradeSignal.BUY);
        PriorTrendAssessment up = detection.assessFactoryPriorTrendForLatestPattern(
                swingSeries(35, 100.0, 0.01), 1, TimeInterval.DAILY, TradeSignal.SELL);

        assertThat(down.direction()).isEqualTo(TrendDirection.SIDEWAYS);
        assertThat(up.direction()).isEqualTo(TrendDirection.SIDEWAYS);
    }

    @Test
    void usesSymmetricSwingStructureForWeeklyAndMonthlySellContext() {
        PriorTrendAssessment weekly = detection.assessFactoryPriorTrendForLatestPattern(
                swingSeries(35, 100.0, 0.8), 1, TimeInterval.WEEKLY, TradeSignal.SELL);
        PriorTrendAssessment monthly = detection.assessFactoryPriorTrendForLatestPattern(
                swingSeries(35, 100.0, 0.8), 1, TimeInterval.MONTHLY, TradeSignal.SELL);

        assertThat(weekly.direction()).isEqualTo(TrendDirection.UP);
        assertThat(monthly.direction()).isEqualTo(TrendDirection.UP);
        assertThat(weekly.description()).contains("minimum swing displacement 0.25 ATR");
        assertThat(monthly.description()).contains("minimum swing displacement 0.25 ATR");
    }

    @Test
    void classifiesTinyWeeklyAndMonthlyPivotDriftAsSidewaysInBothDirections() {
        for (TimeInterval interval : List.of(TimeInterval.WEEKLY, TimeInterval.MONTHLY)) {
            PriorTrendAssessment down = detection.assessFactoryPriorTrendForLatestPattern(
                    swingSeries(35, 100.0, -0.01), 1, interval, TradeSignal.BUY);
            PriorTrendAssessment up = detection.assessFactoryPriorTrendForLatestPattern(
                    swingSeries(35, 100.0, 0.01), 1, interval, TradeSignal.SELL);

            assertThat(down.direction()).isEqualTo(TrendDirection.SIDEWAYS);
            assertThat(up.direction()).isEqualTo(TrendDirection.SIDEWAYS);
        }
    }

    @Test
    void acceptsAdaptiveDirectionWhenTheOriginalRuleIsNonDirectional() {
        for (TimeInterval interval : List.of(
                TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY)) {
            CandlePatternDetectionService.TrendDetectionRules strictOriginalRule =
                    CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(
                            interval, interval == TimeInterval.DAILY ? 0.25 : 0.0,
                            true, 4, 6, 50.0);
            PriorTrendAssessment buy = detection.assessPriorTrendForLatestPattern(
                    swingSeries(35, 140.0, -0.8), 1, strictOriginalRule, TradeSignal.BUY);
            PriorTrendAssessment sell = detection.assessPriorTrendForLatestPattern(
                    swingSeries(35, 80.0, 0.8), 1, strictOriginalRule, TradeSignal.SELL);

            assertThat(buy.direction()).isEqualTo(TrendDirection.DOWN);
            assertThat(sell.direction()).isEqualTo(TrendDirection.UP);
            assertThat(buy.description()).contains("conflict-aware OR result: accepted down");
            assertThat(sell.description()).contains("conflict-aware OR result: accepted up");
        }
    }

    @Test
    void combinesIndependentModelsWithConflictAwareOr() {
        assertThat(detection.combineIndependentTrendDirections(
                TrendDirection.DOWN, TrendDirection.SIDEWAYS)).isEqualTo(TrendDirection.DOWN);
        assertThat(detection.combineIndependentTrendDirections(
                TrendDirection.SIDEWAYS, TrendDirection.UP)).isEqualTo(TrendDirection.UP);
        assertThat(detection.combineIndependentTrendDirections(
                TrendDirection.DOWN, TrendDirection.DOWN)).isEqualTo(TrendDirection.DOWN);
        assertThat(detection.combineIndependentTrendDirections(
                TrendDirection.DOWN, TrendDirection.UP)).isEqualTo(TrendDirection.SIDEWAYS);
    }

    @Test
    void acceptsOriginalRuleWhenAdaptiveSwingStructureIsUnavailable() {
        for (TimeInterval interval : List.of(
                TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY)) {
            PriorTrendAssessment buy = detection.assessFactoryPriorTrendForLatestPattern(
                    linearSeries(35, 140.0, -1.0), 1, interval, TradeSignal.BUY);
            PriorTrendAssessment sell = detection.assessFactoryPriorTrendForLatestPattern(
                    linearSeries(35, 80.0, 1.0), 1, interval, TradeSignal.SELL);

            assertThat(buy.direction()).isEqualTo(TrendDirection.DOWN);
            assertThat(sell.direction()).isEqualTo(TrendDirection.UP);
            assertThat(buy.description()).contains("conflict-aware OR result: accepted down");
            assertThat(sell.description()).contains("conflict-aware OR result: accepted up");
        }
    }

    private List<EnrichedCandle> linearSeries(int size, double start, double step) {
        List<EnrichedCandle> candles = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            double close = start + step * index;
            candles.add(new EnrichedCandle(
                    (long) index, close - 0.3, close + 1.0, close - 1.0, close,
                    1_000, 1_000, 50, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0,
                    2.0, 0, Double.NaN, Double.NaN, Double.NaN));
        }
        return candles;
    }

    private List<EnrichedCandle> swingSeries(int size, double start, double step) {
        double[] cycle = {0.0, 2.0, 4.0, 2.0, 0.0, -2.0, -4.0, -2.0};
        List<EnrichedCandle> candles = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            double close = start + step * index + cycle[index % cycle.length];
            int firstTailIndex = size - 7;
            if (index >= firstTailIndex && index < size - 1) {
                double tailStart = start + step * firstTailIndex + cycle[firstTailIndex % cycle.length];
                close = tailStart + step * 1.5 * (index - firstTailIndex);
            }
            candles.add(new EnrichedCandle(
                    (long) index, close - 0.3, close + 1.0, close - 1.0, close,
                    1_000, 1_000, 50, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0,
                    2.0, 0, Double.NaN, Double.NaN, Double.NaN));
        }
        return candles;
    }
}
