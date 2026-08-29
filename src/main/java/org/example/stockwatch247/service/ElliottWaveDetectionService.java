package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ElliottWaveDetectionService {
    public static final String SETUP_SCORE_VERSION = "ELLIOTT_V1";
    private static final int MIN_CANDLES = 34;
    private static final int MIN_CONFIDENCE = 75;
    private static final int HIGH_CONFIDENCE = 85;
    private static final double BREAKOUT_BUFFER = 1.003;
    private static final int DEFAULT_PRESENT_SIGNAL_LOOKBACK_CANDLES = 1;
    private static final int MAX_CONFIRMATION_LAG_CANDLES = 3;
    private static final int MIN_IMPULSE_SPAN_CANDLES = 15;
    private static final int MIN_LEG_SPAN_CANDLES = 2;
    private static final int MIN_STRUCTURE_QUALITY = 68;
    private static final double NORMAL_WAVE_TWO_MIN_RETRACEMENT = 0.236;
    private static final double COMMON_WAVE_TWO_MIN_RETRACEMENT = 0.382;
    private static final double COMMON_WAVE_TWO_MAX_RETRACEMENT = 0.618;
    private static final double NORMAL_WAVE_TWO_MAX_RETRACEMENT = 0.786;
    private static final double MAX_WAVE_TWO_RETRACEMENT = 1.0;
    private static final double PRELIMINARY_WAVE_THREE_MIN_RATIO = 0.8;
    private static final double NORMAL_WAVE_FOUR_MIN_RETRACEMENT = 0.146;
    private static final double COMMON_WAVE_FOUR_MIN_RETRACEMENT = 0.236;
    private static final double COMMON_WAVE_FOUR_MAX_RETRACEMENT = 0.5;
    private static final double NORMAL_WAVE_FOUR_MAX_RETRACEMENT = 0.618;
    private static final double MAX_WAVE_FOUR_RETRACEMENT = 1.0;
    private static final double NORMAL_CORRECTION_MIN_RETRACEMENT = 0.236;
    private static final double COMMON_CORRECTION_MIN_RETRACEMENT = 0.382;
    private static final double COMMON_CORRECTION_MAX_RETRACEMENT = 0.618;
    private static final double NORMAL_CORRECTION_MAX_RETRACEMENT = 0.786;
    private static final double MAX_CONTINUATION_CORRECTION_RETRACEMENT = 1.0;
    private static final double NORMAL_WAVE_C_TO_A_MIN_RATIO = 0.5;
    private static final double COMMON_WAVE_C_TO_A_MIN_RATIO = 0.8;
    private static final double COMMON_WAVE_C_TO_A_MAX_RATIO = 1.25;
    private static final double NORMAL_WAVE_C_TO_A_MAX_RATIO = 2.0;
    private static final double MAX_WAVE_B_RELATIVE_RECOVERY = 2.0;
    private static final int TRUNCATED_WAVE_FIVE_PENALTY = 18;
    private static final int EXPANDED_FLAT_PENALTY = 4;
    private static final int RUNNING_FLAT_PENALTY = 14;
    private static final double[] PIVOT_SENSITIVITIES = {0.75, 1.25, 2.0, 3.0};
    private final int presentSignalLookbackCandles;
    private final ScoringModel scoringModel;
    private final DetectionRules detectionRules;

    public ElliottWaveDetectionService() {
        this(DEFAULT_PRESENT_SIGNAL_LOOKBACK_CANDLES, ScoringModel.V1, DetectionRules.factory());
    }

    ElliottWaveDetectionService(int presentSignalLookbackCandles) {
        this(presentSignalLookbackCandles, ScoringModel.V1, DetectionRules.factory());
    }

    ElliottWaveDetectionService(int presentSignalLookbackCandles, ScoringModel scoringModel) {
        this(presentSignalLookbackCandles, scoringModel, DetectionRules.factory());
    }

    private ElliottWaveDetectionService(int presentSignalLookbackCandles,
                                        ScoringModel scoringModel,
                                        DetectionRules detectionRules) {
        this.presentSignalLookbackCandles = Math.max(0, presentSignalLookbackCandles);
        this.scoringModel = scoringModel == null ? ScoringModel.V1 : scoringModel;
        this.detectionRules = detectionRules == null ? DetectionRules.factory() : detectionRules;
    }

    public ElliottWaveDetectionService configured(DetectionRules rules) {
        return new ElliottWaveDetectionService(presentSignalLookbackCandles, scoringModel, rules);
    }

    public List<DetectedSignal> detect(List<EnrichedCandle> recentCandles) {
        if (recentCandles == null || recentCandles.size() < detectionRules.minimumCandles()) {
            return List.of();
        }

        List<EnrichedCandle> candles = recentCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        if (candles.size() < detectionRules.minimumCandles()) {
            return List.of();
        }

        EnrichedCandle current = candles.get(candles.size() - 1);
        Map<String, DetectedSignal> bestSignals = new LinkedHashMap<>();
        for (List<Pivot> pivots : findPivotSets(candles)) {
            if (pivots.size() < 5) {
                continue;
            }
            detectBullishImpulse(candles, pivots, current).ifPresent(signal -> mergeSignal(bestSignals, signal));
            detectBearishImpulse(candles, pivots, current).ifPresent(signal -> mergeSignal(bestSignals, signal));
            detectBullishWaveVEnd(candles, pivots, current).ifPresent(signal -> mergeSignal(bestSignals, signal));
            detectBearishWaveVEnd(candles, pivots, current).ifPresent(signal -> mergeSignal(bestSignals, signal));
            detectBullishCorrection(candles, pivots, current).ifPresent(signal -> mergeSignal(bestSignals, signal));
            detectBearishCorrection(candles, pivots, current).ifPresent(signal -> mergeSignal(bestSignals, signal));
        }
        return List.copyOf(bestSignals.values());
    }

    public List<DetectedSignal> detectAlertSignals(List<EnrichedCandle> recentCandles) {
        return detect(recentCandles).stream()
                .filter(signal -> signal.eligibilityScore() >= detectionRules.minimumSignalConfidence())
                .toList();
    }

    private void mergeSignal(Map<String, DetectedSignal> bestSignals, DetectedSignal candidate) {
        String patternName = candidate.pattern().name();
        String signalType = patternName.endsWith("WAVE_V_END")
                ? "ELLIOTT_WAVE_V_END"
                : patternName.endsWith("CORRECTION") ? "ELLIOTT_CORRECTION" : patternName;
        String key = signalType + ':' + candidate.tradeSignal().name();
        DetectedSignal existing = bestSignals.get(key);
        if (existing == null || candidate.eligibilityScore() > existing.eligibilityScore()) {
            bestSignals.put(key, candidate);
        }
    }

    public java.util.Optional<ElliottWaveStructure> findLatestWaveStructure(List<EnrichedCandle> recentCandles) {
        if (recentCandles == null || recentCandles.size() < detectionRules.minimumCandles()) {
            return java.util.Optional.empty();
        }
        List<EnrichedCandle> candles = recentCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        if (candles.size() < detectionRules.minimumCandles()) {
            return java.util.Optional.empty();
        }
        List<StructureCandidate> structures = new ArrayList<>();
        for (List<Pivot> pivots : findPivotSets(candles)) {
            addConfirmedStructures(candles, pivots, structures);
            addProvisionalBullishWaveVStructure(candles, pivots, structures);
            addProvisionalBearishWaveVStructure(candles, pivots, structures);
            addProvisionalBullishStructure(candles, pivots, structures);
            addProvisionalBearishStructure(candles, pivots, structures);
        }
        return structures.stream()
                .filter(candidate -> candidate.structure().qualityScore() >= detectionRules.minimumStructureQuality())
                .max(Comparator.comparingInt(StructureCandidate::completionIndex)
                        .thenComparingInt(candidate -> candidate.structure().qualityScore())
                        .thenComparingLong(candidate -> structureSpan(candidate.structure())))
                .map(StructureCandidate::structure);
    }

    public List<ElliottWaveStructure> findHistoricalWaveStructures(List<EnrichedCandle> historicalCandles) {
        return selectNonOverlappingStructures(collectHistoricalWaveStructures(historicalCandles));
    }

    /**
     * Returns the lifecycle identity shared by the motive and corrective stages
     * of one Elliott cycle. It intentionally excludes the revisable V/C endpoint.
     */
    public java.util.Optional<String> lifecycleCycleKey(ElliottWaveStructure structure) {
        return ElliottWaveSignalLifecyclePolicy.cycleKey(structure);
    }

    public ElliottScoreAssessment scoreHistoricalStructure(
            List<EnrichedCandle> historicalCandles,
            ElliottWaveStructure structure,
            ElliottSignalStage stage,
            Long confirmationTimestamp) {
        if (historicalCandles == null || structure == null || stage == null
                || confirmationTimestamp == null) {
            return new ElliottScoreAssessment(0, List.of());
        }
        List<EnrichedCandle> candles = historicalCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        int confirmationIndex = indexAtTimestamp(candles, confirmationTimestamp);
        if (confirmationIndex < 1) {
            return new ElliottScoreAssessment(0, List.of());
        }
        List<EnrichedCandle> detectionCandles = candles.subList(0, confirmationIndex + 1);
        boolean correctionEnd = stage == ElliottSignalStage.CORRECTION_END;
        CandlePattern pattern = historicalPattern(structure, correctionEnd);
        int productionWindowStart = Math.max(0, detectionCandles.size() - 100);
        java.util.Optional<DetectedSignal> reconstructedSignal = detect(
                detectionCandles.subList(productionWindowStart, detectionCandles.size())).stream()
                .filter(signal -> signal.pattern() == pattern)
                .filter(signal -> signal.candleTimestamp().equals(confirmationTimestamp))
                .findFirst();
        if (reconstructedSignal.isPresent()) {
            DetectedSignal signal = reconstructedSignal.orElseThrow();
            return new ElliottScoreAssessment(
                    signal.confidenceScore(),
                    signal.reasons().stream()
                            .filter(reason -> !reason.startsWith("V1 detection eligibility:"))
                            .toList());
        }
        List<Pivot> pivots = new ArrayList<>();
        for (ElliottWavePoint point : structure.points()) {
            int index = indexAtTimestamp(detectionCandles, point.timestamp());
            if (index < 0) {
                return new ElliottScoreAssessment(0, List.of());
            }
            pivots.add(new Pivot(index, "HIGH".equalsIgnoreCase(point.pivotType())
                    ? PivotType.HIGH : PivotType.LOW, point.price()));
        }
        if (pivots.size() < (correctionEnd ? 9 : 6)) {
            return new ElliottScoreAssessment(0, List.of());
        }
        TradeSignal tradeSignal = correctionEnd
                ? ("BULLISH".equals(structure.direction()) ? TradeSignal.BUY : TradeSignal.SELL)
                : ("BULLISH".equals(structure.direction()) ? TradeSignal.SELL : TradeSignal.BUY);
        CorrectionMetrics correction = correctionEnd
                ? new CorrectionMetrics(structure.correctionRetracement(), structure.waveCToARatio(),
                structure.correctionVariant())
                : null;
        V2Score score = v2Score(pattern, tradeSignal, detectionCandles, pivots, correction,
                structure.qualityScore());
        return new ElliottScoreAssessment(score.score(), score.reasons());
    }

    private int indexAtTimestamp(List<EnrichedCandle> candles, Long timestamp) {
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).timestamp().equals(timestamp)) {
                return index;
            }
        }
        return -1;
    }

    private CandlePattern historicalPattern(ElliottWaveStructure structure, boolean correctionEnd) {
        boolean bullish = "BULLISH".equals(structure.direction());
        if (correctionEnd) {
            return bullish ? bullishCorrectionPattern(structure.correctionVariant())
                    : bearishCorrectionPattern(structure.correctionVariant());
        }
        if (structure.impulseVariant() == ImpulseVariant.TRUNCATED_FIFTH) {
            return bullish ? CandlePattern.ELLIOTT_BULLISH_TRUNCATED_WAVE_V_END
                    : CandlePattern.ELLIOTT_BEARISH_TRUNCATED_WAVE_V_END;
        }
        return bullish ? CandlePattern.ELLIOTT_BULLISH_WAVE_V_END
                : CandlePattern.ELLIOTT_BEARISH_WAVE_V_END;
    }

    public java.util.Optional<ElliottWaveStructure> findLatestStructureForCycle(
            List<EnrichedCandle> historicalCandles,
            String cycleKey,
            ElliottSignalStage stage) {
        if (cycleKey == null || cycleKey.isBlank() || stage == null) {
            return java.util.Optional.empty();
        }
        return collectHistoricalWaveStructures(historicalCandles).stream()
                .filter(structure -> ElliottWaveSignalLifecyclePolicy.cycleKey(structure)
                        .filter(cycleKey::equals)
                        .isPresent())
                .filter(structure -> structure.correctionComplete()
                        == (stage == ElliottSignalStage.CORRECTION_END))
                .max(Comparator.comparingLong(this::terminalPointTimestamp)
                        .thenComparingLong(structure -> structure.confirmationTimestamp() == null
                                ? Long.MIN_VALUE
                                : structure.confirmationTimestamp())
                        .thenComparingInt(ElliottWaveStructure::qualityScore));
    }

    private List<ElliottWaveStructure> collectHistoricalWaveStructures(
            List<EnrichedCandle> historicalCandles) {
        if (historicalCandles == null || historicalCandles.size() < detectionRules.minimumCandles()) {
            return List.of();
        }
        List<EnrichedCandle> candles = historicalCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        if (candles.size() < detectionRules.minimumCandles()) {
            return List.of();
        }
        Map<String, ElliottWaveStructure> structuresByCycle = new LinkedHashMap<>();
        for (List<Pivot> pivots : findPivotSets(candles)) {
            List<StructureCandidate> candidates = new ArrayList<>();
            addConfirmedStructures(candles, pivots, candidates);
            addProvisionalBullishWaveVStructure(candles, pivots, candidates);
            addProvisionalBearishWaveVStructure(candles, pivots, candidates);
            addProvisionalBullishStructure(candles, pivots, candidates);
            addProvisionalBearishStructure(candles, pivots, candidates);
            candidates.stream()
                    .map(StructureCandidate::structure)
                    .filter(structure -> structure.qualityScore() >= detectionRules.minimumStructureQuality())
                    .forEach(structure -> mergeHistoricalStructure(structuresByCycle, structure));
        }
        return new ArrayList<>(structuresByCycle.values());
    }

    private long terminalPointTimestamp(ElliottWaveStructure structure) {
        if (structure == null || structure.points() == null || structure.points().isEmpty()
                || structure.points().getLast().timestamp() == null) {
            return Long.MIN_VALUE;
        }
        return structure.points().getLast().timestamp();
    }

    public java.util.Optional<ElliottWaveStructure> findStructureForSignal(
            List<EnrichedCandle> historicalCandles,
            CandlePattern pattern,
            Long signalTimestamp) {
        if (historicalCandles == null || pattern == null || signalTimestamp == null
                || !pattern.name().startsWith("ELLIOTT_")) {
            return java.util.Optional.empty();
        }
        List<EnrichedCandle> detectionHistory = historicalCandles.stream()
                .filter(this::hasCompleteData)
                .filter(candle -> candle.timestamp() <= signalTimestamp)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        return findHistoricalWaveStructures(detectionHistory).stream()
                .filter(structure -> matchesRecordedPattern(pattern, structure))
                .filter(structure -> structure.confirmationTimestamp() != null
                        && structure.confirmationTimestamp() <= signalTimestamp)
                .max(Comparator.comparingLong(ElliottWaveStructure::confirmationTimestamp)
                        .thenComparingInt(ElliottWaveStructure::qualityScore)
                        .thenComparingLong(structure -> structureSpan(structure)));
    }

    private boolean matchesRecordedPattern(CandlePattern pattern, ElliottWaveStructure structure) {
        String patternName = pattern.name();
        String expectedDirection = patternName.contains("BEARISH") ? "BEARISH" : "BULLISH";
        boolean expectedCorrection = patternName.endsWith("CORRECTION");
        if (!expectedDirection.equals(structure.direction())
                || expectedCorrection != structure.correctionComplete()) {
            return false;
        }
        if (patternName.contains("TRUNCATED")) {
            return structure.impulseVariant() == ImpulseVariant.TRUNCATED_FIFTH;
        }
        if (patternName.contains("EXPANDED_FLAT")) {
            return structure.correctionVariant() == CorrectionVariant.EXPANDED_FLAT;
        }
        if (patternName.contains("RUNNING_FLAT")) {
            return structure.correctionVariant() == CorrectionVariant.RUNNING_FLAT;
        }
        if (expectedCorrection) {
            return structure.correctionVariant() == CorrectionVariant.STANDARD;
        }
        return structure.impulseVariant() == ImpulseVariant.STANDARD;
    }

    private void mergeHistoricalStructure(Map<String, ElliottWaveStructure> structuresByCycle,
                                          ElliottWaveStructure structure) {
        ElliottWavePoint endpoint = structure.points().getLast();
        String key = structure.direction() + ':' + endpoint.label() + ':' + endpoint.timestamp();
        ElliottWaveStructure existing = structuresByCycle.get(key);
        if (existing == null
                || structure.qualityScore() > existing.qualityScore()
                || structure.qualityScore() == existing.qualityScore()
                && structureSpan(structure) > structureSpan(existing)) {
            structuresByCycle.put(key, structure);
        }
    }

    private void addConfirmedStructures(List<EnrichedCandle> candles,
                                        List<Pivot> pivots,
                                        List<StructureCandidate> structures) {
        for (int start = 0; start + 8 < pivots.size(); start++) {
            List<Pivot> sequence = List.copyOf(pivots.subList(start, start + 9));
            if (matchesTypes(sequence, PivotType.LOW, PivotType.HIGH, PivotType.LOW, PivotType.HIGH,
                    PivotType.LOW, PivotType.HIGH, PivotType.LOW, PivotType.HIGH, PivotType.LOW)
                    && isBullishCorrectionComplete(sequence)) {
                addConfirmedStructure(candles, sequence, "BULLISH", true,
                        firstBullishReboundIndex(candles, sequence.get(8)), structures);
            }
            if (matchesTypes(sequence, PivotType.HIGH, PivotType.LOW, PivotType.HIGH, PivotType.LOW,
                    PivotType.HIGH, PivotType.LOW, PivotType.HIGH, PivotType.LOW, PivotType.HIGH)
                    && isBearishCorrectionComplete(sequence)) {
                addConfirmedStructure(candles, sequence, "BEARISH", true,
                        firstBearishRejectionIndex(candles, sequence.get(8)), structures);
            }
        }
        for (int start = 0; start + 5 < pivots.size(); start++) {
            List<Pivot> sequence = List.copyOf(pivots.subList(start, start + 6));
            if (matchesTypes(sequence, PivotType.LOW, PivotType.HIGH, PivotType.LOW,
                    PivotType.HIGH, PivotType.LOW, PivotType.HIGH)
                    && isBullishImpulseComplete(sequence.get(0), sequence.get(1), sequence.get(2),
                    sequence.get(3), sequence.get(4), sequence.get(5))) {
                addConfirmedStructure(candles, sequence, "BULLISH", false,
                        firstBearishRejectionIndex(candles, sequence.get(5)), structures);
            }
            if (matchesTypes(sequence, PivotType.HIGH, PivotType.LOW, PivotType.HIGH,
                    PivotType.LOW, PivotType.HIGH, PivotType.LOW)
                    && isBearishImpulseComplete(sequence.get(0), sequence.get(1), sequence.get(2),
                    sequence.get(3), sequence.get(4), sequence.get(5))) {
                addConfirmedStructure(candles, sequence, "BEARISH", false,
                        firstBullishReboundIndex(candles, sequence.get(5)), structures);
            }
        }
    }

    private void addConfirmedStructure(List<EnrichedCandle> candles,
                                       List<Pivot> sequence,
                                       String direction,
                                       boolean correctionComplete,
                                       int confirmationIndex,
                                       List<StructureCandidate> structures) {
        Pivot endpoint = sequence.getLast();
        if (!isTimelyConfirmation(endpoint, confirmationIndex)) {
            return;
        }
        int quality = structureQuality(candles, sequence, direction, correctionComplete);
        structures.add(new StructureCandidate(confirmationIndex,
                toStructure(direction, correctionComplete, candles, sequence, confirmationIndex, quality)));
    }

    private java.util.Optional<DetectedSignal> detectBullishImpulse(List<EnrichedCandle> candles,
                                                                    List<Pivot> pivots,
                                                                    EnrichedCandle current) {
        List<Pivot> sequence = lastAlternating(pivots, PivotType.LOW, PivotType.HIGH, PivotType.LOW,
                PivotType.HIGH, PivotType.LOW);
        if (sequence.isEmpty()) {
            return java.util.Optional.empty();
        }

        Pivot wave0 = sequence.get(0);
        Pivot wave1 = sequence.get(1);
        Pivot wave2 = sequence.get(2);
        Pivot wave3 = sequence.get(3);
        Pivot wave4 = sequence.get(4);

        if (!isBullishImpulseBase(wave0, wave1, wave2, wave3, wave4)) {
            return java.util.Optional.empty();
        }
        EnrichedCandle previous = candles.get(candles.size() - 2);
        double breakoutLevel = wave3.price() * (1.0 + detectionRules.breakoutBufferFraction());
        if (!isCurrentBullishBreakout(candles, wave4, breakoutLevel)) {
            return java.util.Optional.empty();
        }

        WaveEvidence evidence = bullishImpulseEvidence(candles, current, wave0, wave1, wave2, wave3, wave4);
        addPreliminaryImpulseTimingQuality(evidence, wave0, wave4);
        if (previous.close() > breakoutLevel) {
            evidence.reasons().add("breakout remains active on the latest candle with bullish follow-through");
        }
        return java.util.Optional.of(signal(
                CandlePattern.ELLIOTT_BULLISH_IMPULSE,
                TradeSignal.BUY,
                current,
                evidence,
                candles,
                sequence,
                null
        ));
    }

    private java.util.Optional<DetectedSignal> detectBearishImpulse(List<EnrichedCandle> candles,
                                                                    List<Pivot> pivots,
                                                                    EnrichedCandle current) {
        List<Pivot> sequence = lastAlternating(pivots, PivotType.HIGH, PivotType.LOW, PivotType.HIGH,
                PivotType.LOW, PivotType.HIGH);
        if (sequence.isEmpty()) {
            return java.util.Optional.empty();
        }

        Pivot wave0 = sequence.get(0);
        Pivot wave1 = sequence.get(1);
        Pivot wave2 = sequence.get(2);
        Pivot wave3 = sequence.get(3);
        Pivot wave4 = sequence.get(4);

        if (!isBearishImpulseBase(wave0, wave1, wave2, wave3, wave4)) {
            return java.util.Optional.empty();
        }
        EnrichedCandle previous = candles.get(candles.size() - 2);
        double breakdownLevel = wave3.price() / (1.0 + detectionRules.breakoutBufferFraction());
        if (!isCurrentBearishBreakdown(candles, wave4, breakdownLevel)) {
            return java.util.Optional.empty();
        }

        WaveEvidence evidence = bearishImpulseEvidence(candles, current, wave0, wave1, wave2, wave3, wave4);
        addPreliminaryImpulseTimingQuality(evidence, wave0, wave4);
        if (previous.close() < breakdownLevel) {
            evidence.reasons().add("breakdown remains active on the latest candle with bearish follow-through");
        }
        return java.util.Optional.of(signal(
                CandlePattern.ELLIOTT_BEARISH_IMPULSE,
                TradeSignal.SELL,
                current,
                evidence,
                candles,
                sequence,
                null
        ));
    }

    private java.util.Optional<DetectedSignal> detectBullishWaveVEnd(List<EnrichedCandle> candles,
                                                                     List<Pivot> pivots,
                                                                     EnrichedCandle current) {
        List<Pivot> sequence = lastAlternating(pivots, PivotType.LOW, PivotType.HIGH, PivotType.LOW,
                PivotType.HIGH, PivotType.LOW);
        if (sequence.isEmpty()) {
            return java.util.Optional.empty();
        }
        Pivot wave5 = highestPivotAfter(candles, sequence.get(4), candles.size() - 1);
        if (wave5 == null || !isBullishImpulseComplete(
                sequence.get(0), sequence.get(1), sequence.get(2), sequence.get(3), sequence.get(4), wave5)
                || !isCurrentBearishRejection(candles, wave5)) {
            return java.util.Optional.empty();
        }
        WaveEvidence evidence = bullishImpulseEvidence(
                candles, current, sequence.get(0), sequence.get(1), sequence.get(2), sequence.get(3), sequence.get(4));
        addCompletedImpulseQuality(evidence, sequence.get(0), sequence.get(3), wave5);
        boolean truncated = isTruncatedWaveFive(sequence.get(3), wave5);
        evidence.reasons().add(truncated
                ? "truncated bullish wave V ended below wave III with a current bearish reversal"
                : "bullish wave V ended with a current bearish reversal below the previous candle low");
        return java.util.Optional.of(signal(
                truncated ? CandlePattern.ELLIOTT_BULLISH_TRUNCATED_WAVE_V_END
                        : CandlePattern.ELLIOTT_BULLISH_WAVE_V_END,
                TradeSignal.SELL,
                current,
                evidence,
                candles,
                withPivot(sequence, wave5),
                null
        ));
    }

    private java.util.Optional<DetectedSignal> detectBearishWaveVEnd(List<EnrichedCandle> candles,
                                                                     List<Pivot> pivots,
                                                                     EnrichedCandle current) {
        List<Pivot> sequence = lastAlternating(pivots, PivotType.HIGH, PivotType.LOW, PivotType.HIGH,
                PivotType.LOW, PivotType.HIGH);
        if (sequence.isEmpty()) {
            return java.util.Optional.empty();
        }
        Pivot wave5 = lowestPivotAfter(candles, sequence.get(4), candles.size() - 1);
        if (wave5 == null || !isBearishImpulseComplete(
                sequence.get(0), sequence.get(1), sequence.get(2), sequence.get(3), sequence.get(4), wave5)
                || !isCurrentBullishRebound(candles, wave5)) {
            return java.util.Optional.empty();
        }
        WaveEvidence evidence = bearishImpulseEvidence(
                candles, current, sequence.get(0), sequence.get(1), sequence.get(2), sequence.get(3), sequence.get(4));
        addCompletedImpulseQuality(evidence, sequence.get(0), sequence.get(3), wave5);
        boolean truncated = isTruncatedWaveFive(sequence.get(3), wave5);
        evidence.reasons().add(truncated
                ? "truncated bearish wave V ended above wave III with a current bullish reversal"
                : "bearish wave V ended with a current bullish reversal above the previous candle high");
        return java.util.Optional.of(signal(
                truncated ? CandlePattern.ELLIOTT_BEARISH_TRUNCATED_WAVE_V_END
                        : CandlePattern.ELLIOTT_BEARISH_WAVE_V_END,
                TradeSignal.BUY,
                current,
                evidence,
                candles,
                withPivot(sequence, wave5),
                null
        ));
    }

    private java.util.Optional<DetectedSignal> detectBullishCorrection(List<EnrichedCandle> candles,
                                                                       List<Pivot> pivots,
                                                                       EnrichedCandle current) {
        List<Pivot> sequence = lastAlternating(pivots, PivotType.LOW, PivotType.HIGH, PivotType.LOW,
                PivotType.HIGH, PivotType.LOW, PivotType.HIGH, PivotType.LOW, PivotType.HIGH);
        if (sequence.isEmpty()) {
            return java.util.Optional.empty();
        }

        Pivot wave0 = sequence.get(0);
        Pivot wave1 = sequence.get(1);
        Pivot wave2 = sequence.get(2);
        Pivot wave3 = sequence.get(3);
        Pivot wave4 = sequence.get(4);
        Pivot wave5 = sequence.get(5);
        Pivot waveA = sequence.get(6);
        Pivot waveB = sequence.get(7);
        Pivot waveC = lowestPivotAfter(candles, waveB, candles.size() - 1);

        if (waveC == null || !isBullishCorrectionComplete(List.of(
                wave0, wave1, wave2, wave3, wave4, wave5, waveA, waveB, waveC))) {
            return java.util.Optional.empty();
        }
        List<Pivot> complete = List.of(wave0, wave1, wave2, wave3, wave4, wave5, waveA, waveB, waveC);
        CorrectionMetrics correction = correctionMetrics(complete, "BULLISH");

        if (!isCurrentBullishRebound(candles, waveC)) {
            return java.util.Optional.empty();
        }

        WaveEvidence evidence = correctionEvidence(current, true, correction);
        addCompletedImpulseCautions(evidence, wave0, wave1, wave2, wave3, wave4, wave5);
        evidence.reasons().add("bullish five-wave structure completed before distinct A, B and C correction legs");
        evidence.reasons().add("wave C ended with a current close back above the previous candle high");
        return java.util.Optional.of(signal(
                bullishCorrectionPattern(correction.variant()),
                TradeSignal.BUY,
                current,
                evidence,
                candles,
                complete,
                correction
        ));
    }

    private java.util.Optional<DetectedSignal> detectBearishCorrection(List<EnrichedCandle> candles,
                                                                       List<Pivot> pivots,
                                                                       EnrichedCandle current) {
        List<Pivot> sequence = lastAlternating(pivots, PivotType.HIGH, PivotType.LOW, PivotType.HIGH,
                PivotType.LOW, PivotType.HIGH, PivotType.LOW, PivotType.HIGH, PivotType.LOW);
        if (sequence.isEmpty()) {
            return java.util.Optional.empty();
        }

        Pivot wave0 = sequence.get(0);
        Pivot wave1 = sequence.get(1);
        Pivot wave2 = sequence.get(2);
        Pivot wave3 = sequence.get(3);
        Pivot wave4 = sequence.get(4);
        Pivot wave5 = sequence.get(5);
        Pivot waveA = sequence.get(6);
        Pivot waveB = sequence.get(7);
        Pivot waveC = highestPivotAfter(candles, waveB, candles.size() - 1);

        if (waveC == null || !isBearishCorrectionComplete(List.of(
                wave0, wave1, wave2, wave3, wave4, wave5, waveA, waveB, waveC))) {
            return java.util.Optional.empty();
        }
        List<Pivot> complete = List.of(wave0, wave1, wave2, wave3, wave4, wave5, waveA, waveB, waveC);
        CorrectionMetrics correction = correctionMetrics(complete, "BEARISH");

        if (!isCurrentBearishRejection(candles, waveC)) {
            return java.util.Optional.empty();
        }

        WaveEvidence evidence = correctionEvidence(current, false, correction);
        addCompletedImpulseCautions(evidence, wave0, wave1, wave2, wave3, wave4, wave5);
        evidence.reasons().add("bearish five-wave structure completed before distinct A, B and C correction legs");
        evidence.reasons().add("wave C ended with a current close back below the previous candle low");
        return java.util.Optional.of(signal(
                bearishCorrectionPattern(correction.variant()),
                TradeSignal.SELL,
                current,
                evidence,
                candles,
                complete,
                correction
        ));
    }

    private boolean isCurrentBullishBreakout(List<EnrichedCandle> candles, Pivot wave4, double breakoutLevel) {
        int currentIndex = candles.size() - 1;
        int breakoutIndex = firstCandleIndexAfterPivot(candles, wave4, candle -> candle.close() > breakoutLevel);
        if (!isPresentSignalIndex(currentIndex, breakoutIndex)) {
            return false;
        }
        if (breakoutIndex == currentIndex) {
            return true;
        }
        EnrichedCandle current = candles.get(currentIndex);
        EnrichedCandle previous = candles.get(currentIndex - 1);
        return current.close() > breakoutLevel
                && (current.close() >= previous.close() || current.high() > previous.high());
    }

    private boolean isCurrentBearishBreakdown(List<EnrichedCandle> candles, Pivot wave4, double breakdownLevel) {
        int currentIndex = candles.size() - 1;
        int breakdownIndex = firstCandleIndexAfterPivot(candles, wave4, candle -> candle.close() < breakdownLevel);
        if (!isPresentSignalIndex(currentIndex, breakdownIndex)) {
            return false;
        }
        if (breakdownIndex == currentIndex) {
            return true;
        }
        EnrichedCandle current = candles.get(currentIndex);
        EnrichedCandle previous = candles.get(currentIndex - 1);
        return current.close() < breakdownLevel
                && (current.close() <= previous.close() || current.low() < previous.low());
    }

    private boolean isCurrentBullishRebound(List<EnrichedCandle> candles, Pivot correctionLow) {
        int currentIndex = candles.size() - 1;
        int reboundIndex = firstBullishReboundIndex(candles, correctionLow);
        if (!isPresentSignalIndex(currentIndex, reboundIndex)) {
            return false;
        }
        if (reboundIndex == currentIndex) {
            return true;
        }
        EnrichedCandle current = candles.get(currentIndex);
        EnrichedCandle previous = candles.get(currentIndex - 1);
        return current.close() >= previous.close() || current.high() > previous.high();
    }

    private boolean isCurrentBearishRejection(List<EnrichedCandle> candles, Pivot correctionHigh) {
        int currentIndex = candles.size() - 1;
        int rejectionIndex = firstBearishRejectionIndex(candles, correctionHigh);
        if (!isPresentSignalIndex(currentIndex, rejectionIndex)) {
            return false;
        }
        if (rejectionIndex == currentIndex) {
            return true;
        }
        EnrichedCandle current = candles.get(currentIndex);
        EnrichedCandle previous = candles.get(currentIndex - 1);
        return current.close() <= previous.close() || current.low() < previous.low();
    }

    private int firstBullishReboundIndex(List<EnrichedCandle> candles, Pivot correctionLow) {
        return firstIndexedSignalAfterPivot(candles, correctionLow, index -> index > 0
                && candles.get(index).close() > candles.get(index - 1).high());
    }

    private int firstBearishRejectionIndex(List<EnrichedCandle> candles, Pivot correctionHigh) {
        return firstIndexedSignalAfterPivot(candles, correctionHigh, index -> index > 0
                && candles.get(index).close() < candles.get(index - 1).low());
    }

    private boolean isTimelyConfirmation(Pivot endpoint, int confirmationIndex) {
        return confirmationIndex > endpoint.index()
                && confirmationIndex - endpoint.index() <= detectionRules.maximumConfirmationLagCandles();
    }

    private boolean hasTimelyBullishRebound(List<EnrichedCandle> candles, Pivot endpoint) {
        return isTimelyConfirmation(endpoint, firstBullishReboundIndex(candles, endpoint));
    }

    private boolean hasTimelyBearishRejection(List<EnrichedCandle> candles, Pivot endpoint) {
        return isTimelyConfirmation(endpoint, firstBearishRejectionIndex(candles, endpoint));
    }

    private int firstCandleIndexAfterPivot(List<EnrichedCandle> candles,
                                           Pivot pivot,
                                           java.util.function.Predicate<EnrichedCandle> predicate) {
        for (int index = pivot.index() + 1; index < candles.size(); index++) {
            if (predicate.test(candles.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private int firstIndexedSignalAfterPivot(List<EnrichedCandle> candles,
                                             Pivot pivot,
                                             java.util.function.IntPredicate predicate) {
        for (int index = pivot.index() + 1; index < candles.size(); index++) {
            if (predicate.test(index)) {
                return index;
            }
        }
        return -1;
    }

    private boolean isPresentSignalIndex(int currentIndex, int signalIndex) {
        return signalIndex >= 0
                && currentIndex - signalIndex <= presentSignalLookbackCandles;
    }

    private WaveEvidence bullishImpulseEvidence(List<EnrichedCandle> candles,
                                                EnrichedCandle current,
                                                Pivot wave0,
                                                Pivot wave1,
                                                Pivot wave2,
                                                Pivot wave3,
                                                Pivot wave4) {
        double wave1Length = wave1.price() - wave0.price();
        double wave3Length = wave3.price() - wave2.price();
        double wave2Retracement = safeRatio(wave1.price() - wave2.price(), wave1Length);
        double wave4Retracement = safeRatio(wave3.price() - wave4.price(), wave3Length);
        WaveEvidence evidence = new WaveEvidence(60, new ArrayList<>());
        evidence.reasons().add("bullish Elliott impulse structure: higher pivots and wave-5 breakout");
        addImpulseQuality(evidence, wave1Length, wave3Length, wave2Retracement, wave4Retracement);
        addBullishContext(evidence, candles, current);
        return evidence;
    }

    private WaveEvidence bearishImpulseEvidence(List<EnrichedCandle> candles,
                                                EnrichedCandle current,
                                                Pivot wave0,
                                                Pivot wave1,
                                                Pivot wave2,
                                                Pivot wave3,
                                                Pivot wave4) {
        double wave1Length = wave0.price() - wave1.price();
        double wave3Length = wave2.price() - wave3.price();
        double wave2Retracement = safeRatio(wave2.price() - wave1.price(), wave1Length);
        double wave4Retracement = safeRatio(wave4.price() - wave3.price(), wave3Length);
        WaveEvidence evidence = new WaveEvidence(60, new ArrayList<>());
        evidence.reasons().add("bearish Elliott impulse structure: lower pivots and wave-5 breakdown");
        addImpulseQuality(evidence, wave1Length, wave3Length, wave2Retracement, wave4Retracement);
        addBearishContext(evidence, candles, current);
        return evidence;
    }

    private void addImpulseQuality(WaveEvidence evidence,
                                   double wave1Length,
                                   double wave3Length,
                                   double wave2Retracement,
                                   double wave4Retracement) {
        if (between(wave2Retracement, detectionRules.waveTwoPreferredMinRetracement(),
                detectionRules.waveTwoPreferredMaxRetracement())) {
            evidence.add(5, "wave 2 retracement is within normal Elliott bounds");
        } else {
            int penalty = unusualWaveTwoPenalty(wave2Retracement);
            String depth = formatPercentage(wave2Retracement);
            if (isDeepWaveTwo(wave2Retracement)) {
                evidence.add(-penalty, "wave 2 retracement is very deep at " + depth
                        + "; confidence reduced by " + penalty + " points");
            } else {
                evidence.add(-penalty, "wave 2 retracement is unusually shallow at " + depth
                        + "; confidence reduced by " + penalty + " points");
            }
        }
        if (between(wave2Retracement, detectionRules.waveTwoCommonMinRetracement(),
                detectionRules.waveTwoCommonMaxRetracement())) {
            evidence.add(5, "wave 2 retracement is near the common Fibonacci zone");
        }
        if (wave3Length >= wave1Length) {
            evidence.add(10, "wave 3 is at least as large as wave 1");
        } else {
            double waveThreeRatio = safeRatio(wave3Length, wave1Length);
            if (waveThreeRatio < detectionRules.preliminaryWaveThreeMinRatio()) {
                int penalty = shortPreliminaryWaveThreePenalty(waveThreeRatio);
                evidence.add(-penalty, "wave 3 is only " + formatPercentage(waveThreeRatio)
                        + " of wave 1; confidence reduced by " + penalty + " points");
            }
        }
        if (between(wave4Retracement, detectionRules.waveFourPreferredMinRetracement(),
                detectionRules.waveFourPreferredMaxRetracement())) {
            evidence.add(5, "wave 4 retracement is within normal Elliott bounds");
        } else {
            int penalty = unusualWaveFourPenalty(wave4Retracement);
            String shape = wave4Retracement > detectionRules.waveFourPreferredMaxRetracement() ? "deep" : "shallow";
            evidence.add(-penalty, "wave 4 retracement is unusually " + shape + " at "
                    + formatPercentage(wave4Retracement) + "; confidence reduced by " + penalty + " points");
        }
        if (between(wave4Retracement, detectionRules.waveFourCommonMinRetracement(),
                detectionRules.waveFourCommonMaxRetracement())) {
            evidence.add(5, "wave 4 retracement is near the common Fibonacci zone");
        }
    }

    private WaveEvidence correctionEvidence(EnrichedCandle current,
                                            boolean bullish,
                                            CorrectionMetrics correction) {
        WaveEvidence evidence = new WaveEvidence(62, new ArrayList<>());
        double retracement = correction.retracement();
        if (between(retracement, detectionRules.correctionPreferredMinRetracement(),
                detectionRules.correctionPreferredMaxRetracement())) {
            evidence.add(8, "correction retracement is within normal Elliott bounds");
        } else {
            int penalty = unusualCorrectionRetracementPenalty(retracement);
            String shape = retracement > detectionRules.correctionPreferredMaxRetracement() ? "deep" : "shallow";
            evidence.add(-penalty, "A-B-C correction is unusually " + shape + " at "
                    + formatPercentage(retracement) + "; confidence reduced by " + penalty + " points");
        }
        if (between(retracement, detectionRules.correctionCommonMinRetracement(),
                detectionRules.correctionCommonMaxRetracement())) {
            evidence.add(8, "correction retracement is near the common Fibonacci zone");
        }
        addWaveCToAWaveQuality(evidence, correction.waveCToARatio());
        if (correction.variant() == CorrectionVariant.EXPANDED_FLAT) {
            evidence.add(-EXPANDED_FLAT_PENALTY,
                    "expanded-flat geometry is valid but less certain without internal subwave confirmation; "
                            + "confidence reduced by " + EXPANDED_FLAT_PENALTY + " points");
        } else if (correction.variant() == CorrectionVariant.RUNNING_FLAT) {
            evidence.add(-RUNNING_FLAT_PENALTY,
                    "running-flat geometry is rare; confidence reduced by " + RUNNING_FLAT_PENALTY + " points");
        }
        if (bullish) {
            if (isAvailable(current.fastEma()) && current.close() > current.fastEma()) {
                evidence.add(7, "close is above the 20-period EMA after the correction");
            }
            if (isAvailable(current.rsi()) && current.rsi() >= 40 && current.rsi() <= 68) {
                evidence.add(5, "RSI supports a bullish rebound without extreme overbought pressure");
            }
        } else {
            if (isAvailable(current.fastEma()) && current.close() < current.fastEma()) {
                evidence.add(7, "close is below the 20-period EMA after the correction");
            }
            if (isAvailable(current.rsi()) && current.rsi() <= 60 && current.rsi() >= 32) {
                evidence.add(5, "RSI supports a bearish continuation without extreme oversold pressure");
            }
        }
        if (isVolumeSurge(current)) {
            evidence.add(5, "volume is at least 20% above its 20-period average");
        }
        return evidence;
    }

    private void addWaveCToAWaveQuality(WaveEvidence evidence, double ratio) {
        if (between(ratio, detectionRules.waveCPreferredMinRatio(), detectionRules.waveCPreferredMaxRatio())) {
            evidence.add(5, "wave C length is proportionate to wave A");
        } else {
            int penalty = unusualWaveCToAPenalty(ratio);
            evidence.add(-penalty, "wave C is an atypical " + roundRatio(ratio) + " times wave A; "
                    + "confidence reduced by " + penalty + " points");
        }
        if (between(ratio, detectionRules.waveCCommonMinRatio(), detectionRules.waveCCommonMaxRatio())) {
            evidence.add(5, "wave C is near equality with wave A");
        }
    }

    private void addPreliminaryImpulseTimingQuality(WaveEvidence evidence, Pivot wave0, Pivot wave4) {
        int expectedSpanBeforeWaveFive = detectionRules.minimumImpulseSpanCandles()
                - detectionRules.minimumLegSpanCandles();
        int observedSpan = wave4.index() - wave0.index();
        if (observedSpan < expectedSpanBeforeWaveFive) {
            int penalty = shortImpulseSpanPenalty(observedSpan + detectionRules.minimumLegSpanCandles());
            evidence.add(-penalty, "the developing impulse is compressed in time; confidence reduced by "
                    + penalty + " points");
        }
    }

    private void addCompletedImpulseQuality(WaveEvidence evidence,
                                            Pivot wave0,
                                            Pivot wave3,
                                            Pivot wave5) {
        int penalty = shortImpulseSpanPenalty(wave5.index() - wave0.index());
        if (penalty > 0) {
            evidence.add(-penalty, "the five-wave impulse spans fewer than "
                    + detectionRules.minimumImpulseSpanCandles()
                    + " candles; confidence reduced by " + penalty + " points");
        }
        if (isTruncatedWaveFive(wave3, wave5)) {
            evidence.add(-TRUNCATED_WAVE_FIVE_PENALTY,
                    "wave V is truncated and did not exceed wave III; confidence reduced by "
                            + TRUNCATED_WAVE_FIVE_PENALTY + " points");
        }
    }

    private void addCompletedImpulseCautions(WaveEvidence evidence,
                                              Pivot wave0,
                                              Pivot wave1,
                                              Pivot wave2,
                                              Pivot wave3,
                                              Pivot wave4,
                                              Pivot wave5) {
        double wave1Length = Math.abs(wave1.price() - wave0.price());
        double wave3Length = Math.abs(wave3.price() - wave2.price());
        double wave2Retracement = safeRatio(Math.abs(wave1.price() - wave2.price()), wave1Length);
        double wave4Retracement = safeRatio(Math.abs(wave3.price() - wave4.price()), wave3Length);
        int waveTwoPenalty = unusualWaveTwoPenalty(wave2Retracement);
        if (waveTwoPenalty > 0) {
            String shape = isDeepWaveTwo(wave2Retracement) ? "deep" : "shallow";
            evidence.add(-waveTwoPenalty, "wave 2 is unusually " + shape + " at "
                    + formatPercentage(wave2Retracement) + "; confidence reduced by "
                    + waveTwoPenalty + " points");
        }
        double waveThreeRatio = safeRatio(wave3Length, wave1Length);
        if (waveThreeRatio < detectionRules.preliminaryWaveThreeMinRatio()) {
            int penalty = shortPreliminaryWaveThreePenalty(waveThreeRatio);
            evidence.add(-penalty, "wave 3 is only " + formatPercentage(waveThreeRatio)
                    + " of wave 1; confidence reduced by " + penalty + " points");
        }
        int waveFourPenalty = unusualWaveFourPenalty(wave4Retracement);
        if (waveFourPenalty > 0) {
            String shape = wave4Retracement > detectionRules.waveFourPreferredMaxRetracement() ? "deep" : "shallow";
            evidence.add(-waveFourPenalty, "wave 4 is unusually " + shape + " at "
                    + formatPercentage(wave4Retracement) + "; confidence reduced by "
                    + waveFourPenalty + " points");
        }
        addCompletedImpulseQuality(evidence, wave0, wave3, wave5);
    }

    private void addBullishContext(WaveEvidence evidence, List<EnrichedCandle> candles, EnrichedCandle current) {
        if (isAvailable(current.fastEma()) && current.close() > current.fastEma()) {
            evidence.add(7, "close is above the 20-period EMA");
        }
        if (emaRising(candles)) {
            evidence.add(5, "20-period EMA is rising");
        }
        if (isAvailable(current.rsi()) && current.rsi() >= 45 && current.rsi() <= 72) {
            evidence.add(5, "RSI confirms bullish momentum without extreme overbought pressure");
        }
        if (isVolumeSurge(current)) {
            evidence.add(5, "volume is at least 20% above its 20-period average");
        }
    }

    private void addBearishContext(WaveEvidence evidence, List<EnrichedCandle> candles, EnrichedCandle current) {
        if (isAvailable(current.fastEma()) && current.close() < current.fastEma()) {
            evidence.add(7, "close is below the 20-period EMA");
        }
        if (emaFalling(candles)) {
            evidence.add(5, "20-period EMA is falling");
        }
        if (isAvailable(current.rsi()) && current.rsi() <= 55 && current.rsi() >= 28) {
            evidence.add(5, "RSI confirms bearish momentum without extreme oversold pressure");
        }
        if (isVolumeSurge(current)) {
            evidence.add(5, "volume is at least 20% above its 20-period average");
        }
    }

    private boolean isBullishImpulseBase(Pivot wave0, Pivot wave1, Pivot wave2, Pivot wave3, Pivot wave4) {
        double wave1Length = wave1.price() - wave0.price();
        double wave3Length = wave3.price() - wave2.price();
        double wave2Retracement = safeRatio(wave1.price() - wave2.price(), wave1Length);
        double wave4Retracement = safeRatio(wave3.price() - wave4.price(), wave3Length);

        return wave1.price() > wave0.price()
                && wave2.price() > wave0.price()
                && wave3.price() > wave1.price()
                && wave4.price() > wave2.price()
                && (!detectionRules.requireWaveFourNoOverlap() || wave4.price() > wave1.price())
                && wave1Length > 0.0
                && isValidWaveTwoRetracement(wave2Retracement)
                && isValidWaveFourRetracement(wave4Retracement)
                && hasValidImpulseTiming(wave0, wave1, wave2, wave3, wave4);
    }

    private boolean isBearishImpulseBase(Pivot wave0, Pivot wave1, Pivot wave2, Pivot wave3, Pivot wave4) {
        double wave1Length = wave0.price() - wave1.price();
        double wave3Length = wave2.price() - wave3.price();
        double wave2Retracement = safeRatio(wave2.price() - wave1.price(), wave1Length);
        double wave4Retracement = safeRatio(wave4.price() - wave3.price(), wave3Length);

        return wave1.price() < wave0.price()
                && wave2.price() < wave0.price()
                && wave3.price() < wave1.price()
                && wave4.price() < wave2.price()
                && (!detectionRules.requireWaveFourNoOverlap() || wave4.price() < wave1.price())
                && wave1Length > 0.0
                && isValidWaveTwoRetracement(wave2Retracement)
                && isValidWaveFourRetracement(wave4Retracement)
                && hasValidImpulseTiming(wave0, wave1, wave2, wave3, wave4);
    }

    private boolean isValidWaveTwoRetracement(double retracement) {
        return retracement > 0.0 && retracement < detectionRules.waveTwoMaximumRetracement();
    }

    private boolean isValidWaveFourRetracement(double retracement) {
        return retracement > 0.0 && retracement < detectionRules.waveFourMaximumRetracement();
    }

    private boolean isBullishImpulseComplete(Pivot wave0,
                                             Pivot wave1,
                                             Pivot wave2,
                                             Pivot wave3,
                                             Pivot wave4,
                                             Pivot wave5) {
        double wave1Length = wave1.price() - wave0.price();
        double wave3Length = wave3.price() - wave2.price();
        double wave5Length = wave5.price() - wave4.price();
        return isBullishImpulseBase(wave0, wave1, wave2, wave3, wave4)
                && wave5.price() > wave4.price()
                && actionaryWaveLengthsAllowed(wave1Length, wave3Length, wave5Length)
                && (detectionRules.allowTruncatedFifth() || wave5.price() > wave3.price())
                && wave5.index() - wave4.index() >= detectionRules.minimumLegSpanCandles();
    }

    private boolean isBearishImpulseComplete(Pivot wave0,
                                             Pivot wave1,
                                             Pivot wave2,
                                             Pivot wave3,
                                             Pivot wave4,
                                             Pivot wave5) {
        double wave1Length = wave0.price() - wave1.price();
        double wave3Length = wave2.price() - wave3.price();
        double wave5Length = wave4.price() - wave5.price();
        return isBearishImpulseBase(wave0, wave1, wave2, wave3, wave4)
                && wave5.price() < wave4.price()
                && actionaryWaveLengthsAllowed(wave1Length, wave3Length, wave5Length)
                && (detectionRules.allowTruncatedFifth() || wave5.price() < wave3.price())
                && wave5.index() - wave4.index() >= detectionRules.minimumLegSpanCandles();
    }

    private boolean hasValidImpulseTiming(Pivot wave0, Pivot wave1, Pivot wave2, Pivot wave3, Pivot wave4) {
        return wave1.index() - wave0.index() >= detectionRules.minimumLegSpanCandles()
                && wave2.index() - wave1.index() >= detectionRules.minimumLegSpanCandles()
                && wave3.index() - wave2.index() >= detectionRules.minimumLegSpanCandles()
                && wave4.index() - wave3.index() >= detectionRules.minimumLegSpanCandles();
    }

    private boolean actionaryWaveLengthsAllowed(double wave1Length,
                                                 double wave3Length,
                                                 double wave5Length) {
        if (detectionRules.requireWaveThreeNotShortest()
                && wave3Length < Math.min(wave1Length, wave5Length)) return false;
        if (!detectionRules.allowWaveOneLongest()
                && wave1Length > Math.max(wave3Length, wave5Length)) return false;
        if (detectionRules.requireWaveOneShortest()
                && wave1Length > Math.min(wave3Length, wave5Length)) return false;
        return detectionRules.allowWaveFiveLongest()
                || wave5Length <= Math.max(wave1Length, wave3Length);
    }

    private boolean isBullishCorrectionComplete(List<Pivot> sequence) {
        if (sequence.size() != 9) {
            return false;
        }
        Pivot wave0 = sequence.get(0);
        Pivot wave1 = sequence.get(1);
        Pivot wave2 = sequence.get(2);
        Pivot wave3 = sequence.get(3);
        Pivot wave4 = sequence.get(4);
        Pivot wave5 = sequence.get(5);
        Pivot waveA = sequence.get(6);
        Pivot waveB = sequence.get(7);
        Pivot waveC = sequence.get(8);
        double waveALength = wave5.price() - waveA.price();
        double waveBRecovery = safeRatio(waveB.price() - waveA.price(), waveALength);
        CorrectionMetrics correction = correctionMetrics(sequence, "BULLISH");
        return isBullishImpulseComplete(wave0, wave1, wave2, wave3, wave4, wave5)
                && waveA.price() < wave5.price()
                && (!detectionRules.requireWaveAWithinOrigin() || waveA.price() > wave0.price())
                && waveB.price() > waveA.price()
                && waveC.price() < waveB.price()
                && waveBRecovery <= detectionRules.waveBMaximumRecovery()
                && correction.retracement() > 0.0
                && correction.retracement() < detectionRules.correctionMaximumRetracement()
                && correction.waveCToARatio() > 0.0
                && waveCToARatioAllowed(correction.waveCToARatio())
                && correctionVariantAllowed(correction.variant())
                && waveA.index() - wave5.index() >= detectionRules.minimumLegSpanCandles()
                && waveB.index() - waveA.index() >= detectionRules.minimumLegSpanCandles()
                && waveC.index() - waveB.index() >= detectionRules.minimumLegSpanCandles();
    }

    private boolean isBearishCorrectionComplete(List<Pivot> sequence) {
        if (sequence.size() != 9) {
            return false;
        }
        Pivot wave0 = sequence.get(0);
        Pivot wave1 = sequence.get(1);
        Pivot wave2 = sequence.get(2);
        Pivot wave3 = sequence.get(3);
        Pivot wave4 = sequence.get(4);
        Pivot wave5 = sequence.get(5);
        Pivot waveA = sequence.get(6);
        Pivot waveB = sequence.get(7);
        Pivot waveC = sequence.get(8);
        double waveALength = waveA.price() - wave5.price();
        double waveBRecovery = safeRatio(waveA.price() - waveB.price(), waveALength);
        CorrectionMetrics correction = correctionMetrics(sequence, "BEARISH");
        return isBearishImpulseComplete(wave0, wave1, wave2, wave3, wave4, wave5)
                && waveA.price() > wave5.price()
                && (!detectionRules.requireWaveAWithinOrigin() || waveA.price() < wave0.price())
                && waveB.price() < waveA.price()
                && waveC.price() > waveB.price()
                && waveBRecovery <= detectionRules.waveBMaximumRecovery()
                && correction.retracement() > 0.0
                && correction.retracement() < detectionRules.correctionMaximumRetracement()
                && correction.waveCToARatio() > 0.0
                && waveCToARatioAllowed(correction.waveCToARatio())
                && correctionVariantAllowed(correction.variant())
                && waveA.index() - wave5.index() >= detectionRules.minimumLegSpanCandles()
                && waveB.index() - waveA.index() >= detectionRules.minimumLegSpanCandles()
                && waveC.index() - waveB.index() >= detectionRules.minimumLegSpanCandles();
    }

    private boolean waveCToARatioAllowed(double ratio) {
        return !detectionRules.limitWaveCToARatio()
                || between(ratio, detectionRules.waveCAllowedMinRatio(), detectionRules.waveCAllowedMaxRatio());
    }

    private boolean correctionVariantAllowed(CorrectionVariant variant) {
        return switch (variant) {
            case STANDARD -> detectionRules.allowStandardCorrection();
            case EXPANDED_FLAT -> detectionRules.allowExpandedFlat();
            case RUNNING_FLAT -> detectionRules.allowRunningFlat();
            case NONE -> true;
        };
    }

    private CorrectionMetrics correctionMetrics(List<Pivot> sequence, String direction) {
        Pivot wave0 = sequence.get(0);
        Pivot wave5 = sequence.get(5);
        Pivot waveA = sequence.get(6);
        Pivot waveB = sequence.get(7);
        Pivot waveC = sequence.get(8);
        double impulseLength = Math.abs(wave5.price() - wave0.price());
        double waveALength = Math.abs(wave5.price() - waveA.price());
        double waveCLength = Math.abs(waveB.price() - waveC.price());
        double retracement = safeRatio(Math.abs(wave5.price() - waveC.price()), impulseLength);
        double waveCToARatio = safeRatio(waveCLength, waveALength);
        boolean bullish = "BULLISH".equals(direction);
        boolean waveBBeyondOrigin = bullish
                ? waveB.price() > wave5.price()
                : waveB.price() < wave5.price();
        boolean waveCBeyondA = bullish
                ? waveC.price() < waveA.price()
                : waveC.price() > waveA.price();
        CorrectionVariant variant = !waveBBeyondOrigin
                ? CorrectionVariant.STANDARD
                : waveCBeyondA ? CorrectionVariant.EXPANDED_FLAT : CorrectionVariant.RUNNING_FLAT;
        return new CorrectionMetrics(retracement, waveCToARatio, variant);
    }

    private CandlePattern bullishCorrectionPattern(CorrectionVariant variant) {
        return switch (variant) {
            case EXPANDED_FLAT -> CandlePattern.ELLIOTT_BULLISH_EXPANDED_FLAT_CORRECTION;
            case RUNNING_FLAT -> CandlePattern.ELLIOTT_BULLISH_RUNNING_FLAT_CORRECTION;
            default -> CandlePattern.ELLIOTT_BULLISH_CORRECTION;
        };
    }

    private CandlePattern bearishCorrectionPattern(CorrectionVariant variant) {
        return switch (variant) {
            case EXPANDED_FLAT -> CandlePattern.ELLIOTT_BEARISH_EXPANDED_FLAT_CORRECTION;
            case RUNNING_FLAT -> CandlePattern.ELLIOTT_BEARISH_RUNNING_FLAT_CORRECTION;
            default -> CandlePattern.ELLIOTT_BEARISH_CORRECTION;
        };
    }

    private Pivot lowestPivotAfter(List<EnrichedCandle> candles, Pivot after, int endExclusive) {
        if (after.index() + 1 >= endExclusive) {
            return null;
        }
        int lowestIndex = after.index() + 1;
        double lowestPrice = candles.get(lowestIndex).low();
        for (int index = lowestIndex + 1; index < endExclusive; index++) {
            if (candles.get(index).low() < lowestPrice) {
                lowestIndex = index;
                lowestPrice = candles.get(index).low();
            }
        }
        return new Pivot(lowestIndex, PivotType.LOW, lowestPrice);
    }

    private Pivot highestPivotAfter(List<EnrichedCandle> candles, Pivot after, int endExclusive) {
        if (after.index() + 1 >= endExclusive) {
            return null;
        }
        int highestIndex = after.index() + 1;
        double highestPrice = candles.get(highestIndex).high();
        for (int index = highestIndex + 1; index < endExclusive; index++) {
            if (candles.get(index).high() > highestPrice) {
                highestIndex = index;
                highestPrice = candles.get(index).high();
            }
        }
        return new Pivot(highestIndex, PivotType.HIGH, highestPrice);
    }

    private void addProvisionalBullishStructure(List<EnrichedCandle> candles,
                                                List<Pivot> pivots,
                                                List<StructureCandidate> structures) {
        List<Pivot> sequence = lastAlternating(pivots, PivotType.LOW, PivotType.HIGH, PivotType.LOW,
                PivotType.HIGH, PivotType.LOW, PivotType.HIGH, PivotType.LOW, PivotType.HIGH);
        if (sequence.isEmpty()) {
            return;
        }
        Pivot waveC = lowestPivotAfter(candles, sequence.get(7), candles.size() - 1);
        if (waveC == null) {
            return;
        }
        List<Pivot> complete = new ArrayList<>(sequence);
        complete.add(waveC);
        int confirmationIndex = firstBullishReboundIndex(candles, waveC);
        if (isBullishCorrectionComplete(complete) && hasTimelyBullishRebound(candles, waveC)) {
            int quality = structureQuality(candles, complete, "BULLISH", true);
            structures.add(new StructureCandidate(confirmationIndex,
                    toStructure("BULLISH", true, candles, complete, confirmationIndex, quality)));
        }
    }

    private void addProvisionalBearishStructure(List<EnrichedCandle> candles,
                                                List<Pivot> pivots,
                                                List<StructureCandidate> structures) {
        List<Pivot> sequence = lastAlternating(pivots, PivotType.HIGH, PivotType.LOW, PivotType.HIGH,
                PivotType.LOW, PivotType.HIGH, PivotType.LOW, PivotType.HIGH, PivotType.LOW);
        if (sequence.isEmpty()) {
            return;
        }
        Pivot waveC = highestPivotAfter(candles, sequence.get(7), candles.size() - 1);
        if (waveC == null) {
            return;
        }
        List<Pivot> complete = new ArrayList<>(sequence);
        complete.add(waveC);
        int confirmationIndex = firstBearishRejectionIndex(candles, waveC);
        if (isBearishCorrectionComplete(complete) && hasTimelyBearishRejection(candles, waveC)) {
            int quality = structureQuality(candles, complete, "BEARISH", true);
            structures.add(new StructureCandidate(confirmationIndex,
                    toStructure("BEARISH", true, candles, complete, confirmationIndex, quality)));
        }
    }

    private void addProvisionalBullishWaveVStructure(List<EnrichedCandle> candles,
                                                      List<Pivot> pivots,
                                                      List<StructureCandidate> structures) {
        List<Pivot> sequence = lastAlternating(pivots, PivotType.LOW, PivotType.HIGH, PivotType.LOW,
                PivotType.HIGH, PivotType.LOW);
        if (sequence.isEmpty()) {
            return;
        }
        Pivot wave5 = highestPivotAfter(candles, sequence.get(4), candles.size() - 1);
        if (wave5 == null || !isBullishImpulseComplete(
                sequence.get(0), sequence.get(1), sequence.get(2), sequence.get(3), sequence.get(4), wave5)
                || !hasTimelyBearishRejection(candles, wave5)) {
            return;
        }
        List<Pivot> complete = new ArrayList<>(sequence);
        complete.add(wave5);
        int confirmationIndex = firstBearishRejectionIndex(candles, wave5);
        int quality = structureQuality(candles, complete, "BULLISH", false);
        structures.add(new StructureCandidate(confirmationIndex,
                toStructure("BULLISH", false, candles, complete, confirmationIndex, quality)));
    }

    private void addProvisionalBearishWaveVStructure(List<EnrichedCandle> candles,
                                                      List<Pivot> pivots,
                                                      List<StructureCandidate> structures) {
        List<Pivot> sequence = lastAlternating(pivots, PivotType.HIGH, PivotType.LOW, PivotType.HIGH,
                PivotType.LOW, PivotType.HIGH);
        if (sequence.isEmpty()) {
            return;
        }
        Pivot wave5 = lowestPivotAfter(candles, sequence.get(4), candles.size() - 1);
        if (wave5 == null || !isBearishImpulseComplete(
                sequence.get(0), sequence.get(1), sequence.get(2), sequence.get(3), sequence.get(4), wave5)
                || !hasTimelyBullishRebound(candles, wave5)) {
            return;
        }
        List<Pivot> complete = new ArrayList<>(sequence);
        complete.add(wave5);
        int confirmationIndex = firstBullishReboundIndex(candles, wave5);
        int quality = structureQuality(candles, complete, "BEARISH", false);
        structures.add(new StructureCandidate(confirmationIndex,
                toStructure("BEARISH", false, candles, complete, confirmationIndex, quality)));
    }

    private boolean matchesTypes(List<Pivot> pivots, PivotType... types) {
        if (pivots.size() != types.length) {
            return false;
        }
        for (int index = 0; index < types.length; index++) {
            if (pivots.get(index).type() != types[index]) {
                return false;
            }
        }
        return true;
    }

    private List<ElliottWaveStructure> selectNonOverlappingStructures(List<ElliottWaveStructure> structures) {
        List<ElliottWaveStructure> ranked = structures.stream()
                .sorted(Comparator.comparingInt(ElliottWaveStructure::qualityScore).reversed()
                        .thenComparing(ElliottWaveStructure::correctionComplete, Comparator.reverseOrder())
                        .thenComparing(Comparator.comparingLong(this::structureSpan).reversed()))
                .toList();
        List<ElliottWaveStructure> selected = new ArrayList<>();
        for (ElliottWaveStructure candidate : ranked) {
            boolean materiallyOverlaps = selected.stream()
                    .anyMatch(existing -> overlapRatio(candidate, existing)
                            > detectionRules.maximumStructureOverlapFraction());
            if (!materiallyOverlaps) {
                selected.add(candidate);
            }
        }
        return selected.stream()
                .sorted(Comparator.comparing(structure -> structure.points().getFirst().timestamp()))
                .toList();
    }

    private double overlapRatio(ElliottWaveStructure first, ElliottWaveStructure second) {
        long firstStart = first.points().getFirst().timestamp();
        long firstEnd = first.points().getLast().timestamp();
        long secondStart = second.points().getFirst().timestamp();
        long secondEnd = second.points().getLast().timestamp();
        long overlap = Math.max(0L, Math.min(firstEnd, secondEnd) - Math.max(firstStart, secondStart));
        long shorterSpan = Math.max(1L, Math.min(firstEnd - firstStart, secondEnd - secondStart));
        return overlap / (double) shorterSpan;
    }

    private long structureSpan(ElliottWaveStructure structure) {
        return Math.max(0L,
                structure.points().getLast().timestamp() - structure.points().getFirst().timestamp());
    }

    private int structureQuality(List<EnrichedCandle> candles,
                                 List<Pivot> sequence,
                                 String direction,
                                 boolean correctionComplete) {
        Pivot wave0 = sequence.get(0);
        Pivot wave1 = sequence.get(1);
        Pivot wave2 = sequence.get(2);
        Pivot wave3 = sequence.get(3);
        Pivot wave4 = sequence.get(4);
        Pivot wave5 = sequence.get(5);
        double wave1Length = Math.abs(wave1.price() - wave0.price());
        double wave3Length = Math.abs(wave3.price() - wave2.price());
        double wave5Length = Math.abs(wave5.price() - wave4.price());
        double wave2Retracement = safeRatio(Math.abs(wave1.price() - wave2.price()), wave1Length);
        double wave4Retracement = safeRatio(Math.abs(wave3.price() - wave4.price()), wave3Length);
        double atr = averageTrueRange(candles, wave0.index(), sequence.getLast().index());
        double totalMove = Math.abs(wave5.price() - wave0.price());
        int impulseSpan = wave5.index() - wave0.index();

        int score = 55;
        score += (int) Math.round(Math.min(12.0, safeRatio(totalMove, Math.max(atr, 0.000001))));
        if (impulseSpan >= detectionRules.minimumImpulseSpanCandles()) {
            score += Math.min(8, (impulseSpan - detectionRules.minimumImpulseSpanCandles()) / 5);
        } else {
            score -= shortImpulseSpanPenalty(impulseSpan);
        }
        if (wave3Length >= wave1Length && wave3Length >= wave5Length) {
            score += 7;
        }
        score -= shortPreliminaryWaveThreePenalty(safeRatio(wave3Length, wave1Length));
        if (between(wave2Retracement, detectionRules.waveTwoCommonMinRetracement(),
                detectionRules.waveTwoCommonMaxRetracement())) {
            score += 5;
        }
        score -= unusualWaveTwoPenalty(wave2Retracement);
        if (between(wave4Retracement, detectionRules.waveFourCommonMinRetracement(),
                detectionRules.waveFourCommonMaxRetracement())) {
            score += 5;
        }
        score -= unusualWaveFourPenalty(wave4Retracement);
        if (isTruncatedWaveFive(wave3, wave5)) {
            score -= TRUNCATED_WAVE_FIVE_PENALTY;
        }
        double alternation = Math.abs(wave2Retracement - wave4Retracement);
        if (alternation >= 0.08) {
            score += 3;
        }

        int originLookback = Math.max(8, impulseSpan / 2);
        int firstOriginIndex = Math.max(0, wave0.index() - originLookback);
        double originDisplacement;
        if ("BULLISH".equals(direction)) {
            double precedingLow = wave0.price();
            for (int index = firstOriginIndex; index <= wave0.index(); index++) {
                precedingLow = Math.min(precedingLow, candles.get(index).low());
            }
            originDisplacement = wave0.price() - precedingLow;
        } else {
            double precedingHigh = wave0.price();
            for (int index = firstOriginIndex; index <= wave0.index(); index++) {
                precedingHigh = Math.max(precedingHigh, candles.get(index).high());
            }
            originDisplacement = precedingHigh - wave0.price();
        }
        double normalizedOriginDisplacement = safeRatio(originDisplacement, Math.max(atr, 0.000001));
        if (normalizedOriginDisplacement <= 0.75) {
            score += 8;
        } else if (normalizedOriginDisplacement <= 1.5) {
            score += 3;
        } else {
            score -= Math.min(18, (int) Math.round(normalizedOriginDisplacement * 3.0));
        }

        if (correctionComplete) {
            CorrectionMetrics correction = correctionMetrics(sequence, direction);
            score += 4;
            if (between(correction.retracement(), detectionRules.correctionPreferredMinRetracement(),
                    detectionRules.correctionPreferredMaxRetracement())) {
                score += 4;
            } else {
                score -= unusualCorrectionRetracementPenalty(correction.retracement());
            }
            if (between(correction.waveCToARatio(), detectionRules.waveCCommonMinRatio(),
                    detectionRules.waveCCommonMaxRatio())) {
                score += 5;
            } else if (!between(correction.waveCToARatio(), detectionRules.waveCPreferredMinRatio(),
                    detectionRules.waveCPreferredMaxRatio())) {
                score -= unusualWaveCToAPenalty(correction.waveCToARatio());
            }
            if (correction.variant() == CorrectionVariant.EXPANDED_FLAT) {
                score -= EXPANDED_FLAT_PENALTY;
            } else if (correction.variant() == CorrectionVariant.RUNNING_FLAT) {
                score -= RUNNING_FLAT_PENALTY;
            }
        }
        return clampScore(score);
    }

    private ElliottWaveStructure toStructure(String direction,
                                              boolean correctionComplete,
                                              List<EnrichedCandle> candles,
                                              List<Pivot> pivots,
                                              int confirmationIndex,
                                              int qualityScore) {
        String[] labels = {"", "I", "II", "III", "IV", "V", "A", "B", "C"};
        List<ElliottWavePoint> points = new ArrayList<>();
        for (int index = 0; index < pivots.size(); index++) {
            Pivot pivot = pivots.get(index);
            points.add(new ElliottWavePoint(
                    labels[index],
                    candles.get(pivot.index()).timestamp(),
                    pivot.price(),
                    pivot.type().name()));
        }
        Long confirmationTimestamp = confirmationIndex >= 0 && confirmationIndex < candles.size()
                ? candles.get(confirmationIndex).timestamp()
                : null;
        double waveTwoRetracement = safeRatio(
                Math.abs(pivots.get(1).price() - pivots.get(2).price()),
                Math.abs(pivots.get(1).price() - pivots.get(0).price()));
        double waveThreeToOneRatio = safeRatio(
                Math.abs(pivots.get(3).price() - pivots.get(2).price()),
                Math.abs(pivots.get(1).price() - pivots.get(0).price()));
        double waveFourRetracement = safeRatio(
                Math.abs(pivots.get(3).price() - pivots.get(4).price()),
                Math.abs(pivots.get(3).price() - pivots.get(2).price()));
        ImpulseVariant impulseVariant = isTruncatedWaveFive(pivots.get(3), pivots.get(5))
                ? ImpulseVariant.TRUNCATED_FIFTH
                : ImpulseVariant.STANDARD;
        CorrectionMetrics correction = correctionComplete
                ? correctionMetrics(pivots, direction)
                : new CorrectionMetrics(0.0, 0.0, CorrectionVariant.NONE);
        List<String> qualityWarnings = structureQualityWarnings(
                pivots, waveTwoRetracement, waveThreeToOneRatio, waveFourRetracement, correction);
        return new ElliottWaveStructure(direction, correctionComplete, List.copyOf(points),
                confirmationTimestamp, qualityScore, waveTwoRetracement, isDeepWaveTwo(waveTwoRetracement),
                waveThreeToOneRatio, waveFourRetracement, impulseVariant, correction.variant(),
                correction.retracement(), correction.waveCToARatio(), qualityWarnings);
    }

    private List<String> structureQualityWarnings(List<Pivot> pivots,
                                                  double waveTwoRetracement,
                                                  double waveThreeToOneRatio,
                                                  double waveFourRetracement,
                                                  CorrectionMetrics correction) {
        List<String> warnings = new ArrayList<>();
        if (unusualWaveTwoPenalty(waveTwoRetracement) > 0) {
            warnings.add((isDeepWaveTwo(waveTwoRetracement) ? "Deep" : "Shallow")
                    + " Wave II " + formatPercentage(waveTwoRetracement) + " — reduced confidence");
        }
        if (waveThreeToOneRatio < detectionRules.preliminaryWaveThreeMinRatio()) {
            warnings.add("Wave III is " + formatPercentage(waveThreeToOneRatio)
                    + " of Wave I — reduced confidence");
        }
        if (unusualWaveFourPenalty(waveFourRetracement) > 0) {
            warnings.add((waveFourRetracement > detectionRules.waveFourPreferredMaxRetracement() ? "Deep" : "Shallow")
                    + " Wave IV " + formatPercentage(waveFourRetracement) + " — reduced confidence");
        }
        int impulseSpan = pivots.get(5).index() - pivots.get(0).index();
        if (impulseSpan < detectionRules.minimumImpulseSpanCandles()) {
            warnings.add("Compressed " + impulseSpan + "-candle impulse — reduced confidence");
        }
        if (isTruncatedWaveFive(pivots.get(3), pivots.get(5))) {
            warnings.add("Truncated Wave V — reduced confidence");
        }
        if (correction.variant() != CorrectionVariant.NONE) {
            if (unusualCorrectionRetracementPenalty(correction.retracement()) > 0) {
                warnings.add("A–B–C retracement " + formatPercentage(correction.retracement())
                        + " — reduced confidence");
            }
            if (unusualWaveCToAPenalty(correction.waveCToARatio()) > 0) {
                warnings.add("Wave C is " + roundRatio(correction.waveCToARatio())
                        + "× Wave A — reduced confidence");
            }
            if (correction.variant() == CorrectionVariant.EXPANDED_FLAT) {
                warnings.add("Expanded-flat candidate — reduced confidence");
            } else if (correction.variant() == CorrectionVariant.RUNNING_FLAT) {
                warnings.add("Running-flat candidate — reduced confidence");
            }
        }
        return List.copyOf(warnings);
    }

    private int unusualWaveTwoPenalty(double retracement) {
        if (isDeepWaveTwo(retracement)) {
            double depthWithinDeepZone = safeRatio(
                    retracement - detectionRules.waveTwoPreferredMaxRetracement(),
                    detectionRules.waveTwoMaximumRetracement()
                            - detectionRules.waveTwoPreferredMaxRetracement());
            return 8 + (int) Math.round(Math.min(1.0, depthWithinDeepZone) * 12.0);
        }
        return retracement < detectionRules.waveTwoPreferredMinRetracement() ? 6 : 0;
    }

    private boolean isDeepWaveTwo(double retracement) {
        return retracement > detectionRules.waveTwoPreferredMaxRetracement()
                && retracement < detectionRules.waveTwoMaximumRetracement();
    }

    private int shortPreliminaryWaveThreePenalty(double waveThreeToOneRatio) {
        if (waveThreeToOneRatio >= detectionRules.preliminaryWaveThreeMinRatio()) {
            return 0;
        }
        double shortfall = safeRatio(
                detectionRules.preliminaryWaveThreeMinRatio() - Math.max(0.0, waveThreeToOneRatio),
                detectionRules.preliminaryWaveThreeMinRatio());
        return 6 + (int) Math.round(Math.min(1.0, shortfall) * 8.0);
    }

    private int unusualWaveFourPenalty(double retracement) {
        if (retracement < detectionRules.waveFourPreferredMinRetracement()) {
            double shortfall = safeRatio(detectionRules.waveFourPreferredMinRetracement()
                            - Math.max(0.0, retracement),
                    detectionRules.waveFourPreferredMinRetracement());
            return 6 + (int) Math.round(Math.min(1.0, shortfall) * 4.0);
        }
        if (retracement > detectionRules.waveFourPreferredMaxRetracement()) {
            double depth = safeRatio(retracement - detectionRules.waveFourPreferredMaxRetracement(),
                    detectionRules.waveFourMaximumRetracement()
                            - detectionRules.waveFourPreferredMaxRetracement());
            return 8 + (int) Math.round(Math.min(1.0, depth) * 8.0);
        }
        return 0;
    }

    private int shortImpulseSpanPenalty(int impulseSpan) {
        return impulseSpan >= detectionRules.minimumImpulseSpanCandles()
                ? 0
                : Math.min(10, Math.max(1,
                (detectionRules.minimumImpulseSpanCandles() - impulseSpan) * 2));
    }

    private int unusualCorrectionRetracementPenalty(double retracement) {
        if (retracement < detectionRules.correctionPreferredMinRetracement()) {
            double shortfall = safeRatio(detectionRules.correctionPreferredMinRetracement()
                            - Math.max(0.0, retracement),
                    detectionRules.correctionPreferredMinRetracement());
            return 6 + (int) Math.round(Math.min(1.0, shortfall) * 4.0);
        }
        if (retracement > detectionRules.correctionPreferredMaxRetracement()) {
            double depth = safeRatio(retracement - detectionRules.correctionPreferredMaxRetracement(),
                    detectionRules.correctionMaximumRetracement()
                            - detectionRules.correctionPreferredMaxRetracement());
            return 8 + (int) Math.round(Math.min(1.0, depth) * 10.0);
        }
        return 0;
    }

    private int unusualWaveCToAPenalty(double ratio) {
        if (ratio < detectionRules.waveCPreferredMinRatio()) {
            double shortfall = safeRatio(detectionRules.waveCPreferredMinRatio() - Math.max(0.0, ratio),
                    detectionRules.waveCPreferredMinRatio());
            return 6 + (int) Math.round(Math.min(1.0, shortfall) * 4.0);
        }
        if (ratio > detectionRules.waveCPreferredMaxRatio()) {
            return 8 + Math.min(8,
                    (int) Math.round((ratio - detectionRules.waveCPreferredMaxRatio()) * 4.0));
        }
        return 0;
    }

    private boolean isTruncatedWaveFive(Pivot wave3, Pivot wave5) {
        return wave5.type() == PivotType.HIGH
                ? wave5.price() <= wave3.price()
                : wave5.price() >= wave3.price();
    }

    private String roundRatio(double value) {
        return Double.toString(Math.round(value * 100.0) / 100.0);
    }

    private String formatPercentage(double value) {
        double rounded = Math.round(value * 1_000.0) / 10.0;
        return rounded + "%";
    }

    /**
     * Looks for one defensible Elliott degree inside a completed parent leg.
     * The supplied candles must already be limited to the parent leg and to the
     * historical as-of boundary. A missing result is intentional: Elliott
     * labels are not forced onto price action that does not satisfy the rules.
     */
    public java.util.Optional<ElliottSubdivision> findSubdivision(
            List<EnrichedCandle> parentCandles,
            String parentLabel,
            double parentStartPrice,
            double parentEndPrice) {
        if (parentCandles == null || parentLabel == null
                || !Double.isFinite(parentStartPrice) || !Double.isFinite(parentEndPrice)
                || Double.compare(parentStartPrice, parentEndPrice) == 0) {
            return java.util.Optional.empty();
        }
        List<EnrichedCandle> candles = parentCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        if (candles.size() < 6) {
            return java.util.Optional.empty();
        }

        String normalizedLabel = grammarLabel(parentLabel);
        boolean motiveExpected = java.util.Set.of("I", "III", "V", "1", "3", "5", "A", "C")
                .contains(normalizedLabel);
        boolean correctionExpected = java.util.Set.of("II", "IV", "2", "4", "A", "B", "C", "D", "E")
                .contains(normalizedLabel);
        List<SubdivisionCandidate> candidates = new ArrayList<>();
        for (double sensitivity : detectionRules.pivotSensitivities()) {
            List<Pivot> pivots = findPivots(candles, sensitivity);
            if (motiveExpected) {
                collectMotiveSubdivisionCandidates(
                        candles, pivots, parentStartPrice, parentEndPrice,
                        diagonalAllowedAt(normalizedLabel), candidates);
            }
            if (correctionExpected) {
                collectCorrectionSubdivisionCandidates(
                        candles, pivots, parentStartPrice, parentEndPrice, candidates);
                collectComplexCorrectionSubdivisionCandidates(
                        candles, pivots, parentStartPrice, parentEndPrice, candidates);
                if (detectionRules.allowTriangles()
                        && java.util.Set.of("IV", "4", "B").contains(normalizedLabel)) {
                    collectTriangleSubdivisionCandidates(
                            candles, pivots, parentStartPrice, parentEndPrice, candidates);
                }
            }
        }
        if (candidates.isEmpty()) {
            return bestEffortSubdivision(candles, normalizedLabel,
                    motiveExpected, correctionExpected, parentStartPrice, parentEndPrice);
        }

        List<SubdivisionCandidate> ranked = candidates.stream()
                .sorted(Comparator.comparingInt(SubdivisionCandidate::confidence).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (SubdivisionCandidate candidate) -> candidate.pivots().getLast().index()
                                        - candidate.pivots().getFirst().index()).reversed()))
                .toList();
        SubdivisionCandidate best = ranked.getFirst();
        if (best.confidence() < 60) {
            return bestEffortSubdivision(candles, normalizedLabel,
                    motiveExpected, correctionExpected, parentStartPrice, parentEndPrice);
        }
        List<ElliottSubdivisionAlternative> alternatives = ranked.stream()
                .skip(1)
                .filter(candidate -> candidate.kind() != best.kind()
                        || !subdivisionKey(candidate).equals(subdivisionKey(best)))
                .filter(candidate -> candidate.confidence() >= best.confidence() - 8)
                .limit(1)
                .map(candidate -> new ElliottSubdivisionAlternative(
                        candidate.kind().displayName,
                        candidate.confidence(),
                        subdivisionPoints(candles, candidate)))
                .toList();
        return java.util.Optional.of(new ElliottSubdivision(
                best.kind().displayName,
                best.confidence(),
                true,
                subdivisionPoints(candles, best),
                best.evidence(),
                alternatives));
    }

    /**
     * Returns only structurally valid lower-timeframe counts for top-down fractal mapping.
     * Unlike the interactive legacy drill-down, this method never manufactures a provisional
     * best-fit count: callers can reject the parent and try another pivot candidate instead.
     */
    public List<ElliottSubdivision> findStrictSubdivisions(
            List<EnrichedCandle> parentCandles,
            String parentLabel,
            double parentStartPrice,
            double parentEndPrice) {
        if (parentCandles == null || parentLabel == null
                || !Double.isFinite(parentStartPrice) || !Double.isFinite(parentEndPrice)
                || Double.compare(parentStartPrice, parentEndPrice) == 0) {
            return List.of();
        }
        List<EnrichedCandle> candles = parentCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        String label = grammarLabel(parentLabel);
        boolean motive = java.util.Set.of("I", "III", "V", "1", "3", "5", "A", "C")
                .contains(label);
        boolean corrective = java.util.Set.of("II", "IV", "2", "4", "A", "B", "C", "D", "E")
                .contains(label);
        int minimumCandles = motive && !corrective ? 6 : 4;
        if ((!motive && !corrective) || candles.size() < minimumCandles) return List.of();

        List<SubdivisionCandidate> candidates = new ArrayList<>();
        for (double sensitivity : detectionRules.pivotSensitivities()) {
            List<Pivot> pivots = findPivots(candles, sensitivity);
            if (motive) {
                collectStrictMotiveSubdivisionCandidates(
                        candles, pivots, parentStartPrice, parentEndPrice,
                        diagonalAllowedAt(label), candidates);
            }
            if (corrective) {
                collectCorrectionSubdivisionCandidates(
                        candles, pivots, parentStartPrice, parentEndPrice, candidates);
                collectComplexCorrectionSubdivisionCandidates(
                        candles, pivots, parentStartPrice, parentEndPrice, candidates);
                if (detectionRules.allowTriangles()
                        && java.util.Set.of("IV", "4", "B").contains(label)) {
                    collectTriangleSubdivisionCandidates(
                            candles, pivots, parentStartPrice, parentEndPrice, candidates);
                }
            }
        }
        Map<String, SubdivisionCandidate> distinct = new LinkedHashMap<>();
        candidates.stream()
                .filter(candidate -> candidate.confidence() >= 60)
                .sorted(Comparator.comparingInt(SubdivisionCandidate::confidence).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (SubdivisionCandidate candidate) -> candidate.pivots().getLast().index()
                                        - candidate.pivots().getFirst().index()).reversed()))
                .forEach(candidate -> distinct.putIfAbsent(subdivisionKey(candidate), candidate));
        return distinct.values().stream().limit(24)
                .map(candidate -> new ElliottSubdivision(
                        candidate.kind().displayName,
                        candidate.confidence(),
                        true,
                        subdivisionPoints(candles, candidate),
                        candidate.evidence(),
                        List.of()))
                .toList();
    }

    private String grammarLabel(String parentLabel) {
        String normalized = parentLabel == null
                ? "" : parentLabel.trim().toUpperCase(java.util.Locale.ROOT);
        int separator = normalized.lastIndexOf('.');
        if (separator >= 0 && separator + 1 < normalized.length()) {
            normalized = normalized.substring(separator + 1);
        }
        if (normalized.equals("X") || normalized.matches("X[0-9]+")) return "B";
        return normalized;
    }

    /**
     * Finds the best current parent-degree impulse count from Wave II onward.
     * Every completed parent leg must have a validated lower-degree structure;
     * provisional best-fit drill-down counts are deliberately excluded.
     */
    public List<DevelopingImpulse> findDevelopingImpulses(List<EnrichedCandle> recentCandles) {
        return findDevelopingImpulses(recentCandles, null);
    }

    /**
     * Finds developing parent counts while validating every completed parent leg on the
     * supplied next-lower timeframe. The parent series still owns 0/I/II/III/IV/V; the
     * child series is used only for subdivision grammar inside those boundaries.
     */
    public List<DevelopingImpulse> findDevelopingImpulses(
            List<EnrichedCandle> recentCandles,
            List<EnrichedCandle> lowerDegreeCandles) {
        return findDevelopingImpulses(
                recentCandles, lowerDegreeCandles, new DevelopingScanContext());
    }

    /**
     * Historical scanners may retain the context across adjacent as-of windows. Completed
     * leg boundaries are immutable, so their lower-degree validation can be reused without
     * changing confirmation timing or exposing future candles.
     */
    public List<DevelopingImpulse> findDevelopingImpulses(
            List<EnrichedCandle> recentCandles,
            List<EnrichedCandle> lowerDegreeCandles,
            DevelopingScanContext scanContext) {
        Map<String, List<DevelopingImpulse>> byDevelopment = new LinkedHashMap<>();
        for (DevelopingImpulse hypothesis : findDevelopingImpulseHypotheses(
                recentCandles, lowerDegreeCandles, scanContext)) {
            byDevelopment.computeIfAbsent(hypothesis.developmentKey(), ignored -> new ArrayList<>())
                    .add(hypothesis);
        }
        return byDevelopment.values().stream()
                .map(hypotheses -> withHypothesisEvidence(hypotheses.getFirst(), hypotheses.size()))
                .sorted(Comparator.comparingLong(DevelopingImpulse::confirmationTimestamp)
                        .thenComparingInt(candidate -> candidate.stage().progressionOrder()))
                .toList();
    }

    private DevelopingImpulse withHypothesisEvidence(DevelopingImpulse primary, int hypothesisCount) {
        List<String> evidence = new ArrayList<>(primary.evidence());
        evidence.add(hypothesisCount == 1
                ? "One parent-degree count currently satisfies every Elliott rule."
                : hypothesisCount + " admissible parent-degree counts remain; this is the highest-ranked scale-coherent count.");
        return new DevelopingImpulse(
                primary.developmentKey(), primary.direction(), primary.stage(), primary.pattern(),
                primary.expectedMove(), primary.confirmationTimestamp(), primary.confirmationClose(),
                primary.endpointPrice(), primary.stopLossPrice(), primary.targetPrice(),
                primary.forecastLabel(), primary.correctionType(), primary.confidenceScore(),
                primary.points(), List.copyOf(evidence), primary.completedStructure());
    }

    /**
     * Returns the admissible parent-degree counts retained for each developing
     * cycle, ordered with the primary count first. Keeping alternatives is
     * essential while corrective structures are still unfolding: several
     * counts can satisfy every Elliott rule until later price action resolves
     * their degree.
     */
    public List<DevelopingImpulse> findDevelopingImpulseHypotheses(List<EnrichedCandle> recentCandles) {
        return findDevelopingImpulseHypotheses(recentCandles, null);
    }

    public List<DevelopingImpulse> findDevelopingImpulseHypotheses(
            List<EnrichedCandle> recentCandles,
            List<EnrichedCandle> lowerDegreeCandles) {
        return findDevelopingImpulseHypotheses(
                recentCandles, lowerDegreeCandles, new DevelopingScanContext());
    }

    public List<DevelopingImpulse> findDevelopingImpulseHypotheses(
            List<EnrichedCandle> recentCandles,
            List<EnrichedCandle> lowerDegreeCandles,
            DevelopingScanContext scanContext) {
        if (recentCandles == null || recentCandles.size() < detectionRules.minimumCandles()) {
            return List.of();
        }
        DevelopingScanContext context = scanContext == null
                ? new DevelopingScanContext() : scanContext;
        List<EnrichedCandle> candles = recentCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        if (candles.size() < detectionRules.minimumCandles()) return List.of();
        List<EnrichedCandle> childCandles = lowerDegreeCandles == null
                ? List.of()
                : lowerDegreeCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();

        Map<String, List<RankedDevelopingImpulse>> hypothesesByDevelopment = new LinkedHashMap<>();
        List<DevelopmentPivotSet> pivotSets = findDevelopmentPivotSets(candles);
        Map<String, PivotSupport> supportByPivot = new LinkedHashMap<>();
        for (DevelopmentPivotSet pivotSet : pivotSets) {
            for (Pivot pivot : pivotSet.pivots()) {
                String key = pivotKey(pivot);
                PivotSupport support = supportByPivot.computeIfAbsent(
                        key, ignored -> new PivotSupport(pivot, new java.util.TreeSet<>()));
                support.scaleRanks().add(pivotSet.scaleRank());
            }
        }
        List<Pivot> allPivots = supportByPivot.values().stream()
                .map(PivotSupport::pivot).sorted(Comparator.comparingInt(Pivot::index)).toList();
        java.util.Set<String> seededPairs = new java.util.HashSet<>();
        Map<String, java.util.Optional<ElliottSubdivision>> subdivisionCache =
                context.subdivisionCache;
        for (DevelopmentPivotSet pivotSet : pivotSets) {
            List<Pivot> pivots = pivotSet.pivots();
            for (int index = 0; index + 1 < pivots.size(); index++) {
                Pivot origin = pivots.get(index);
                Pivot waveOne = pivots.get(index + 1);
                String pairKey = origin.type() + ":" + origin.index() + ':' + waveOne.index();
                if (!seededPairs.add(pairKey)) continue;
                extendDevelopingImpulse(candles, List.of(origin, waveOne), allPivots,
                        supportByPivot, hypothesesByDevelopment, childCandles, subdivisionCache);
            }
        }
        return hypothesesByDevelopment.values().stream()
                .flatMap(List::stream)
                .sorted(rankedDevelopingComparator())
                .map(RankedDevelopingImpulse::candidate)
                .toList();
    }

    private void extendDevelopingImpulse(
            List<EnrichedCandle> candles,
            List<Pivot> prefix,
            List<Pivot> allPivots,
            Map<String, PivotSupport> supportByPivot,
            Map<String, List<RankedDevelopingImpulse>> hypothesesByDevelopment,
            List<EnrichedCandle> lowerDegreeCandles,
            Map<String, java.util.Optional<ElliottSubdivision>> subdivisionCache) {
        if (prefix.size() >= 3 && prefix.size() <= 6) {
            DevelopingImpulse candidate = developingImpulse(
                    candles, prefix, 0, lowerDegreeCandles, subdivisionCache).orElse(null);
            if (candidate == null || candidate.stage().progressionOrder() != prefix.size() - 1) return;
            retainDevelopingHypothesis(candidate, prefix, supportByPivot, hypothesesByDevelopment);
        } else if (prefix.size() > 6) {
            DevelopingImpulse waveV = developingImpulse(
                    candles, List.copyOf(prefix.subList(0, 6)), 0,
                    lowerDegreeCandles, subdivisionCache).orElse(null);
            if (waveV == null || waveV.stage() != ElliottSignalStage.WAVE_V_END) return;
            if (prefix.size() == 9) {
                DevelopingImpulse correction = developingImpulse(
                        candles, prefix, 0, lowerDegreeCandles, subdivisionCache).orElse(null);
                if (correction != null && correction.stage() == ElliottSignalStage.CORRECTION_END) {
                    retainDevelopingHypothesis(
                            correction, prefix, supportByPivot, hypothesesByDevelopment);
                }
                return;
            }
        }
        if (prefix.size() >= 9) return;
        Pivot last = prefix.getLast();
        PivotType required = opposite(last.type());
        java.util.NavigableSet<Integer> commonScales = commonScaleRanks(prefix, supportByPivot);
        if (commonScales.isEmpty()) return;
        for (Pivot next : allPivots) {
            if (next.type() != required || next.index() <= last.index()) continue;
            PivotSupport nextSupport = supportByPivot.get(pivotKey(next));
            if (nextSupport == null || java.util.Collections.disjoint(
                    commonScales, nextSupport.scaleRanks())) continue;
            List<Pivot> extended = new ArrayList<>(prefix);
            extended.add(next);
            extendDevelopingImpulse(candles, List.copyOf(extended), allPivots,
                    supportByPivot, hypothesesByDevelopment, lowerDegreeCandles, subdivisionCache);
        }
    }

    private java.util.NavigableSet<Integer> commonScaleRanks(
            List<Pivot> pivots,
            Map<String, PivotSupport> supportByPivot) {
        java.util.NavigableSet<Integer> common = null;
        for (Pivot pivot : pivots) {
            PivotSupport support = supportByPivot.get(pivotKey(pivot));
            if (support == null) return new java.util.TreeSet<>();
            if (common == null) common = new java.util.TreeSet<>(support.scaleRanks());
            else common.retainAll(support.scaleRanks());
            if (common.isEmpty()) return common;
        }
        return common == null ? new java.util.TreeSet<>() : common;
    }

    private List<DevelopmentPivotSet> findDevelopmentPivotSets(List<EnrichedCandle> candles) {
        List<DevelopmentPivotSet> sets = new ArrayList<>();
        List<Double> sensitivities = new ArrayList<>(detectionRules.pivotSensitivities());
        double configuredMaximum = sensitivities.stream().mapToDouble(Double::doubleValue).max().orElse(3.0);
        sensitivities.add(configuredMaximum * 1.75);
        sensitivities.add(configuredMaximum * 2.50);
        // Bearish impulses need one additional parent-degree pass because the
        // percentage reversal floor shrinks after Wave I. Without it, valid
        // lower-degree Wave I pivots can survive every configured scale and
        // prevent 0/I from becoming a seed pair.
        sensitivities.add(configuredMaximum * 3.25);
        for (int scaleRank = 0; scaleRank < sensitivities.size(); scaleRank++) {
            double sensitivity = sensitivities.get(scaleRank);
            List<Pivot> pivots = findPivots(candles, sensitivity);
            if (pivots.size() < 3) continue;
            sets.add(new DevelopmentPivotSet(scaleRank, sensitivity, pivots));
        }
        return List.copyOf(sets);
    }

    private void retainDevelopingHypothesis(
            DevelopingImpulse candidate,
            List<Pivot> parent,
            Map<String, PivotSupport> supportByPivot,
            Map<String, List<RankedDevelopingImpulse>> hypothesesByDevelopment) {
        RankedDevelopingImpulse ranked = new RankedDevelopingImpulse(
                candidate, degreeCoherence(parent, supportByPivot), parentPath(parent));
        List<RankedDevelopingImpulse> retained = hypothesesByDevelopment.computeIfAbsent(
                candidate.developmentKey(), ignored -> new ArrayList<>());
        for (int index = 0; index < retained.size(); index++) {
            if (!retained.get(index).parentPath().equals(ranked.parentPath())) continue;
            if (rankedDevelopingComparator().compare(ranked, retained.get(index)) < 0) {
                retained.set(index, ranked);
            }
            retained.sort(rankedDevelopingComparator());
            return;
        }
        retained.add(ranked);
        retained.sort(rankedDevelopingComparator());
        if (retained.size() > 24) retained.subList(24, retained.size()).clear();
    }

    private Comparator<RankedDevelopingImpulse> rankedDevelopingComparator() {
        return Comparator.comparingInt(
                        (RankedDevelopingImpulse ranked) -> ranked.candidate().stage().progressionOrder()).reversed()
                .thenComparing(Comparator.comparingInt(
                        (RankedDevelopingImpulse ranked) ->
                                structuralGuidelineScore(ranked.candidate())).reversed())
                .thenComparing(Comparator.comparingInt(
                        RankedDevelopingImpulse::degreeCoherence).reversed())
                .thenComparing(Comparator.comparingInt(
                        (RankedDevelopingImpulse ranked) -> ranked.candidate().confidenceScore()).reversed())
                .thenComparing(ranked -> ranked.candidate().confirmationTimestamp());
    }

    private int structuralGuidelineScore(DevelopingImpulse candidate) {
        boolean triangle = candidate.correctionType() != null
                && candidate.correctionType().toLowerCase(java.util.Locale.ROOT).contains("triangle");
        boolean alternation = candidate.evidence().stream()
                .anyMatch(reason -> reason.contains("alternation supports"));
        return triangle || alternation ? 1 : 0;
    }

    private int degreeCoherence(List<Pivot> parent, Map<String, PivotSupport> supportByPivot) {
        java.util.Set<Integer> common = null;
        int persistence = 0;
        Map<Integer, Integer> coverage = new java.util.HashMap<>();
        for (Pivot pivot : parent) {
            PivotSupport support = supportByPivot.get(pivotKey(pivot));
            if (support == null) continue;
            persistence += support.scaleRanks().size();
            for (int rank : support.scaleRanks()) coverage.merge(rank, 1, Integer::sum);
            if (common == null) common = new java.util.HashSet<>(support.scaleRanks());
            else common.retainAll(support.scaleRanks());
        }
        if (common != null && !common.isEmpty()) {
            int coarsestCommonScale = common.stream().mapToInt(Integer::intValue).max().orElse(0);
            return 1_000 + coarsestCommonScale * 25 + persistence;
        }
        int bestCoverage = coverage.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return bestCoverage * 100 + persistence;
    }

    private String parentPath(List<Pivot> parent) {
        return parent.stream().map(this::pivotKey)
                .collect(java.util.stream.Collectors.joining("-"));
    }

    private String pivotKey(Pivot pivot) {
        return pivot.type() + ":" + pivot.index();
    }

    private java.util.Optional<DevelopingImpulse> developingImpulse(
            List<EnrichedCandle> candles, List<Pivot> pivots, int start) {
        return developingImpulse(candles, pivots, start, List.of(), new java.util.HashMap<>());
    }

    private java.util.Optional<DevelopingImpulse> developingImpulse(
            List<EnrichedCandle> candles,
            List<Pivot> pivots,
            int start,
            List<EnrichedCandle> lowerDegreeCandles,
            Map<String, java.util.Optional<ElliottSubdivision>> subdivisionCache) {
        Pivot wave0 = pivots.get(start);
        Pivot wave1 = pivots.get(start + 1);
        Pivot wave2 = pivots.get(start + 2);
        boolean bullish = matchesTypes(List.of(wave0, wave1, wave2),
                PivotType.LOW, PivotType.HIGH, PivotType.LOW);
        boolean bearish = matchesTypes(List.of(wave0, wave1, wave2),
                PivotType.HIGH, PivotType.LOW, PivotType.HIGH);
        if (!bullish && !bearish) return java.util.Optional.empty();
        if (bullish
                ? !(wave1.price() > wave0.price() && wave2.price() > wave0.price() && wave2.price() < wave1.price())
                : !(wave1.price() < wave0.price() && wave2.price() < wave0.price() && wave2.price() > wave1.price())) {
            return java.util.Optional.empty();
        }

        ElliottSubdivision waveOne = cachedStrictSubdivision(
                candles, lowerDegreeCandles, wave0, wave1, "I", subdivisionCache);
        ElliottSubdivision waveTwo = cachedStrictSubdivision(
                candles, lowerDegreeCandles, wave1, wave2, "II", subdivisionCache);
        if (waveOne == null || waveTwo == null) return java.util.Optional.empty();

        ElliottSignalStage stage = ElliottSignalStage.WAVE_II_END;
        int endpointOffset = 2;
        List<ElliottSubdivision> subdivisions = new ArrayList<>(List.of(waveOne, waveTwo));
        if (start + 3 < pivots.size()) {
            Pivot wave3 = pivots.get(start + 3);
            boolean parentProgresses = bullish ? wave3.price() > wave1.price() : wave3.price() < wave1.price();
            ElliottSubdivision third = parentProgresses
                    ? cachedStrictSubdivision(candles, lowerDegreeCandles, wave2, wave3,
                    "III", subdivisionCache) : null;
            if (third != null) {
                stage = ElliottSignalStage.WAVE_III_END;
                endpointOffset = 3;
                subdivisions.add(third);
                if (start + 4 < pivots.size()) {
                    Pivot wave4 = pivots.get(start + 4);
                    boolean noOverlap = !detectionRules.requireWaveFourNoOverlap()
                            || (bullish ? wave4.price() > wave1.price() : wave4.price() < wave1.price());
                    ElliottSubdivision fourth = noOverlap
                            ? cachedWaveFourSubdivision(candles, lowerDegreeCandles,
                            wave3, wave4, waveTwo, subdivisionCache) : null;
                    if (fourth != null) {
                        stage = ElliottSignalStage.WAVE_IV_END;
                        endpointOffset = 4;
                        subdivisions.add(fourth);
                        if (start + 5 < pivots.size()) {
                            Pivot wave5 = pivots.get(start + 5);
                            ElliottSubdivision fifth = cachedStrictSubdivision(
                                    candles, lowerDegreeCandles, wave4, wave5,
                                    "V", subdivisionCache);
                            boolean complete = fifth != null && (bullish
                                    ? isBullishImpulseComplete(wave0, wave1, wave2, wave3, wave4, wave5)
                                    : isBearishImpulseComplete(wave0, wave1, wave2, wave3, wave4, wave5));
                            if (complete) {
                                stage = ElliottSignalStage.WAVE_V_END;
                                endpointOffset = 5;
                                subdivisions.add(fifth);
                                if (start + 8 < pivots.size()) {
                                    Pivot waveA = pivots.get(start + 6);
                                    Pivot waveB = pivots.get(start + 7);
                                    Pivot waveC = pivots.get(start + 8);
                                    List<Pivot> completeCorrection = List.of(
                                            wave0, wave1, wave2, wave3, wave4, wave5,
                                            waveA, waveB, waveC);
                                    boolean correctionGeometry = bullish
                                            ? isBullishCorrectionComplete(completeCorrection)
                                            : isBearishCorrectionComplete(completeCorrection);
                                    if (correctionGeometry) {
                                        double waveALength = Math.abs(wave5.price() - waveA.price());
                                        double waveBRetracement = safeRatio(
                                                Math.abs(waveB.price() - waveA.price()), waveALength);
                                        boolean flat = waveBRetracement >= .90;
                                        ElliottSubdivision first = flat
                                                ? cachedCorrectiveSubdivision(
                                                candles, lowerDegreeCandles, wave5, waveA,
                                                "A", false, subdivisionCache)
                                                : cachedMotiveSubdivision(
                                                candles, lowerDegreeCandles, wave5, waveA,
                                                "A", subdivisionCache);
                                        ElliottSubdivision second = cachedCorrectiveSubdivision(
                                                candles, lowerDegreeCandles, waveA, waveB,
                                                "B", true, subdivisionCache);
                                        ElliottSubdivision thirdCorrection = cachedMotiveSubdivision(
                                                candles, lowerDegreeCandles, waveB, waveC,
                                                "C", subdivisionCache);
                                        if (first != null && second != null && thirdCorrection != null) {
                                            stage = ElliottSignalStage.CORRECTION_END;
                                            endpointOffset = 8;
                                            subdivisions.add(first);
                                            subdivisions.add(second);
                                            subdivisions.add(thirdCorrection);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Pivot endpoint = pivots.get(start + endpointOffset);
        int confirmationIndex = endpoint.type() == PivotType.LOW
                ? firstBullishReboundIndex(candles, endpoint)
                : firstBearishRejectionIndex(candles, endpoint);
        if (!isTimelyConfirmation(endpoint, confirmationIndex)) return java.util.Optional.empty();

        List<Pivot> parent = List.copyOf(pivots.subList(start, start + endpointOffset + 1));
        int confidence = (int) Math.round(subdivisions.stream()
                .mapToInt(ElliottSubdivision::confidence).average().orElse(0.0));
        double atr = averageTrueRange(candles, wave0.index(), endpoint.index());
        double buffer = Math.max(0.0, atr * 0.10);
        double direction = bullish ? 1.0 : -1.0;
        double waveOneLength = Math.abs(wave1.price() - wave0.price());
        double stop;
        double target;
        String forecast;
        TradeSignal expectedMove;
        CandlePattern pattern;
        switch (stage) {
            case WAVE_II_END -> {
                expectedMove = bullish ? TradeSignal.BUY : TradeSignal.SELL;
                pattern = bullish ? CandlePattern.ELLIOTT_BULLISH_WAVE_II_END
                        : CandlePattern.ELLIOTT_BEARISH_WAVE_II_END;
                stop = wave0.price() - direction * buffer;
                target = wave2.price() + direction * waveOneLength * 1.618;
                forecast = "Projected Wave III (1.618x Wave I from Wave II)";
            }
            case WAVE_III_END -> {
                Pivot wave3 = parent.get(3);
                expectedMove = bullish ? TradeSignal.SELL : TradeSignal.BUY;
                pattern = bullish ? CandlePattern.ELLIOTT_BULLISH_WAVE_III_END
                        : CandlePattern.ELLIOTT_BEARISH_WAVE_III_END;
                stop = wave3.price() + direction * buffer;
                target = wave3.price() - direction * Math.abs(wave3.price() - wave2.price()) * .382;
                forecast = "Projected Wave IV (38.2% retracement of Wave III)";
            }
            case WAVE_IV_END -> {
                Pivot wave4 = parent.get(4);
                expectedMove = bullish ? TradeSignal.BUY : TradeSignal.SELL;
                pattern = bullish ? CandlePattern.ELLIOTT_BULLISH_WAVE_IV_END
                        : CandlePattern.ELLIOTT_BEARISH_WAVE_IV_END;
                stop = wave1.price() - direction * buffer;
                target = wave4.price() + direction * waveOneLength;
                forecast = "Projected Wave V (Wave I equality from Wave IV)";
            }
            case WAVE_V_END -> {
                Pivot wave5 = parent.get(5);
                expectedMove = bullish ? TradeSignal.SELL : TradeSignal.BUY;
                pattern = bullish ? CandlePattern.ELLIOTT_BULLISH_WAVE_V_END
                        : CandlePattern.ELLIOTT_BEARISH_WAVE_V_END;
                stop = wave5.price() + direction * buffer;
                target = wave5.price() - direction * Math.abs(wave5.price() - wave0.price()) * .382;
                forecast = "Projected correction (38.2% retracement of the impulse)";
            }
            case CORRECTION_END -> {
                Pivot wave5 = parent.get(5);
                Pivot waveA = parent.get(6);
                Pivot waveB = parent.get(7);
                Pivot waveC = parent.get(8);
                expectedMove = bullish ? TradeSignal.BUY : TradeSignal.SELL;
                CorrectionMetrics correction = correctionMetrics(parent, bullish ? "BULLISH" : "BEARISH");
                pattern = bullish ? bullishCorrectionPattern(correction.variant())
                        : bearishCorrectionPattern(correction.variant());
                stop = waveC.price() - direction * buffer;
                target = wave5.price();
                double bRetracement = safeRatio(
                        Math.abs(waveB.price() - waveA.price()),
                        Math.abs(wave5.price() - waveA.price()));
                boolean flat = bRetracement >= .90;
                String bMode = subdivisions.get(6).structureLabel().toLowerCase(java.util.Locale.ROOT)
                        .contains("triangle") ? " with a triangular Wave B" : "";
                forecast = "Completed " + (flat ? "flat 3-3-5" : "zigzag 5-3-5")
                        + bMode + "; projected primary-trend resumption toward Wave V";
            }
            default -> throw new IllegalStateException("Unsupported developing Elliott stage: " + stage);
        }
        String developmentKey = (bullish ? "BULLISH" : "BEARISH")
                + ':' + candles.get(wave0.index()).timestamp()
                + ':' + candles.get(wave1.index()).timestamp();
        List<ElliottWavePoint> points = parentPoints(candles, parent);
        String correctionType = subdivisions.stream()
                .filter(subdivision -> subdivision.structureLabel().contains("Correct")
                        || subdivision.structureLabel().contains("triangle")
                        || subdivision.structureLabel().contains("W-X")
                        || subdivision.structureLabel().contains("zigzag")
                        || subdivision.structureLabel().contains("combination"))
                .map(ElliottSubdivision::structureLabel)
                .distinct().collect(java.util.stream.Collectors.joining("; "));
        if (stage == ElliottSignalStage.CORRECTION_END) {
            Pivot wave5 = parent.get(5);
            Pivot waveA = parent.get(6);
            Pivot waveB = parent.get(7);
            double bRetracement = safeRatio(
                    Math.abs(waveB.price() - waveA.price()),
                    Math.abs(wave5.price() - waveA.price()));
            boolean triangularB = subdivisions.get(6).structureLabel()
                    .toLowerCase(java.util.Locale.ROOT).contains("triangle");
            correctionType = (bRetracement >= .90 ? "Flat 3-3-5" : "Zigzag 5-3-5")
                    + (triangularB ? " with triangular Wave B (3-3-3-3-3)" : "");
        }
        List<String> evidence = new ArrayList<>();
        evidence.add("Every completed parent wave has a validated lower-degree subdivision.");
        for (int index = 0; index < subdivisions.size(); index++) {
            evidence.add("Wave " + parentLabel(index + 1) + ": "
                    + subdivisions.get(index).structureLabel() + " ("
                    + subdivisions.get(index).confidence() + "/100).");
        }
        ElliottWaveStructure completedStructure = stage == ElliottSignalStage.WAVE_V_END
                || stage == ElliottSignalStage.CORRECTION_END
                ? toStructure(bullish ? "BULLISH" : "BEARISH",
                        stage == ElliottSignalStage.CORRECTION_END, candles, parent,
                        confirmationIndex, structureQuality(
                                candles, parent, bullish ? "BULLISH" : "BEARISH",
                                stage == ElliottSignalStage.CORRECTION_END))
                : null;
        if (stage == ElliottSignalStage.WAVE_V_END && completedStructure != null
                && completedStructure.impulseVariant() == ImpulseVariant.TRUNCATED_FIFTH) {
            pattern = bullish ? CandlePattern.ELLIOTT_BULLISH_TRUNCATED_WAVE_V_END
                    : CandlePattern.ELLIOTT_BEARISH_TRUNCATED_WAVE_V_END;
        }
        return java.util.Optional.of(new DevelopingImpulse(
                developmentKey, bullish ? "BULLISH" : "BEARISH", stage, pattern, expectedMove,
                candles.get(confirmationIndex).timestamp(), candles.get(confirmationIndex).close(),
                endpoint.price(), stop, target, forecast,
                correctionType.isBlank() ? null : correctionType,
                confidence, points, List.copyOf(evidence), completedStructure));
    }

    private ElliottSubdivision cachedStrictSubdivision(
            List<EnrichedCandle> candles,
            List<EnrichedCandle> lowerDegreeCandles,
            Pivot start,
            Pivot end,
            String label,
            Map<String, java.util.Optional<ElliottSubdivision>> cache) {
        String key = "STRICT:" + label + ':'
                + candles.get(start.index()).timestamp() + ':' + start.price() + ':'
                + candles.get(end.index()).timestamp() + ':' + end.price();
        return cache.computeIfAbsent(key, ignored -> java.util.Optional.ofNullable(
                bestStrictSubdivision(candles, lowerDegreeCandles, start, end, label)))
                .orElse(null);
    }

    private ElliottSubdivision cachedMotiveSubdivision(
            List<EnrichedCandle> candles,
            List<EnrichedCandle> lowerDegreeCandles,
            Pivot start,
            Pivot end,
            String label,
            Map<String, java.util.Optional<ElliottSubdivision>> cache) {
        String key = "MOTIVE:" + label + ':'
                + candles.get(start.index()).timestamp() + ':' + start.price() + ':'
                + candles.get(end.index()).timestamp() + ':' + end.price();
        return cache.computeIfAbsent(key, ignored -> java.util.Optional.ofNullable(
                bestSubdivisionByMode(
                        candles, lowerDegreeCandles, start, end, label, true, false)))
                .orElse(null);
    }

    private ElliottSubdivision cachedCorrectiveSubdivision(
            List<EnrichedCandle> candles,
            List<EnrichedCandle> lowerDegreeCandles,
            Pivot start,
            Pivot end,
            String label,
            boolean triangleAllowed,
            Map<String, java.util.Optional<ElliottSubdivision>> cache) {
        String key = "CORRECTIVE:" + label + ':' + triangleAllowed + ':'
                + candles.get(start.index()).timestamp() + ':' + start.price() + ':'
                + candles.get(end.index()).timestamp() + ':' + end.price();
        return cache.computeIfAbsent(key, ignored -> java.util.Optional.ofNullable(
                bestSubdivisionByMode(candles, lowerDegreeCandles, start, end,
                        triangleAllowed ? "B" : "II", false, true)))
                .orElse(null);
    }

    private ElliottSubdivision bestSubdivisionByMode(
            List<EnrichedCandle> candles,
            List<EnrichedCandle> lowerDegreeCandles,
            Pivot start,
            Pivot end,
            String label,
            boolean motive,
            boolean corrective) {
        if (start == null || end == null || start.index() >= end.index()) return null;
        return findStrictSubdivisions(
                        subdivisionCandles(candles, lowerDegreeCandles, start, end),
                        label, start.price(), end.price()).stream()
                .filter(option -> motive == option.structureLabel().startsWith("Motive")
                        || corrective && !option.structureLabel().startsWith("Motive"))
                .max(Comparator.comparingInt(ElliottSubdivision::confidence))
                .orElse(null);
    }

    private ElliottSubdivision cachedWaveFourSubdivision(
            List<EnrichedCandle> candles,
            List<EnrichedCandle> lowerDegreeCandles,
            Pivot start,
            Pivot end,
            ElliottSubdivision waveTwo,
            Map<String, java.util.Optional<ElliottSubdivision>> cache) {
        String key = "FOUR:" + waveTwo.structureLabel() + ':'
                + candles.get(start.index()).timestamp() + ':' + start.price() + ':'
                + candles.get(end.index()).timestamp() + ':' + end.price();
        return cache.computeIfAbsent(key, ignored -> java.util.Optional.ofNullable(
                bestWaveFourSubdivision(candles, lowerDegreeCandles, start, end, waveTwo)))
                .orElse(null);
    }

    private ElliottSubdivision bestStrictSubdivision(
            List<EnrichedCandle> candles, Pivot start, Pivot end, String label) {
        return bestStrictSubdivision(candles, List.of(), start, end, label);
    }

    private ElliottSubdivision bestStrictSubdivision(
            List<EnrichedCandle> candles,
            List<EnrichedCandle> lowerDegreeCandles,
            Pivot start,
            Pivot end,
            String label) {
        if (start == null || end == null || start.index() >= end.index()) return null;
        List<EnrichedCandle> leg = subdivisionCandles(
                candles, lowerDegreeCandles, start, end);
        return findStrictSubdivisions(leg, label, start.price(), end.price()).stream()
                .max(Comparator.comparingInt(ElliottSubdivision::confidence))
                .orElse(null);
    }

    private ElliottSubdivision bestWaveFourSubdivision(
            List<EnrichedCandle> candles,
            Pivot start,
            Pivot end,
            ElliottSubdivision waveTwo) {
        return bestWaveFourSubdivision(candles, List.of(), start, end, waveTwo);
    }

    private ElliottSubdivision bestWaveFourSubdivision(
            List<EnrichedCandle> candles,
            List<EnrichedCandle> lowerDegreeCandles,
            Pivot start,
            Pivot end,
            ElliottSubdivision waveTwo) {
        if (start == null || end == null || start.index() >= end.index()) return null;
        List<ElliottSubdivision> options = findStrictSubdivisions(
                subdivisionCandles(candles, lowerDegreeCandles, start, end),
                "IV", start.price(), end.price());
        ElliottSubdivision best = options.stream()
                .max(Comparator.comparingInt(option ->
                        option.confidence() + waveFourAlternationBonus(waveTwo, option)))
                .orElse(null);
        if (best == null) return null;
        int bonus = waveFourAlternationBonus(waveTwo, best);
        if (bonus == 0) return best;
        List<String> evidence = new ArrayList<>(best.evidence());
        evidence.add("Wave II/Wave IV alternation supports this corrective form (guideline, not a hard rule)." );
        return new ElliottSubdivision(
                best.structureLabel(), Math.min(100, best.confidence() + bonus), best.validated(),
                best.points(), List.copyOf(evidence), best.alternatives());
    }

    private List<EnrichedCandle> subdivisionCandles(
            List<EnrichedCandle> parentCandles,
            List<EnrichedCandle> lowerDegreeCandles,
            Pivot start,
            Pivot end) {
        if (lowerDegreeCandles == null || lowerDegreeCandles.isEmpty()) {
            return parentCandles.subList(start.index(), end.index() + 1);
        }
        long startTimestamp = parentCandles.get(start.index()).timestamp();
        long endExclusive = end.index() + 1 < parentCandles.size()
                ? parentCandles.get(end.index() + 1).timestamp()
                : Long.MAX_VALUE;
        return lowerDegreeCandles.stream()
                .filter(candle -> candle.timestamp() >= startTimestamp)
                .filter(candle -> candle.timestamp() < endExclusive)
                .toList();
    }

    private int waveFourAlternationBonus(ElliottSubdivision waveTwo, ElliottSubdivision waveFour) {
        if (waveTwo == null || waveFour == null) return 0;
        String two = waveTwo.structureLabel().toLowerCase(java.util.Locale.ROOT);
        String four = waveFour.structureLabel().toLowerCase(java.util.Locale.ROOT);
        boolean waveTwoSharp = two.contains("zigzag");
        boolean waveTwoSideways = two.contains("flat") || two.contains("combination")
                || two.contains("triangle");
        if (waveTwoSharp && (four.contains("triangle") || four.contains("flat")
                || four.contains("combination"))) return 12;
        if (waveTwoSideways && four.contains("zigzag")) return 8;
        return 0;
    }

    public java.util.Optional<DevelopingInvalidation> findActionaryStructureMismatch(
            List<EnrichedCandle> recentCandles,
            ElliottSignalStage currentStage,
            List<ElliottWavePoint> parentPoints) {
        if (recentCandles == null || parentPoints == null || parentPoints.size() < 3
                || currentStage != ElliottSignalStage.WAVE_II_END
                && currentStage != ElliottSignalStage.WAVE_IV_END) {
            return java.util.Optional.empty();
        }
        List<EnrichedCandle> candles = recentCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp)).toList();
        ElliottWavePoint startPoint = parentPoints.getLast();
        int startIndex = indexAtTimestamp(candles, startPoint.timestamp());
        if (startIndex < 0) return java.util.Optional.empty();
        boolean bullish = parentPoints.get(1).price() > parentPoints.get(0).price();
        DevelopingInvalidation best = findCorrectionStructureMismatch(
                candles, currentStage, parentPoints, bullish).orElse(null);
        PivotType startType = bullish ? PivotType.LOW : PivotType.HIGH;
        PivotType endpointType = opposite(startType);
        Pivot start = new Pivot(startIndex, startType, startPoint.price());
        int waveOneSpan = Math.max(1,
                indexAtTimestamp(candles, parentPoints.get(1).timestamp())
                        - indexAtTimestamp(candles, parentPoints.get(0).timestamp()));
        int minimumComparableSpan = Math.max(3, waveOneSpan / 2);
        for (double sensitivity : detectionRules.pivotSensitivities()) {
            for (Pivot endpoint : findPivots(candles, sensitivity)) {
                if (endpoint.type() != endpointType
                        || endpoint.index() - startIndex < minimumComparableSpan
                        || !(bullish ? endpoint.price() > start.price() : endpoint.price() < start.price())) {
                    continue;
                }
                if (currentStage == ElliottSignalStage.WAVE_II_END) {
                    double waveOnePrice = parentPoints.get(1).price();
                    if (bullish ? endpoint.price() <= waveOnePrice : endpoint.price() >= waveOnePrice) continue;
                }
                int confirmationIndex = endpoint.type() == PivotType.LOW
                        ? firstBullishReboundIndex(candles, endpoint)
                        : firstBearishRejectionIndex(candles, endpoint);
                if (!isTimelyConfirmation(endpoint, confirmationIndex)) continue;
                String motiveLabel = currentStage == ElliottSignalStage.WAVE_II_END ? "III" : "V";
                ElliottSubdivision motive = bestStrictSubdivision(candles, start, endpoint, motiveLabel);
                ElliottSubdivision corrective = bestStrictSubdivision(candles, start, endpoint, "II");
                if (motive != null || corrective == null) continue;
                String wave = currentStage == ElliottSignalStage.WAVE_II_END ? "Wave III" : "Wave V";
                DevelopingInvalidation mismatch = new DevelopingInvalidation(
                        candles.get(confirmationIndex).timestamp(), candles.get(confirmationIndex).close(),
                        wave + " resolved as " + corrective.structureLabel()
                                + " instead of a validated five-subwave motive structure.");
                if (best == null || mismatch.timestamp() < best.timestamp()) best = mismatch;
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    private java.util.Optional<DevelopingInvalidation> findCorrectionStructureMismatch(
            List<EnrichedCandle> candles,
            ElliottSignalStage currentStage,
            List<ElliottWavePoint> parentPoints,
            boolean bullishParent) {
        int requiredPoints = currentStage == ElliottSignalStage.WAVE_II_END ? 3 : 5;
        if (parentPoints.size() < requiredPoints) return java.util.Optional.empty();
        ElliottWavePoint correctionStartPoint = parentPoints.get(parentPoints.size() - 2);
        ElliottWavePoint storedCorrectionEnd = parentPoints.getLast();
        int startIndex = indexAtTimestamp(candles, correctionStartPoint.timestamp());
        int storedEndIndex = indexAtTimestamp(candles, storedCorrectionEnd.timestamp());
        if (startIndex < 0 || storedEndIndex <= startIndex) return java.util.Optional.empty();

        boolean correctionRises = !bullishParent;
        PivotType startType = correctionRises ? PivotType.LOW : PivotType.HIGH;
        PivotType endpointType = opposite(startType);
        Pivot start = new Pivot(startIndex, startType, correctionStartPoint.price());
        DevelopingInvalidation best = null;
        String correctionLabel = currentStage == ElliottSignalStage.WAVE_II_END ? "II" : "IV";
        for (double sensitivity : detectionRules.pivotSensitivities()) {
            for (Pivot endpoint : findPivots(candles, sensitivity)) {
                if (endpoint.type() != endpointType || endpoint.index() <= storedEndIndex
                        || !directedMove(start, endpoint, correctionRises)) continue;
                int confirmationIndex = endpoint.type() == PivotType.LOW
                        ? firstBullishReboundIndex(candles, endpoint)
                        : firstBearishRejectionIndex(candles, endpoint);
                if (!isTimelyConfirmation(endpoint, confirmationIndex)) continue;
                ElliottSubdivision motive = bestStrictSubdivision(candles, start, endpoint, "I");
                ElliottSubdivision corrective = bestStrictSubdivision(
                        candles, start, endpoint, correctionLabel);
                if (motive == null || corrective != null) continue;
                DevelopingInvalidation mismatch = new DevelopingInvalidation(
                        candles.get(confirmationIndex).timestamp(), candles.get(confirmationIndex).close(),
                        "Wave " + correctionLabel + " extended into " + motive.structureLabel()
                                + " instead of remaining a corrective structure.");
                if (best == null || mismatch.timestamp() < best.timestamp()) best = mismatch;
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    /**
     * A developing cycle is invalidated only after every admissible count for
     * its stable 0/I key has failed. Replacing the primary count with another
     * rule-valid degree interpretation is a revision, not an Elliott-rule
     * invalidation.
     */
    public boolean hasViableDevelopingHypothesis(
            List<EnrichedCandle> recentCandles,
            String developmentKey,
            ElliottSignalStage currentStage) {
        if (developmentKey == null || currentStage == null) return false;
        for (DevelopingImpulse hypothesis : findDevelopingImpulseHypotheses(recentCandles)) {
            if (!developmentKey.equals(hypothesis.developmentKey())
                    || hypothesis.stage().progressionOrder() < currentStage.progressionOrder()) continue;
            if (hypothesis.stage().progressionOrder() > currentStage.progressionOrder()
                    || currentStage != ElliottSignalStage.WAVE_II_END
                    && currentStage != ElliottSignalStage.WAVE_IV_END
                    || findActionaryStructureMismatch(recentCandles, currentStage, hypothesis.points()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<ElliottWavePoint> parentPoints(List<EnrichedCandle> candles, List<Pivot> pivots) {
        List<ElliottWavePoint> points = new ArrayList<>();
        for (int index = 0; index < pivots.size(); index++) {
            Pivot pivot = pivots.get(index);
            points.add(new ElliottWavePoint(index == 0 ? "0" : parentLabel(index),
                    candles.get(pivot.index()).timestamp(), pivot.price(), pivot.type().name()));
        }
        return List.copyOf(points);
    }

    private String parentLabel(int index) {
        return switch (index) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "A";
            case 7 -> "B";
            case 8 -> "C";
            default -> Integer.toString(index);
        };
    }

    private java.util.Optional<ElliottSubdivision> bestEffortSubdivision(
            List<EnrichedCandle> candles,
            String parentLabel,
            boolean motiveExpected,
            boolean correctionExpected,
            double parentStartPrice,
            double parentEndPrice) {
        boolean motive = motiveExpected || !correctionExpected;
        PivotType startType = parentEndPrice > parentStartPrice ? PivotType.LOW : PivotType.HIGH;
        PivotType[] interiorTypes = motive
                ? alternatingInteriorTypes(startType, 4)
                : alternatingInteriorTypes(startType, 2);
        List<Integer> indexes = bestFitInteriorIndexes(
                candles, parentStartPrice, parentEndPrice, interiorTypes);
        if (indexes.size() != interiorTypes.length) {
            return java.util.Optional.empty();
        }
        List<Pivot> pivots = new ArrayList<>();
        pivots.add(new Pivot(0, startType, parentStartPrice));
        for (int index = 0; index < indexes.size(); index++) {
            int candleIndex = indexes.get(index);
            PivotType type = interiorTypes[index];
            EnrichedCandle candle = candles.get(candleIndex);
            pivots.add(new Pivot(candleIndex, type,
                    type == PivotType.HIGH ? candle.high() : candle.low()));
        }
        pivots.add(new Pivot(candles.size() - 1, opposite(startType), parentEndPrice));
        SubdivisionKind kind = motive ? SubdivisionKind.MOTIVE : SubdivisionKind.ZIGZAG;
        SubdivisionCandidate provisional = new SubdivisionCandidate(
                kind,
                45,
                List.copyOf(pivots),
                List.of(
                        motive
                                ? "Best-fit five-wave count shown because the strict impulse and diagonal rules did not produce a validated count."
                                : "Best-fit A-B-C count shown because the strict correction rules did not produce a validated count.",
                        "The dashed count is provisional and should be treated as an alternate interpretation, not a confirmed Elliott structure.",
                        "All selected turning points are chronological and remain inside Wave " + parentLabel + "."));
        return java.util.Optional.of(new ElliottSubdivision(
                motive ? "Provisional motive 1-2-3-4-5" : "Provisional corrective A-B-C",
                provisional.confidence(),
                false,
                subdivisionPoints(candles, provisional),
                provisional.evidence(),
                List.of()));
    }

    private PivotType[] alternatingInteriorTypes(PivotType startType, int count) {
        PivotType[] types = new PivotType[count];
        PivotType next = opposite(startType);
        for (int index = 0; index < count; index++) {
            types[index] = next;
            next = opposite(next);
        }
        return types;
    }

    private List<Integer> bestFitInteriorIndexes(List<EnrichedCandle> candles,
                                                 double startPrice,
                                                 double endPrice,
                                                 PivotType[] interiorTypes) {
        int candleCount = candles.size();
        int interiorCount = interiorTypes.length;
        int gap = Math.max(1, (candleCount - 1) / 24);
        if (candleCount - 1 < (interiorCount + 1) * gap) {
            gap = 1;
        }
        double range = candles.stream()
                .mapToDouble(candle -> candle.high() - candle.low())
                .sum() / Math.max(1, candleCount);
        double fullRange = candles.stream().mapToDouble(EnrichedCandle::high).max().orElse(endPrice)
                - candles.stream().mapToDouble(EnrichedCandle::low).min().orElse(startPrice);
        double normalizer = Math.max(Math.max(range, fullRange), Math.abs(endPrice - startPrice) * 0.25);
        normalizer = Math.max(normalizer, 0.000001);

        double[][] scores = new double[interiorCount][candleCount];
        int[][] previous = new int[interiorCount][candleCount];
        for (int leg = 0; leg < interiorCount; leg++) {
            java.util.Arrays.fill(scores[leg], Double.NEGATIVE_INFINITY);
            java.util.Arrays.fill(previous[leg], -1);
        }
        for (int leg = 0; leg < interiorCount; leg++) {
            int minimumIndex = (leg + 1) * gap;
            int maximumIndex = candleCount - 1 - (interiorCount - leg) * gap;
            for (int index = minimumIndex; index <= maximumIndex; index++) {
                double price = pivotPrice(candles.get(index), interiorTypes[leg]);
                if (leg == 0) {
                    scores[leg][index] = directedLegScore(
                            startPrice, price, interiorTypes[leg], normalizer);
                    continue;
                }
                for (int prior = leg * gap; prior <= index - gap; prior++) {
                    if (!Double.isFinite(scores[leg - 1][prior])) continue;
                    double priorPrice = pivotPrice(candles.get(prior), interiorTypes[leg - 1]);
                    double candidate = scores[leg - 1][prior]
                            + directedLegScore(priorPrice, price, interiorTypes[leg], normalizer);
                    if (candidate > scores[leg][index]) {
                        scores[leg][index] = candidate;
                        previous[leg][index] = prior;
                    }
                }
            }
        }
        int lastLeg = interiorCount - 1;
        int bestIndex = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        PivotType endpointType = opposite(interiorTypes[interiorTypes.length - 1]);
        for (int index = interiorCount * gap; index <= candleCount - 1 - gap; index++) {
            if (!Double.isFinite(scores[lastLeg][index])) continue;
            double price = pivotPrice(candles.get(index), interiorTypes[lastLeg]);
            double score = scores[lastLeg][index]
                    + directedLegScore(price, endPrice, endpointType, normalizer);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        if (bestIndex < 0) return List.of();
        int[] selected = new int[interiorCount];
        int current = bestIndex;
        for (int leg = lastLeg; leg >= 0; leg--) {
            selected[leg] = current;
            current = previous[leg][current];
        }
        return java.util.Arrays.stream(selected).boxed().toList();
    }

    private double pivotPrice(EnrichedCandle candle, PivotType type) {
        return type == PivotType.HIGH ? candle.high() : candle.low();
    }

    private double directedLegScore(double from, double to, PivotType endpointType, double normalizer) {
        double signedMove = endpointType == PivotType.HIGH ? to - from : from - to;
        return signedMove >= 0.0
                ? 1.0 + signedMove / normalizer * 2.0
                : -2.0 + signedMove / normalizer * 3.0;
    }

    private void collectMotiveSubdivisionCandidates(List<EnrichedCandle> candles,
                                                     List<Pivot> detected,
                                                     double startPrice,
                                                     double endPrice,
                                                     boolean diagonalAllowed,
                                                     List<SubdivisionCandidate> candidates) {
        boolean rising = endPrice > startPrice;
        PivotType startType = rising ? PivotType.LOW : PivotType.HIGH;
        PivotType[] interiorTypes = rising
                ? new PivotType[]{PivotType.HIGH, PivotType.LOW, PivotType.HIGH, PivotType.LOW}
                : new PivotType[]{PivotType.LOW, PivotType.HIGH, PivotType.LOW, PivotType.HIGH};
        Pivot start = closestBoundaryPivot(candles, startPrice, startType, true);
        Pivot end = closestBoundaryPivot(candles, endPrice, opposite(startType), false);
        if (start == null || end == null || start.index() >= end.index()) return;
        List<Pivot> interior = pivotsStrictlyBetween(detected, start, end);
        if (interior.size() != 4 || !matchesTypes(interior, interiorTypes)) return;
        List<Pivot> sequence = List.of(start, interior.get(0), interior.get(1),
                interior.get(2), interior.get(3), end);
        boolean standard = rising
                ? isBullishImpulseComplete(sequence.get(0), sequence.get(1), sequence.get(2),
                sequence.get(3), sequence.get(4), sequence.get(5))
                : isBearishImpulseComplete(sequence.get(0), sequence.get(1), sequence.get(2),
                sequence.get(3), sequence.get(4), sequence.get(5));
        boolean diagonal = !standard && diagonalAllowed && isDiagonalSubdivision(sequence, rising);
        if (!standard && !diagonal) return;
        int confidence = motiveSubdivisionConfidence(sequence, rising, diagonal);
        List<String> evidence = new ArrayList<>();
        evidence.add(standard
                ? "Five alternating lower-degree legs satisfy the impulse hard rules."
                : "Five alternating legs fit a diagonal candidate; Wave IV overlap prevents a standard impulse count.");
        evidence.add("Wave III is not the shortest actionary leg.");
        evidence.add("Every pivot at the selected degree is consumed inside the parent boundary.");
        candidates.add(new SubdivisionCandidate(
                diagonal ? SubdivisionKind.DIAGONAL : SubdivisionKind.MOTIVE,
                confidence,
                sequence,
                List.copyOf(evidence)));
    }

    private void collectStrictMotiveSubdivisionCandidates(List<EnrichedCandle> candles,
                                                            List<Pivot> detected,
                                                            double startPrice,
                                                            double endPrice,
                                                            boolean diagonalAllowed,
                                                            List<SubdivisionCandidate> candidates) {
        boolean rising = endPrice > startPrice;
        PivotType startType = rising ? PivotType.LOW : PivotType.HIGH;
        PivotType[] interiorTypes = rising
                ? new PivotType[]{PivotType.HIGH, PivotType.LOW, PivotType.HIGH, PivotType.LOW}
                : new PivotType[]{PivotType.LOW, PivotType.HIGH, PivotType.LOW, PivotType.HIGH};
        Pivot start = closestBoundaryPivot(candles, startPrice, startType, true);
        Pivot end = closestBoundaryPivot(candles, endPrice, opposite(startType), false);
        if (start == null || end == null || start.index() >= end.index()) return;
        List<Pivot> interior = pivotsStrictlyBetween(detected, start, end);
        for (List<Pivot> selected : selectDegreePivots(
                interior, interiorTypes, start, end, 256)) {
            List<Pivot> sequence = List.of(start, selected.get(0), selected.get(1),
                    selected.get(2), selected.get(3), end);
            if (!isScaleCoherentGrouping(interior, sequence)) continue;
            boolean standard = passesFractalMotiveHardRules(sequence, rising);
            boolean diagonal = !standard && diagonalAllowed && isDiagonalSubdivision(sequence, rising);
            if (!standard && !diagonal) continue;
            int groupedPivots = interior.size() - selected.size();
            int confidence = Math.max(60,
                    motiveSubdivisionConfidence(sequence, rising, diagonal)
                            - Math.min(18, groupedPivots * 2));
            List<String> evidence = new ArrayList<>(diagonal
                    ? List.of(
                        "Five chronological waves form a motive diagonal in a legal Wave I/V position.",
                        "Wave 2 does not retrace beyond the origin of Wave 1.",
                        "Wave 3 is not the shortest of Waves 1, 3, and 5.",
                        "Wave 4 overlaps Wave 1 price territory, as required for a diagonal count.")
                    : List.of(
                        "Five chronological child waves fit inside the parent boundary.",
                        "Wave 2 does not retrace beyond the origin of Wave 1.",
                        "Wave 3 is not the shortest of Waves 1, 3, and 5.",
                        "Wave 4 stays outside Wave 1 price territory."));
            if (groupedPivots > 0) {
                evidence.add(groupedPivots
                        + " smaller pivots were retained inside their enclosing wave segments instead of being mistaken for parent-degree waves.");
            } else {
                evidence.add("Every detected pivot at this sensitivity is consumed by the five-wave count.");
            }
            candidates.add(new SubdivisionCandidate(
                    diagonal ? SubdivisionKind.DIAGONAL : SubdivisionKind.MOTIVE,
                    confidence, sequence, List.copyOf(evidence)));
        }
    }

    /**
     * Selects the pivots belonging to the requested Elliott degree. Real waves commonly
     * contain visible lower-degree pivots, so requiring the raw detector to return exactly
     * four (or two) interior pivots confuses degree with grammar. Extra pivots may only be
     * grouped when they stay inside one of the selected enclosing legs; the Elliott hard
     * rules are still evaluated on the selected sequence by the caller.
     */
    private List<List<Pivot>> selectDegreePivots(
            List<Pivot> interior,
            PivotType[] expectedTypes,
            Pivot start,
            Pivot end,
            int maximumSelections) {
        if (interior.size() < expectedTypes.length) return List.of();
        List<List<Pivot>> selections = new ArrayList<>();
        selectDegreePivots(interior, expectedTypes, start, end, 0,
                new ArrayList<>(), selections, maximumSelections);
        return List.copyOf(selections);
    }

    private void selectDegreePivots(
            List<Pivot> interior,
            PivotType[] expectedTypes,
            Pivot start,
            Pivot end,
            int fromIndex,
            List<Pivot> selected,
            List<List<Pivot>> selections,
            int maximumSelections) {
        if (selections.size() >= maximumSelections) return;
        int position = selected.size();
        if (position == expectedTypes.length) {
            if (end.index() - selected.getLast().index()
                    >= detectionRules.minimumLegSpanCandles()) {
                selections.add(List.copyOf(selected));
            }
            return;
        }
        int remaining = expectedTypes.length - position - 1;
        Pivot previous = selected.isEmpty() ? start : selected.getLast();
        for (int index = fromIndex; index < interior.size() - remaining; index++) {
            Pivot candidate = interior.get(index);
            if (candidate.type() != expectedTypes[position]
                    || candidate.index() - previous.index()
                    < detectionRules.minimumLegSpanCandles()) continue;
            selected.add(candidate);
            selectDegreePivots(interior, expectedTypes, start, end, index + 1,
                    selected, selections, maximumSelections);
            selected.removeLast();
            if (selections.size() >= maximumSelections) return;
        }
    }

    private boolean isScaleCoherentGrouping(List<Pivot> detectedInterior, List<Pivot> sequence) {
        for (Pivot pivot : detectedInterior) {
            int segment = -1;
            for (int index = 0; index + 1 < sequence.size(); index++) {
                if (pivot.index() > sequence.get(index).index()
                        && pivot.index() < sequence.get(index + 1).index()) {
                    segment = index;
                    break;
                }
            }
            if (segment < 0 || sequence.contains(pivot)) continue;
            double first = sequence.get(segment).price();
            double second = sequence.get(segment + 1).price();
            double tolerance = Math.max(Math.abs(first), Math.abs(second)) * 1.0e-9;
            if (pivot.price() < Math.min(first, second) - tolerance
                    || pivot.price() > Math.max(first, second) + tolerance) return false;
        }
        return true;
    }

    private boolean diagonalAllowedAt(String normalizedParentLabel) {
        return java.util.Set.of("I", "V", "1", "5", "A", "C")
                .contains(normalizedParentLabel);
    }

    private boolean passesFractalMotiveHardRules(List<Pivot> sequence, boolean rising) {
        Pivot w0 = sequence.get(0);
        Pivot w1 = sequence.get(1);
        Pivot w2 = sequence.get(2);
        Pivot w3 = sequence.get(3);
        Pivot w4 = sequence.get(4);
        Pivot w5 = sequence.get(5);
        double one = Math.abs(w1.price() - w0.price());
        double three = Math.abs(w3.price() - w2.price());
        double five = Math.abs(w5.price() - w4.price());
        boolean directional = rising
                ? w1.price() > w0.price()
                && w2.price() > w0.price()
                && w3.price() > w1.price()
                && w4.price() > w1.price()
                && w5.price() > w4.price()
                : w1.price() < w0.price()
                && w2.price() < w0.price()
                && w3.price() < w1.price()
                && w4.price() < w1.price()
                && w5.price() < w4.price();
        return directional
                && one > 0.0 && three > 0.0 && five > 0.0
                && three >= Math.min(one, five)
                && hasValidImpulseTiming(w0, w1, w2, w3, w4)
                && w5.index() - w4.index() >= detectionRules.minimumLegSpanCandles();
    }

    private void collectCorrectionSubdivisionCandidates(List<EnrichedCandle> candles,
                                                         List<Pivot> detected,
                                                         double startPrice,
                                                         double endPrice,
                                                         List<SubdivisionCandidate> candidates) {
        boolean rising = endPrice > startPrice;
        PivotType startType = rising ? PivotType.LOW : PivotType.HIGH;
        PivotType[] interiorTypes = rising
                ? new PivotType[]{PivotType.HIGH, PivotType.LOW}
                : new PivotType[]{PivotType.LOW, PivotType.HIGH};
        Pivot start = closestBoundaryPivot(candles, startPrice, startType, true);
        Pivot end = closestBoundaryPivot(candles, endPrice, opposite(startType), false);
        if (start == null || end == null || start.index() >= end.index()) return;
        List<Pivot> interior = pivotsStrictlyBetween(detected, start, end);
        if (isCompleteMotiveAtThisDegree(interior, start, end, rising)) return;
        for (List<Pivot> selected : selectDegreePivots(
                interior, interiorTypes, start, end, 256)) {
            List<Pivot> sequence = List.of(start, selected.get(0), selected.get(1), end);
            if (!isScaleCoherentGrouping(interior, sequence)) continue;
            double waveA = Math.abs(sequence.get(1).price() - sequence.get(0).price());
            double waveB = Math.abs(sequence.get(2).price() - sequence.get(1).price());
            double waveC = Math.abs(sequence.get(3).price() - sequence.get(2).price());
            double bRetracement = safeRatio(waveB, waveA);
            double cToA = safeRatio(waveC, waveA);
            boolean progresses = rising
                    ? sequence.get(3).price() > sequence.get(0).price()
                    : sequence.get(3).price() < sequence.get(0).price();
            if (!progresses || waveA <= 0.0 || waveC <= 0.0
                    || bRetracement < 0.10 || bRetracement > detectionRules.waveBMaximumRecovery()
                    || cToA < 0.25 || cToA > 3.0) {
                continue;
            }
            int confidence = 62;
            if (between(bRetracement, 0.382, 0.786)) confidence += 8;
            else if (between(bRetracement, 0.236, 1.0)) confidence += 4;
            if (!waveCToARatioAllowed(cToA)) continue;
            if (between(cToA, detectionRules.waveCCommonMinRatio(), detectionRules.waveCCommonMaxRatio())) confidence += 10;
            else if (between(cToA, detectionRules.waveCPreferredMinRatio(), detectionRules.waveCPreferredMaxRatio())) confidence += 5;
            if (sequence.get(1).index() - sequence.get(0).index() >= detectionRules.minimumLegSpanCandles()
                    && sequence.get(3).index() - sequence.get(2).index() >= detectionRules.minimumLegSpanCandles()) {
                confidence += 5;
            }
            boolean flat = bRetracement >= .90;
            int groupedPivots = interior.size() - selected.size();
            confidence = Math.max(60, confidence - Math.min(18, groupedPivots * 2));
            SubdivisionKind correctionKind = flat
                    ? SubdivisionKind.FLAT : SubdivisionKind.ZIGZAG;
            List<String> evidence = new ArrayList<>(List.of(
                    flat
                            ? "Three alternating lower-degree legs form a flat A-B-C at the selected degree."
                            : "Three alternating lower-degree legs form a zigzag A-B-C at the selected degree.",
                    "Wave B retraces " + formatPercentage(bRetracement) + " of Wave A.",
                    "Wave C is " + roundRatio(cToA) + "x the length of Wave A.",
                    flat
                            ? "The flat uses the textbook 3-3-5 child-wave mode above the terminal observable degree."
                            : "The zigzag uses the textbook 5-3-5 child-wave mode above the terminal observable degree."));
            evidence.add(groupedPivots > 0
                    ? groupedPivots + " smaller pivots remain bounded inside their enclosing A/B/C legs."
                    : "Every detected pivot at this sensitivity is consumed by the A-B-C count.");
            candidates.add(new SubdivisionCandidate(
                    correctionKind,
                    Math.min(92, confidence),
                    sequence,
                    List.copyOf(evidence)));
        }
    }

    private boolean isCompleteMotiveAtThisDegree(
            List<Pivot> interior, Pivot start, Pivot end, boolean rising) {
        if (interior.size() != 4) return false;
        PivotType[] motiveTypes = alternatingInteriorTypes(start.type(), 4);
        if (!matchesTypes(interior, motiveTypes)) return false;
        List<Pivot> sequence = List.of(start, interior.get(0), interior.get(1),
                interior.get(2), interior.get(3), end);
        return passesFractalMotiveHardRules(sequence, rising)
                || isDiagonalSubdivision(sequence, rising);
    }

    private List<Pivot> pivotsStrictlyBetween(List<Pivot> detected, Pivot start, Pivot end) {
        return detected.stream()
                .filter(pivot -> pivot.index() > start.index() && pivot.index() < end.index())
                .toList();
    }

    private void collectComplexCorrectionSubdivisionCandidates(
            List<EnrichedCandle> candles,
            List<Pivot> detected,
            double startPrice,
            double endPrice,
            List<SubdivisionCandidate> candidates) {
        boolean rising = endPrice > startPrice;
        PivotType startType = rising ? PivotType.LOW : PivotType.HIGH;
        Pivot start = closestBoundaryPivot(candles, startPrice, startType, true);
        Pivot end = closestBoundaryPivot(candles, endPrice, opposite(startType), false);
        if (start == null || end == null || start.index() >= end.index()) return;
        collectComplexCorrection(candles, detected, start, end, rising, 7, candidates);
        collectComplexCorrection(candles, detected, start, end, rising, 11, candidates);
        if (detectionRules.allowTriangles()) {
            collectTriangleEndingCombination(detected, start, end, rising, false, candidates);
            collectTriangleEndingCombination(detected, start, end, rising, true, candidates);
        }
    }

    private void collectTriangleEndingCombination(
            List<Pivot> detected,
            Pivot start,
            Pivot end,
            boolean rising,
            boolean triple,
            List<SubdivisionCandidate> candidates) {
        int legCount = triple ? 13 : 9;
        int interiorCount = legCount - 1;
        PivotType[] expected = alternatingInteriorTypes(start.type(), interiorCount);
        List<Pivot> interior = pivotsStrictlyBetween(detected, start, end);
        if (interior.size() != interiorCount || !matchesTypes(interior, expected)) return;
            List<Pivot> sequence = new ArrayList<>();
            sequence.add(start);
            sequence.addAll(interior);
            sequence.add(end);
            if (minimumLegSpan(sequence, legCount) < 1) return;

            CorrectionComponentType first = correctionComponentType(sequence, 0, rising);
            CorrectionComponentType second = triple
                    ? correctionComponentType(sequence, 4, rising) : CorrectionComponentType.NONE;
            int triangleStart = triple ? 8 : 4;
            List<Pivot> triangle = List.copyOf(sequence.subList(triangleStart, triangleStart + 6));
            int zigzags = (first == CorrectionComponentType.ZIGZAG ? 1 : 0)
                    + (second == CorrectionComponentType.ZIGZAG ? 1 : 0);
            boolean connectors = directedMove(sequence.get(3), sequence.get(4), !rising)
                    && (!triple || directedMove(sequence.get(7), sequence.get(8), !rising));
            if (first == CorrectionComponentType.NONE
                    || triple && second == CorrectionComponentType.NONE
                    || zigzags > 1 || !connectors
                    || !directedMove(sequence.getFirst(), sequence.getLast(), rising)
                    || !isContractingTriangleSequence(triangle, rising)) return;

            SubdivisionKind kind = triple ? SubdivisionKind.TRIPLE_THREE : SubdivisionKind.DOUBLE_THREE;
            candidates.add(new SubdivisionCandidate(
                    kind,
                    triple ? 62 : 78,
                    List.copyOf(sequence),
                    List.of(
                            kind.displayName + " ends with the only triangle in the combination.",
                            "Every X connector is corrective and moves against the larger correction.",
                            "The final A-B-C-D-E contracts and occupies the terminal actionary position.")));
    }

    private void collectComplexCorrection(
            List<EnrichedCandle> candles,
            List<Pivot> detected,
            Pivot start,
            Pivot end,
            boolean rising,
            int legCount,
            List<SubdivisionCandidate> candidates) {
        int interiorCount = legCount - 1;
        PivotType[] expected = alternatingInteriorTypes(start.type(), interiorCount);
        List<Pivot> interior = pivotsStrictlyBetween(detected, start, end);
        if (interior.size() != interiorCount || !matchesTypes(interior, expected)) return;
            List<Pivot> sequence = new ArrayList<>();
            sequence.add(start);
            sequence.addAll(interior);
            sequence.add(end);
            if (sequence.size() != legCount + 1 || minimumLegSpan(sequence, legCount) < 1) return;

            CorrectionComponentType first = correctionComponentType(sequence, 0, rising);
            CorrectionComponentType second = correctionComponentType(sequence, 4, rising);
            CorrectionComponentType third = legCount == 7
                    ? CorrectionComponentType.NONE : correctionComponentType(sequence, 8, rising);
            boolean connectors = directedMove(sequence.get(3), sequence.get(4), !rising)
                    && (legCount == 7 || directedMove(sequence.get(7), sequence.get(8), !rising));
            boolean progresses = directedMove(sequence.getFirst(), sequence.getLast(), rising);
            if (first == CorrectionComponentType.NONE || second == CorrectionComponentType.NONE
                    || legCount == 11 && third == CorrectionComponentType.NONE
                    || !connectors || !progresses) return;

            int zigzags = (first == CorrectionComponentType.ZIGZAG ? 1 : 0)
                    + (second == CorrectionComponentType.ZIGZAG ? 1 : 0)
                    + (third == CorrectionComponentType.ZIGZAG ? 1 : 0);
            boolean allZigzags = zigzags == (legCount == 7 ? 2 : 3);
            boolean textbookCombination = !allZigzags && zigzags <= 1;
            if (!allZigzags && !textbookCombination) return;
            SubdivisionKind kind = legCount == 7
                    ? allZigzags ? SubdivisionKind.DOUBLE_ZIGZAG : SubdivisionKind.DOUBLE_THREE
                    : allZigzags ? SubdivisionKind.TRIPLE_ZIGZAG : SubdivisionKind.TRIPLE_THREE;
            String label = kind.displayName;
            int confidence = switch (kind) {
                case DOUBLE_ZIGZAG -> 76;
                case DOUBLE_THREE -> 74;
                case TRIPLE_ZIGZAG -> 64;
                case TRIPLE_THREE -> 60;
                default -> throw new IllegalStateException("Unexpected complex correction kind " + kind);
            };
            candidates.add(new SubdivisionCandidate(
                    kind,
                    confidence,
                    List.copyOf(sequence),
                    List.of(
                            label + " contains " + legCount + " chronological corrective legs.",
                            "W/Y" + (legCount == 11 ? "/Z" : "") + " resolve as "
                                    + first + "/" + second
                                    + (legCount == 11 ? "/" + third : "") + ".",
                            allZigzags
                                    ? "Every actionary component is a zigzag; this is a double/triple zigzag, not a sideways combination."
                                    : "The corrective components satisfy the textbook combination limit of at most one zigzag.",
                            "Each X wave moves against the correction and is treated as corrective at the terminal observable degree.")));
    }

    private CorrectionComponentType correctionComponentType(
            List<Pivot> sequence, int offset, boolean rising) {
        if (offset + 3 >= sequence.size()) return CorrectionComponentType.NONE;
        Pivot a0 = sequence.get(offset);
        Pivot a = sequence.get(offset + 1);
        Pivot b = sequence.get(offset + 2);
        Pivot c = sequence.get(offset + 3);
        double waveA = Math.abs(a.price() - a0.price());
        double waveB = Math.abs(b.price() - a.price());
        double waveC = Math.abs(c.price() - b.price());
        double bRatio = safeRatio(waveB, waveA);
        double cRatio = safeRatio(waveC, waveA);
        boolean valid = directedMove(a0, a, rising)
                && directedMove(a, b, !rising)
                && directedMove(b, c, rising)
                && waveA > 0.0 && waveC > 0.0
                && bRatio >= .10 && bRatio <= detectionRules.waveBMaximumRecovery()
                && cRatio >= .25 && cRatio <= 3.0
                && waveCToARatioAllowed(cRatio);
        if (!valid) return CorrectionComponentType.NONE;
        return bRatio >= .90 ? CorrectionComponentType.FLAT : CorrectionComponentType.ZIGZAG;
    }

    private boolean directedMove(Pivot from, Pivot to, boolean rising) {
        return rising ? to.price() > from.price() : to.price() < from.price();
    }

    private Pivot closestBoundaryPivot(List<EnrichedCandle> candles,
                                       double parentPrice,
                                       PivotType type,
                                       boolean preferEarlierOnTie) {
        if (candles == null || candles.isEmpty() || !Double.isFinite(parentPrice)) return null;
        int bestIndex = -1;
        double bestPrice = Double.NaN;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < candles.size(); index++) {
            EnrichedCandle candle = candles.get(index);
            double candidatePrice = type == PivotType.HIGH ? candle.high() : candle.low();
            if (!Double.isFinite(candidatePrice)) continue;
            double distance = Math.abs(candidatePrice - parentPrice);
            double tolerance = Math.max(1.0, Math.max(Math.abs(parentPrice), Math.abs(candidatePrice))) * 1.0e-10;
            boolean closer = distance + tolerance < bestDistance;
            boolean tied = Math.abs(distance - bestDistance) <= tolerance;
            if (closer || tied && (bestIndex < 0
                    || preferEarlierOnTie && index < bestIndex
                    || !preferEarlierOnTie && index > bestIndex)) {
                bestIndex = index;
                bestPrice = candidatePrice;
                bestDistance = distance;
            }
        }
        return bestIndex < 0 ? null : new Pivot(bestIndex, type, bestPrice);
    }

    private void collectTriangleSubdivisionCandidates(List<EnrichedCandle> candles,
                                                       List<Pivot> detected,
                                                       double startPrice,
                                                       double endPrice,
                                                       List<SubdivisionCandidate> candidates) {
        boolean rising = endPrice > startPrice;
        PivotType startType = rising ? PivotType.LOW : PivotType.HIGH;
        PivotType[] interiorTypes = rising
                ? new PivotType[]{PivotType.HIGH, PivotType.LOW, PivotType.HIGH, PivotType.LOW}
                : new PivotType[]{PivotType.LOW, PivotType.HIGH, PivotType.LOW, PivotType.HIGH};
        Pivot start = new Pivot(0, startType, startPrice);
        Pivot end = new Pivot(candles.size() - 1, opposite(startType), endPrice);
        List<Pivot> interior = pivotsStrictlyBetween(detected, start, end);
        if (interior.size() != 4 || !matchesTypes(interior, interiorTypes)) return;
            List<Pivot> sequence = List.of(start, interior.get(0), interior.get(1),
                    interior.get(2), interior.get(3), end);
            if (!isContractingTriangleSequence(sequence, rising)) return;
            candidates.add(new SubdivisionCandidate(
                    SubdivisionKind.TRIANGLE,
                    82,
                    sequence,
                    List.of(
                            "Five overlapping lower-degree legs form a contracting A-B-C-D-E candidate.",
                            "Successive highs and lows contract inside the opening swing.",
                            "Every child pivot occurs inside the selected parent-wave boundary.")));
    }

    private boolean isContractingTriangleSequence(List<Pivot> sequence, boolean rising) {
        if (sequence.size() != 6) return false;
        boolean contracting = rising
                ? sequence.get(1).price() > sequence.get(3).price()
                && sequence.get(3).price() > sequence.get(5).price()
                && sequence.get(2).price() < sequence.get(4).price()
                : sequence.get(1).price() < sequence.get(3).price()
                && sequence.get(3).price() < sequence.get(5).price()
                && sequence.get(2).price() > sequence.get(4).price();
        boolean staysInsideOpeningSwing = rising
                ? sequence.get(5).price() < sequence.get(1).price()
                && sequence.get(4).price() > sequence.get(0).price()
                : sequence.get(5).price() > sequence.get(1).price()
                && sequence.get(4).price() < sequence.get(0).price();
        return contracting && staysInsideOpeningSwing
                && hasValidImpulseTiming(sequence.get(0), sequence.get(1), sequence.get(2),
                sequence.get(3), sequence.get(4))
                && sequence.get(5).index() - sequence.get(4).index()
                >= detectionRules.minimumLegSpanCandles();
    }

    private boolean isDiagonalSubdivision(List<Pivot> sequence, boolean rising) {
        Pivot w0 = sequence.get(0);
        Pivot w1 = sequence.get(1);
        Pivot w2 = sequence.get(2);
        Pivot w3 = sequence.get(3);
        Pivot w4 = sequence.get(4);
        Pivot w5 = sequence.get(5);
        double one = Math.abs(w1.price() - w0.price());
        double three = Math.abs(w3.price() - w2.price());
        double five = Math.abs(w5.price() - w4.price());
        boolean directional = rising
                ? w1.price() > w0.price() && w2.price() > w0.price()
                && w3.price() > w1.price() && w4.price() > w2.price() && w5.price() > w4.price()
                : w1.price() < w0.price() && w2.price() < w0.price()
                && w3.price() < w1.price() && w4.price() < w2.price() && w5.price() < w4.price();
        boolean waveFourOverlapsWaveOne = rising
                ? w4.price() <= w1.price() : w4.price() >= w1.price();
        return directional
                && waveFourOverlapsWaveOne
                && hasValidImpulseTiming(w0, w1, w2, w3, w4)
                && actionaryWaveLengthsAllowed(one, three, five)
                && w5.index() - w4.index() >= detectionRules.minimumLegSpanCandles();
    }

    private int motiveSubdivisionConfidence(List<Pivot> sequence, boolean rising, boolean diagonal) {
        double one = Math.abs(sequence.get(1).price() - sequence.get(0).price());
        double two = safeRatio(Math.abs(sequence.get(2).price() - sequence.get(1).price()), one);
        double three = Math.abs(sequence.get(3).price() - sequence.get(2).price());
        double four = safeRatio(Math.abs(sequence.get(4).price() - sequence.get(3).price()), three);
        double five = Math.abs(sequence.get(5).price() - sequence.get(4).price());
        int score = diagonal ? 61 : 72;
        if (between(two, detectionRules.waveTwoCommonMinRetracement(),
                detectionRules.waveTwoCommonMaxRetracement())) score += 6;
        if (between(four, detectionRules.waveFourCommonMinRetracement(),
                detectionRules.waveFourCommonMaxRetracement())) score += 6;
        if (safeRatio(three, one) >= 1.0) score += 5;
        if (three >= Math.min(one, five)) score += 4;
        boolean fifthExtends = rising
                ? sequence.get(5).price() > sequence.get(3).price()
                : sequence.get(5).price() < sequence.get(3).price();
        if (fifthExtends) score += 3;
        return Math.min(diagonal ? 76 : 96, score);
    }

    private PivotType opposite(PivotType type) {
        return type == PivotType.HIGH ? PivotType.LOW : PivotType.HIGH;
    }

    private String subdivisionKey(SubdivisionCandidate candidate) {
        return candidate.pivots().stream()
                .map(pivot -> pivot.type().name().charAt(0) + Integer.toString(pivot.index()))
                .collect(java.util.stream.Collectors.joining("-"));
    }

    private List<ElliottWavePoint> subdivisionPoints(List<EnrichedCandle> candles,
                                                     SubdivisionCandidate candidate) {
        String[] labels = switch (candidate.kind()) {
            case ZIGZAG, FLAT -> new String[]{"", "a", "b", "c"};
            case DOUBLE_ZIGZAG ->
                    new String[]{"", "w.a", "w.b", "w.c", "x", "y.a", "y.b", "y.c"};
            case DOUBLE_THREE -> candidate.pivots().size() == 10
                    ? new String[]{"", "w.a", "w.b", "w.c", "x", "y.a", "y.b", "y.c", "y.d", "y.e"}
                    : new String[]{"", "w.a", "w.b", "w.c", "x", "y.a", "y.b", "y.c"};
            case TRIPLE_ZIGZAG -> new String[]{"", "w.a", "w.b", "w.c", "x1",
                    "y.a", "y.b", "y.c", "x2", "z.a", "z.b", "z.c"};
            case TRIPLE_THREE -> candidate.pivots().size() == 14
                    ? new String[]{"", "w.a", "w.b", "w.c", "x1", "y.a", "y.b", "y.c", "x2",
                    "z.a", "z.b", "z.c", "z.d", "z.e"}
                    : new String[]{"", "w.a", "w.b", "w.c", "x1",
                    "y.a", "y.b", "y.c", "x2", "z.a", "z.b", "z.c"};
            case TRIANGLE -> new String[]{"", "a", "b", "c", "d", "e"};
            default -> new String[]{"", "i", "ii", "iii", "iv", "v"};
        };
        List<ElliottWavePoint> points = new ArrayList<>();
        for (int index = 0; index < candidate.pivots().size(); index++) {
            Pivot pivot = candidate.pivots().get(index);
            points.add(new ElliottWavePoint(
                    labels[index],
                    candles.get(pivot.index()).timestamp(),
                    pivot.price(),
                    pivot.type().name()));
        }
        return List.copyOf(points);
    }

    private List<List<Pivot>> findPivotSets(List<EnrichedCandle> candles) {
        Map<String, List<Pivot>> uniqueSets = new LinkedHashMap<>();
        for (double sensitivity : detectionRules.pivotSensitivities()) {
            List<Pivot> pivots = findPivots(candles, sensitivity);
            if (pivots.size() < 5) {
                continue;
            }
            String key = pivots.stream()
                    .map(pivot -> pivot.type().name().charAt(0) + Integer.toString(pivot.index()))
                    .collect(java.util.stream.Collectors.joining("-"));
            uniqueSets.putIfAbsent(key, pivots);
        }
        return List.copyOf(uniqueSets.values());
    }

    private List<Pivot> findPivots(List<EnrichedCandle> candles, double sensitivity) {
        if (candles.size() < 3) {
            return List.of();
        }
        List<Pivot> pivots = new ArrayList<>();
        SwingDirection direction = SwingDirection.UNKNOWN;
        int highIndex = 0;
        int lowIndex = 0;
        double highPrice = candles.get(0).high();
        double lowPrice = candles.get(0).low();

        for (int index = 1; index < candles.size(); index++) {
            EnrichedCandle candle = candles.get(index);
            if (direction == SwingDirection.UNKNOWN) {
                if (candle.high() >= highPrice) {
                    highPrice = candle.high();
                    highIndex = index;
                }
                if (candle.low() <= lowPrice) {
                    lowPrice = candle.low();
                    lowIndex = index;
                }
                double threshold = Math.max(reversalAmount(candles, highIndex, sensitivity),
                        reversalAmount(candles, lowIndex, sensitivity));
                if (highPrice - lowPrice < threshold) {
                    continue;
                }
                if (lowIndex < highIndex || lowIndex == highIndex && closesInUpperHalf(candles.get(index))) {
                    appendPivot(pivots, new Pivot(lowIndex, PivotType.LOW, lowPrice));
                    direction = SwingDirection.UP;
                } else if (highIndex < lowIndex || highIndex == lowIndex) {
                    appendPivot(pivots, new Pivot(highIndex, PivotType.HIGH, highPrice));
                    direction = SwingDirection.DOWN;
                }
                continue;
            }

            if (direction == SwingDirection.UP) {
                boolean newHigh = candle.high() >= highPrice;
                if (newHigh) {
                    highPrice = candle.high();
                    highIndex = index;
                }
                boolean reversed = candle.low() <= highPrice - reversalAmount(candles, highIndex, sensitivity);
                boolean highBeforeLow = highIndex < index || closesInLowerHalf(candle);
                if (reversed && highBeforeLow) {
                    appendPivot(pivots, new Pivot(highIndex, PivotType.HIGH, highPrice));
                    direction = SwingDirection.DOWN;
                    lowIndex = index;
                    lowPrice = candle.low();
                }
            } else {
                boolean newLow = candle.low() <= lowPrice;
                if (newLow) {
                    lowPrice = candle.low();
                    lowIndex = index;
                }
                boolean reversed = candle.high() >= lowPrice + reversalAmount(candles, lowIndex, sensitivity);
                boolean lowBeforeHigh = lowIndex < index || closesInUpperHalf(candle);
                if (reversed && lowBeforeHigh) {
                    appendPivot(pivots, new Pivot(lowIndex, PivotType.LOW, lowPrice));
                    direction = SwingDirection.UP;
                    highIndex = index;
                    highPrice = candle.high();
                }
            }
        }
        return List.copyOf(pivots);
    }

    private void appendPivot(List<Pivot> pivots, Pivot candidate) {
        if (pivots.isEmpty()) {
            pivots.add(candidate);
            return;
        }
        Pivot previous = pivots.getLast();
        if (previous.index() == candidate.index() && previous.type() != candidate.type()) {
            return;
        }
        if (previous.type() != candidate.type()) {
            pivots.add(candidate);
            return;
        }
        boolean moreExtreme = candidate.type() == PivotType.HIGH
                ? candidate.price() > previous.price()
                : candidate.price() < previous.price();
        if (moreExtreme) {
            pivots.set(pivots.size() - 1, candidate);
        }
    }

    private boolean closesInUpperHalf(EnrichedCandle candle) {
        return candle.close() >= candle.low() + (candle.high() - candle.low()) * 0.5;
    }

    private boolean closesInLowerHalf(EnrichedCandle candle) {
        return candle.close() <= candle.low() + (candle.high() - candle.low()) * 0.5;
    }

    private double reversalAmount(List<EnrichedCandle> candles, int index, double sensitivity) {
        EnrichedCandle candle = candles.get(index);
        double volatility = isAvailable(candle.atr()) && candle.atr() > 0.0
                ? candle.atr()
                : averageTrueRange(candles, Math.max(0, index - 13), index);
        double percentageFloor = Math.abs(candle.close())
                * detectionRules.reversalFloorFraction() * sensitivity;
        return Math.max(volatility * sensitivity, percentageFloor);
    }

    private double averageTrueRange(List<EnrichedCandle> candles, int startInclusive, int endInclusive) {
        if (candles.isEmpty()) {
            return 0.000001;
        }
        int from = Math.max(0, startInclusive);
        int to = Math.min(candles.size() - 1, Math.max(from, endInclusive));
        double total = 0.0;
        int count = 0;
        for (int index = from; index <= to; index++) {
            EnrichedCandle candle = candles.get(index);
            if (isAvailable(candle.atr()) && candle.atr() > 0.0) {
                total += candle.atr();
            } else {
                double trueRange = candle.high() - candle.low();
                if (index > 0) {
                    double previousClose = candles.get(index - 1).close();
                    trueRange = Math.max(trueRange, Math.abs(candle.high() - previousClose));
                    trueRange = Math.max(trueRange, Math.abs(candle.low() - previousClose));
                }
                total += Math.max(trueRange, 0.000001);
            }
            count++;
        }
        return count == 0 ? 0.000001 : Math.max(total / count, 0.000001);
    }

    private List<Pivot> lastAlternating(List<Pivot> pivots, PivotType... types) {
        if (pivots.size() < types.length) {
            return List.of();
        }

        for (int start = pivots.size() - types.length; start >= 0; start--) {
            boolean matches = true;
            for (int offset = 0; offset < types.length; offset++) {
                if (pivots.get(start + offset).type() != types[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return List.copyOf(pivots.subList(start, start + types.length));
            }
        }
        return List.of();
    }

    private DetectedSignal signal(CandlePattern pattern,
                                  TradeSignal tradeSignal,
                                  EnrichedCandle current,
                                  WaveEvidence evidence,
                                  List<EnrichedCandle> candles,
                                  List<Pivot> pivots,
                                  CorrectionMetrics correction) {
        int eligibilityScore = clampScore(evidence.score());
        boolean actionableEnding = pattern.name().endsWith("WAVE_V_END")
                || pattern.name().endsWith("CORRECTION");
        V2Score categoryBreakdown = actionableEnding
                ? v2Score(pattern, tradeSignal, candles, pivots, correction, eligibilityScore)
                : null;
        V2Score v2 = categoryBreakdown != null && scoringModel == ScoringModel.V2
                ? categoryBreakdown
                : new V2Score(eligibilityScore, List.copyOf(evidence.reasons()));
        List<String> reasons = new ArrayList<>(v2.reasons());
        if (scoringModel == ScoringModel.V2) {
            reasons.add("V1 detection eligibility: " + eligibilityScore
                    + "/100 (audit only; it selected and qualified the wave but is not part of the V2 total)");
        } else if (categoryBreakdown != null) {
            // Keep V1 as the detector/audit score while persisting category totals that
            // the account-level display profile can safely reweight later.
            reasons.addAll(categoryBreakdown.reasons());
        }

        return new DetectedSignal(
                pattern,
                tradeSignal,
                classifyStrength(v2.score()),
                v2.score(),
                reasons,
                current.timestamp(),
                current.close(),
                eligibilityScore
        );
    }

    private List<Pivot> withPivot(List<Pivot> pivots, Pivot pivot) {
        List<Pivot> result = new ArrayList<>(pivots);
        result.add(pivot);
        return List.copyOf(result);
    }

    private V2Score v2Score(CandlePattern pattern,
                            TradeSignal tradeSignal,
                            List<EnrichedCandle> candles,
                            List<Pivot> pivots,
                            CorrectionMetrics correction,
                            int eligibilityScore) {
        boolean correctionEnd = pattern.name().endsWith("CORRECTION");
        boolean waveVEnd = pattern.name().endsWith("WAVE_V_END");
        boolean bullishImpulse = pivots.getFirst().type() == PivotType.LOW;
        List<String> reasons = new ArrayList<>();

        CategoryScore structure = structuralV2(pivots, correctionEnd, pattern, eligibilityScore);
        reasons.add(structure.reason("Structural / pivot quality"));
        CategoryScore proportions = proportionV2(pivots, correctionEnd, correction);
        reasons.add(proportions.reason("Fibonacci / proportion / alternation"));
        CategoryScore momentum = momentumV2(candles, pivots, tradeSignal, waveVEnd, bullishImpulse);
        reasons.add(momentum.reason("Momentum / divergence"));
        CategoryScore confirmation = confirmationV2(candles, tradeSignal);
        reasons.add(confirmation.reason("Stage-specific confirmation"));
        CategoryScore levels = levelsV2(candles, pivots, tradeSignal, waveVEnd);
        reasons.add(levels.reason("Support / resistance / trend context"));
        CategoryScore volume = volumeV2(candles, pivots, waveVEnd);
        reasons.add(volume.reason("Volume confirmation"));
        CategoryScore timing = timingV2(pivots, correctionEnd);
        reasons.add(timing.reason("Timing / count stability"));

        int score = structure.earned() + proportions.earned() + momentum.earned()
                + confirmation.earned() + levels.earned() + volume.earned() + timing.earned();
        return new V2Score(clampScore(score), List.copyOf(reasons));
    }

    private CategoryScore structuralV2(List<Pivot> pivots,
                                       boolean correctionEnd,
                                       CandlePattern pattern,
                                       int eligibilityScore) {
        int points = correctionEnd ? 6 : 18;
        List<String> details = new ArrayList<>();
        details.add("all mandatory Elliott rules passed (+" + (correctionEnd ? 6 : 18)
                + "/" + (correctionEnd ? 6 : 18) + ")");
        double wave1 = Math.abs(pivots.get(1).price() - pivots.get(0).price());
        double wave3 = Math.abs(pivots.get(3).price() - pivots.get(2).price());
        if (correctionEnd) {
            int prior = Math.max(0, Math.min(16,
                    (int) Math.round((eligibilityScore - detectionRules.minimumSignalConfidence())
                            * 16.0 / 25.0)));
            points += prior;
            details.add("frozen V1 qualification retained as a structural prior (+" + prior + "/16)");
            if (wave3 >= wave1) {
                points += 2;
                details.add("wave III is at least as long as wave I (+2)");
            }
            points += 3;
            details.add("distinct A, B and C pivots completed (+3)");
            if (!pattern.name().contains("EXPANDED_FLAT") && !pattern.name().contains("RUNNING_FLAT")) {
                points += 1;
                details.add("standard correction geometry (+1)");
            }
            if (pivots.get(5).index() - pivots.get(0).index()
                    >= detectionRules.minimumImpulseSpanCandles()) {
                points += 2;
                details.add("motive structure has sufficient span (+2)");
            }
        } else {
            if (wave3 >= wave1) {
                points += 5;
                details.add("wave III is at least as long as wave I (+5)");
            }
            if (!pattern.name().contains("TRUNCATED")) {
                points += 3;
                details.add("wave V exceeded wave III (+3)");
            }
            if (pivots.get(5).index() - pivots.get(0).index()
                    >= detectionRules.minimumImpulseSpanCandles()) {
                points += 2;
                details.add("five-wave structure has sufficient span (+2)");
            }
            if (minimumLegSpan(pivots, 5) >= detectionRules.minimumLegSpanCandles()) {
                points += 2;
                details.add("every motive leg has stable spacing (+2)");
            }
        }
        return new CategoryScore(Math.min(30, points), 30, String.join("; ", details));
    }

    private CategoryScore proportionV2(List<Pivot> pivots,
                                       boolean correctionEnd,
                                       CorrectionMetrics correction) {
        double wave1 = Math.abs(pivots.get(1).price() - pivots.get(0).price());
        double wave3 = Math.abs(pivots.get(3).price() - pivots.get(2).price());
        double wave2 = safeRatio(Math.abs(pivots.get(1).price() - pivots.get(2).price()), wave1);
        double wave4 = safeRatio(Math.abs(pivots.get(3).price() - pivots.get(4).price()), wave3);
        int points = 0;
        List<String> details = new ArrayList<>();
        if (correctionEnd) {
            points += zonePoints(correction.retracement(), detectionRules.correctionCommonMinRetracement(),
                    detectionRules.correctionCommonMaxRetracement(),
                    detectionRules.correctionPreferredMinRetracement(),
                    detectionRules.correctionPreferredMaxRetracement(), 6, 4, "ABC retracement", details);
            points += zonePoints(correction.waveCToARatio(), detectionRules.waveCCommonMinRatio(),
                    detectionRules.waveCCommonMaxRatio(), detectionRules.waveCPreferredMinRatio(),
                    detectionRules.waveCPreferredMaxRatio(), 6, 4, "wave C versus A", details);
            points += zonePoints(wave2, detectionRules.waveTwoCommonMinRetracement(),
                    detectionRules.waveTwoCommonMaxRetracement(),
                    detectionRules.waveTwoPreferredMinRetracement(),
                    detectionRules.waveTwoPreferredMaxRetracement(),
                    3, 2, "wave II", details);
            points += zonePoints(wave4, detectionRules.waveFourCommonMinRetracement(),
                    detectionRules.waveFourCommonMaxRetracement(),
                    detectionRules.waveFourPreferredMinRetracement(),
                    detectionRules.waveFourPreferredMaxRetracement(),
                    3, 2, "wave IV", details);
            if (Math.abs(wave2 - wave4) >= 0.12) {
                points += 2;
                details.add("waves II and IV show alternation (+2)");
            }
        } else {
            points += zonePoints(wave2, detectionRules.waveTwoCommonMinRetracement(),
                    detectionRules.waveTwoCommonMaxRetracement(),
                    detectionRules.waveTwoPreferredMinRetracement(),
                    detectionRules.waveTwoPreferredMaxRetracement(),
                    5, 3, "wave II", details);
            points += zonePoints(wave4, detectionRules.waveFourCommonMinRetracement(),
                    detectionRules.waveFourCommonMaxRetracement(),
                    detectionRules.waveFourPreferredMinRetracement(),
                    detectionRules.waveFourPreferredMaxRetracement(),
                    5, 3, "wave IV", details);
            if (Math.abs(wave2 - wave4) >= 0.12) {
                points += 4;
                details.add("waves II and IV show alternation (+4)");
            } else {
                points += 2;
                details.add("waves II and IV have limited alternation (+2)");
            }
            double waveThreeRatio = safeRatio(wave3, wave1);
            if (between(waveThreeRatio, 1.0, 2.618)) {
                points += 3;
                details.add("wave III proportion is typical (+3)");
            }
            double wave5 = Math.abs(pivots.get(5).price() - pivots.get(4).price());
            if (between(safeRatio(wave5, wave1), 0.382, 2.618)) {
                points += 3;
                details.add("wave V proportion is plausible (+3)");
            }
        }
        return new CategoryScore(Math.min(20, points), 20,
                details.isEmpty() ? "no common proportion zones were met" : String.join("; ", details));
    }

    private int zonePoints(double value,
                           double commonMin,
                           double commonMax,
                           double normalMin,
                           double normalMax,
                           int commonPoints,
                           int normalPoints,
                           String label,
                           List<String> details) {
        if (between(value, commonMin, commonMax)) {
            details.add(label + " is in its common zone (+" + commonPoints + ")");
            return commonPoints;
        }
        if (between(value, normalMin, normalMax)) {
            details.add(label + " is within normal bounds (+" + normalPoints + ")");
            return normalPoints;
        }
        return 0;
    }

    private CategoryScore momentumV2(List<EnrichedCandle> candles,
                                     List<Pivot> pivots,
                                     TradeSignal tradeSignal,
                                     boolean waveVEnd,
                                     boolean bullishImpulse) {
        int points = 0;
        List<String> details = new ArrayList<>();
        Pivot comparison = waveVEnd ? pivots.get(3) : pivots.get(6);
        Pivot terminal = pivots.getLast();
        EnrichedCandle earlier = candles.get(comparison.index());
        EnrichedCandle later = candles.get(terminal.index());
        boolean terminalIsHigh = terminal.type() == PivotType.HIGH;
        if (diverges(earlier.rsi(), later.rsi(), terminalIsHigh)) {
            points += waveVEnd ? 6 : 5;
            details.add("RSI divergence appears at the terminal pivot (+" + (waveVEnd ? 6 : 5) + ")");
        }
        if (diverges(earlier.macdHistogram(), later.macdHistogram(), terminalIsHigh)) {
            points += waveVEnd ? 5 : 4;
            details.add("MACD histogram divergence appears at the terminal pivot (+" + (waveVEnd ? 5 : 4) + ")");
        }
        EnrichedCandle current = candles.getLast();
        EnrichedCandle previous = candles.get(candles.size() - 2);
        if (movesWithSignal(previous.rsi(), current.rsi(), tradeSignal)) {
            points += 2;
            details.add("RSI turned with the signal (+2)");
        }
        if (movesWithSignal(previous.macdHistogram(), current.macdHistogram(), tradeSignal)) {
            points += 2;
            details.add("MACD histogram turned with the signal (+2)");
        }
        if (!waveVEnd && isAvailable(current.rsi())
                && (tradeSignal == TradeSignal.BUY ? current.rsi() < 70 : current.rsi() > 30)) {
            points += 2;
            details.add("reversal is not momentum-exhausted (+2)");
        }
        return new CategoryScore(Math.min(15, points), 15,
                details.isEmpty() ? "no momentum divergence or turn was confirmed" : String.join("; ", details));
    }

    private boolean diverges(double earlier, double later, boolean terminalIsHigh) {
        return isAvailable(earlier) && isAvailable(later)
                && (terminalIsHigh ? later < earlier : later > earlier);
    }

    private boolean movesWithSignal(double previous, double current, TradeSignal signal) {
        return isAvailable(previous) && isAvailable(current)
                && (signal == TradeSignal.BUY ? current > previous : current < previous);
    }

    private CategoryScore confirmationV2(List<EnrichedCandle> candles, TradeSignal signal) {
        EnrichedCandle current = candles.getLast();
        EnrichedCandle previous = candles.get(candles.size() - 2);
        int points = 7;
        List<String> details = new ArrayList<>();
        details.add("close broke the prior candle extreme (+7)");
        if (signal == TradeSignal.BUY ? current.close() > current.open() : current.close() < current.open()) {
            points += 3;
            details.add("confirmation candle closed in the signal direction (+3)");
        }
        double range = current.high() - current.low();
        if (range > 0.0) {
            double closeLocation = (current.close() - current.low()) / range;
            if (signal == TradeSignal.BUY ? closeLocation >= 0.65 : closeLocation <= 0.35) {
                points += 3;
                details.add("close finished decisively within its range (+3)");
            }
        }
        if (isAvailable(current.fastEma())
                && (signal == TradeSignal.BUY ? current.close() > current.fastEma() : current.close() < current.fastEma())) {
            points += 2;
            details.add("close confirmed through the fast EMA (+2)");
        }
        return new CategoryScore(Math.min(15, points), 15, String.join("; ", details));
    }

    private CategoryScore levelsV2(List<EnrichedCandle> candles,
                                   List<Pivot> pivots,
                                   TradeSignal signal,
                                   boolean waveVEnd) {
        EnrichedCandle current = candles.getLast();
        EnrichedCandle terminal = candles.get(pivots.getLast().index());
        int points = 0;
        List<String> details = new ArrayList<>();
        if (waveVEnd) {
            if (isAvailable(terminal.upperBollinger()) && isAvailable(terminal.lowerBollinger())
                    && (signal == TradeSignal.SELL ? terminal.high() >= terminal.upperBollinger()
                    : terminal.low() <= terminal.lowerBollinger())) {
                points += 4;
                details.add("wave V tested an outer Bollinger band (+4)");
            }
            if (isAvailable(current.upperBollinger()) && isAvailable(current.lowerBollinger())
                    && current.close() < current.upperBollinger() && current.close() > current.lowerBollinger()) {
                points += 2;
                details.add("confirmation closed back inside the bands (+2)");
            }
            if (isAvailable(terminal.longSma()) && isAvailable(terminal.atr()) && terminal.atr() > 0
                    && Math.abs(terminal.close() - terminal.longSma()) >= terminal.atr() * 2.0) {
                points += 2;
                details.add("terminal price was extended from the long trend (+2)");
            }
        } else {
            if (isAvailable(current.fastEma())
                    && (signal == TradeSignal.BUY ? current.close() > current.fastEma() : current.close() < current.fastEma())) {
                points += 3;
                details.add("close aligned with the fast EMA (+3)");
            }
            if (isAvailable(current.slowEma())
                    && (signal == TradeSignal.BUY ? current.close() > current.slowEma() : current.close() < current.slowEma())) {
                points += 3;
                details.add("close aligned with the slow EMA (+3)");
            }
            if (isAvailable(terminal.upperBollinger()) && isAvailable(terminal.lowerBollinger())
                    && (signal == TradeSignal.BUY ? terminal.low() <= terminal.lowerBollinger()
                    : terminal.high() >= terminal.upperBollinger())) {
                points += 2;
                details.add("wave C tested an outer volatility level (+2)");
            }
        }
        if (isAvailable(current.rollingVwap())
                && (signal == TradeSignal.BUY ? current.close() > current.rollingVwap()
                : current.close() < current.rollingVwap())) {
            points += 2;
            details.add("close confirmed on the signal side of rolling VWAP (+2)");
        }
        return new CategoryScore(Math.min(10, points), 10,
                details.isEmpty() ? "no additional level confluence was present" : String.join("; ", details));
    }

    private CategoryScore volumeV2(List<EnrichedCandle> candles,
                                   List<Pivot> pivots,
                                   boolean waveVEnd) {
        EnrichedCandle current = candles.getLast();
        EnrichedCandle terminal = candles.get(pivots.getLast().index());
        EnrichedCandle comparison = candles.get((waveVEnd ? pivots.get(3) : pivots.get(6)).index());
        int points = 0;
        List<String> details = new ArrayList<>();
        if (isAvailable(comparison.volume()) && terminal.volume() <= comparison.volume()) {
            points += waveVEnd ? 3 : 2;
            details.add("terminal-leg volume did not expand versus the comparison pivot (+"
                    + (waveVEnd ? 3 : 2) + ")");
        }
        if (isAvailable(current.averageVolume()) && current.averageVolume() > 0.0
                && current.volume() >= current.averageVolume() * 1.2) {
            points += waveVEnd ? 2 : 3;
            details.add("confirmation volume was at least 1.2x average (+" + (waveVEnd ? 2 : 3) + ")");
        }
        return new CategoryScore(Math.min(5, points), 5,
                details.isEmpty() ? "volume supplied no additional confirmation" : String.join("; ", details));
    }

    private CategoryScore timingV2(List<Pivot> pivots, boolean correctionEnd) {
        int points = 0;
        List<String> details = new ArrayList<>();
        if (pivots.get(5).index() - pivots.get(0).index()
                >= detectionRules.minimumImpulseSpanCandles()) {
            points += 3;
            details.add("motive count spans enough candles (+3)");
        }
        int lastLeg = correctionEnd ? 8 : 5;
        if (minimumLegSpan(pivots, lastLeg) >= detectionRules.minimumLegSpanCandles()) {
            points += 2;
            details.add("terminal count has no compressed one-candle leg (+2)");
        }
        return new CategoryScore(points, 5,
                details.isEmpty() ? "the count is temporally compressed" : String.join("; ", details));
    }

    private int minimumLegSpan(List<Pivot> pivots, int lastIndex) {
        int minimum = Integer.MAX_VALUE;
        for (int index = 1; index <= lastIndex; index++) {
            minimum = Math.min(minimum, pivots.get(index).index() - pivots.get(index - 1).index());
        }
        return minimum;
    }

    private SignalStength classifyStrength(int confidenceScore) {
        if (confidenceScore < detectionRules.minimumSignalConfidence()) {
            return SignalStength.LOW_CONFIDENCE;
        }
        return confidenceScore >= HIGH_CONFIDENCE
                ? SignalStength.HIGH_CONFIDENCE
                : SignalStength.MEDIUM_CONFIDENCE;
    }

    private boolean emaRising(List<EnrichedCandle> candles) {
        if (candles.size() < 4) {
            return false;
        }
        EnrichedCandle current = candles.get(candles.size() - 1);
        EnrichedCandle previous = candles.get(candles.size() - 4);
        return isAvailable(current.fastEma())
                && isAvailable(previous.fastEma())
                && current.fastEma() > previous.fastEma();
    }

    private boolean emaFalling(List<EnrichedCandle> candles) {
        if (candles.size() < 4) {
            return false;
        }
        EnrichedCandle current = candles.get(candles.size() - 1);
        EnrichedCandle previous = candles.get(candles.size() - 4);
        return isAvailable(current.fastEma())
                && isAvailable(previous.fastEma())
                && current.fastEma() < previous.fastEma();
    }

    private boolean isVolumeSurge(EnrichedCandle candle) {
        return isAvailable(candle.averageVolume()) && candle.volume() > candle.averageVolume() * 1.2;
    }

    private boolean hasCompleteData(EnrichedCandle candle) {
        return candle != null
                && candle.timestamp() != null
                && isAvailable(candle.open())
                && isAvailable(candle.high())
                && isAvailable(candle.low())
                && isAvailable(candle.close());
    }

    private boolean isAvailable(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private boolean between(double value, double minInclusive, double maxInclusive) {
        return isAvailable(value) && value >= minInclusive && value <= maxInclusive;
    }

    private double safeRatio(double numerator, double denominator) {
        if (Math.abs(denominator) < 0.000001) {
            return Double.NaN;
        }
        return numerator / denominator;
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(score, 100));
    }

    private enum PivotType {
        HIGH,
        LOW
    }

    enum ScoringModel {
        V1,
        V2
    }

    private enum SwingDirection {
        UNKNOWN,
        UP,
        DOWN
    }

    private record Pivot(int index, PivotType type, double price) {
    }

    private record DevelopmentPivotSet(int scaleRank, double sensitivity, List<Pivot> pivots) {
        private DevelopmentPivotSet {
            pivots = List.copyOf(pivots);
        }
    }

    private record PivotSupport(Pivot pivot, java.util.NavigableSet<Integer> scaleRanks) {
    }

    private record RankedDevelopingImpulse(DevelopingImpulse candidate,
                                           int degreeCoherence,
                                           String parentPath) {
    }

    private enum SubdivisionKind {
        MOTIVE("Motive 1-2-3-4-5"),
        DIAGONAL("Motive diagonal 1-2-3-4-5"),
        ZIGZAG("Corrective zigzag A-B-C"),
        FLAT("Corrective flat A-B-C"),
        DOUBLE_ZIGZAG("Double zigzag W-X-Y"),
        DOUBLE_THREE("Double-three combination W-X-Y"),
        TRIPLE_ZIGZAG("Triple zigzag W-X-Y-X-Z"),
        TRIPLE_THREE("Triple-three combination W-X-Y-X-Z"),
        TRIANGLE("Contracting triangle A-B-C-D-E");

        private final String displayName;

        SubdivisionKind(String displayName) {
            this.displayName = displayName;
        }
    }

    private enum CorrectionComponentType {
        NONE,
        ZIGZAG,
        FLAT
    }

    private record SubdivisionCandidate(SubdivisionKind kind,
                                        int confidence,
                                        List<Pivot> pivots,
                                        List<String> evidence) {
    }

    public record DetectionRules(
            int minimumCandles,
            int minimumSignalConfidence,
            int minimumStructureQuality,
            int maximumConfirmationLagCandles,
            int minimumImpulseSpanCandles,
            int minimumLegSpanCandles,
            double breakoutBufferFraction,
            double reversalFloorFraction,
            List<Double> pivotSensitivities,
            double maximumStructureOverlapFraction,
            double waveTwoMaximumRetracement,
            double waveTwoPreferredMinRetracement,
            double waveTwoPreferredMaxRetracement,
            double waveTwoCommonMinRetracement,
            double waveTwoCommonMaxRetracement,
            double preliminaryWaveThreeMinRatio,
            double waveFourMaximumRetracement,
            double waveFourPreferredMinRetracement,
            double waveFourPreferredMaxRetracement,
            double waveFourCommonMinRetracement,
            double waveFourCommonMaxRetracement,
            boolean requireWaveFourNoOverlap,
            boolean requireWaveThreeNotShortest,
            boolean allowWaveOneLongest,
            boolean requireWaveOneShortest,
            boolean allowWaveFiveLongest,
            boolean allowTruncatedFifth,
            double correctionMaximumRetracement,
            double waveBMaximumRecovery,
            double correctionPreferredMinRetracement,
            double correctionPreferredMaxRetracement,
            double correctionCommonMinRetracement,
            double correctionCommonMaxRetracement,
            double waveCPreferredMinRatio,
            double waveCPreferredMaxRatio,
            double waveCCommonMinRatio,
            double waveCCommonMaxRatio,
            boolean requireWaveAWithinOrigin,
            boolean allowStandardCorrection,
            boolean allowExpandedFlat,
            boolean allowRunningFlat,
            boolean allowTriangles,
            boolean limitWaveCToARatio,
            double waveCAllowedMinRatio,
            double waveCAllowedMaxRatio) {
        public DetectionRules {
            pivotSensitivities = pivotSensitivities == null
                    ? List.of(.75, 1.25, 2.0, 3.0) : List.copyOf(pivotSensitivities);
        }

        public static DetectionRules factory() {
            return new DetectionRules(
                    MIN_CANDLES, MIN_CONFIDENCE, MIN_STRUCTURE_QUALITY, MAX_CONFIRMATION_LAG_CANDLES,
                    MIN_IMPULSE_SPAN_CANDLES, MIN_LEG_SPAN_CANDLES, BREAKOUT_BUFFER - 1.0,
                    .0125, java.util.Arrays.stream(PIVOT_SENSITIVITIES).boxed().toList(), .40,
                    MAX_WAVE_TWO_RETRACEMENT, NORMAL_WAVE_TWO_MIN_RETRACEMENT,
                    NORMAL_WAVE_TWO_MAX_RETRACEMENT, COMMON_WAVE_TWO_MIN_RETRACEMENT,
                    COMMON_WAVE_TWO_MAX_RETRACEMENT, PRELIMINARY_WAVE_THREE_MIN_RATIO,
                    MAX_WAVE_FOUR_RETRACEMENT, NORMAL_WAVE_FOUR_MIN_RETRACEMENT,
                    NORMAL_WAVE_FOUR_MAX_RETRACEMENT, COMMON_WAVE_FOUR_MIN_RETRACEMENT,
                    COMMON_WAVE_FOUR_MAX_RETRACEMENT, true, true, true, false, true, true,
                    MAX_CONTINUATION_CORRECTION_RETRACEMENT, MAX_WAVE_B_RELATIVE_RECOVERY,
                    NORMAL_CORRECTION_MIN_RETRACEMENT, NORMAL_CORRECTION_MAX_RETRACEMENT,
                    COMMON_CORRECTION_MIN_RETRACEMENT, COMMON_CORRECTION_MAX_RETRACEMENT,
                    NORMAL_WAVE_C_TO_A_MIN_RATIO, NORMAL_WAVE_C_TO_A_MAX_RATIO,
                    COMMON_WAVE_C_TO_A_MIN_RATIO, COMMON_WAVE_C_TO_A_MAX_RATIO,
                    true, true, true, true, true, false, .10, 5.0);
        }

        public DetectionRules withAllowWaveOneLongest(boolean allow) {
            return new DetectionRules(
                    minimumCandles, minimumSignalConfidence, minimumStructureQuality,
                    maximumConfirmationLagCandles, minimumImpulseSpanCandles, minimumLegSpanCandles,
                    breakoutBufferFraction, reversalFloorFraction, pivotSensitivities,
                    maximumStructureOverlapFraction, waveTwoMaximumRetracement,
                    waveTwoPreferredMinRetracement, waveTwoPreferredMaxRetracement,
                    waveTwoCommonMinRetracement, waveTwoCommonMaxRetracement,
                    preliminaryWaveThreeMinRatio, waveFourMaximumRetracement,
                    waveFourPreferredMinRetracement, waveFourPreferredMaxRetracement,
                    waveFourCommonMinRetracement, waveFourCommonMaxRetracement,
                    requireWaveFourNoOverlap, requireWaveThreeNotShortest, allow,
                    requireWaveOneShortest,
                    allowWaveFiveLongest, allowTruncatedFifth, correctionMaximumRetracement,
                    waveBMaximumRecovery, correctionPreferredMinRetracement,
                    correctionPreferredMaxRetracement, correctionCommonMinRetracement,
                    correctionCommonMaxRetracement, waveCPreferredMinRatio,
                    waveCPreferredMaxRatio, waveCCommonMinRatio, waveCCommonMaxRatio,
                    requireWaveAWithinOrigin, allowStandardCorrection, allowExpandedFlat,
                    allowRunningFlat, allowTriangles, limitWaveCToARatio,
                    waveCAllowedMinRatio, waveCAllowedMaxRatio);
        }

        public DetectionRules withRequireWaveOneShortest(boolean required) {
            return new DetectionRules(
                    minimumCandles, minimumSignalConfidence, minimumStructureQuality,
                    maximumConfirmationLagCandles, minimumImpulseSpanCandles, minimumLegSpanCandles,
                    breakoutBufferFraction, reversalFloorFraction, pivotSensitivities,
                    maximumStructureOverlapFraction, waveTwoMaximumRetracement,
                    waveTwoPreferredMinRetracement, waveTwoPreferredMaxRetracement,
                    waveTwoCommonMinRetracement, waveTwoCommonMaxRetracement,
                    preliminaryWaveThreeMinRatio, waveFourMaximumRetracement,
                    waveFourPreferredMinRetracement, waveFourPreferredMaxRetracement,
                    waveFourCommonMinRetracement, waveFourCommonMaxRetracement,
                    requireWaveFourNoOverlap, requireWaveThreeNotShortest, allowWaveOneLongest,
                    required, allowWaveFiveLongest, allowTruncatedFifth, correctionMaximumRetracement,
                    waveBMaximumRecovery, correctionPreferredMinRetracement,
                    correctionPreferredMaxRetracement, correctionCommonMinRetracement,
                    correctionCommonMaxRetracement, waveCPreferredMinRatio,
                    waveCPreferredMaxRatio, waveCCommonMinRatio, waveCCommonMaxRatio,
                    requireWaveAWithinOrigin, allowStandardCorrection, allowExpandedFlat,
                    allowRunningFlat, allowTriangles, limitWaveCToARatio,
                    waveCAllowedMinRatio, waveCAllowedMaxRatio);
        }
    }

    public enum ImpulseVariant {
        STANDARD,
        TRUNCATED_FIFTH
    }

    public enum CorrectionVariant {
        NONE,
        STANDARD,
        EXPANDED_FLAT,
        RUNNING_FLAT
    }

    private record CorrectionMetrics(double retracement,
                                     double waveCToARatio,
                                     CorrectionVariant variant) {
    }

    private record V2Score(int score, List<String> reasons) {
    }

    private record CategoryScore(int earned, int maximum, String details) {
        private String reason(String category) {
            return category + " +" + earned + "/" + maximum + ": " + details;
        }
    }

    public record ElliottWavePoint(String label, Long timestamp, double price, String pivotType) {
    }

    public record ElliottSubdivision(String structureLabel,
                                     int confidence,
                                     boolean validated,
                                     List<ElliottWavePoint> points,
                                     List<String> evidence,
                                     List<ElliottSubdivisionAlternative> alternatives) {
    }

    public record ElliottSubdivisionAlternative(String structureLabel,
                                                int confidence,
                                                List<ElliottWavePoint> points) {
    }

    public record ElliottScoreAssessment(int score, List<String> reasons) {
        public ElliottScoreAssessment {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public record DevelopingImpulse(
            String developmentKey,
            String direction,
            ElliottSignalStage stage,
            CandlePattern pattern,
            TradeSignal expectedMove,
            long confirmationTimestamp,
            double confirmationClose,
            double endpointPrice,
            double stopLossPrice,
            double targetPrice,
            String forecastLabel,
            String correctionType,
            int confidenceScore,
            List<ElliottWavePoint> points,
            List<String> evidence,
            ElliottWaveStructure completedStructure) {
        public DevelopingImpulse {
            points = points == null ? List.of() : List.copyOf(points);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record DevelopingInvalidation(long timestamp, double closePrice, String reason) {
    }

    public static final class DevelopingScanContext {
        private final Map<String, java.util.Optional<ElliottSubdivision>> subdivisionCache =
                new java.util.HashMap<>();

        public int cachedLegCount() {
            return subdivisionCache.size();
        }

        public void clear() {
            subdivisionCache.clear();
        }
    }

    public record ElliottWaveStructure(String direction,
                                       boolean correctionComplete,
                                       List<ElliottWavePoint> points,
                                       Long confirmationTimestamp,
                                       int qualityScore,
                                       double waveTwoRetracement,
                                       boolean deepWaveTwo,
                                       double waveThreeToOneRatio,
                                       double waveFourRetracement,
                                       ImpulseVariant impulseVariant,
                                       CorrectionVariant correctionVariant,
                                       double correctionRetracement,
                                       double waveCToARatio,
                                       List<String> qualityWarnings) {
    }

    private record StructureCandidate(int completionIndex, ElliottWaveStructure structure) {
    }

    private static final class WaveEvidence {
        private int score;
        private final List<String> reasons;

        private WaveEvidence(int score, List<String> reasons) {
            this.score = score;
            this.reasons = reasons;
        }

        private void add(int points, String reason) {
            reasons.add(reason);
            score = clamp(score + points);
        }

        private int score() {
            return score;
        }

        private List<String> reasons() {
            return reasons;
        }

        private static int clamp(int value) {
            return Math.max(0, Math.min(value, 100));
        }
    }
}
