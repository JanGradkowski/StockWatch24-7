package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
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
    private static final int MIN_CANDLES = 34;
    private static final int MIN_CONFIDENCE = 75;
    private static final int HIGH_CONFIDENCE = 85;
    private static final double BREAKOUT_BUFFER = 1.003;
    private static final int DEFAULT_PRESENT_SIGNAL_LOOKBACK_CANDLES = 1;
    private static final int MAX_CONFIRMATION_LAG_CANDLES = 3;
    private static final int MIN_IMPULSE_SPAN_CANDLES = 15;
    private static final int MIN_LEG_SPAN_CANDLES = 2;
    private static final int MIN_STRUCTURE_QUALITY = 68;
    private static final double[] PIVOT_SENSITIVITIES = {0.75, 1.25, 2.0, 3.0};
    private final int presentSignalLookbackCandles;

    public ElliottWaveDetectionService() {
        this(DEFAULT_PRESENT_SIGNAL_LOOKBACK_CANDLES);
    }

    ElliottWaveDetectionService(int presentSignalLookbackCandles) {
        this.presentSignalLookbackCandles = Math.max(0, presentSignalLookbackCandles);
    }

    public List<DetectedSignal> detect(List<EnrichedCandle> recentCandles) {
        if (recentCandles == null || recentCandles.size() < MIN_CANDLES) {
            return List.of();
        }

        List<EnrichedCandle> candles = recentCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        if (candles.size() < MIN_CANDLES) {
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

    private void mergeSignal(Map<String, DetectedSignal> bestSignals, DetectedSignal candidate) {
        String key = candidate.pattern().name() + ':' + candidate.tradeSignal().name();
        DetectedSignal existing = bestSignals.get(key);
        if (existing == null || candidate.confidenceScore() > existing.confidenceScore()) {
            bestSignals.put(key, candidate);
        }
    }

    public java.util.Optional<ElliottWaveStructure> findLatestWaveStructure(List<EnrichedCandle> recentCandles) {
        if (recentCandles == null || recentCandles.size() < MIN_CANDLES) {
            return java.util.Optional.empty();
        }
        List<EnrichedCandle> candles = recentCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        if (candles.size() < MIN_CANDLES) {
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
                .filter(candidate -> candidate.structure().qualityScore() >= MIN_STRUCTURE_QUALITY)
                .max(Comparator.comparingInt(StructureCandidate::completionIndex)
                        .thenComparingInt(candidate -> candidate.structure().qualityScore())
                        .thenComparingLong(candidate -> structureSpan(candidate.structure())))
                .map(StructureCandidate::structure);
    }

    public List<ElliottWaveStructure> findHistoricalWaveStructures(List<EnrichedCandle> historicalCandles) {
        if (historicalCandles == null || historicalCandles.size() < MIN_CANDLES) {
            return List.of();
        }
        List<EnrichedCandle> candles = historicalCandles.stream()
                .filter(this::hasCompleteData)
                .sorted(Comparator.comparing(EnrichedCandle::timestamp))
                .toList();
        if (candles.size() < MIN_CANDLES) {
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
                    .filter(structure -> structure.qualityScore() >= MIN_STRUCTURE_QUALITY)
                    .forEach(structure -> mergeHistoricalStructure(structuresByCycle, structure));
        }
        return selectNonOverlappingStructures(new ArrayList<>(structuresByCycle.values()));
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
        double breakoutLevel = wave3.price() * BREAKOUT_BUFFER;
        if (!isCurrentBullishBreakout(candles, wave4, breakoutLevel)) {
            return java.util.Optional.empty();
        }

        WaveEvidence evidence = bullishImpulseEvidence(candles, current, wave0, wave1, wave2, wave3, wave4);
        if (previous.close() > breakoutLevel) {
            evidence.reasons().add("breakout remains active on the latest candle with bullish follow-through");
        }
        return java.util.Optional.of(signal(CandlePattern.ELLIOTT_BULLISH_IMPULSE, TradeSignal.BUY, current, evidence));
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
        double breakdownLevel = wave3.price() / BREAKOUT_BUFFER;
        if (!isCurrentBearishBreakdown(candles, wave4, breakdownLevel)) {
            return java.util.Optional.empty();
        }

        WaveEvidence evidence = bearishImpulseEvidence(candles, current, wave0, wave1, wave2, wave3, wave4);
        if (previous.close() < breakdownLevel) {
            evidence.reasons().add("breakdown remains active on the latest candle with bearish follow-through");
        }
        return java.util.Optional.of(signal(CandlePattern.ELLIOTT_BEARISH_IMPULSE, TradeSignal.SELL, current, evidence));
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
        evidence.reasons().add("bullish wave V ended with a current bearish reversal below the previous candle low");
        return java.util.Optional.of(signal(
                CandlePattern.ELLIOTT_BULLISH_WAVE_V_END, TradeSignal.SELL, current, evidence));
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
        evidence.reasons().add("bearish wave V ended with a current bullish reversal above the previous candle high");
        return java.util.Optional.of(signal(
                CandlePattern.ELLIOTT_BEARISH_WAVE_V_END, TradeSignal.BUY, current, evidence));
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
        double retracement = safeRatio(wave5.price() - waveC.price(), wave5.price() - wave0.price());

        if (!isCurrentBullishRebound(candles, waveC)) {
            return java.util.Optional.empty();
        }

        WaveEvidence evidence = correctionEvidence(current, true, retracement);
        evidence.reasons().add("bullish five-wave structure completed before distinct A, B and C correction legs");
        evidence.reasons().add("wave C ended with a current close back above the previous candle high");
        return java.util.Optional.of(signal(CandlePattern.ELLIOTT_BULLISH_CORRECTION, TradeSignal.BUY, current, evidence));
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
        double retracement = safeRatio(waveC.price() - wave5.price(), wave0.price() - wave5.price());

        if (!isCurrentBearishRejection(candles, waveC)) {
            return java.util.Optional.empty();
        }

        WaveEvidence evidence = correctionEvidence(current, false, retracement);
        evidence.reasons().add("bearish five-wave structure completed before distinct A, B and C correction legs");
        evidence.reasons().add("wave C ended with a current close back below the previous candle low");
        return java.util.Optional.of(signal(CandlePattern.ELLIOTT_BEARISH_CORRECTION, TradeSignal.SELL, current, evidence));
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
                && confirmationIndex - endpoint.index() <= MAX_CONFIRMATION_LAG_CANDLES;
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
        if (between(wave2Retracement, 0.236, 0.786)) {
            evidence.add(5, "wave 2 retracement is within normal Elliott bounds");
        }
        if (between(wave2Retracement, 0.382, 0.618)) {
            evidence.add(5, "wave 2 retracement is near the common Fibonacci zone");
        }
        if (wave3Length >= wave1Length) {
            evidence.add(10, "wave 3 is at least as large as wave 1");
        }
        if (between(wave4Retracement, 0.146, 0.618)) {
            evidence.add(5, "wave 4 retracement is within normal Elliott bounds");
        }
        if (between(wave4Retracement, 0.236, 0.5)) {
            evidence.add(5, "wave 4 retracement is near the common Fibonacci zone");
        }
    }

    private WaveEvidence correctionEvidence(EnrichedCandle current, boolean bullish, double retracement) {
        WaveEvidence evidence = new WaveEvidence(62, new ArrayList<>());
        if (between(retracement, 0.236, 0.786)) {
            evidence.add(8, "correction retracement is within normal Elliott bounds");
        }
        if (between(retracement, 0.382, 0.618)) {
            evidence.add(8, "correction retracement is near the common Fibonacci zone");
        }
        if (bullish) {
            if (isAvailable(current.ema20()) && current.close() > current.ema20()) {
                evidence.add(7, "close is above the 20-period EMA after the correction");
            }
            if (isAvailable(current.rsi14()) && current.rsi14() >= 40 && current.rsi14() <= 68) {
                evidence.add(5, "RSI supports a bullish rebound without extreme overbought pressure");
            }
        } else {
            if (isAvailable(current.ema20()) && current.close() < current.ema20()) {
                evidence.add(7, "close is below the 20-period EMA after the correction");
            }
            if (isAvailable(current.rsi14()) && current.rsi14() <= 60 && current.rsi14() >= 32) {
                evidence.add(5, "RSI supports a bearish continuation without extreme oversold pressure");
            }
        }
        if (isVolumeSurge(current)) {
            evidence.add(5, "volume is at least 20% above its 20-period average");
        }
        return evidence;
    }

    private void addBullishContext(WaveEvidence evidence, List<EnrichedCandle> candles, EnrichedCandle current) {
        if (isAvailable(current.ema20()) && current.close() > current.ema20()) {
            evidence.add(7, "close is above the 20-period EMA");
        }
        if (emaRising(candles)) {
            evidence.add(5, "20-period EMA is rising");
        }
        if (isAvailable(current.rsi14()) && current.rsi14() >= 45 && current.rsi14() <= 72) {
            evidence.add(5, "RSI confirms bullish momentum without extreme overbought pressure");
        }
        if (isVolumeSurge(current)) {
            evidence.add(5, "volume is at least 20% above its 20-period average");
        }
    }

    private void addBearishContext(WaveEvidence evidence, List<EnrichedCandle> candles, EnrichedCandle current) {
        if (isAvailable(current.ema20()) && current.close() < current.ema20()) {
            evidence.add(7, "close is below the 20-period EMA");
        }
        if (emaFalling(candles)) {
            evidence.add(5, "20-period EMA is falling");
        }
        if (isAvailable(current.rsi14()) && current.rsi14() <= 55 && current.rsi14() >= 28) {
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
                && wave4.price() > wave1.price()
                && wave1Length > 0.0
                && wave3Length >= wave1Length * 0.8
                && between(wave2Retracement, 0.236, 0.786)
                && between(wave4Retracement, 0.146, 0.618)
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
                && wave4.price() < wave1.price()
                && wave1Length > 0.0
                && wave3Length >= wave1Length * 0.8
                && between(wave2Retracement, 0.236, 0.786)
                && between(wave4Retracement, 0.146, 0.618)
                && hasValidImpulseTiming(wave0, wave1, wave2, wave3, wave4);
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
                && wave5.price() > wave3.price()
                && wave3Length >= Math.min(wave1Length, wave5Length)
                && wave5.index() - wave0.index() >= MIN_IMPULSE_SPAN_CANDLES
                && wave5.index() - wave4.index() >= MIN_LEG_SPAN_CANDLES;
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
                && wave5.price() < wave3.price()
                && wave3Length >= Math.min(wave1Length, wave5Length)
                && wave5.index() - wave0.index() >= MIN_IMPULSE_SPAN_CANDLES
                && wave5.index() - wave4.index() >= MIN_LEG_SPAN_CANDLES;
    }

    private boolean hasValidImpulseTiming(Pivot wave0, Pivot wave1, Pivot wave2, Pivot wave3, Pivot wave4) {
        return wave1.index() - wave0.index() >= MIN_LEG_SPAN_CANDLES
                && wave2.index() - wave1.index() >= MIN_LEG_SPAN_CANDLES
                && wave3.index() - wave2.index() >= MIN_LEG_SPAN_CANDLES
                && wave4.index() - wave3.index() >= MIN_LEG_SPAN_CANDLES;
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
        double retracement = safeRatio(wave5.price() - waveC.price(), wave5.price() - wave0.price());
        double waveALength = wave5.price() - waveA.price();
        double waveCLength = waveB.price() - waveC.price();
        return isBullishImpulseComplete(wave0, wave1, wave2, wave3, wave4, wave5)
                && waveA.price() < wave5.price()
                && waveA.price() > wave0.price()
                && waveB.price() > waveA.price()
                && waveB.price() < wave5.price() * 1.01
                && waveC.price() < waveB.price()
                && waveC.price() <= waveA.price() * 1.02
                && between(retracement, 0.236, 0.786)
                && between(safeRatio(waveCLength, waveALength), 0.5, 2.0)
                && waveA.index() - wave5.index() >= MIN_LEG_SPAN_CANDLES
                && waveB.index() - waveA.index() >= MIN_LEG_SPAN_CANDLES
                && waveC.index() - waveB.index() >= MIN_LEG_SPAN_CANDLES;
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
        double retracement = safeRatio(waveC.price() - wave5.price(), wave0.price() - wave5.price());
        double waveALength = waveA.price() - wave5.price();
        double waveCLength = waveC.price() - waveB.price();
        return isBearishImpulseComplete(wave0, wave1, wave2, wave3, wave4, wave5)
                && waveA.price() > wave5.price()
                && waveA.price() < wave0.price()
                && waveB.price() < waveA.price()
                && waveB.price() > wave5.price() / 1.01
                && waveC.price() > waveB.price()
                && waveC.price() >= waveA.price() * 0.98
                && between(retracement, 0.236, 0.786)
                && between(safeRatio(waveCLength, waveALength), 0.5, 2.0)
                && waveA.index() - wave5.index() >= MIN_LEG_SPAN_CANDLES
                && waveB.index() - waveA.index() >= MIN_LEG_SPAN_CANDLES
                && waveC.index() - waveB.index() >= MIN_LEG_SPAN_CANDLES;
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
        if (isBullishCorrectionComplete(complete) && isCurrentBullishRebound(candles, waveC)) {
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
        if (isBearishCorrectionComplete(complete) && isCurrentBearishRejection(candles, waveC)) {
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
                || !isCurrentBearishRejection(candles, wave5)) {
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
                || !isCurrentBullishRebound(candles, wave5)) {
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
                    .anyMatch(existing -> overlapRatio(candidate, existing) > 0.40);
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
        score += Math.min(8, Math.max(0, (impulseSpan - MIN_IMPULSE_SPAN_CANDLES) / 5));
        if (wave3Length >= wave1Length && wave3Length >= wave5Length) {
            score += 7;
        }
        if (between(wave2Retracement, 0.382, 0.618)) {
            score += 5;
        }
        if (between(wave4Retracement, 0.236, 0.5)) {
            score += 5;
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
            Pivot waveA = sequence.get(6);
            Pivot waveB = sequence.get(7);
            Pivot waveC = sequence.get(8);
            double waveALength = Math.abs(wave5.price() - waveA.price());
            double waveCLength = Math.abs(waveB.price() - waveC.price());
            double acRatio = safeRatio(waveCLength, waveALength);
            score += 4;
            if (between(acRatio, 0.8, 1.25)) {
                score += 5;
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
        return new ElliottWaveStructure(direction, correctionComplete, List.copyOf(points),
                confirmationTimestamp, qualityScore);
    }

    private List<List<Pivot>> findPivotSets(List<EnrichedCandle> candles) {
        Map<String, List<Pivot>> uniqueSets = new LinkedHashMap<>();
        for (double sensitivity : PIVOT_SENSITIVITIES) {
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
        double volatility = isAvailable(candle.atr14()) && candle.atr14() > 0.0
                ? candle.atr14()
                : averageTrueRange(candles, Math.max(0, index - 13), index);
        double percentageFloor = Math.abs(candle.close()) * 0.0125 * sensitivity;
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
            if (isAvailable(candle.atr14()) && candle.atr14() > 0.0) {
                total += candle.atr14();
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
                                  WaveEvidence evidence) {
        int confidenceScore = clampScore(evidence.score());
        List<String> reasons = new ArrayList<>(evidence.reasons());
        if (confidenceScore < MIN_CONFIDENCE) {
            reasons.add("Elliott Wave classification confidence is below the calibrated signal range; pattern is still detected");
        }

        return new DetectedSignal(
                pattern,
                tradeSignal,
                classifyStrength(confidenceScore),
                confidenceScore,
                reasons,
                current.timestamp(),
                current.close()
        );
    }

    private SignalStength classifyStrength(int confidenceScore) {
        if (confidenceScore < MIN_CONFIDENCE) {
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
        return isAvailable(current.ema20()) && isAvailable(previous.ema20()) && current.ema20() > previous.ema20();
    }

    private boolean emaFalling(List<EnrichedCandle> candles) {
        if (candles.size() < 4) {
            return false;
        }
        EnrichedCandle current = candles.get(candles.size() - 1);
        EnrichedCandle previous = candles.get(candles.size() - 4);
        return isAvailable(current.ema20()) && isAvailable(previous.ema20()) && current.ema20() < previous.ema20();
    }

    private boolean isVolumeSurge(EnrichedCandle candle) {
        return isAvailable(candle.averageVolume20()) && candle.volume() > candle.averageVolume20() * 1.2;
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

    private enum SwingDirection {
        UNKNOWN,
        UP,
        DOWN
    }

    private record Pivot(int index, PivotType type, double price) {
    }

    public record ElliottWavePoint(String label, Long timestamp, double price, String pivotType) {
    }

    public record ElliottWaveStructure(String direction,
                                       boolean correctionComplete,
                                       List<ElliottWavePoint> points,
                                       Long confirmationTimestamp,
                                       int qualityScore) {
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
