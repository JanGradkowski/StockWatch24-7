package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.technical.TechnicalResearchSnapshot;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repeatable, offline comparison of established StockWatch calculations and
 * TA4J candidates. It reports evidence only and cannot switch production code.
 */
public final class TechnicalCalculationComparisonService {
    private static final int MINIMUM_PROMOTION_SAMPLE = 100;
    private static final double MATERIAL_IMPROVEMENT = 0.02;
    private final TechnicalIndicatorEnrichmentService enrichmentService;

    public TechnicalCalculationComparisonService() {
        this(new TechnicalIndicatorEnrichmentService());
    }

    TechnicalCalculationComparisonService(TechnicalIndicatorEnrichmentService enrichmentService) {
        this.enrichmentService = enrichmentService;
    }

    public ComparisonReport compare(List<Candle> rawCandles, TimeInterval interval) {
        List<Candle> candles = rawCandles == null ? List.of() : rawCandles.stream()
                .filter(candle -> candle != null && candle.getTimestamp() != null)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        if (candles.size() < 3) {
            return ComparisonReport.unavailable();
        }
        TechnicalIndicatorProfile profile = TechnicalIndicatorProfile.forInterval(interval);
        List<EnrichedCandle> established = enrichmentService.enrich(candles, candles.size(), profile);
        List<TechnicalResearchSnapshot> research = enrichmentService.research(
                candles, candles.size(), profile, true);
        Map<Long, TechnicalResearchSnapshot> researchByTimestamp = new LinkedHashMap<>();
        research.forEach(snapshot -> researchByTimestamp.put(snapshot.timestamp(), snapshot));

        double establishedError = 0.0;
        double ta4jError = 0.0;
        int samples = 0;
        for (int index = 0; index < established.size() - 1; index++) {
            EnrichedCandle current = established.get(index);
            EnrichedCandle next = established.get(index + 1);
            TechnicalResearchSnapshot candidate = researchByTimestamp.get(current.timestamp());
            if (candidate == null || !Double.isFinite(current.volumeProfilePointOfControl())
                    || !Double.isFinite(candidate.volumeProfileKdeMode())
                    || !Double.isFinite(current.atr()) || current.atr() <= 0.0) continue;
            establishedError += Math.abs(next.close() - current.volumeProfilePointOfControl()) / current.atr();
            ta4jError += Math.abs(next.close() - candidate.volumeProfileKdeMode()) / current.atr();
            samples++;
        }
        VolumeProfileComparison volume = samples == 0
                ? VolumeProfileComparison.unavailable()
                : comparison(samples, establishedError / samples, ta4jError / samples);
        return new ComparisonReport(
                new CoreIndicatorFinding(
                        "TA4J_ALREADY_IN_PRODUCTION",
                        "RSI, EMA, SMA, MACD, CCI, ATR, Bollinger Bands, VWAP, and average volume were already TA4J calculations; independent parity tests validate them rather than pretending there were two production engines."),
                volume,
                "Mean one-candle-forward absolute distance from the estimated price mode, normalized by the current ATR. This measures price-location usefulness, not profitability.",
                "No result automatically changes detection, scoring, explanations, alerts, or business decisions.");
    }

    private VolumeProfileComparison comparison(int samples,
                                               double establishedError,
                                               double ta4jError) {
        double improvement = establishedError == 0.0
                ? 0.0 : (establishedError - ta4jError) / establishedError;
        String winner = Math.abs(improvement) < MATERIAL_IMPROVEMENT
                ? "NO_MATERIAL_DIFFERENCE"
                : improvement > 0 ? "TA4J_KDE" : "ESTABLISHED_BINNED_PROFILE";
        String recommendation = samples >= MINIMUM_PROMOTION_SAMPLE && improvement >= MATERIAL_IMPROVEMENT
                ? "REVIEW_TA4J_CANDIDATE"
                : "KEEP_ESTABLISHED_PRODUCTION_INPUT";
        return new VolumeProfileComparison(
                true, samples, establishedError, ta4jError, improvement, winner, recommendation);
    }

    public record ComparisonReport(CoreIndicatorFinding coreIndicators,
                                   VolumeProfileComparison volumeProfile,
                                   String volumeMetric,
                                   String safetyBoundary) {
        private static ComparisonReport unavailable() {
            return new ComparisonReport(
                    new CoreIndicatorFinding("UNAVAILABLE", "Insufficient completed candles."),
                    VolumeProfileComparison.unavailable(),
                    "Unavailable",
                    "No production behavior changed.");
        }
    }

    public record CoreIndicatorFinding(String status, String explanation) {
    }

    public record VolumeProfileComparison(boolean available,
                                          int samples,
                                          double establishedMeanAtrError,
                                          double ta4jMeanAtrError,
                                          double ta4jRelativeImprovement,
                                          String lowerErrorMethod,
                                          String recommendation) {
        private static VolumeProfileComparison unavailable() {
            return new VolumeProfileComparison(false, 0, Double.NaN, Double.NaN,
                    Double.NaN, "UNAVAILABLE", "KEEP_ESTABLISHED_PRODUCTION_INPUT");
        }
    }
}
