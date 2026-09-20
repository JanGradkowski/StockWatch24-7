package org.example.stockwatch247.service;

import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TradePlanRefinementTest {
    static Candle bar(long t, double o, double h, double l, double c) {
        return new Candle("TEST", "1d", t, o, h, l, c, 100L);
    }
    static List<ElliottWaveDetectionService.ElliottWavePoint> points(double... prices) {
        String[] labels = {"0", "I", "II", "III", "IV", "V", "A", "B", "C"};
        List<ElliottWaveDetectionService.ElliottWavePoint> result = new ArrayList<>();
        for (int i=0;i<prices.length;i++) result.add(new ElliottWaveDetectionService.ElliottWavePoint(labels[i], 1L+i, prices[i], i%2==0?"LOW":"HIGH"));
        return result;
    }
    @ParameterizedTest @EnumSource(value=TimeInterval.class, names={"DAILY","WEEKLY","MONTHLY"})
    void refusesWaveFourOverlapAndImpossibleFifthTargetsOnBothSides(TimeInterval interval) {
        for (boolean buy : List.of(true, false)) {
            double[] prices = {100,120,110,123,121};
            if (!buy) for(int i=0;i<prices.length;i++) prices[i]=240-prices[i];
            assertThat(ElliottTradePlanPolicy.calculate(ElliottSignalStage.WAVE_III_END,
                    buy?"BULLISH":"BEARISH", buy?TradeSignal.SELL:TradeSignal.BUY,
                    buy?122:118, points(Arrays.copyOf(prices,4)), 2, interval)).isEmpty();
            assertThat(ElliottTradePlanPolicy.calculate(ElliottSignalStage.WAVE_IV_END,
                    buy?"BULLISH":"BEARISH", buy?TradeSignal.BUY:TradeSignal.SELL,
                    buy?122:118, points(prices), 2, interval)).isEmpty();
        }
    }
    @ParameterizedTest @EnumSource(value=TimeInterval.class, names={"DAILY","WEEKLY","MONTHLY"})
    void preservesWideStructuralStopAndRejectsTheTrade(TimeInterval interval) {
        var prefs=CandlestickPatternPreferencesService.factoryPreferences();
        var plan=CandlestickSignalLifecyclePolicy.tradePlan(TradeSignal.BUY,100,70,interval,2,4,prefs.circuitBreaker(interval));
        assertThat(plan.stopLossPrice()).isLessThan(70);
        assertThat(plan.actionable()).isFalse();
        assertThat(plan.qualification()).contains("too wide");
        assertThat(plan.atrCircuitBreakerApplied()).isFalse();
        assertThat(plan.riskAtr()).isGreaterThan(7);
    }
    @Test void primaryTargetDoesNotJumpToAnExtensionToPassRewardRisk() {
        var plan=ElliottTradePlanPolicy.calculate(ElliottSignalStage.WAVE_II_END,"BULLISH",TradeSignal.BUY,
                108,points(100,110,104),4,TimeInterval.DAILY).orElseThrow();
        assertThat(plan.targetMidpoint()).isEqualTo(120.18);
        assertThat(plan.actionable()).isFalse();
        assertThat(plan.secondaryTarget()).isGreaterThan(plan.targetTriggerPrice());
    }
    @Test void gapsUseOpeningPriceAndAmbiguousBarsUseStopFirst() {
        var gap=TradeOutcomePolicy.evaluate(TradeSignal.BUY,95,110.0,bar(1,90,101,89,100));
        assertThat(gap.kind()).isEqualTo(TradeOutcomePolicy.Kind.STOPPED);
        assertThat(gap.price()).isEqualTo(90);
        var both=TradeOutcomePolicy.evaluate(TradeSignal.BUY,95,110.0,bar(1,100,112,94,109));
        assertThat(both.kind()).isEqualTo(TradeOutcomePolicy.Kind.STOPPED);
        assertThat(both.reason()).contains("order unknown");
        var targetGap=TradeOutcomePolicy.evaluate(TradeSignal.SELL,105,90.0,bar(1,89,106,88,99));
        assertThat(targetGap.kind()).isEqualTo(TradeOutcomePolicy.Kind.TARGET_REACHED);
        assertThat(targetGap.price()).isEqualTo(90);
    }
    @Test void rejectsMissingVolatilityAndInvalidPricesWithoutTightening() {
        assertThat(TradeRiskPolicy.qualify(TradeSignal.BUY,100,95,110,Double.NaN,TimeInterval.DAILY,2).reason()).contains("volatility");
        assertThatThrownBy(()->CandlestickSignalLifecyclePolicy.tradePlan(TradeSignal.BUY,100,-5,TimeInterval.DAILY,2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TradeOutcomePolicy.evaluate(TradeSignal.BUY,95,110.0,bar(1,100,99,101,100))).isNull();
    }
    @Test void invalidPriceBarCannotCreateAnExpiryOrSkipToALaterTarget() {
        var bad = bar(1,100,99,101,100);
        assertThat(CandlestickSignalLifecyclePolicy.resolveProtective(TradeSignal.BUY,110,95,List.of(bad),1)).isNull();
        assertThat(CandlestickSignalLifecyclePolicy.resolveProtective(TradeSignal.BUY,110,95,
                List.of(bad,bar(2,100,115,98,112)),2)).isNull();
    }
    @Test void wilderAtrIsCausalAndUsesGaps() {
        var bars=List.of(bar(1,100,102,98,100),bar(2,110,112,108,110),bar(3,110,111,109,110),bar(4,1000,1001,999,1000));
        // Seed = (4+12)/2=8; next=(8+2)/2=5.
        assertThat(TradeRiskPolicy.atr(bars,2,2)).isEqualTo(5);
        assertThat(TradeRiskPolicy.atr(bars.subList(0,3),2,2)).isEqualTo(5);
    }
    @Test void incompleteLowerTimeframeHistoryCannotOverrideConservativeParentOutcome() {
        var parent=bar(1,100,112,94,105); parent.setVolume(200L);
        var lower=List.of(bar(1,100,112,99,111),bar(2,111,111,94,105));
        assertThat(TradeExecutionService.reconciles(parent,lower)).isTrue();
        assertThat(TradeExecutionService.reconciles(parent,lower.subList(0,1))).isFalse();
    }
    @Test void nearestConfirmedResistanceLimitsCandlestickObjectiveWithoutFutureLeakage() {
        var bars=List.of(bar(1,100,101,99,100),bar(2,101,102,100,101),bar(3,103,105,101,102),
                bar(4,101,102,99,100),bar(5,100,101,98,100),bar(6,100,500,90,300));
        assertThat(TradeRiskPolicy.nearestObjective(bars,4,TradeSignal.BUY,100,112)).isEqualTo(105);
    }
    @ParameterizedTest @EnumSource(HarmonicPatternType.class)
    void harmonicTargetsAndRiskQualificationExistAcrossIntervalsAndDirections(HarmonicPatternType type) {
        for(var interval:List.of(TimeInterval.DAILY,TimeInterval.WEEKLY,TimeInterval.MONTHLY)) {
            for (var side:List.of(TradeSignal.BUY,TradeSignal.SELL)) {
                String[] labels=type==HarmonicPatternType.SHARK?new String[]{"0","X","A","B","C"}:new String[]{"X","A","B","C","D"};
                double[] prices=type==HarmonicPatternType.SHARK?new double[]{100,120,108,124,99}:new double[]{100,120,110,115,104};
                var ps=new ArrayList<HarmonicPatternDetectionService.HarmonicPoint>();
                for(int i=0;i<5;i++) ps.add(new HarmonicPatternDetectionService.HarmonicPoint(labels[i],i+1L,
                        side==TradeSignal.BUY?prices[i]:200-prices[i],HarmonicPatternDetectionService.PivotType.LOW));
                var f=new HarmonicPatternDetectionService.HarmonicFormation(type,side,
                        side==TradeSignal.BUY?HarmonicPatternDetectionService.Direction.BULLISH:HarmonicPatternDetectionService.Direction.BEARISH,
                        ps,6L,90,0,new LinkedHashMap<>(),List.of(),"TEST");
                var p=HarmonicStopPlanPolicy.calculate(f,side==TradeSignal.BUY?105:95,2,interval).orElseThrow();
                assertThat(p.primaryTarget()).isPositive();
                assertThat(p.qualification()).isNotBlank();
                assertThat(p.horizon()).isPositive();
                if(type==HarmonicPatternType.SHARK) { assertThat(p.secondaryTarget()).isNull(); assertThat(p.horizon()).isLessThan(6); }
                else assertThat(p.secondaryTarget()).isNotNull();
            }
        }
    }
}
