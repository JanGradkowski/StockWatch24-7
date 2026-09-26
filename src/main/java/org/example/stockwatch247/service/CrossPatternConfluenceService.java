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
    public static final String VERSION = "CONFLUENCE_V3_CAUSAL";
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
                rawCandles,
                candlestickCandles,
                trendRules == null ? CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(interval) : trendRules,
                candlestickDefinitions == null
                        ? CandlestickPatternPreferencesService.factoryPreferences() : candlestickDefinitions));
        ElliottWaveDetectionService waves = elliottDetector == null
                ? new ElliottWaveDetectionService() : elliottDetector;
        HarmonicPatternDetectionService harmonics = harmonicDetector == null
                ? new HarmonicPatternDetectionService() : harmonicDetector;
        List<EnrichedCandle> enriched = elliottCandles == null
                || elliottCandles.stream().anyMatch(c -> c == null || c.timestamp() == null) ? List.of()
                : elliottCandles.stream()
                .sorted(Comparator.comparing(EnrichedCandle::timestamp)).toList();
        // Prefixes are evaluated lazily: a live score needs only the previous eight bars.
        // The cached keys describe everything observable at a prefix, not a final chart layout.
        Map<Integer, Map<String, Observation>> prefixCache = new java.util.HashMap<>();
        return new Timeline(candles.stream().map(Candle::getTimestamp).toList(), observations, index -> {
            Map<String, Observation> current = prefixCache.computeIfAbsent(index,
                    i -> prefixObservations(candles, enriched, i, waves, harmonics));
            Map<String, Observation> previous = index == 0 ? Map.of()
                    : prefixCache.computeIfAbsent(index - 1,
                    i -> prefixObservations(candles, enriched, i, waves, harmonics));
            long availableAt = candles.get(index).getTimestamp();
            return current.entrySet().stream().filter(entry -> !previous.containsKey(entry.getKey()))
                    .map(entry -> new Observation(entry.getValue().family(), entry.getValue().direction(),
                            availableAt, entry.getValue().pattern())).distinct().toList();
        });
    }

    private Map<String, Observation> prefixObservations(List<Candle> raw, List<EnrichedCandle> enriched,
                                                       int index, ElliottWaveDetectionService waves,
                                                       HarmonicPatternDetectionService harmonics) {
        long timestamp = raw.get(index).getTimestamp();
        List<Candle> prefix = raw.subList(0, index + 1);
        List<EnrichedCandle> wavePrefix = enriched.stream().filter(c -> c.timestamp() <= timestamp).toList();
        Map<String, Observation> observations = new LinkedHashMap<>();
        for (var signal : waves.detect(wavePrefix)) {
            if (signal.candleTimestamp() != timestamp) continue;
            observations.put("signal:" + signal.pattern() + ':' + signal.tradeSignal() + ':'
                            + signal.trendStartTimestamp(),
                    new Observation(AlertPatternFamily.ELLIOTT_WAVE, signal.tradeSignal(), timestamp, signal.pattern()));
        }
        for (var candidate : waves.findDevelopingImpulseHypotheses(wavePrefix)) {
            String key = candidate.pattern() + ":" + candidate.points();
            observations.put(key, new Observation(AlertPatternFamily.ELLIOTT_WAVE,
                    candidate.expectedMove(), candidate.confirmationTimestamp(), candidate.pattern()));
        }
        for (var formation : harmonics.detectAll(prefix)) {
            String key = formation.pattern() + ":" + formation.points();
            observations.put(key, new Observation(AlertPatternFamily.HARMONIC_FORMATION,
                    formation.tradeSignal(), formation.confirmationTimestamp(),
                    HarmonicPatternDetectionService.signalPattern(formation.pattern())));
        }
        return observations;
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

        Map<AlertPatternFamily, List<Observation>> mostRecentByFamily = new EnumMap<>(AlertPatternFamily.class);
        for (Observation observation : timeline.observationsBetween(firstIndex, targetIndex)) {
            Integer observationIndex = candleIndexes.get(observation.timestamp());
            if (observation.family() == targetFamily || observationIndex == null
                    || !directional(observation.direction())) {
                continue;
            }
            List<Observation> latest = mostRecentByFamily.get(observation.family());
            if (latest == null || observation.timestamp() > latest.getFirst().timestamp()) {
                mostRecentByFamily.put(observation.family(), new ArrayList<>(List.of(observation)));
            } else if (observation.timestamp() == latest.getFirst().timestamp()) {
                latest.add(observation);
            }
        }

        List<Evidence> evidence = new ArrayList<>();
        int adjustment = 0;
        for (AlertPatternFamily family : List.of(
                AlertPatternFamily.CANDLESTICK,
                AlertPatternFamily.ELLIOTT_WAVE,
                AlertPatternFamily.HARMONIC_FORMATION)) {
            if (family == targetFamily) continue;
            List<Observation> latest = mostRecentByFamily.get(family);
            if (latest == null) continue;
            Observation observation = latest.stream().min(Comparator.comparing(
                    item -> item.pattern() == null ? "" : item.pattern().name())).orElseThrow();
            boolean mixed = latest.stream().anyMatch(item -> item.direction() != observation.direction());
            Weight weight = effectivePolicy.weight(family);
            boolean supporting = !mixed && observation.direction() == targetDirection;
            int points = mixed || !weight.enabled() ? 0
                    : supporting ? weight.supportingPoints() : -weight.opposingPoints();
            int candlesAgo = targetIndex - candleIndexes.get(observation.timestamp());
            evidence.add(new Evidence(family, mixed ? null : observation.pattern(), mixed ? TradeSignal.HOLD : observation.direction(),
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
        CandlestickFormationIntegrity integrity = new CandlestickFormationIntegrity(rawCandles);
        rawCandles = normalizeCandles(rawCandles);
        Map<Long, Candle> rawByTimestamp = new LinkedHashMap<>();
        for (Candle candle : rawCandles) rawByTimestamp.put(candle.getTimestamp(), candle);
        List<Observation> result = new ArrayList<>();
        for (int index = 1; index < enriched.size(); index++) {
            int contextStart = Math.max(0, index - 99);
            List<DetectedSignal> signals = candlestickDetector.detectAlertSignals(
                    integrity.context(enriched.subList(contextStart, index + 1)), trendRules, definitions);
            for (DetectedSignal signal : signals) {
                long effectiveTimestamp = signal.candleTimestamp();
                if (CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(signal.pattern())) {
                    int rawIndex = candleIndex(rawCandles, signal.candleTimestamp());
                    if (rawIndex < 0 || rawIndex + 1 >= rawCandles.size()
                            || !integrity.adjacent(signal.candleTimestamp(), rawCandles.get(rawIndex + 1).getTimestamp())) continue;
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

    private String reason(int baseScore, int adjustment, int adjustedScore, List<Evidence> evidence) {
        StringBuilder detail = new StringBuilder(REASON_PREFIX).append(' ')
                .append(String.format(Locale.ROOT, "%+d", adjustment)).append(": base score ")
                .append(baseScore).append("/100");
        if (evidence.isEmpty()) {
            detail.append("; no Elliott, harmonic, or candlestick signal from another family was detected during the preceding eight candles");
        } else {
            for (Evidence item : evidence) {
                if (item.direction() == TradeSignal.HOLD) {
                    detail.append("; ").append(familyLabel(item.family()))
                            .append(" had mixed bullish and bearish signals ").append(item.candlesAgo())
                            .append(item.candlesAgo() == 1 ? " candle" : " candles")
                            .append(" earlier (0 points; conflicting evidence)");
                    continue;
                }
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
        return candles == null || candles.stream().anyMatch(c -> c == null || c.getTimestamp() == null)
                ? List.of() : candles.stream()
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

    public static final class Timeline {
        private final List<Long> candleTimestamps;
        private final List<Observation> supplied;
        private final java.util.function.IntFunction<List<Observation>> replay;
        private final Map<Integer, List<Observation>> cache = new java.util.HashMap<>();

        public Timeline(List<Long> timestamps, List<Observation> observations) {
            this(timestamps, observations, null);
        }

        private Timeline(List<Long> timestamps, List<Observation> observations,
                         java.util.function.IntFunction<List<Observation>> replay) {
            candleTimestamps = timestamps == null ? List.of() : List.copyOf(timestamps);
            supplied = observations == null ? List.of() : List.copyOf(observations);
            this.replay = replay;
        }

        public List<Long> candleTimestamps() { return candleTimestamps; }

        public List<Observation> observations() {
            return observationsBetween(0, candleTimestamps.size());
        }

        private synchronized List<Observation> observationsBetween(int from, int to) {
            List<Observation> result = new ArrayList<>(supplied);
            if (replay != null) for (int i = from; i < to; i++) {
                result.addAll(cache.computeIfAbsent(i, replay::apply));
            }
            return result.stream().distinct().sorted(Comparator.comparingLong(Observation::timestamp)
                    .thenComparing(item -> item.pattern().name())).toList();
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
