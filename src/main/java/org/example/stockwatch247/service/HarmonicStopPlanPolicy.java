package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.model.enums.TimeInterval;

import java.util.List;
import java.util.Optional;

/**
 * Pure stop calculation for a formation that the harmonic detector has already validated.
 * This class never participates in pivot discovery, formation classification, scoring,
 * confluence, or signal eligibility.
 */
final class HarmonicStopPlanPolicy {
    /** V3 enters at the next candle's open; stored V2 plans entered at the confirmation close. */
    static final String VERSION = "HARMONIC_TRADE_V3";
    static final String PREVIOUS_VERSION = "HARMONIC_TRADE_V2";

    static boolean qualifiedPlan(String version) {
        return VERSION.equals(version) || PREVIOUS_VERSION.equals(version);
    }

    static boolean entersAtNextOpen(String version) {
        return VERSION.equals(version);
    }

    static double requiredRewardRisk(TimeInterval interval) {
        return interval == TimeInterval.DAILY ? 2 : 3;
    }

    /** V3 plans scale out: half at the first target, the stop to breakeven, the rest at the second. */
    static boolean scalesOut(String version) {
        return VERSION.equals(version);
    }

    /** Minimum blended reward/risk for a scale-out plan. */
    static double scaleOutRequiredRewardRisk(TimeInterval interval) {
        return interval == TimeInterval.DAILY ? 1.5 : 2.0;
    }

    /** The average exit if both halves reach their targets; a single target when there is no second one. */
    static double blendedTarget(double primary, Double secondary) {
        return secondary == null ? primary : (primary + secondary) / 2.0;
    }
    static final double EQUITY_BUFFER_PERCENT = .50;

    private HarmonicStopPlanPolicy() {
    }

    static Optional<StopPlan> calculate(
            HarmonicPatternDetectionService.HarmonicFormation formation,
            double confirmationEntryPrice) {
        return calculate(formation, confirmationEntryPrice, Double.NaN, TimeInterval.DAILY);
    }

    static Optional<StopPlan> calculate(HarmonicPatternDetectionService.HarmonicFormation formation,
                                        double confirmationEntryPrice, double atr, TimeInterval interval) {
        return calculate(formation, confirmationEntryPrice, atr, interval, null);
    }

    /** {@code userRequiredRewardRisk}: the user's minimum reward/risk; null uses the interval default. */
    static Optional<StopPlan> calculate(HarmonicPatternDetectionService.HarmonicFormation formation,
                                        double confirmationEntryPrice, double atr, TimeInterval interval,
                                        Double userRequiredRewardRisk) {
        if (formation == null || formation.pattern() == null || formation.tradeSignal() == null
                || formation.tradeSignal() != TradeSignal.BUY
                && formation.tradeSignal() != TradeSignal.SELL
                || !Double.isFinite(confirmationEntryPrice) || confirmationEntryPrice <= 0.0) {
            return Optional.empty();
        }
        List<HarmonicPatternDetectionService.HarmonicPoint> points = formation.points();
        if (points == null || points.size() < 4 || points.size() > 5) return Optional.empty();

        StructuralLevel level = structuralLevel(formation.pattern(), points).orElse(null);
        if (level == null || !Double.isFinite(level.price()) || level.price() <= 0.0) {
            return Optional.empty();
        }
        boolean buy = formation.tradeSignal() == TradeSignal.BUY;
        if (buy && level.price() >= confirmationEntryPrice
                || !buy && level.price() <= confirmationEntryPrice) {
            return Optional.empty();
        }
        double buffer = TradeRiskPolicy.buffer(confirmationEntryPrice, atr, interval);
        double stop = TradeRiskPolicy.roundStop(buy ? level.price() - buffer : level.price() + buffer,
                formation.tradeSignal(), confirmationEntryPrice);
        if (!Double.isFinite(stop) || stop <= 0.0) return Optional.empty();
        double distance = Math.abs(confirmationEntryPrice - stop);
        double distancePercent = distance / confirmationEntryPrice * 100.0;
        boolean shark = formation.pattern() == HarmonicPatternType.SHARK;
        boolean measuredFromC = shark || formation.pattern() == HarmonicPatternType.CYPHER
                || formation.pattern() == HarmonicPatternType.FIVE_ZERO;
        Double endpoint = price(points, "D");
        Double anchor = price(points, measuredFromC ? "C" : "A");
        if (endpoint == null || anchor == null) return Optional.empty();
        double primary = endpoint + (anchor - endpoint) * (shark ? .50 : .382);
        Double secondary = shark ? null : endpoint + (anchor - endpoint) * .618;
        // Taking half at the 38.2% target and the rest at 61.8% gives an achievable blended reward; the
        // first target alone rarely clears 2R for patterns whose stop sits beyond X.
        double blended = blendedTarget(primary, secondary);
        double requiredRr = userRequiredRewardRisk != null && Double.isFinite(userRequiredRewardRisk)
                && userRequiredRewardRisk > 0 ? userRequiredRewardRisk : scaleOutRequiredRewardRisk(interval);
        var qualification = TradeRiskPolicy.qualify(formation.tradeSignal(), confirmationEntryPrice,
                stop, blended, atr, interval, requiredRr);
        int horizon = shark ? switch (interval) { case DAILY -> 5; case WEEKLY -> 4; default -> 3; }
                : TradeRiskPolicy.profile(interval).harmonicHorizon();
        return Optional.of(new StopPlan(
                VERSION, formation.pattern(), formation.tradeSignal(), confirmationEntryPrice,
                level.price(), buffer, buffer / level.price() * 100, stop,
                distance, distancePercent, level.basis(), level.formula(), primary, secondary,
                TradeRiskPolicy.reward(formation.tradeSignal(), confirmationEntryPrice, blended) / distance,
                qualification.actionable(), qualification.reason(), qualification.riskAtr(), horizon,
                (shark ? "50% CD reaction target" : measuredFromC
                        ? "38.2% CD primary / 61.8% CD secondary" : "38.2% AD primary / 61.8% AD secondary")
                        + (secondary == null ? "" : "; half exits at the first target, the stop then moves to breakeven, the rest exits at the second")));
    }

    private static Optional<StructuralLevel> structuralLevel(
            HarmonicPatternType pattern,
            List<HarmonicPatternDetectionService.HarmonicPoint> points) {
        Double x = price(points, "X");
        return switch (pattern) {
            case GARTLEY -> fixed(x, "Point X / 1.0 XA",
                    "Internal Gartley invalidation at Point X");
            case BAT -> fixed(x, "Point X / 1.0 XA",
                    "Internal Bat invalidation at Point X");
            case CYPHER -> fixed(x, "Point X / 1.0 XC boundary",
                    "Cypher invalidation at Point X beyond the 0.786 XC completion");
            case BUTTERFLY -> xaExtension(points, 1.414,
                    "1.414 XA extension", "A + (X - A) × 1.414");
            case CRAB, DEEP_CRAB -> xaExtension(points, 2.0,
                    "2.0 XA extension", "A + (X - A) × 2.0");
            case ALTERNATE_BAT -> xaExtension(points, 1.27, "1.27 XA extension", "A + (X - A) x 1.27");
            case FIVE_ZERO -> fixed(price(points, "B"), "Point B", "5-0 origin of BC");
            case AB_CD, ALTERNATE_AB_CD -> fixed(price(points, "D"), "Point D", "Terminal D plus volatility buffer");
            case SHARK -> xaExtension(points, 1.27,
                    "1.27 XA extension", "A + (X - A) × 1.27");
        };
    }

    private static Optional<StructuralLevel> fixed(
            Double value, String basis, String formula) {
        return value == null ? Optional.empty()
                : Optional.of(new StructuralLevel(value, basis, formula));
    }

    private static Optional<StructuralLevel> xaExtension(
            List<HarmonicPatternDetectionService.HarmonicPoint> points,
            double ratio,
            String basis,
            String formula) {
        Double x = price(points, "X");
        Double a = price(points, "A");
        if (x == null || a == null) return Optional.empty();
        return Optional.of(new StructuralLevel(a + (x - a) * ratio, basis, formula));
    }

    private static Double price(
            List<HarmonicPatternDetectionService.HarmonicPoint> points,
            String label) {
        return points.stream()
                .filter(point -> point != null && label.equalsIgnoreCase(point.label()))
                .map(HarmonicPatternDetectionService.HarmonicPoint::price)
                .filter(value -> Double.isFinite(value) && value > 0.0)
                .findFirst().orElse(null);
    }

    record StopPlan(
            String version,
            HarmonicPatternType pattern,
            TradeSignal tradeSignal,
            double entryPrice,
            double structuralInvalidationPrice,
            double bufferAmount,
            double bufferPercent,
            double stopLossPrice,
            double stopDistance,
            double stopDistancePercent,
            String basis,
            String formula, double primaryTarget, Double secondaryTarget, double rewardRisk,
            boolean actionable, String qualification, Double riskAtr, int horizon, String targetBasis) {
    }

    private record StructuralLevel(double price, String basis, String formula) {
    }
}
