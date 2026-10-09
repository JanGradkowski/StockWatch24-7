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
    /** V4 enters at the next candle's open; stored V3 plans entered at the notification close. */
    static final String VERSION = "ELLIOTT_FIB_RR_V4";
    static final String PREVIOUS_VERSION = "ELLIOTT_FIB_RR_V3";

    static boolean qualifiedPlan(String version) {
        return VERSION.equals(version) || PREVIOUS_VERSION.equals(version);
    }

    static boolean entersAtNextOpen(String version) {
        return VERSION.equals(version);
    }
    static final double TARGET_ZONE_PERCENT = 1.5;
    private static final double ATR_STOP_BUFFER_MULTIPLIER = 0.10;
    private static final double FALLBACK_STOP_BUFFER_PERCENT = 0.10;

    private ElliottTradePlanPolicy() {
    }

    static double defaultRequiredRewardRisk(TimeInterval interval) {
        return interval == TimeInterval.DAILY ? 2.0 : 3.0;
    }

    static Optional<TradePlan> calculate(
            ElliottSignalStage stage,
            String cycleDirection,
            TradeSignal expectedMove,
            double entryPrice,
            List<ElliottWaveDetectionService.ElliottWavePoint> points,
            double atr,
            TimeInterval interval) {
        return calculate(stage, cycleDirection, expectedMove, entryPrice, points, atr, interval, null);
    }

    /** {@code requiredRewardRisk}: the user's minimum reward/risk; null uses the interval default. */
    static Optional<TradePlan> calculate(
            ElliottSignalStage stage,
            String cycleDirection,
            TradeSignal expectedMove,
            double entryPrice,
            List<ElliottWaveDetectionService.ElliottWavePoint> points,
            double atr,
            TimeInterval interval,
            Double requiredRewardRisk) {
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
        // Weekly and monthly swings span large percentage moves, so their Fibonacci ratios are
        // measured on a log scale (equal ratios of percentage change); daily swings stay arithmetic.
        if (logScale(interval) && positivePrices(points)) {
            return logScaleTargets(stage, bullishCycle, points).flatMap(log -> finish(stage, expectedMove,
                    entryPrice, points, atr, interval, requiredRewardRisk, bullishCycle,
                    log.structuralStop(), log.hardInvalidation(), log.hardInvalidationSide(), log.targets()));
        }
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
                hardInvalidation = wave1;
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
        return finish(stage, expectedMove, entryPrice, points, atr, interval, requiredRewardRisk, bullishCycle,
                structuralStop, hardInvalidation, hardInvalidationSide, targets);
    }

    static boolean logScale(TimeInterval interval) {
        return interval == TimeInterval.WEEKLY || interval == TimeInterval.MONTHLY;
    }

    private static boolean positivePrices(List<ElliottWaveDetectionService.ElliottWavePoint> points) {
        return points.stream().allMatch(point -> Double.isFinite(point.price()) && point.price() > 0.0);
    }

    private record StructureTargets(Double structuralStop, Double hardInvalidation,
                                    BoundarySide hardInvalidationSide, List<TargetCandidate> targets) {
    }

    /** {@code from} moved by {@code ratio} times the log-move from {@code a} to {@code b}. */
    private static double logProjection(double from, double a, double b, double ratio) {
        return from * Math.pow(b / a, ratio);
    }

    private static Optional<StructureTargets> logScaleTargets(
            ElliottSignalStage stage, boolean bullishCycle, List<ElliottWaveDetectionService.ElliottWavePoint> points) {
        double wave0 = price(points, "0");
        double wave1 = price(points, "I");
        double wave2 = price(points, "II");
        BoundarySide beyondOrigin = bullishCycle ? BoundarySide.BELOW : BoundarySide.ABOVE;
        List<TargetCandidate> targets = new ArrayList<>();
        switch (stage) {
            case WAVE_II_END -> {
                targets.add(target(logProjection(wave2, wave0, wave1, 1.618),
                        "Wave III at 161.8% of Wave I from Wave II (log scale)", 1.618));
                targets.add(target(logProjection(wave2, wave0, wave1, 2.618),
                        "Extended Wave III at 261.8% of Wave I from Wave II (log scale)", 2.618));
                return Optional.of(new StructureTargets(wave0, wave0, beyondOrigin, targets));
            }
            case WAVE_III_END -> {
                Double wave3 = price(points, "III");
                if (wave3 == null) return Optional.empty();
                targets.add(target(logProjection(wave3, wave3, wave2, .382),
                        "Wave IV at a 38.2% retracement of Wave III (log scale)", .382));
                targets.add(target(logProjection(wave3, wave3, wave2, .50),
                        "Deeper Wave IV at a 50.0% retracement of Wave III (log scale)", .50));
                return Optional.of(new StructureTargets(wave3, wave1, beyondOrigin, targets));
            }
            case WAVE_IV_END -> {
                Double wave3 = price(points, "III");
                Double wave4 = price(points, "IV");
                if (wave3 == null || wave4 == null) return Optional.empty();
                targets.add(target(logProjection(wave4, wave0, wave1, 1.0),
                        "Wave V at equality with Wave I from Wave IV (log scale)", 1.0));
                targets.add(target(logProjection(wave4, wave0, wave3, .618),
                        "Wave V at 61.8% of the Wave 0-to-III advance from Wave IV (log scale)", .618));
                return Optional.of(new StructureTargets(wave4, wave1, beyondOrigin, targets));
            }
            case WAVE_V_END -> {
                Double wave3 = price(points, "III");
                Double wave4 = price(points, "IV");
                Double wave5 = price(points, "V");
                if (wave3 == null || wave4 == null || wave5 == null) return Optional.empty();
                // Shortest-wave rule in percentage terms, consistent with the log-scale targets:
                // Wave V cannot outrun Wave III's percentage move when Wave I was the longer one.
                double waveOneLength = Math.abs(Math.log(wave1 / wave0));
                double waveThreeLength = Math.abs(Math.log(wave3 / wave2));
                Double hard = null;
                BoundarySide hardSide = null;
                if (waveOneLength > waveThreeLength) {
                    hard = wave4 * (wave3 / wave2);
                    hardSide = bullishCycle ? BoundarySide.ABOVE : BoundarySide.BELOW;
                }
                targets.add(target(wave4, "Correction toward the previous Wave IV territory", null));
                targets.add(target(logProjection(wave5, wave5, wave0, .382),
                        "Correction at a 38.2% retracement of the complete impulse (log scale)", .382));
                return Optional.of(new StructureTargets(wave5, hard, hardSide, targets));
            }
            case CORRECTION_END -> {
                Double wave5 = price(points, "V");
                Double waveC = price(points, "C");
                if (wave5 == null || waveC == null) return Optional.empty();
                targets.add(target(wave5, "Primary-trend resumption toward the prior Wave V extreme", null));
                return Optional.of(new StructureTargets(waveC, wave0, beyondOrigin, targets));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    private static Optional<TradePlan> finish(
            ElliottSignalStage stage, TradeSignal expectedMove, double entryPrice,
            List<ElliottWaveDetectionService.ElliottWavePoint> points, double atr, TimeInterval interval,
            Double requiredRewardRisk, boolean bullishCycle, Double structuralStop, Double hardInvalidation,
            BoundarySide hardInvalidationSide, List<TargetCandidate> targets) {
        if (structuralStop == null) return Optional.empty();
        double stopBuffer = TradeRiskPolicy.buffer(entryPrice, atr, interval);
        double stopLoss = TradeRiskPolicy.roundStop(expectedMove == TradeSignal.BUY
                ? structuralStop - stopBuffer : structuralStop + stopBuffer, expectedMove, entryPrice);
        double risk = expectedMove == TradeSignal.BUY
                ? entryPrice - stopLoss : stopLoss - entryPrice;
        if (!Double.isFinite(risk) || risk <= 0.0 || stopLoss <= 0.0) {
            return Optional.empty();
        }

        double required = requiredRewardRisk != null && Double.isFinite(requiredRewardRisk) && requiredRewardRisk > 0
                ? requiredRewardRisk : defaultRequiredRewardRisk(interval);
        List<QualifiedTarget> validTargets = targets.stream()
                .filter(candidate -> candidate != null && Double.isFinite(candidate.midpoint())
                        && candidate.midpoint() > 0.0)
                .map(candidate -> qualify(candidate, expectedMove, entryPrice, risk, atr))
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> ElliottTargetRules.allowed(stage, bullishCycle, points, candidate.zoneLow(),
                        candidate.zoneHigh(), logScale(interval) && positivePrices(points)))
                .sorted(java.util.Comparator.comparingDouble(QualifiedTarget::rewardRiskRatio))
                .toList();
        if (validTargets.isEmpty()) return Optional.empty();
        QualifiedTarget selected = validTargets.getFirst();
        TradeRiskPolicy.Qualification riskCheck = TradeRiskPolicy.qualify(expectedMove, entryPrice,
                stopLoss, selected.triggerPrice(), atr, interval, required);
        boolean actionable = riskCheck.actionable();
        String qualification = riskCheck.reason();
        Double secondaryTarget = validTargets.size() > 1 ? validTargets.get(1).triggerPrice() : null;
        return Optional.of(new TradePlan(
                VERSION, stage, expectedMove, entryPrice,
                structuralStop, stopLoss, stopBuffer,
                hardInvalidation, hardInvalidationSide,
                selected.candidate().midpoint(), selected.zoneLow(), selected.zoneHigh(),
                selected.triggerPrice(), selected.candidate().basis(),
                selected.candidate().fibonacciRatio(), (selected.zoneHigh() - selected.zoneLow()) / selected.candidate().midpoint() * 50,
                required, selected.rewardRiskRatio(), actionable, qualification,
                secondaryTarget, riskCheck.riskAtr(), riskCheck.riskPercent(),
                TradeRiskPolicy.profile(interval).elliottHorizon()));
    }

    private static QualifiedTarget qualify(
            TargetCandidate candidate,
            TradeSignal direction,
            double entry,
            double risk, double atr) {
        double width = TradeRiskPolicy.zoneWidth(candidate.midpoint(), atr);
        double zoneLow = candidate.midpoint() - width;
        double zoneHigh = candidate.midpoint() + width;
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
        return ElliottTargetRules.price(points, label);
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
            String qualification, Double secondaryTarget, Double riskAtr, double riskPercent, int horizon) {
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
