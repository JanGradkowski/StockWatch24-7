package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TradeSignal;

import java.util.List;
import java.util.Optional;

/**
 * Structural close-based boundaries for an actionable Elliott turning point.
 * Wave-V endings confirm beyond wave IV and completed corrections confirm
 * beyond wave B. The terminal wave V/C extreme is the invalidation boundary.
 */
final class ElliottWaveSignalLifecyclePolicy {

    private static final String[] IMPULSE_ANCHOR_LABELS = {"0", "I", "II", "III", "IV"};

    private ElliottWaveSignalLifecyclePolicy() {
    }

    static Optional<LifecycleBoundaries> boundaries(
            CandlePattern pattern,
            TradeSignal tradeSignal,
            ElliottWaveDetectionService.ElliottWaveStructure structure) {
        if (pattern == null || tradeSignal == null || structure == null
                || !pattern.name().startsWith("ELLIOTT_")) {
            return Optional.empty();
        }

        String patternName = pattern.name();
        ElliottSignalStage signalStage = stage(pattern).orElse(null);
        if (signalStage == null) {
            return Optional.empty();
        }
        boolean correction = signalStage == ElliottSignalStage.CORRECTION_END;

        String expectedDirection = patternName.contains("BEARISH") ? "BEARISH" : "BULLISH";
        TradeSignal expectedSignal = correction
                ? ("BULLISH".equals(expectedDirection) ? TradeSignal.BUY : TradeSignal.SELL)
                : ("BULLISH".equals(expectedDirection) ? TradeSignal.SELL : TradeSignal.BUY);
        if (!expectedDirection.equals(structure.direction())
                || structure.correctionComplete() != correction
                || tradeSignal != expectedSignal) {
            return Optional.empty();
        }

        List<ElliottWaveDetectionService.ElliottWavePoint> points = structure.points();
        if (points == null || points.size() < 2) {
            return Optional.empty();
        }
        ElliottWaveDetectionService.ElliottWavePoint triggerPoint = points.get(points.size() - 2);
        ElliottWaveDetectionService.ElliottWavePoint endpoint = points.getLast();
        String expectedTriggerLabel = correction ? "B" : "IV";
        String expectedEndpointLabel = correction ? "C" : "V";
        if (!expectedTriggerLabel.equalsIgnoreCase(triggerPoint.label())
                || !expectedEndpointLabel.equalsIgnoreCase(endpoint.label())) {
            return Optional.empty();
        }

        double confirmationTrigger = triggerPoint.price();
        Double invalidationBoundary = structuralInvalidationBoundary(structure, signalStage);
        boolean validDirectionalOrder = invalidationBoundary == null
                || (tradeSignal == TradeSignal.BUY
                ? confirmationTrigger > invalidationBoundary
                : confirmationTrigger < invalidationBoundary);
        if (!Double.isFinite(confirmationTrigger)
                || invalidationBoundary != null && !Double.isFinite(invalidationBoundary)
                || !validDirectionalOrder) {
            return Optional.empty();
        }

        double structureHigh = points.stream()
                .mapToDouble(ElliottWaveDetectionService.ElliottWavePoint::price)
                .max()
                .orElse(Double.NaN);
        double structureLow = points.stream()
                .mapToDouble(ElliottWaveDetectionService.ElliottWavePoint::price)
                .min()
                .orElse(Double.NaN);
        if (!Double.isFinite(structureHigh) || !Double.isFinite(structureLow)
                || structureHigh <= structureLow) {
            return Optional.empty();
        }
        String cycleKey = cycleKey(structure).orElse(null);
        if (cycleKey == null || endpoint.timestamp() == null || triggerPoint.timestamp() == null
                || structure.confirmationTimestamp() == null) {
            return Optional.empty();
        }
        return Optional.of(new LifecycleBoundaries(
                structureHigh,
                structureLow,
                confirmationTrigger,
                invalidationBoundary,
                cycleKey,
                signalStage,
                endpoint.timestamp(),
                endpoint.price(),
                triggerPoint.timestamp(),
                structure.confirmationTimestamp()
        ));
    }

    static Optional<ElliottSignalStage> stage(CandlePattern pattern) {
        if (pattern == null || !pattern.name().startsWith("ELLIOTT_")) {
            return Optional.empty();
        }
        if (pattern.name().endsWith("WAVE_V_END")) {
            return Optional.of(ElliottSignalStage.WAVE_V_END);
        }
        if (pattern.name().endsWith("CORRECTION")) {
            return Optional.of(ElliottSignalStage.CORRECTION_END);
        }
        return Optional.empty();
    }

    static Optional<String> cycleKey(ElliottWaveDetectionService.ElliottWaveStructure structure) {
        if (structure == null || structure.points() == null
                || structure.points().size() < IMPULSE_ANCHOR_LABELS.length) {
            return Optional.empty();
        }
        StringBuilder key = new StringBuilder(structure.direction());
        for (int index = 0; index < IMPULSE_ANCHOR_LABELS.length; index++) {
            ElliottWaveDetectionService.ElliottWavePoint point = structure.points().get(index);
            boolean originLabel = index == 0
                    && (point.label() == null || point.label().isBlank() || "0".equals(point.label()));
            if ((!originLabel && !IMPULSE_ANCHOR_LABELS[index].equalsIgnoreCase(point.label()))
                    || point.timestamp() == null) {
                return Optional.empty();
            }
            key.append(':').append(point.timestamp());
        }
        return Optional.of(key.toString());
    }

    static Optional<StructuralInvalidation> structuralInvalidation(
            String cycleKey,
            ElliottSignalStage stage,
            Double invalidationBoundary,
            Long lifecycleAnchorTimestamp,
            List<EnrichedCandle> candles) {
        if (cycleKey == null || stage == null || invalidationBoundary == null
                || lifecycleAnchorTimestamp == null || candles == null) {
            return Optional.empty();
        }
        boolean bullishCycle = cycleKey.startsWith("BULLISH:");
        for (EnrichedCandle candle : candles) {
            if (candle == null || candle.timestamp() <= lifecycleAnchorTimestamp) {
                continue;
            }
            boolean broken = switch (stage) {
                case WAVE_V_END -> bullishCycle
                        ? candle.high() > invalidationBoundary
                        : candle.low() < invalidationBoundary;
                case CORRECTION_END -> bullishCycle
                        ? candle.low() <= invalidationBoundary
                        : candle.high() >= invalidationBoundary;
            };
            if (broken) {
                String reason = stage == ElliottSignalStage.WAVE_V_END
                        ? "The extended Wave V made Wave III the shortest impulse wave, so the stored count no longer satisfies the Elliott impulse rules."
                        : "Wave C crossed the impulse origin, so the stored Elliott correction is no longer structurally valid.";
                return Optional.of(new StructuralInvalidation(candle.timestamp(), candle.close(), reason));
            }
        }
        return Optional.empty();
    }

    static LifecycleResolution resolve(
            TradeSignal tradeSignal,
            double confirmationTriggerPrice,
            List<Candle> subsequentCandles,
            int confirmationWindowCandles) {
        if ((tradeSignal != TradeSignal.BUY && tradeSignal != TradeSignal.SELL)
                || confirmationWindowCandles < 1) {
            return null;
        }
        List<Candle> candles = subsequentCandles == null ? List.of() : subsequentCandles;
        int observedCandles = Math.min(candles.size(), confirmationWindowCandles);
        for (int index = 0; index < observedCandles; index++) {
            Candle candle = candles.get(index);
            boolean confirmed = tradeSignal == TradeSignal.BUY
                    ? candle.getClosePrice() > confirmationTriggerPrice
                    : candle.getClosePrice() < confirmationTriggerPrice;
            if (confirmed) {
                return new LifecycleResolution(SignalLifecycleStatus.CONFIRMED, candle, index + 1);
            }
        }
        if (observedCandles >= confirmationWindowCandles) {
            return new LifecycleResolution(
                    SignalLifecycleStatus.EXPIRED,
                    candles.get(confirmationWindowCandles - 1),
                    confirmationWindowCandles
            );
        }
        return null;
    }

    private static Double structuralInvalidationBoundary(
            ElliottWaveDetectionService.ElliottWaveStructure structure,
            ElliottSignalStage stage) {
        List<ElliottWaveDetectionService.ElliottWavePoint> points = structure.points();
        if (stage == ElliottSignalStage.CORRECTION_END) {
            return points.getFirst().price();
        }
        double waveOneLength = Math.abs(points.get(1).price() - points.get(0).price());
        double waveThreeLength = Math.abs(points.get(3).price() - points.get(2).price());
        if (waveOneLength <= waveThreeLength) {
            return null;
        }
        double waveFourPrice = points.get(4).price();
        return "BULLISH".equals(structure.direction())
                ? waveFourPrice + waveThreeLength
                : waveFourPrice - waveThreeLength;
    }

    record LifecycleBoundaries(
            double structureHigh,
            double structureLow,
            double confirmationTrigger,
            Double invalidationBoundary,
            String cycleKey,
            ElliottSignalStage stage,
            long endpointTimestamp,
            double endpointPrice,
            long terminalAnchorTimestamp,
            long lifecycleAnchorTimestamp
    ) {
    }

    record StructuralInvalidation(long timestamp, double closePrice, String reason) {
    }

    record LifecycleResolution(
            SignalLifecycleStatus status,
            Candle resolutionCandle,
            int candleOffset
    ) {
    }
}
