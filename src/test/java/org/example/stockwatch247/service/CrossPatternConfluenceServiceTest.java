package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class CrossPatternConfluenceServiceTest {
    private final CrossPatternConfluenceService service =
            new CrossPatternConfluenceService(new CandlePatternDetectionService());
    private final List<Long> candles = LongStream.rangeClosed(1, 20).boxed().toList();

    @Test
    void addsTenForEachMatchingOtherFamilyAndCapsAtOneHundred() {
        var timeline = timeline(
                observation(AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY, 14,
                        CandlePattern.ELLIOTT_BULLISH_CORRECTION),
                observation(AlertPatternFamily.HARMONIC_FORMATION, TradeSignal.BUY, 18,
                        CandlePattern.HARMONIC_GARTLEY));

        var assessment = service.assess(90, AlertPatternFamily.CANDLESTICK,
                TradeSignal.BUY, 20, timeline);

        assertThat(assessment.adjustment()).isEqualTo(20);
        assertThat(assessment.adjustedScore()).isEqualTo(100);
        assertThat(assessment.evidence()).hasSize(2);
        assertThat(assessment.reason()).contains("+20", "after the 0-100 cap");
    }

    @Test
    void subtractsTenForEachOpposingOtherFamilyAndCapsAtZero() {
        var timeline = timeline(
                observation(AlertPatternFamily.CANDLESTICK, TradeSignal.SELL, 17,
                        CandlePattern.BEARISH_ENGULFING),
                observation(AlertPatternFamily.HARMONIC_FORMATION, TradeSignal.SELL, 19,
                        CandlePattern.HARMONIC_BAT));

        var assessment = service.assess(5, AlertPatternFamily.ELLIOTT_WAVE,
                TradeSignal.BUY, 20, timeline);

        assertThat(assessment.adjustment()).isEqualTo(-20);
        assertThat(assessment.adjustedScore()).isZero();
        assertThat(assessment.reason()).contains("-20", "after the 0-100 cap");
    }

    @Test
    void usesOnlyEarlierEightCandlesAndNeverTheTargetOrFuture() {
        var timeline = timeline(
                observation(AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY, 11,
                        CandlePattern.ELLIOTT_BULLISH_CORRECTION),
                observation(AlertPatternFamily.HARMONIC_FORMATION, TradeSignal.BUY, 12,
                        CandlePattern.HARMONIC_GARTLEY),
                observation(AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.SELL, 20,
                        CandlePattern.ELLIOTT_BEARISH_CORRECTION),
                observation(AlertPatternFamily.HARMONIC_FORMATION, TradeSignal.SELL, 21,
                        CandlePattern.HARMONIC_BAT));

        var assessment = service.assess(70, AlertPatternFamily.CANDLESTICK,
                TradeSignal.BUY, 20, timeline);

        assertThat(assessment.adjustment()).isEqualTo(10);
        assertThat(assessment.evidence()).singleElement()
                .satisfies(item -> {
                    assertThat(item.timestamp()).isEqualTo(12);
                    assertThat(item.candlesAgo()).isEqualTo(8);
                });
    }

    @Test
    void countsEachFamilyOnceUsingItsMostRecentSignal() {
        var timeline = timeline(
                observation(AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.SELL, 14,
                        CandlePattern.ELLIOTT_BEARISH_CORRECTION),
                observation(AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY, 18,
                        CandlePattern.ELLIOTT_BULLISH_CORRECTION),
                observation(AlertPatternFamily.CANDLESTICK, TradeSignal.SELL, 19,
                        CandlePattern.BEARISH_ENGULFING));

        var assessment = service.assess(70, AlertPatternFamily.HARMONIC_FORMATION,
                TradeSignal.BUY, 20, timeline);

        assertThat(assessment.adjustment()).isZero();
        assertThat(assessment.evidence()).hasSize(2);
        assertThat(assessment.evidence()).filteredOn(item ->
                item.family() == AlertPatternFamily.ELLIOTT_WAVE)
                .singleElement().extracting(CrossPatternConfluenceService.Evidence::timestamp)
                .isEqualTo(18L);
    }

    @Test
    void ignoresSignalsFromTheTargetFamily() {
        var assessment = service.assess(70, AlertPatternFamily.CANDLESTICK,
                TradeSignal.BUY, 20, timeline(
                        observation(AlertPatternFamily.CANDLESTICK, TradeSignal.BUY, 19,
                                CandlePattern.HAMMER)));

        assertThat(assessment.adjustment()).isZero();
        assertThat(assessment.adjustedScore()).isEqualTo(70);
    }

    @Test
    void appliesIndependentConfiguredRewardsAndPenalties() {
        var policy = new CrossPatternConfluenceService.Policy(
                AlertPatternFamily.CANDLESTICK,
                Map.of(
                        AlertPatternFamily.ELLIOTT_WAVE,
                        new CrossPatternConfluenceService.Weight(true, 18, 7),
                        AlertPatternFamily.HARMONIC_FORMATION,
                        new CrossPatternConfluenceService.Weight(true, 4, 12)));
        var assessment = service.assess(70, AlertPatternFamily.CANDLESTICK, TradeSignal.BUY, 20,
                timeline(
                        observation(AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY, 18,
                                CandlePattern.ELLIOTT_BULLISH_CORRECTION),
                        observation(AlertPatternFamily.HARMONIC_FORMATION, TradeSignal.SELL, 19,
                                CandlePattern.HARMONIC_BAT)),
                policy);

        assertThat(assessment.adjustment()).isEqualTo(6);
        assertThat(assessment.adjustedScore()).isEqualTo(76);
        assertThat(assessment.reason()).contains("+18 points", "-12 points");
    }

    @Test
    void disabledSourceIsExplainedButDoesNotChangeTheScore() {
        var policy = new CrossPatternConfluenceService.Policy(
                AlertPatternFamily.CANDLESTICK,
                Map.of(AlertPatternFamily.ELLIOTT_WAVE,
                        new CrossPatternConfluenceService.Weight(false, 25, 15)));
        var assessment = service.assess(70, AlertPatternFamily.CANDLESTICK, TradeSignal.BUY, 20,
                timeline(observation(AlertPatternFamily.ELLIOTT_WAVE, TradeSignal.BUY, 19,
                        CandlePattern.ELLIOTT_BULLISH_CORRECTION)), policy);

        assertThat(assessment.adjustment()).isZero();
        assertThat(assessment.adjustedScore()).isEqualTo(70);
        assertThat(assessment.evidence()).singleElement()
                .satisfies(item -> assertThat(item.enabled()).isFalse());
        assertThat(assessment.reason()).contains("disabled for this scoring profile", "same-direction evidence");
    }

    private CrossPatternConfluenceService.Timeline timeline(
            CrossPatternConfluenceService.Observation... observations) {
        return new CrossPatternConfluenceService.Timeline(candles, List.of(observations));
    }

    private CrossPatternConfluenceService.Observation observation(
            AlertPatternFamily family, TradeSignal direction, long timestamp, CandlePattern pattern) {
        return new CrossPatternConfluenceService.Observation(family, direction, timestamp, pattern);
    }
}
