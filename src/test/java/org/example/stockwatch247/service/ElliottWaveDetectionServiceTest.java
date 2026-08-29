package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ElliottWaveDetectionServiceTest {
    private final ElliottWaveDetectionService detectionService = new ElliottWaveDetectionService();
    private final ElliottWaveDetectionService v2DetectionService = new ElliottWaveDetectionService(
            1, ElliottWaveDetectionService.ScoringModel.V2);

    @Test
    void detectsBullishImpulseBreakoutFromAlternatingPivots() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0),
                anchor(6, 100.0),
                anchor(14, 121.0),
                anchor(24, 110.0),
                anchor(38, 143.0),
                anchor(54, 126.0),
                anchor(75, 142.0),
                anchor(76, 146.0)
        ));

        List<DetectedSignal> signals = detectionService.detect(candles);

        assertThat(signals).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_IMPULSE);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(signal.confidenceScore()).isGreaterThanOrEqualTo(75);
            assertThat(signal.candleTimestamp()).isEqualTo(76L);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("wave-5 breakout"));
        });
    }

    @Test
    void optionalShortestWaveOneRuleRejectsAnOtherwiseValidLongerWaveOne() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0),
                anchor(6, 100.0),
                anchor(14, 128.0),
                anchor(24, 112.0),
                anchor(38, 150.0),
                anchor(54, 130.0),
                anchor(68, 155.0),
                anchor(69, 151.0)
        ));
        ElliottWaveDetectionService strict = detectionService.configured(
                ElliottWaveDetectionService.DetectionRules.factory()
                        .withRequireWaveOneShortest(true));

        assertThat(detectionService.detect(candles))
                .anyMatch(signal -> signal.pattern() == CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
        assertThat(strict.detect(candles))
                .noneMatch(signal -> signal.pattern() == CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
    }

    @Test
    void detectsOnlyRecentBullishImpulseFollowThroughOnTheLatestCandle() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0),
                anchor(6, 100.0),
                anchor(14, 121.0),
                anchor(24, 110.0),
                anchor(38, 143.0),
                anchor(54, 126.0),
                anchor(75, 142.0),
                anchor(76, 146.0),
                anchor(77, 147.0)
        ));

        List<DetectedSignal> signals = detectionService.detect(candles);

        assertThat(signals).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_IMPULSE);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(signal.candleTimestamp()).isEqualTo(77L);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("follow-through"));
        });
    }

    @Test
    void ignoresStaleBullishImpulseBreakoutWhenLatestCandleIsNoLongerPresentSignal() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0),
                anchor(6, 100.0),
                anchor(14, 121.0),
                anchor(24, 110.0),
                anchor(38, 143.0),
                anchor(54, 126.0),
                anchor(75, 142.0),
                anchor(76, 146.0),
                anchor(80, 148.0)
        ));

        List<DetectedSignal> signals = detectionService.detect(candles);

        assertThat(signals)
                .noneMatch(signal -> signal.pattern() == CandlePattern.ELLIOTT_BULLISH_IMPULSE
                        && signal.candleTimestamp().equals(80L));
    }

    @Test
    void detectsBuyAtTheCurrentEndOfBullishWaveC() {
        List<EnrichedCandle> candles = bullishAbcCorrectionSeries();

        assertThat(detectionService.detect(candles)).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_CORRECTION);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(signal.candleTimestamp()).isEqualTo(87L);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("wave C ended"));
            assertThat(signal.reasons().stream()
                    .filter(reason -> reason.matches("^.+ \\+[0-9]+/[0-9]+: .+$")))
                    .hasSize(7);
        });
    }

    @Test
    void v2ScoreDoesNotReplaceTheFrozenV1AlertEligibilityGate() {
        List<EnrichedCandle> candles = bullishAbcCorrectionSeries();

        DetectedSignal signal = v2DetectionService.detectAlertSignals(candles).stream()
                .filter(candidate -> candidate.pattern() == CandlePattern.ELLIOTT_BULLISH_CORRECTION)
                .findFirst()
                .orElseThrow();

        assertThat(signal.eligibilityScore()).isEqualTo(100);
        assertThat(signal.confidenceScore()).isEqualTo(74);
        assertThat(signal.reasons().stream()
                .filter(reason -> reason.matches("^.+ \\+[0-9]+/[0-9]+: .+$")))
                .hasSize(7);
        assertThat(signal.reasons().getLast()).startsWith("V1 detection eligibility: 100/100");
    }

    @Test
    void exposesTheSameFiveWaveAndAbcPointsForChartRendering() {
        ElliottWaveDetectionService.ElliottWaveStructure structure = detectionService
                .findLatestWaveStructure(bullishAbcCorrectionSeries())
                .orElseThrow();

        assertThat(structure.direction()).isEqualTo("BULLISH");
        assertThat(structure.correctionComplete()).isTrue();
        assertThat(structure.points()).extracting(ElliottWaveDetectionService.ElliottWavePoint::label)
                .containsExactly("", "I", "II", "III", "IV", "V", "A", "B", "C");
        assertThat(structure.points().getLast().timestamp()).isEqualTo(86L);
        assertThat(detectionService.findHistoricalWaveStructures(bullishAbcCorrectionSeries()))
                .anySatisfy(historic -> assertThat(historic.points())
                        .extracting(ElliottWaveDetectionService.ElliottWavePoint::label)
                        .containsExactly("", "I", "II", "III", "IV", "V", "A", "B", "C"));
    }

    @Test
    void reconstructsTheSevenV2CategoriesForHistoricalElliottDetails() {
        List<EnrichedCandle> candles = bullishAbcCorrectionSeries();
        ElliottWaveDetectionService.ElliottWaveStructure structure = detectionService
                .findLatestWaveStructure(candles)
                .orElseThrow();

        ElliottWaveDetectionService.ElliottScoreAssessment assessment = v2DetectionService
                .scoreHistoricalStructure(candles, structure, ElliottSignalStage.CORRECTION_END, 87L);

        assertThat(assessment.score()).isBetween(0, 100);
        assertThat(assessment.reasons()).hasSize(7);
        assertThat(assessment.reasons()).extracting(reason -> reason.substring(0, reason.indexOf(" +")))
                .containsExactly(
                        "Structural / pivot quality",
                        "Fibonacci / proportion / alternation",
                        "Momentum / divergence",
                        "Stage-specific confirmation",
                        "Support / resistance / trend context",
                        "Volume confirmation",
                        "Timing / count stability");
    }

    @Test
    void keepsBearishOutsideCandleAsWaveCEndAndUsesNextCandleOnlyAsConfirmation() {
        List<EnrichedCandle> candles = bullishAbcCorrectionSeries();
        replaceCandle(candles, candle(86L, 140.0, 142.0, 128.0, 130.0));
        replaceCandle(candles, candle(87L, 130.0, 144.0, 129.0, 143.0));

        ElliottWaveDetectionService.ElliottWaveStructure structure = detectionService
                .findLatestWaveStructure(candles)
                .orElseThrow();

        assertThat(structure.correctionComplete()).isTrue();
        assertThat(structure.points().getLast().label()).isEqualTo("C");
        assertThat(structure.points().getLast().timestamp()).isEqualTo(86L);
        assertThat(structure.points().getLast().price()).isEqualTo(128.0);
        assertThat(structure.confirmationTimestamp()).isEqualTo(87L);
        assertThat(structure.qualityScore()).isGreaterThanOrEqualTo(68);
        assertThat(detectionService.detect(candles)).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_CORRECTION);
            assertThat(signal.candleTimestamp()).isEqualTo(87L);
        });
    }

    @Test
    void prefersProminentTrendOriginAndSuppressesOverlappingLateSubCount() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 120.0), anchor(10, 80.0),
                anchor(25, 110.0), anchor(35, 95.0),
                anchor(40, 110.0), anchor(44, 103.0), anchor(50, 128.0),
                anchor(52, 118.0), anchor(55, 140.0),
                anchor(70, 120.0), anchor(90, 155.0), anchor(91, 150.0)
        ));

        List<ElliottWaveDetectionService.ElliottWaveStructure> structures =
                detectionService.findHistoricalWaveStructures(candles);

        assertThat(structures).isNotEmpty();
        assertThat(structures).anySatisfy(structure ->
                assertThat(structure.points().getFirst().timestamp()).isEqualTo(10L));
        assertThat(structures).noneMatch(structure ->
                structure.points().getFirst().timestamp().equals(35L));
    }

    @Test
    void rejectsImpulseWhenWaveFourOverlapsWaveOneTerritory() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0), anchor(14, 121.0),
                anchor(24, 110.0), anchor(38, 143.0), anchor(54, 118.0),
                anchor(68, 150.0), anchor(69, 147.0)
        ));

        assertThat(detectionService.findLatestWaveStructure(candles)).isEmpty();
        assertThat(detectionService.detect(candles))
                .noneMatch(signal -> signal.pattern() == CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
    }

    @Test
    void rejectsImpulseWhenWaveThreeIsShortestActionaryWave() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0), anchor(14, 121.0),
                anchor(24, 110.0), anchor(38, 128.0), anchor(54, 122.0),
                anchor(68, 154.0), anchor(69, 150.0)
        ));

        assertThat(detectionService.findLatestWaveStructure(candles)).isEmpty();
        assertThat(detectionService.detect(candles))
                .noneMatch(signal -> signal.pattern() == CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
    }

    @Test
    void detectsSellAtTheCurrentEndOfBearishWaveC() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 138.0),
                anchor(6, 150.0),
                anchor(14, 129.0),
                anchor(24, 140.0),
                anchor(38, 107.0),
                anchor(54, 124.0),
                anchor(68, 100.0),
                anchor(74, 116.0),
                anchor(80, 106.0),
                anchor(86, 120.0),
                anchor(87, 117.0)
        ));

        assertThat(detectionService.detect(candles)).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BEARISH_CORRECTION);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.SELL);
            assertThat(signal.candleTimestamp()).isEqualTo(87L);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("wave C ended"));
        });
    }

    @Test
    void detectsSellAndDrawsImpulseAtTheEndOfBullishWaveV() {
        List<EnrichedCandle> candles = bullishWaveVEndSeries();

        assertThat(detectionService.detect(candles)).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.SELL);
            assertThat(signal.candleTimestamp()).isEqualTo(69L);
        });
        ElliottWaveDetectionService.ElliottWaveStructure structure = detectionService
                .findLatestWaveStructure(candles)
                .orElseThrow();
        assertThat(structure.correctionComplete()).isFalse();
        assertThat(structure.points()).extracting(ElliottWaveDetectionService.ElliottWavePoint::label)
                .containsExactly("", "I", "II", "III", "IV", "V");
    }

    @Test
    void detectsBuyAtTheEndOfBearishWaveV() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 138.0), anchor(6, 150.0), anchor(14, 129.0),
                anchor(24, 140.0), anchor(38, 107.0), anchor(54, 124.0),
                anchor(68, 100.0), anchor(69, 103.0)
        ));

        assertThat(detectionService.detect(candles)).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BEARISH_WAVE_V_END);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(signal.candleTimestamp()).isEqualTo(69L);
        });
    }

    @Test
    void keepsDeepWaveTwoBelowTheOriginAsReducedConfidenceStructure() {
        List<EnrichedCandle> candles = deepBearishWaveTwoSeries(69);

        ElliottWaveDetectionService.ElliottWaveStructure structure = detectionService
                .findLatestWaveStructure(candles)
                .orElseThrow();
        assertThat(structure.direction()).isEqualTo("BEARISH");
        assertThat(structure.correctionComplete()).isFalse();
        assertThat(structure.deepWaveTwo()).isTrue();
        assertThat(structure.waveTwoRetracement()).isBetween(0.95, 0.99);
        assertThat(structure.qualityScore()).isGreaterThanOrEqualTo(68);

        List<DetectedSignal> detectedCandidates = detectionService.detect(candles);
        assertThat(detectedCandidates).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BEARISH_WAVE_V_END);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(signal.confidenceScore()).isLessThan(75);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("very deep at 97.3%"));
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("confidence reduced by 18 points"));
        });
        assertThat(detectionService.detectAlertSignals(candles))
                .noneMatch(signal -> signal.pattern() == CandlePattern.ELLIOTT_BEARISH_WAVE_V_END);
    }

    @Test
    void keepsConfirmedDeepStructureOnChartAfterEmailWindowExpires() {
        List<EnrichedCandle> candles = deepBearishWaveTwoSeries(71);

        assertThat(detectionService.detect(candles))
                .noneMatch(signal -> signal.pattern() == CandlePattern.ELLIOTT_BEARISH_WAVE_V_END);
        assertThat(detectionService.findLatestWaveStructure(candles)).hasValueSatisfying(structure -> {
            assertThat(structure.direction()).isEqualTo("BEARISH");
            assertThat(structure.deepWaveTwo()).isTrue();
            assertThat(structure.confirmationTimestamp()).isEqualTo(69L);
        });
    }

    @Test
    void stillRejectsWaveTwoThatCrossesTheImpulseOrigin() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 520.0), anchor(6, 555.45), anchor(14, 492.37),
                anchor(24, 557.0), anchor(38, 356.28), anchor(54, 466.32),
                anchor(68, 349.20), anchor(69, 390.49)
        ));

        assertThat(detectionService.findLatestWaveStructure(candles)).isEmpty();
        assertThat(detectionService.detect(candles))
                .noneMatch(signal -> signal.pattern() == CandlePattern.ELLIOTT_BEARISH_WAVE_V_END);
    }

    @Test
    void acceptsDeepWaveFourWithoutWaveOneOverlapAndReducesConfidence() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0), anchor(14, 121.0),
                anchor(24, 110.0), anchor(38, 160.0), anchor(54, 125.0),
                anchor(68, 170.0), anchor(69, 166.0)
        ));

        assertThat(detectionService.findLatestWaveStructure(candles)).hasValueSatisfying(structure -> {
            assertThat(structure.waveFourRetracement()).isBetween(0.65, 0.80);
            assertThat(structure.qualityWarnings()).anyMatch(warning -> warning.contains("Deep Wave IV"));
        });
        assertThat(detectionService.detect(candles)).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_WAVE_V_END);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("wave 4 retracement is unusually deep"));
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("confidence reduced"));
        });
    }

    @Test
    void acceptsWaveThreeBelowOldEightyPercentGuidelineWhenItIsNotShortest() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0), anchor(14, 130.0),
                anchor(24, 115.0), anchor(38, 138.0), anchor(54, 133.0),
                anchor(68, 150.0), anchor(69, 146.0)
        ));

        assertThat(detectionService.findLatestWaveStructure(candles)).hasValueSatisfying(structure -> {
            assertThat(structure.waveThreeToOneRatio()).isBetween(0.70, 0.80);
            assertThat(structure.qualityWarnings()).anyMatch(warning -> warning.contains("Wave III is"));
        });
        assertThat(detectionService.detect(candles)).anySatisfy(signal ->
                assertThat(signal.reasons()).anyMatch(reason -> reason.contains("wave 3 is only")));
    }

    @Test
    void acceptsCompressedImpulseAndTreatsFifteenCandlesAsQualityGuideline() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(20, 100.0), anchor(22, 121.0),
                anchor(24, 110.0), anchor(26, 143.0), anchor(28, 126.0),
                anchor(32, 150.0), anchor(34, 147.0)
        ));

        assertThat(detectionService.findLatestWaveStructure(candles)).hasValueSatisfying(structure ->
                assertThat(structure.qualityWarnings())
                        .anyMatch(warning -> warning.contains("Compressed 12-candle impulse")));
        assertThat(detectionService.detect(candles)).anySatisfy(signal ->
                assertThat(signal.reasons()).anyMatch(reason -> reason.contains("fewer than 15 candles")));
    }

    @Test
    void acceptsDeepAbcCorrectionWithoutCrossingTheImpulseOriginAndPenalizesIt() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0), anchor(14, 121.0),
                anchor(24, 110.0), anchor(38, 143.0), anchor(54, 126.0),
                anchor(68, 150.0), anchor(74, 120.0), anchor(80, 140.0),
                anchor(86, 108.0), anchor(87, 111.0)
        ));

        assertThat(detectionService.findLatestWaveStructure(candles)).hasValueSatisfying(structure -> {
            assertThat(structure.correctionRetracement()).isBetween(0.80, 0.90);
            assertThat(structure.qualityWarnings()).anyMatch(warning -> warning.contains("A–B–C retracement"));
        });
        assertThat(detectionService.detect(candles)).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_CORRECTION);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("A-B-C correction is unusually deep"));
        });
    }

    @Test
    void acceptsAtypicalWaveCToWaveARatioAndPenalizesIt() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0), anchor(14, 121.0),
                anchor(24, 110.0), anchor(38, 143.0), anchor(54, 126.0),
                anchor(68, 150.0), anchor(74, 136.0), anchor(80, 144.0),
                anchor(86, 113.0), anchor(87, 116.0)
        ));

        assertThat(detectionService.findLatestWaveStructure(candles)).hasValueSatisfying(structure -> {
            assertThat(structure.waveCToARatio()).isGreaterThan(2.0);
            assertThat(structure.qualityWarnings()).anyMatch(warning -> warning.contains("× Wave A"));
        });
        assertThat(detectionService.detect(candles)).anySatisfy(signal ->
                assertThat(signal.reasons()).anyMatch(reason -> reason.contains("wave C is an atypical")));
    }

    @Test
    void classifiesExpandedFlatCorrectionExplicitly() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0), anchor(14, 121.0),
                anchor(24, 110.0), anchor(38, 143.0), anchor(54, 126.0),
                anchor(68, 150.0), anchor(74, 134.0), anchor(80, 155.0),
                anchor(86, 128.0), anchor(87, 131.0)
        ));

        assertThat(detectionService.detect(candles)).anySatisfy(signal ->
                assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_EXPANDED_FLAT_CORRECTION));
        assertThat(detectionService.findLatestWaveStructure(candles)).hasValueSatisfying(structure -> {
            assertThat(structure.correctionVariant())
                    .isEqualTo(ElliottWaveDetectionService.CorrectionVariant.EXPANDED_FLAT);
            assertThat(structure.qualityWarnings()).contains("Expanded-flat candidate — reduced confidence");
        });
    }

    @Test
    void classifiesRareRunningFlatCorrectionExplicitlyAndPenalizesIt() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0), anchor(14, 121.0),
                anchor(24, 110.0), anchor(38, 143.0), anchor(54, 126.0),
                anchor(68, 150.0), anchor(74, 134.0), anchor(80, 155.0),
                anchor(86, 138.0), anchor(87, 141.0)
        ));

        assertThat(detectionService.detect(candles)).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_RUNNING_FLAT_CORRECTION);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("running-flat geometry is rare"));
        });
        assertThat(detectionService.findLatestWaveStructure(candles)).hasValueSatisfying(structure ->
                assertThat(structure.correctionVariant())
                        .isEqualTo(ElliottWaveDetectionService.CorrectionVariant.RUNNING_FLAT));
    }

    @Test
    void classifiesTruncatedWaveFiveExplicitlyAndAppliesHeavyPenalty() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0), anchor(14, 121.0),
                anchor(24, 110.0), anchor(38, 150.0), anchor(54, 126.0),
                anchor(68, 145.0), anchor(69, 141.0)
        ));

        assertThat(detectionService.detect(candles)).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_TRUNCATED_WAVE_V_END);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("confidence reduced by 18 points"));
        });
        assertThat(detectionService.findLatestWaveStructure(candles)).hasValueSatisfying(structure -> {
            assertThat(structure.impulseVariant())
                    .isEqualTo(ElliottWaveDetectionService.ImpulseVariant.TRUNCATED_FIFTH);
            assertThat(structure.qualityWarnings()).contains("Truncated Wave V — reduced confidence");
        });
    }

    @Test
    void exposesOneValidatedFiveWaveDegreeInsideAMotiveParent() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 100.0),
                anchor(8, 121.0),
                anchor(16, 110.0),
                anchor(27, 145.0),
                anchor(35, 128.0),
                anchor(46, 152.0)
        ));

        ElliottWaveDetectionService.ElliottSubdivision subdivision = detectionService
                .findSubdivision(candles, "III", 100.0, 152.0)
                .orElseThrow();

        assertThat(subdivision.structureLabel()).contains("Motive");
        assertThat(subdivision.confidence()).isGreaterThanOrEqualTo(60);
        assertThat(subdivision.points())
                .extracting(ElliottWaveDetectionService.ElliottWavePoint::label)
                .containsExactly("", "i", "ii", "iii", "iv", "v");
    }

    @Test
    void strictSubdivisionAnchorsAtTheActualLowerTimeframePivots() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0),
                anchor(6, 100.0),
                anchor(14, 121.0),
                anchor(24, 110.0),
                anchor(38, 145.0),
                anchor(46, 128.0),
                anchor(58, 152.0)
        ));

        ElliottWaveDetectionService.ElliottSubdivision subdivision = detectionService
                .findStrictSubdivisions(candles, "III", 100.0, 152.0)
                .getFirst();

        assertThat(subdivision.points().getFirst().timestamp()).isEqualTo(6L);
        assertThat(subdivision.points().getLast().timestamp()).isEqualTo(58L);
        assertThat(subdivision.points().getFirst().price()).isEqualTo(candles.get(5).low());
        assertThat(subdivision.points().getLast().price()).isEqualTo(candles.getLast().high());
    }

    @Test
    void exposesAbcInsideACorrectiveParentWithoutForcingFiveWaves() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 150.0),
                anchor(10, 130.0),
                anchor(19, 142.0),
                anchor(30, 124.0)
        ));

        ElliottWaveDetectionService.ElliottSubdivision subdivision = detectionService
                .findSubdivision(candles, "IV", 150.0, 124.0)
                .orElseThrow();

        assertThat(subdivision.structureLabel()).isEqualTo("Corrective zigzag A-B-C");
        assertThat(subdivision.points())
                .extracting(ElliottWaveDetectionService.ElliottWavePoint::label)
                .containsExactly("", "a", "b", "c");
    }

    @Test
    void recognizesAContractingTriangleInsideWaveFour() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 150.0),
                anchor(8, 120.0),
                anchor(15, 140.0),
                anchor(22, 125.0),
                anchor(29, 136.0),
                anchor(36, 128.0)
        ));

        ElliottWaveDetectionService.ElliottSubdivision subdivision = detectionService
                .findSubdivision(candles, "IV", 150.0, 128.0)
                .orElseThrow();

        assertThat(subdivision.structureLabel()).contains("triangle");
        assertThat(subdivision.points())
                .extracting(ElliottWaveDetectionService.ElliottWavePoint::label)
                .containsExactly("", "a", "b", "c", "d", "e");
    }

    @Test
    void acceptsAContractingTriangleAsAStrictWaveFourCorrection() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 150.0),
                anchor(8, 120.0),
                anchor(15, 140.0),
                anchor(22, 125.0),
                anchor(29, 136.0),
                anchor(36, 128.0)
        ));

        assertThat(detectionService.findStrictSubdivisions(candles, "IV", 150.0, 128.0))
                .anySatisfy(subdivision -> {
                    assertThat(subdivision.structureLabel()).contains("triangle");
                    assertThat(subdivision.validated()).isTrue();
                    assertThat(subdivision.points())
                            .extracting(ElliottWaveDetectionService.ElliottWavePoint::label)
                            .containsExactly("", "a", "b", "c", "d", "e");
                });
    }

    @Test
    void doesNotLabelAStandaloneTriangleAsWaveTwo() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 150.0),
                anchor(8, 120.0),
                anchor(15, 140.0),
                anchor(22, 125.0),
                anchor(29, 136.0),
                anchor(36, 128.0)
        ));

        assertThat(detectionService.findStrictSubdivisions(candles, "II", 150.0, 128.0))
                .noneMatch(subdivision -> subdivision.structureLabel().contains("triangle"));
    }

    @Test
    void acceptsAMotiveDiagonalOnlyInLegalWaveOneOrFivePositions() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 100.0),
                anchor(8, 120.0),
                anchor(15, 108.0),
                anchor(22, 128.0),
                anchor(29, 116.0),
                anchor(36, 134.0)
        ));

        assertThat(detectionService.findStrictSubdivisions(candles, "I", 99.4, 134.6))
                .anyMatch(subdivision -> subdivision.structureLabel().contains("diagonal"));
        assertThat(detectionService.findStrictSubdivisions(candles, "V", 99.4, 134.6))
                .anyMatch(subdivision -> subdivision.structureLabel().contains("diagonal"));
        assertThat(detectionService.findStrictSubdivisions(candles, "III", 99.4, 134.6))
                .noneMatch(subdivision -> subdivision.structureLabel().contains("diagonal"));
    }

    @Test
    void distinguishesATextbookFlatFromAZigzag() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 150.0),
                anchor(10, 130.0),
                anchor(19, 148.0),
                anchor(30, 124.0)
        ));

        assertThat(detectionService.findStrictSubdivisions(candles, "IV", 150.6, 123.4))
                .anyMatch(subdivision -> subdivision.structureLabel().equals("Corrective flat A-B-C"));
    }

    @Test
    void recognizesASevenLegWxyComplexCorrection() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 150.0), anchor(8, 130.0), anchor(15, 142.0), anchor(22, 124.0),
                anchor(29, 138.0), anchor(36, 118.0), anchor(43, 132.0), anchor(50, 110.0)
        ));

        assertThat(detectionService.findStrictSubdivisions(candles, "IV", 150.0, 110.0))
                .anySatisfy(subdivision -> {
                    assertThat(subdivision.structureLabel()).isEqualTo("Double zigzag W-X-Y");
                    assertThat(subdivision.validated()).isTrue();
                    assertThat(subdivision.points()).hasSize(8);
                });
    }

    @Test
    void distinguishesADoubleThreeCombinationFromADoubleZigzag() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 150.0), anchor(8, 130.0), anchor(15, 142.0), anchor(22, 124.0),
                anchor(29, 138.0), anchor(36, 118.0), anchor(43, 136.0), anchor(50, 110.0)
        ));

        assertThat(detectionService.findStrictSubdivisions(candles, "IV", 150.6, 109.4))
                .anyMatch(subdivision -> subdivision.structureLabel()
                        .equals("Double-three combination W-X-Y"));
    }

    @Test
    void permitsWaveTwoCombinationToEndInATriangleWithoutAdmittingAStandaloneTriangle() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 150.0), anchor(8, 130.0), anchor(15, 142.0), anchor(22, 124.0),
                anchor(29, 138.0), anchor(36, 110.0), anchor(43, 132.0), anchor(50, 114.0),
                anchor(57, 128.0), anchor(64, 118.0)
        ));

        assertThat(detectionService.findStrictSubdivisions(candles, "II", 150.6, 117.4))
                .anySatisfy(subdivision -> {
                    assertThat(subdivision.structureLabel())
                            .isEqualTo("Double-three combination W-X-Y");
                    assertThat(subdivision.validated()).isTrue();
                    assertThat(subdivision.points()).hasSize(10);
                    assertThat(subdivision.evidence())
                            .anyMatch(item -> item.contains("ends with the only triangle"));
                });
    }

    @Test
    void invalidatesWaveTwoWhenAnInitialAbcExtendsIntoFiveSameDegreeWaves() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 90.0), anchor(8, 150.0),
                anchor(15, 130.0), anchor(22, 142.0), anchor(29, 110.0),
                anchor(36, 124.0), anchor(43, 100.0), anchor(44, 105.0)
        ));
        List<ElliottWaveDetectionService.ElliottWavePoint> parent = List.of(
                new ElliottWaveDetectionService.ElliottWavePoint("0", 1L, 90.0, "LOW"),
                new ElliottWaveDetectionService.ElliottWavePoint("I", 8L, 150.0, "HIGH"),
                new ElliottWaveDetectionService.ElliottWavePoint("II", 29L, 110.0, "LOW"));

        assertThat(detectionService.findActionaryStructureMismatch(
                candles, ElliottSignalStage.WAVE_II_END, parent))
                .hasValueSatisfying(invalidation ->
                        assertThat(invalidation.reason()).contains(
                                "Wave II extended into Motive", "instead of remaining a corrective structure"));
    }

    @Test
    void invalidatesADevelopingWaveThreeThatResolvesAsAbcInsteadOfFiveSubwaves() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 100.0), anchor(8, 120.0), anchor(15, 110.0),
                anchor(22, 130.0), anchor(29, 120.0), anchor(36, 140.0), anchor(37, 137.0)
        ));
        List<ElliottWaveDetectionService.ElliottWavePoint> parent = List.of(
                new ElliottWaveDetectionService.ElliottWavePoint("0", 1L, 100.0, "LOW"),
                new ElliottWaveDetectionService.ElliottWavePoint("I", 8L, 120.0, "HIGH"),
                new ElliottWaveDetectionService.ElliottWavePoint("II", 15L, 110.0, "LOW"));

        assertThat(detectionService.findActionaryStructureMismatch(
                candles, ElliottSignalStage.WAVE_II_END, parent))
                .hasValueSatisfying(invalidation ->
                        assertThat(invalidation.reason()).contains("Wave III", "Corrective zigzag A-B-C"));
    }

    @Test
    void detectsWaveTwoOnlyAfterNestedFiveAndCorrectiveStructuresAreConfirmed() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0),
                anchor(12, 112.0), anchor(18, 108.0), anchor(26, 130.0),
                anchor(32, 124.0), anchor(40, 140.0),
                anchor(48, 125.0), anchor(54, 134.0), anchor(62, 115.0), anchor(63, 119.0)
        ));

        assertThat(detectionService.findStrictSubdivisions(
                candles.subList(5, 40), "I", 99.4, 140.6)).isNotEmpty();
        assertThat(detectionService.findStrictSubdivisions(
                candles.subList(39, 62), "II", 140.6, 114.4)).isNotEmpty();

        assertThat(detectionService.findDevelopingImpulses(candles))
                .anySatisfy(candidate -> {
                    assertThat(candidate.stage()).isEqualTo(ElliottSignalStage.WAVE_II_END);
                    assertThat(candidate.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_WAVE_II_END);
                    assertThat(candidate.expectedMove()).isEqualTo(TradeSignal.BUY);
                    assertThat(candidate.evidence()).anyMatch(reason -> reason.contains("Wave I: Motive"));
                    assertThat(candidate.evidence()).anyMatch(reason -> reason.contains("Wave II: Corrective"));
                    assertThat(candidate.targetPrice()).isGreaterThan(candidate.confirmationClose());
                    assertThat(candidate.stopLossPrice()).isLessThan(100.0);
                });
    }

    @Test
    void emitsWaveFiveOnlyWhenAllFiveParentSubdivisionsValidate() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0),
                anchor(12, 112.0), anchor(18, 108.0), anchor(26, 130.0),
                anchor(32, 124.0), anchor(40, 140.0),
                anchor(48, 125.0), anchor(54, 134.0), anchor(62, 115.0),
                anchor(70, 145.0), anchor(76, 135.0), anchor(86, 180.0),
                anchor(92, 165.0), anchor(102, 200.0),
                anchor(110, 180.0), anchor(116, 192.0), anchor(124, 170.0),
                anchor(132, 195.0), anchor(138, 185.0), anchor(148, 220.0),
                anchor(154, 208.0), anchor(162, 230.0), anchor(163, 225.0)
        ));

        assertThat(detectionService.findDevelopingImpulses(candles))
                .anySatisfy(candidate -> {
                    assertThat(candidate.stage()).isEqualTo(ElliottSignalStage.WAVE_V_END);
                    assertThat(candidate.points())
                            .extracting(ElliottWaveDetectionService.ElliottWavePoint::label)
                            .containsExactly("0", "I", "II", "III", "IV", "V");
                    assertThat(candidate.evidence()).anyMatch(reason -> reason.contains("Wave I: Motive"));
                    assertThat(candidate.evidence()).anyMatch(reason -> reason.contains("Wave II: Corrective"));
                    assertThat(candidate.evidence()).anyMatch(reason -> reason.contains("Wave III: Motive"));
                    assertThat(candidate.evidence()).anyMatch(reason -> reason.contains("Wave IV: Corrective"));
                    assertThat(candidate.evidence()).anyMatch(reason -> reason.contains("Wave V: Motive"));
                    assertThat(candidate.completedStructure()).isNotNull();
                });
    }

    @Test
    void extendsAValidatedWaveFiveCycleIntoASameCycleZigzagCorrection() {
        List<EnrichedCandle> candles = completeNestedImpulseWithNestedZigzagCorrection();

        assertThat(detectionService.findDevelopingImpulseHypotheses(candles))
                .anySatisfy(candidate -> {
                    assertThat(candidate.stage()).isEqualTo(ElliottSignalStage.CORRECTION_END);
                    assertThat(candidate.pattern()).isEqualTo(CandlePattern.ELLIOTT_BULLISH_CORRECTION);
                    assertThat(candidate.expectedMove()).isEqualTo(TradeSignal.BUY);
                    assertThat(candidate.correctionType()).isEqualTo("Zigzag 5-3-5");
                    assertThat(candidate.points())
                            .extracting(ElliottWaveDetectionService.ElliottWavePoint::label)
                            .containsExactly("0", "I", "II", "III", "IV", "V", "A", "B", "C");
                    assertThat(candidate.evidence()).anyMatch(reason -> reason.contains("Wave A: Motive"));
                    assertThat(candidate.evidence()).anyMatch(reason -> reason.contains("Wave B: Corrective"));
                    assertThat(candidate.evidence()).anyMatch(reason -> reason.contains("Wave C: Motive"));
                    assertThat(candidate.completedStructure()).isNotNull();
                    assertThat(candidate.completedStructure().correctionComplete()).isTrue();
                    assertThat(candidate.targetPrice()).isGreaterThan(candidate.confirmationClose());
                    assertThat(candidate.stopLossPrice()).isLessThan(candidate.endpointPrice());
                });
    }

    @Test
    void extendsAValidatedWaveFiveCycleIntoASameCycleFlatCorrection() {
        List<EnrichedCandle> candles = completeNestedImpulseWithNestedFlatCorrection();

        assertThat(detectionService.findDevelopingImpulseHypotheses(candles))
                .anySatisfy(candidate -> {
                    assertThat(candidate.stage()).isEqualTo(ElliottSignalStage.CORRECTION_END);
                    assertThat(candidate.correctionType()).isEqualTo("Flat 3-3-5");
                    assertThat(candidate.evidence()).anyMatch(reason ->
                            reason.contains("Wave A: Corrective"));
                    assertThat(candidate.evidence()).anyMatch(reason ->
                            reason.contains("Wave B: Corrective"));
                    assertThat(candidate.evidence()).anyMatch(reason ->
                            reason.contains("Wave C: Motive"));
                });
    }

    @Test
    void permitsAContractingTriangleInsideWaveBButStillRequiresMotiveWaveC() {
        List<EnrichedCandle> candles = completeNestedImpulseWithTriangularWaveB();

        assertThat(detectionService.findDevelopingImpulseHypotheses(candles))
                .anySatisfy(candidate -> {
                    assertThat(candidate.stage()).isEqualTo(ElliottSignalStage.CORRECTION_END);
                    assertThat(candidate.correctionType()).isEqualTo(
                            "Zigzag 5-3-5 with triangular Wave B (3-3-3-3-3)");
                    assertThat(candidate.evidence()).anyMatch(reason ->
                            reason.contains("Wave B: Contracting triangle A-B-C-D-E"));
                    assertThat(candidate.evidence()).anyMatch(reason -> reason.contains("Wave C: Motive"));
                });
    }

    @Test
    void exposesADashedProvisionalCountWhenStrictRulesCannotValidateTheParent() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 100.0),
                anchor(30, 140.0)
        ));

        assertThat(detectionService.findSubdivision(candles, "III", 100.0, 140.0))
                .hasValueSatisfying(subdivision -> {
                    assertThat(subdivision.validated()).isFalse();
                    assertThat(subdivision.structureLabel()).contains("Provisional motive");
                    assertThat(subdivision.points())
                            .extracting(ElliottWaveDetectionService.ElliottWavePoint::label)
                            .containsExactly("", "i", "ii", "iii", "iv", "v");
                });
    }

    @Test
    void returnsNoSignalsWhenHistoryIsTooShortForWaveStructure() {
        List<EnrichedCandle> candles = syntheticSeries(List.of(
                anchor(1, 100.0),
                anchor(10, 110.0),
                anchor(20, 105.0),
                anchor(30, 112.0)
        ));

        assertThat(detectionService.detect(candles)).isEmpty();
    }

    private List<EnrichedCandle> syntheticSeries(List<Anchor> anchors) {
        List<Anchor> sortedAnchors = anchors.stream()
                .sorted(Comparator.comparingInt(Anchor::index))
                .toList();
        List<EnrichedCandle> candles = new ArrayList<>();
        for (int anchorIndex = 0; anchorIndex < sortedAnchors.size() - 1; anchorIndex++) {
            Anchor start = sortedAnchors.get(anchorIndex);
            Anchor end = sortedAnchors.get(anchorIndex + 1);
            int from = anchorIndex == 0 ? start.index() : start.index() + 1;
            for (int index = from; index <= end.index(); index++) {
                double progress = (index - start.index()) / (double) (end.index() - start.index());
                double close = start.price() + (end.price() - start.price()) * progress;
                candles.add(candle(index, close));
            }
        }
        return candles;
    }

    private List<EnrichedCandle> bullishAbcCorrectionSeries() {
        return syntheticSeries(List.of(
                anchor(1, 112.0),
                anchor(6, 100.0),
                anchor(14, 121.0),
                anchor(24, 110.0),
                anchor(38, 143.0),
                anchor(54, 126.0),
                anchor(68, 150.0),
                anchor(74, 134.0),
                anchor(80, 144.0),
                anchor(86, 130.0),
                anchor(87, 133.0)
        ));
    }

    private List<EnrichedCandle> bullishWaveVEndSeries() {
        return syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0), anchor(14, 121.0),
                anchor(24, 110.0), anchor(38, 143.0), anchor(54, 126.0),
                anchor(68, 150.0), anchor(69, 147.0)
        ));
    }

    private List<EnrichedCandle> completeNestedImpulseWithDoubleZigzagCorrection() {
        return syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0),
                anchor(12, 112.0), anchor(18, 108.0), anchor(26, 130.0),
                anchor(32, 124.0), anchor(40, 140.0),
                anchor(48, 125.0), anchor(54, 134.0), anchor(62, 115.0),
                anchor(70, 145.0), anchor(76, 135.0), anchor(86, 180.0),
                anchor(92, 165.0), anchor(102, 200.0),
                anchor(110, 180.0), anchor(116, 192.0), anchor(124, 170.0),
                anchor(132, 195.0), anchor(138, 185.0), anchor(148, 220.0),
                anchor(154, 208.0), anchor(162, 230.0), anchor(163, 225.0),
                anchor(170, 180.0), anchor(178, 195.0), anchor(186, 165.0),
                anchor(194, 190.0), anchor(202, 155.0), anchor(210, 175.0),
                anchor(218, 140.0), anchor(219, 145.0)
        ));
    }

    private List<EnrichedCandle> completeNestedImpulseWithNestedZigzagCorrection() {
        return syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0),
                anchor(12, 112.0), anchor(18, 108.0), anchor(26, 130.0),
                anchor(32, 124.0), anchor(40, 140.0),
                anchor(48, 125.0), anchor(54, 134.0), anchor(62, 115.0),
                anchor(70, 145.0), anchor(76, 135.0), anchor(86, 180.0),
                anchor(92, 165.0), anchor(102, 200.0),
                anchor(110, 180.0), anchor(116, 192.0), anchor(124, 170.0),
                anchor(132, 195.0), anchor(138, 185.0), anchor(148, 220.0),
                anchor(154, 208.0), anchor(162, 230.0),
                anchor(170, 210.0), anchor(178, 218.0), anchor(186, 190.0),
                anchor(194, 205.0), anchor(202, 175.0),
                anchor(210, 190.0), anchor(218, 180.0), anchor(226, 198.0),
                anchor(234, 170.0), anchor(242, 185.0), anchor(250, 150.0),
                anchor(258, 165.0), anchor(266, 130.0), anchor(267, 135.0)
        ));
    }

    private List<EnrichedCandle> completeNestedImpulseWithNestedFlatCorrection() {
        return syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0),
                anchor(12, 112.0), anchor(18, 108.0), anchor(26, 130.0),
                anchor(32, 124.0), anchor(40, 140.0),
                anchor(48, 125.0), anchor(54, 134.0), anchor(62, 115.0),
                anchor(70, 145.0), anchor(76, 135.0), anchor(86, 180.0),
                anchor(92, 165.0), anchor(102, 200.0),
                anchor(110, 180.0), anchor(116, 192.0), anchor(124, 170.0),
                anchor(132, 195.0), anchor(138, 185.0), anchor(148, 220.0),
                anchor(154, 208.0), anchor(162, 230.0),
                anchor(170, 195.0), anchor(178, 215.0), anchor(186, 180.0),
                anchor(194, 210.0), anchor(202, 190.0), anchor(210, 228.0),
                anchor(218, 190.0), anchor(226, 205.0), anchor(234, 165.0),
                anchor(242, 180.0), anchor(250, 140.0), anchor(251, 145.0)
        ));
    }

    private List<EnrichedCandle> completeNestedImpulseWithTriangularWaveB() {
        return syntheticSeries(List.of(
                anchor(1, 112.0), anchor(6, 100.0),
                anchor(12, 112.0), anchor(18, 108.0), anchor(26, 130.0),
                anchor(32, 124.0), anchor(40, 140.0),
                anchor(48, 125.0), anchor(54, 134.0), anchor(62, 115.0),
                anchor(70, 145.0), anchor(76, 135.0), anchor(86, 180.0),
                anchor(92, 165.0), anchor(102, 200.0),
                anchor(110, 180.0), anchor(116, 192.0), anchor(124, 170.0),
                anchor(132, 195.0), anchor(138, 185.0), anchor(148, 220.0),
                anchor(154, 208.0), anchor(162, 230.0),
                anchor(170, 210.0), anchor(178, 218.0), anchor(186, 190.0),
                anchor(194, 205.0), anchor(202, 175.0),
                anchor(210, 205.0), anchor(218, 182.0), anchor(226, 200.0),
                anchor(234, 187.0), anchor(242, 195.0),
                anchor(250, 170.0), anchor(258, 185.0), anchor(266, 150.0),
                anchor(274, 165.0), anchor(282, 130.0), anchor(283, 135.0)
        ));
    }

    private List<EnrichedCandle> completeNestedImpulseWithTriangleEndingCorrection() {
        List<Anchor> anchors = new ArrayList<>(List.of(
                anchor(1, 112.0), anchor(6, 100.0),
                anchor(12, 112.0), anchor(18, 108.0), anchor(26, 130.0),
                anchor(32, 124.0), anchor(40, 140.0),
                anchor(48, 125.0), anchor(54, 134.0), anchor(62, 115.0),
                anchor(70, 145.0), anchor(76, 135.0), anchor(86, 180.0),
                anchor(92, 165.0), anchor(102, 200.0),
                anchor(110, 180.0), anchor(116, 192.0), anchor(124, 170.0),
                anchor(132, 195.0), anchor(138, 185.0), anchor(148, 220.0),
                anchor(154, 208.0), anchor(162, 230.0), anchor(163, 225.0),
                anchor(170, 180.0), anchor(178, 195.0), anchor(186, 165.0),
                anchor(194, 190.0), anchor(202, 145.0), anchor(210, 180.0),
                anchor(218, 150.0), anchor(226, 170.0), anchor(234, 155.0),
                anchor(235, 160.0)
        ));
        return syntheticSeries(anchors);
    }

    private List<EnrichedCandle> deepBearishWaveTwoSeries(int lastIndex) {
        List<Anchor> anchors = new ArrayList<>(List.of(
                anchor(1, 520.0), anchor(6, 555.45), anchor(14, 492.37),
                anchor(24, 553.72), anchor(38, 356.28), anchor(54, 466.32),
                anchor(68, 349.20), anchor(69, 390.49)
        ));
        if (lastIndex > 69) {
            anchors.add(anchor(lastIndex, 395.0));
        }
        return syntheticSeries(anchors);
    }

    private EnrichedCandle candle(long timestamp, double close) {
        return candle(timestamp, close - 0.4, close + 0.6, close - 0.6, close);
    }

    private EnrichedCandle candle(long timestamp,
                                  double open,
                                  double high,
                                  double low,
                                  double close) {
        return new EnrichedCandle(
                timestamp,
                open,
                high,
                low,
                close,
                1_500.0,
                1_000.0,
                58.0,
                close - 2.0,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                4.0
        );
    }

    private void replaceCandle(List<EnrichedCandle> candles, EnrichedCandle replacement) {
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).timestamp().equals(replacement.timestamp())) {
                candles.set(index, replacement);
                return;
            }
        }
        throw new IllegalArgumentException("Missing candle " + replacement.timestamp());
    }

    private Anchor anchor(int index, double price) {
        return new Anchor(index, price);
    }

    private record Anchor(int index, double price) {
    }
}
