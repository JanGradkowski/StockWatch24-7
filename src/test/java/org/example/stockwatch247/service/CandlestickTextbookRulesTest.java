package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandlestickTextbookRulesTest {
    private final CandlePatternDetectionService detector = new CandlePatternDetectionService();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void starRequiresMiddleBodyToGapBeyondFirstBody(boolean bearish) {
        CandlePattern pattern = bearish ? CandlePattern.EVENING_STAR : CandlePattern.MORNING_STAR;
        assertPattern(pattern, bearish, true, bar(31,160,161,149,150), bar(32,149,150.5,148,149.5), bar(33,149,158,148,157));
        assertPattern(pattern, bearish, false, bar(31,160,161,149,150), bar(32,175,177,173,175.5), bar(33,149,158,148,157));
        assertPattern(pattern, bearish, false, bar(31,160,161,149,150), bar(32,149.5,151,148,150), bar(33,149,158,148,157));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reversalPairRequiresOpenBeyondPreviousWickAndClosePastMidpoint(boolean bearish) {
        CandlePattern pattern = bearish ? CandlePattern.DARK_CLOUD_COVER : CandlePattern.PIERCING_LINE;
        EnrichedCandle first = bar(31,160,161,149,150);
        assertPattern(pattern, bearish, true, first, bar(32,148,157,147,156));
        assertPattern(pattern, bearish, false, first, bar(32,149.5,157,149,156));
        assertPattern(pattern, bearish, false, first, bar(32,149,157,148,156));
        assertPattern(pattern, bearish, false, first, bar(32,148,156,147,155));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void engulfingUsesRealBodiesEvenWhenWicksAreLarge(boolean bearish) {
        CandlePattern pattern = bearish ? CandlePattern.BEARISH_ENGULFING : CandlePattern.BULLISH_ENGULFING;
        EnrichedCandle first = bar(31,161,166,156,160);
        assertPattern(pattern, bearish, true, first, bar(32,159.9,162,159.8,161.1));
        assertPattern(pattern, bearish, true, first, bar(32,160,162,159,161.1));
        assertPattern(pattern, bearish, false, first, bar(32,160,162,159,161));
        assertPattern(pattern, bearish, false, first, bar(32,160.1,162,159,161.1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void haramiAllowsAllBodyColorCombinationsButRequiresContainment(boolean bearish) {
        CandlePattern pattern = bearish ? CandlePattern.BEARISH_HARAMI : CandlePattern.BULLISH_HARAMI;
        for (boolean firstBullish : List.of(false, true)) {
            for (boolean secondBullish : List.of(false, true)) {
                EnrichedCandle first = bar(31,firstBullish ? 150 : 160,161,149,firstBullish ? 160 : 150);
                EnrichedCandle second = bar(32,secondBullish ? 153.5 : 154,155,152,secondBullish ? 154 : 153.5);
                assertPattern(pattern, bearish, true, first, second);
            }
        }
        assertPattern(pattern, bearish, false, bar(31,160,161,149,150), bar(32,149.5,151,149,150.2));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void haramiCrossHasDirectionalIdentityAndTwoCandleStructuralStop(boolean bearish) {
        CandlePattern pattern = bearish ? CandlePattern.BEARISH_HARAMI_CROSS : CandlePattern.BULLISH_HARAMI_CROSS;
        List<EnrichedCandle> candles = series(bearish, bar(31,160,161,149,150), bar(32,154,155,153,154));
        var signals = detector.detectAlertSignalsFactory(candles, TimeInterval.DAILY);
        assertThat(signals).anyMatch(signal -> signal.pattern() == pattern);
        assertThat(signals).noneMatch(signal -> signal.pattern() == (bearish ? CandlePattern.BEARISH_HARAMI : CandlePattern.BULLISH_HARAMI));
        assertThat(CandlestickSignalLifecyclePolicy.patternCandleCount(pattern)).isEqualTo(2);
        var signal = signals.stream().filter(item -> item.pattern() == pattern).findFirst().orElseThrow();
        var plan = CandlestickSignalLifecyclePolicy.tradePlan(pattern, signal.tradeSignal(), signal.closePrice(),
                candles.subList(30,32).stream().map(CandlestickTextbookRulesTest::raw).toList(), TimeInterval.DAILY);
        assertThat(plan.stopLossPrice()).isEqualTo(bearish ? 251.0 : 149.0);
    }

    @Test
    void invalidOrDuplicateBarCannotJoinAnEngulfingFormation() {
        for (EnrichedCandle boundary : List.of(bar(32,160,Double.NaN,150,155), bar(32,160,159,150,155), bar(31,160,161,150,155))) {
            assertPattern(CandlePattern.BULLISH_ENGULFING, false, false,
                    bar(31,162,163,159,160), boundary, bar(33,159,164,158.5,163));
        }
    }

    @Test
    void rawDataGuardRetainsBoundariesLostDuringEnrichmentAndRejectsStaleLatestSignal() {
        var candles = series(false, bar(31,162,163,159,160), bar(32,160,Double.NaN,150,155), bar(33,159,164,158.5,163));
        var integrity = new CandlestickFormationIntegrity(candles.stream().map(CandlestickTextbookRulesTest::raw).toList());
        var filtered = candles.stream().filter(c -> Double.isFinite(c.high())).toList();
        assertThat(integrity.context(filtered)).containsExactly(candles.getLast());
        assertThat(detector.detectAlertSignalsFactory(integrity.latestContext(filtered), TimeInterval.DAILY)).isEmpty();
        assertThat(integrity.adjacent(31,33)).isFalse();
        var tailInvalid = candles.subList(0,32);
        var latestGuard = new CandlestickFormationIntegrity(tailInvalid.stream().map(CandlestickTextbookRulesTest::raw).toList());
        assertThat(latestGuard.latestContext(tailInvalid.subList(0,31))).isEmpty();
    }

    @Test
    void detectionRecoversAfterBadDataAndReplayDoesNotChangePastSignals() {
        var candles = new ArrayList<>(List.of(bar(0,160,Double.NaN,150,155)));
        candles.addAll(series(false, bar(31,161,166,156,160), bar(32,159.9,162,159.8,161.1)));
        var before = detector.detectAlertSignalsFactory(candles, TimeInterval.DAILY);
        assertThat(before).anyMatch(signal -> signal.pattern() == CandlePattern.BULLISH_ENGULFING);
        int cutoff = candles.size();
        candles.add(bar(33,161,200,150,190));
        assertThat(detector.detectAlertSignalsFactory(candles.subList(0,cutoff), TimeInterval.DAILY)).isEqualTo(before);
    }

    private void assertPattern(CandlePattern pattern, boolean bearish, boolean expected, EnrichedCandle... formation) {
        var candles = series(bearish, formation);
        assertThat(detector.detectAlertSignalsFactory(candles, TimeInterval.DAILY).stream().anyMatch(s -> s.pattern() == pattern))
                .as("%s in %s", pattern, List.of(formation)).isEqualTo(expected);
        assertThat(detector.geometricCandidatesAt(candles, candles.size()-1).stream().anyMatch(candidate -> candidate.pattern() == pattern)).isEqualTo(expected);
    }

    private List<EnrichedCandle> series(boolean bearish, EnrichedCandle... formation) {
        List<EnrichedCandle> candles = new ArrayList<>();
        for (int index=0; index<30; index++) {
            double open = 220 - 2*index;
            candles.add(bar(index+1,open,open+.5,open-1.5,open-1));
        }
        candles.addAll(List.of(formation));
        return bearish ? candles.stream().map(c -> bar(c.timestamp(),400-c.open(),400-c.low(),400-c.high(),400-c.close())).toList() : candles;
    }

    private static EnrichedCandle bar(long timestamp, double open, double high, double low, double close) {
        return new EnrichedCandle(timestamp,open,high,low,close,1000,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN);
    }

    private static Candle raw(EnrichedCandle c) {
        return new Candle("TEST","daily",c.timestamp(),c.open(),c.high(),c.low(),c.close(),1000L);
    }
}
