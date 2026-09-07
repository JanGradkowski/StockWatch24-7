package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure risk/reward projection built on an already validated Elliott count.
 * This policy never participates in pivot selection, subdivision validation,
 * confidence scoring, or signal eligibility.
 */
final class ElliottTradePlanPolicy {
    static final String VERSION = "ELLIOTT_FIB_RR_V2";
    static final double TARGET_ZONE_PERCENT = 1.5;
    private static final double ATR_STOP_BUFFER_MULTIPLIER = 0.10;
    private static final double FALLBACK_STOP_BUFFER_PERCENT = 0.10;

    private ElliottTradePlanPolicy() {
    }

    static Optional<TradePlan> calculate(
            ElliottSignalStage stage,
            String cycleDirection,
            TradeSignal expectedMove,
            double entryPrice,
            List<ElliottWaveDetectionService.ElliottWavePoint> points,
            double atr,
            TimeInterval interval) {
        if (stage == null || expectedMove == null
                || expectedMove != TradeSignal.BUY && expectedMove != TradeSignal.SELL
                || !Double.isFinite(entryPrice) || entryPrice <= 0.0
                || points == null || points.isEmpty() || interval == null) {
            return Optional.empty();
        }
        boolean bullishCycle = "BULLISH".equalsIgnoreCase(cycleDirection);
        boolean bearishCycle = "BEARISH".equalsIgnoreCase(cycleDirection);
        if (!bullishCycle && !bearishCycle) return Optional.empty();
        double cycleSign = bullishCycle ? 1.0 : -1.0;

        Double wave0 = price(points, "0");
        Double wave1 = price(points, "I");
        Double wave2 = price(points, "II");
        if (wave0 == null || wave1 == null || wave2 == null) return Optional.empty();

        Double structuralStop;
        Double hardInvalidation = null;
        BoundarySide hardInvalidationSide = null;
        List<TargetCandidate> targets = new ArrayList<>();
        switch (stage) {
            case WAVE_II_END -> {
                structuralStop = wave0;
                hardInvalidation = wave0;
                hardInvalidationSide = bullishCycle ? BoundarySide.BELOW : BoundarySide.ABOVE;
                double waveOneLength = Math.abs(wave1 - wave0);
                double standardArithmeticTarget = wave2 + cycleSign * waveOneLength * 1.618;
                double extendedArithmeticTarget = wave2 + cycleSign * waveOneLength * 2.618;
                targets.add(target(standardArithmeticTarget,
                        "Wave III at 161.8% of Wave I from Wave II", 1.618));
                targets.add(target(extendedArithmeticTarget,
                        "Extended Wave III at 261.8% of Wave I from Wave II", 2.618));
                if (bearishCycle
                        && standardArithmeticTarget <= 0.0
                        && extendedArithmeticTarget <= 0.0) {
                    targets.add(logarithmicBearishWaveThreeTarget(wave0, wave1, wave2));
                }
            }
            case WAVE_III_END -> {
                Double wave3 = price(points, "III");
                if (wave3 == null) return Optional.empty();
                structuralStop = wave3;
                hardInvalidation = wave0;
                hardInvalidationSide = bullishCycle ? BoundarySide.BELOW : BoundarySide.ABOVE;
                double waveThreeLength = Math.abs(wave3 - wave2);
                targets.add(target(wave3 - cycleSign * waveThreeLength * .382,
                        "Wave IV at a 38.2% retracement of Wave III", .382));
                targets.add(target(wave3 - cycleSign * waveThreeLength * .50,
                        "Deeper Wave IV at a 50.0% retracement of Wave III", .50));
            }
            case WAVE_IV_END -> {
                Double wave3 = price(points, "III");
                Double wave4 = price(points, "IV");
                if (wave3 == null || wave4 == null) return Optional.empty();
                structuralStop = wave4;
                hardInvalidation = wave1;
                hardInvalidationSide = bullishCycle ? BoundarySide.BELOW : BoundarySide.ABOVE;
                targets.add(target(wave4 + cycleSign * Math.abs(wave1 - wave0),
                        "Wave V at equality with Wave I from Wave IV", 1.0));
                targets.add(target(wave4 + cycleSign * Math.abs(wave3 - wave0) * .618,
                        "Wave V at 61.8% of the Wave 0-to-III advance from Wave IV", .618));
            }
            case WAVE_V_END -> {
                Double wave3 = price(points, "III");
                Double wave4 = price(points, "IV");
                Double wave5 = price(points, "V");
                if (wave3 == null || wave4 == null || wave5 == null) return Optional.empty();
                structuralStop = wave5;
                double waveOneLength = Math.abs(wave1 - wave0);
                double waveThreeLength = Math.abs(wave3 - wave2);
                if (waveOneLength > waveThreeLength) {
                    hardInvalidation = wave4 + cycleSign * waveThreeLength;
                    hardInvalidationSide = bullishCycle ? BoundarySide.ABOVE : BoundarySide.BELOW;
                }
                targets.add(target(wave4,
                        "Correction toward the previous Wave IV territory", null));
                targets.add(target(wave5 - cycleSign * Math.abs(wave5 - wave0) * .382,
                        "Correction at a 38.2% retracement of the complete impulse", .382));
            }
            case CORRECTION_END -> {
                Double wave5 = price(points, "V");
                Double waveC = price(points, "C");
                if (wave5 == null || waveC == null) return Optional.empty();
                structuralStop = waveC;
                hardInvalidation = wave0;
                hardInvalidationSide = bullishCycle ? BoundarySide.BELOW : BoundarySide.ABOVE;
                targets.add(target(wave5,
                        "Primary-trend resumption toward the prior Wave V extreme", null));
            }
            default -> {
                return Optional.empty();
            }
        }

        double stopBuffer = Double.isFinite(atr) && atr > 0.0
                ? atr * ATR_STOP_BUFFER_MULTIPLIER
                : entryPrice * FALLBACK_STOP_BUFFER_PERCENT / 100.0;
        stopBuffer = Math.max(stopBuffer, entryPrice * 0.000001);
        double stopLoss = expectedMove == TradeSignal.BUY
                ? structuralStop - stopBuffer : structuralStop + stopBuffer;
        double risk = expectedMove == TradeSignal.BUY
                ? entryPrice - stopLoss : stopLoss - entryPrice;
        if (!Double.isFinite(risk) || risk <= 0.0 || stopLoss <= 0.0) {
            return Optional.empty();
        }

        double requiredRewardRisk = interval == TimeInterval.DAILY ? 2.0 : 3.0;
        List<QualifiedTarget> validTargets = targets.stream()
                .filter(candidate -> candidate != null && Double.isFinite(candidate.midpoint())
                        && candidate.midpoint() > 0.0)
                .map(candidate -> qualify(candidate, expectedMove, entryPrice, risk))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (validTargets.isEmpty()) return Optional.empty();
        QualifiedTarget selected = validTargets.stream()
                .filter(candidate -> candidate.rewardRiskRatio() + 0.000001 >= requiredRewardRisk)
                .findFirst()
                .orElse(validTargets.getFirst());
        boolean actionable = selected.rewardRiskRatio() + 0.000001 >= requiredRewardRisk;
        String qualification = actionable
                ? "Meets the minimum 1:%.0f %s risk/reward requirement."
                        .formatted(requiredRewardRisk, interval.name().toLowerCase(Locale.ROOT))
                : "Projection only: the nearest valid Fibonacci target provides 1:%.2f, below the required 1:%.0f."
                        .formatted(selected.rewardRiskRatio(), requiredRewardRisk);
        return Optional.of(new TradePlan(
                VERSION, stage, expectedMove, entryPrice,
                structuralStop, stopLoss, stopBuffer,
                hardInvalidation, hardInvalidationSide,
                selected.candidate().midpoint(), selected.zoneLow(), selected.zoneHigh(),
                selected.triggerPrice(), selected.candidate().basis(),
                selected.candidate().fibonacciRatio(), TARGET_ZONE_PERCENT,
                requiredRewardRisk, selected.rewardRiskRatio(), actionable, qualification));
    }

    private static QualifiedTarget qualify(
            TargetCandidate candidate,
            TradeSignal direction,
            double entry,
            double risk) {
        double zoneLow = candidate.midpoint() * (1.0 - TARGET_ZONE_PERCENT / 100.0);
        double zoneHigh = candidate.midpoint() * (1.0 + TARGET_ZONE_PERCENT / 100.0);
        double trigger = direction == TradeSignal.BUY ? zoneLow : zoneHigh;
        double reward = direction == TradeSignal.BUY ? trigger - entry : entry - trigger;
        if (!Double.isFinite(reward) || reward <= 0.0) return null;
        return new QualifiedTarget(candidate, zoneLow, zoneHigh, trigger, reward / risk);
    }

    private static TargetCandidate target(double midpoint, String basis, Double fibonacciRatio) {
        return new TargetCandidate(midpoint, basis, fibonacciRatio);
    }

    private static TargetCandidate logarithmicBearishWaveThreeTarget(
            double wave0,
            double wave1,
            double wave2) {
        if (wave0 <= 0.0 || wave1 <= 0.0 || wave2 <= 0.0 || wave1 >= wave0) return null;
        double midpoint = wave2 * Math.pow(wave1 / wave0, 1.618);
        return target(midpoint,
                "Logarithmic Wave III at 161.8% after both arithmetic extensions reached zero",
                1.618);
    }

    private static Double price(
            List<ElliottWaveDetectionService.ElliottWavePoint> points,
            String label) {
        return points.stream()
                .filter(point -> point != null && label.equalsIgnoreCase(point.label()))
                .map(ElliottWaveDetectionService.ElliottWavePoint::price)
                .filter(value -> Double.isFinite(value) && value > 0.0)
                .findFirst().orElse(null);
    }

    enum BoundarySide {
        BELOW,
        ABOVE
    }

    record TradePlan(
            String version,
            ElliottSignalStage stage,
            TradeSignal expectedMove,
            double entryPrice,
            double structuralStopPrice,
            double stopLossPrice,
            double stopBuffer,
            Double hardInvalidationPrice,
            BoundarySide hardInvalidationSide,
            double targetMidpoint,
            double targetZoneLow,
            double targetZoneHigh,
            double targetTriggerPrice,
            String targetBasis,
            Double fibonacciRatio,
            double targetZonePercent,
            double requiredRewardRiskRatio,
            double actualRewardRiskRatio,
            boolean actionable,
            String qualification) {
    }

    private record TargetCandidate(double midpoint, String basis, Double fibonacciRatio) {
    }

    private record QualifiedTarget(
            TargetCandidate candidate,
            double zoneLow,
            double zoneHigh,
            double triggerPrice,
            double rewardRiskRatio) {
    }
}
