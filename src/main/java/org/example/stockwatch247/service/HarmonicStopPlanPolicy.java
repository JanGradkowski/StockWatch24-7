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
    static final String VERSION = "HARMONIC_TRADE_V2";
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
        if (formation == null || formation.pattern() == null || formation.tradeSignal() == null
                || formation.tradeSignal() != TradeSignal.BUY
                && formation.tradeSignal() != TradeSignal.SELL
                || !Double.isFinite(confirmationEntryPrice) || confirmationEntryPrice <= 0.0) {
            return Optional.empty();
        }
        List<HarmonicPatternDetectionService.HarmonicPoint> points = formation.points();
        if (points == null || points.size() != 5) return Optional.empty();

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
        Double endpoint = price(points, shark ? "C" : "D");
        Double anchor = price(points, shark ? "B" : formation.pattern() == HarmonicPatternType.CYPHER ? "C" : "A");
        if (endpoint == null || anchor == null) return Optional.empty();
        double primary = endpoint + (anchor - endpoint) * (shark ? .50 : .382);
        Double secondary = shark ? null : endpoint + (anchor - endpoint) * .618;
        double requiredRr = interval == TimeInterval.DAILY ? 2 : 3;
        var qualification = TradeRiskPolicy.qualify(formation.tradeSignal(), confirmationEntryPrice,
                stop, primary, atr, interval, requiredRr);
        int horizon = shark ? switch (interval) { case DAILY -> 5; case WEEKLY -> 4; default -> 3; }
                : TradeRiskPolicy.profile(interval).harmonicHorizon();
        return Optional.of(new StopPlan(
                VERSION, formation.pattern(), formation.tradeSignal(), confirmationEntryPrice,
                level.price(), buffer, buffer / level.price() * 100, stop,
                distance, distancePercent, level.basis(), level.formula(), primary, secondary,
                TradeRiskPolicy.reward(formation.tradeSignal(), confirmationEntryPrice, primary) / distance,
                qualification.actionable(), qualification.reason(), qualification.riskAtr(), horizon,
                shark ? "50% BC reaction target" : formation.pattern() == HarmonicPatternType.CYPHER
                        ? "38.2% CD primary / 61.8% CD secondary" : "38.2% AD primary / 61.8% AD secondary"));
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
            case CRAB -> xaExtension(points, 2.0,
                    "2.0 XA extension", "A + (X - A) × 2.0");
            case SHARK -> oxExtension(points, 1.27,
                    "1.27 OX extension", "X + (0 - X) × 1.27");
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

    private static Optional<StructuralLevel> oxExtension(
            List<HarmonicPatternDetectionService.HarmonicPoint> points,
            double ratio,
            String basis,
            String formula) {
        Double origin = price(points, "0");
        Double x = price(points, "X");
        if (origin == null || x == null) return Optional.empty();
        return Optional.of(new StructuralLevel(x + (origin - x) * ratio, basis, formula));
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
