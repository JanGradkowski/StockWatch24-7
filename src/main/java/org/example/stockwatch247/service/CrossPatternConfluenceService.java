package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies look-back-only score confluence across the three application-owned
 * technical pattern families. Detection remains inside the existing detectors;
 * this service only builds a common timestamped timeline and adjusts a score.
 */
@Service
public class CrossPatternConfluenceService {
    public static final int LOOKBACK_CANDLES = 8;
    public static final int POINTS_PER_FAMILY = 10;
    public static final String REASON_PREFIX = "Cross-pattern confluence";

    private final CandlePatternDetectionService candlestickDetector;

    public CrossPatternConfluenceService(CandlePatternDetectionService candlestickDetector) {
        this.candlestickDetector = candlestickDetector;
    }

    public Timeline buildTimeline(
            List<Candle> rawCandles,
            List<EnrichedCandle> candlestickCandles,
            List<EnrichedCandle> elliottCandles,
            TimeInterval interval,
            CandlePatternDetectionService.TrendDetectionRules trendRules,
            CandlestickPatternPreferencesService.PreferencesView candlestickDefinitions,
            ElliottWaveDetectionService elliottDetector,
            HarmonicPatternDetectionService harmonicDetector) {
        List<Candle> candles = normalizeCandles(rawCandles);
        List<Observation> observations = new ArrayList<>();
        observations.addAll(candlestickObservations(
                candles,
                candlestickCandles,
                trendRules == null ? CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(interval) : trendRules,
                candlestickDefinitions == null
                        ? CandlestickPatternPreferencesService.factoryPreferences() : candlestickDefinitions));
        observations.addAll(elliottObservations(
                candles,
                elliottCandles,
                elliottDetector == null ? new ElliottWaveDetectionService() : elliottDetector));
        observations.addAll(harmonicObservations(
                candles,
                harmonicDetector == null ? new HarmonicPatternDetectionService() : harmonicDetector));
        Map<String, Observation> unique = new LinkedHashMap<>();
        observations.stream()
                .sorted(Comparator.comparingLong(Observation::timestamp)
                        .thenComparing(item -> item.family().name())
                        .thenComparing(item -> item.pattern().name()))
                .forEach(item -> unique.putIfAbsent(item.key(), item));
        return new Timeline(candles.stream().map(Candle::getTimestamp).toList(),
                List.copyOf(unique.values()));
    }

    public Assessment assess(int baseScore,
                             AlertPatternFamily targetFamily,
                             TradeSignal targetDirection,
                             long targetTimestamp,
                             Timeline timeline) {
        return assess(baseScore, targetFamily, targetDirection, targetTimestamp, timeline,
                Policy.factory(targetFamily));
    }

    public Assessment assess(int baseScore,
                             AlertPatternFamily targetFamily,
                             TradeSignal targetDirection,
                             long targetTimestamp,
                             Timeline timeline,
                             Policy policy) {
        int normalizedBase = Math.clamp(baseScore, 0, 100);
        if (targetFamily == null || !directional(targetDirection) || timeline == null) {
            return Assessment.none(normalizedBase);
        }
        Policy effectivePolicy = policy == null ? Policy.factory(targetFamily) : policy;
        int targetIndex = timeline.candleTimestamps().indexOf(targetTimestamp);
        if (targetIndex < 0) {
            return Assessment.none(normalizedBase);
        }
        int firstIndex = Math.max(0, targetIndex - LOOKBACK_CANDLES);
        Map<Long, Integer> candleIndexes = new LinkedHashMap<>();
        for (int index = firstIndex; index < targetIndex; index++) {
            candleIndexes.put(timeline.candleTimestamps().get(index), index);
        }

        Map<AlertPatternFamily, Observation> mostRecentByFamily = new EnumMap<>(AlertPatternFamily.class);
        for (Observation observation : timeline.observations()) {
            Integer observationIndex = candleIndexes.get(observation.timestamp());
            if (observation.family() == targetFamily || observationIndex == null
                    || !directional(observation.direction())) {
                continue;
            }
            mostRecentByFamily.merge(observation.family(), observation,
                    (left, right) -> right.timestamp() > left.timestamp() ? right : left);
        }

        List<Evidence> evidence = new ArrayList<>();
        int adjustment = 0;
        for (AlertPatternFamily family : List.of(
                AlertPatternFamily.CANDLESTICK,
                AlertPatternFamily.ELLIOTT_WAVE,
                AlertPatternFamily.HARMONIC_FORMATION)) {
            if (family == targetFamily) continue;
            Observation observation = mostRecentByFamily.get(family);
            if (observation == null) continue;
            Weight weight = effectivePolicy.weight(family);
            boolean supporting = observation.direction() == targetDirection;
            int points = !weight.enabled() ? 0
                    : supporting ? weight.supportingPoints() : -weight.opposingPoints();
            int candlesAgo = targetIndex - candleIndexes.get(observation.timestamp());
            evidence.add(new Evidence(family, observation.pattern(), observation.direction(),
                    observation.timestamp(), candlesAgo, points, weight.enabled(), supporting));
            adjustment += points;
        }
        int adjustedScore = Math.clamp(normalizedBase + adjustment, 0, 100);
        return new Assessment(adjustment, adjustedScore, List.copyOf(evidence),
                reason(normalizedBase, adjustment, adjustedScore, evidence));
    }

    public DetectedSignal apply(DetectedSignal signal,
                                AlertPatternFamily family,
                                Timeline timeline) {
        return apply(signal, family, timeline, Policy.factory(family));
    }

    public DetectedSignal apply(DetectedSignal signal,
                                AlertPatternFamily family,
                                Timeline timeline,
                                Policy policy) {
        if (signal == null) return null;
        Assessment assessment = assess(signal.confidenceScore(), family, signal.tradeSignal(),
                signal.candleTimestamp(), timeline, policy);
        if (assessment.evidence().isEmpty()) return signal;
        List<String> reasons = new ArrayList<>(signal.reasons());
        reasons.removeIf(reason -> reason != null && reason.startsWith(REASON_PREFIX));
        reasons.add(assessment.reason());
        return new DetectedSignal(signal.pattern(), signal.tradeSignal(),
                assessment.adjustedScore() == signal.confidenceScore()
                        ? signal.strength() : strength(assessment.adjustedScore()),
                assessment.adjustedScore(), List.copyOf(reasons), signal.candleTimestamp(), signal.closePrice(),
                signal.eligibilityScore(), signal.trendStartTimestamp());
    }

    private List<Observation> candlestickObservations(
            List<Candle> rawCandles,
            List<EnrichedCandle> enrichedCandles,
            CandlePatternDetectionService.TrendDetectionRules trendRules,
            CandlestickPatternPreferencesService.PreferencesView definitions) {
        if (enrichedCandles == null || enrichedCandles.size() < 2) return List.of();
        List<EnrichedCandle> enriched = enrichedCandles.stream()
                .sorted(Comparator.comparing(EnrichedCandle::timestamp)).toList();
        Map<Long, Candle> rawByTimestamp = new LinkedHashMap<>();
        for (Candle candle : rawCandles) rawByTimestamp.put(candle.getTimestamp(), candle);
        List<Observation> result = new ArrayList<>();
        for (int index = 1; index < enriched.size(); index++) {
            int contextStart = Math.max(0, index - 99);
            List<DetectedSignal> signals = candlestickDetector.detectAlertSignals(
                    enriched.subList(contextStart, index + 1), trendRules, definitions);
            for (DetectedSignal signal : signals) {
                long effectiveTimestamp = signal.candleTimestamp();
                if (CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(signal.pattern())) {
                    int rawIndex = candleIndex(rawCandles, signal.candleTimestamp());
                    if (rawIndex < 0 || rawIndex + 1 >= rawCandles.size()) continue;
                    CandlestickSignalLifecyclePolicy.LifecycleResolution gate =
                            CandlestickSignalLifecyclePolicy.resolveCandidateGate(
                                    signal.pattern(), signal.tradeSignal(), signal.closePrice(),
                                    List.of(rawCandles.get(rawIndex + 1)));
                    if (gate == null || gate.status()
                            != org.example.stockwatch247.model.enums.SignalLifecycleStatus.DETECTED) continue;
                    effectiveTimestamp = gate.resolutionCandle().getTimestamp();
                }
                if (rawByTimestamp.containsKey(effectiveTimestamp)) {
                    result.add(new Observation(AlertPatternFamily.CANDLESTICK, signal.tradeSignal(),
                            effectiveTimestamp, signal.pattern()));
                }
            }
        }
        return List.copyOf(result);
    }

    private List<Observation> elliottObservations(
            List<Candle> candles,
            List<EnrichedCandle> enrichedCandles,
            ElliottWaveDetectionService detector) {
        if (enrichedCandles == null || enrichedCandles.isEmpty()) return List.of();
        List<Observation> result = new ArrayList<>();
        for (ElliottWaveDetectionService.ElliottWaveStructure structure
                : detector.findHistoricalWaveStructures(enrichedCandles)) {
            addElliottStage(result, candles, structure, ElliottSignalStage.WAVE_V_END);
            if (structure.correctionComplete()) {
                addElliottStage(result, candles, structure, ElliottSignalStage.CORRECTION_END);
            }
        }
        return List.copyOf(result);
    }

    private void addElliottStage(List<Observation> result,
                                 List<Candle> candles,
                                 ElliottWaveDetectionService.ElliottWaveStructure structure,
                                 ElliottSignalStage stage) {
        ElliottWaveDetectionService.ElliottWavePoint endpoint = stage == ElliottSignalStage.CORRECTION_END
                && structure.correctionComplete() && !structure.points().isEmpty()
                ? structure.points().getLast()
                : structure.points().stream()
                .filter(point -> "V".equalsIgnoreCase(point.label())).findFirst().orElse(null);
        if (endpoint == null) return;
        boolean bullishStructure = "BULLISH".equalsIgnoreCase(structure.direction());
        TradeSignal direction = stage == ElliottSignalStage.CORRECTION_END
                ? (bullishStructure ? TradeSignal.BUY : TradeSignal.SELL)
                : (bullishStructure ? TradeSignal.SELL : TradeSignal.BUY);
        Long confirmation = confirmationTimestamp(candles, endpoint.timestamp(), direction);
        if (confirmation == null) return;
        CandlePattern pattern = stage == ElliottSignalStage.CORRECTION_END
                ? (direction == TradeSignal.BUY ? CandlePattern.ELLIOTT_BULLISH_CORRECTION
                : CandlePattern.ELLIOTT_BEARISH_CORRECTION)
                : (direction == TradeSignal.BUY ? CandlePattern.ELLIOTT_BULLISH_WAVE_V_END
                : CandlePattern.ELLIOTT_BEARISH_WAVE_V_END);
        result.add(new Observation(AlertPatternFamily.ELLIOTT_WAVE, direction, confirmation, pattern));
    }

    private List<Observation> harmonicObservations(
            List<Candle> candles,
            HarmonicPatternDetectionService detector) {
        return detector.detectHistorical(candles).stream()
                .map(formation -> new Observation(
                        AlertPatternFamily.HARMONIC_FORMATION,
                        formation.tradeSignal(),
                        formation.confirmationTimestamp(),
                        HarmonicPatternDetectionService.signalPattern(formation.pattern())))
                .toList();
    }

    private Long confirmationTimestamp(List<Candle> candles, long endpointTimestamp, TradeSignal direction) {
        int endpointIndex = candleIndex(candles, endpointTimestamp);
        if (endpointIndex < 0) return null;
        int lastCandidate = Math.min(candles.size() - 1, endpointIndex + 3);
        for (int index = endpointIndex + 1; index <= lastCandidate; index++) {
            Candle current = candles.get(index);
            Candle previous = candles.get(index - 1);
            boolean confirmed = direction == TradeSignal.BUY
                    ? current.getClosePrice() > previous.getHighPrice()
                    : current.getClosePrice() < previous.getLowPrice();
            if (confirmed) return current.getTimestamp();
        }
        return null;
    }

    private String reason(int baseScore, int adjustment, int adjustedScore, List<Evidence> evidence) {
        StringBuilder detail = new StringBuilder(REASON_PREFIX).append(' ')
                .append(String.format(Locale.ROOT, "%+d", adjustment)).append(": base score ")
                .append(baseScore).append("/100");
        if (evidence.isEmpty()) {
            detail.append("; no Elliott, harmonic, or candlestick signal from another family was detected during the preceding eight candles");
        } else {
            for (Evidence item : evidence) {
                detail.append("; ").append(familyLabel(item.family())).append(' ')
                        .append(patternLabel(item.pattern())).append(" was ")
                        .append(item.direction() == TradeSignal.BUY ? "bullish" : "bearish")
                        .append(" and occurred ").append(item.candlesAgo())
                        .append(item.candlesAgo() == 1 ? " candle" : " candles")
                        .append(" earlier (");
                if (item.enabled()) {
                    detail.append(String.format(Locale.ROOT, "%+d", item.points())).append(" points)");
                } else {
                    detail.append("disabled for this scoring profile, ")
                            .append(item.supporting() ? "same-direction evidence" : "opposite-direction evidence")
                            .append(')');
                }
            }
        }
        detail.append("; final score ").append(adjustedScore).append("/100");
        if (baseScore + adjustment != adjustedScore) detail.append(" after the 0-100 cap");
        return detail.append('.').toString();
    }

    private static String familyLabel(AlertPatternFamily family) {
        return switch (family) {
            case ELLIOTT_WAVE -> "Elliott wave";
            case HARMONIC_FORMATION -> "Harmonic formation";
            default -> "Candlestick pattern";
        };
    }

    private static String patternLabel(CandlePattern pattern) {
        String value = pattern == null ? "signal" : pattern.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return value.replace("elliott bullish ", "").replace("elliott bearish ", "")
                .replace("harmonic ", "");
    }

    private static int candleIndex(List<Candle> candles, long timestamp) {
        for (int index = 0; index < candles.size(); index++) {
            if (candles.get(index).getTimestamp() == timestamp) return index;
        }
        return -1;
    }

    private static List<Candle> normalizeCandles(List<Candle> candles) {
        return candles == null ? List.of() : candles.stream()
                .filter(candle -> candle != null && candle.getTimestamp() != null)
                .sorted(Comparator.comparing(Candle::getTimestamp)).toList();
    }

    private static boolean directional(TradeSignal signal) {
        return signal == TradeSignal.BUY || signal == TradeSignal.SELL;
    }

    private static org.example.stockwatch247.model.enums.SignalStength strength(int score) {
        if (score >= 85) return org.example.stockwatch247.model.enums.SignalStength.HIGH_CONFIDENCE;
        if (score >= 75) return org.example.stockwatch247.model.enums.SignalStength.MEDIUM_CONFIDENCE;
        if (score > 0) return org.example.stockwatch247.model.enums.SignalStength.LOW_CONFIDENCE;
        return org.example.stockwatch247.model.enums.SignalStength.WEAK_IGNORE;
    }

    public record Timeline(List<Long> candleTimestamps, List<Observation> observations) {
        public Timeline {
            candleTimestamps = candleTimestamps == null ? List.of() : List.copyOf(candleTimestamps);
            observations = observations == null ? List.of() : List.copyOf(observations);
        }
    }

    public record Observation(AlertPatternFamily family, TradeSignal direction,
                              long timestamp, CandlePattern pattern) {
        private String key() {
            return family + "|" + direction + "|" + timestamp + "|" + pattern;
        }
    }

    public record Evidence(AlertPatternFamily family, CandlePattern pattern, TradeSignal direction,
                           long timestamp, int candlesAgo, int points, boolean enabled,
                           boolean supporting) { }

    public record Weight(boolean enabled, int supportingPoints, int opposingPoints) {
        public Weight {
            supportingPoints = Math.clamp(supportingPoints, 0, 100);
            opposingPoints = Math.clamp(opposingPoints, 0, 100);
        }

        public static Weight factory() {
            return new Weight(true, POINTS_PER_FAMILY, POINTS_PER_FAMILY);
        }
    }

    public record Policy(AlertPatternFamily targetFamily, Map<AlertPatternFamily, Weight> weights) {
        public Policy {
            weights = weights == null ? Map.of() : Map.copyOf(weights);
        }

        public Weight weight(AlertPatternFamily sourceFamily) {
            if (sourceFamily == null || sourceFamily == targetFamily) {
                return new Weight(false, 0, 0);
            }
            return weights.getOrDefault(sourceFamily, Weight.factory());
        }

        public static Policy factory(AlertPatternFamily targetFamily) {
            Map<AlertPatternFamily, Weight> weights = new EnumMap<>(AlertPatternFamily.class);
            for (AlertPatternFamily family : List.of(
                    AlertPatternFamily.CANDLESTICK,
                    AlertPatternFamily.ELLIOTT_WAVE,
                    AlertPatternFamily.HARMONIC_FORMATION)) {
                if (family != targetFamily) weights.put(family, Weight.factory());
            }
            return new Policy(targetFamily, weights);
        }
    }

    public record Assessment(int adjustment, int adjustedScore, List<Evidence> evidence, String reason) {
        public Assessment {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            reason = reason == null ? "" : reason;
        }

        private static Assessment none(int score) {
            return new Assessment(0, score, List.of(),
                    REASON_PREFIX + " +0: confluence could not be evaluated; final score " + score + "/100.");
        }
    }
}
