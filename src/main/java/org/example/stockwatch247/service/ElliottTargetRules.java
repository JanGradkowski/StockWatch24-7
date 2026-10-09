package org.example.stockwatch247.service;

import java.util.List;
import org.example.stockwatch247.model.enums.ElliottSignalStage;

/** Standard-impulse target constraints shared by trade plans, detector previews and scenarios.
 * Diagonals require an explicit different policy; they must not bypass these checks implicitly. */
final class ElliottTargetRules {
    /** Shallowest textbook Wave IV retracement; less legal room leaves no standard target. */
    static final double COMMON_WAVE_FOUR_MIN_RETRACEMENT = .236;

    /**
     * Fraction of Wave III that Wave IV may retrace before overlapping Wave I territory.
     * NaN when Wave III does not progress beyond Wave I. This is a guideline input only:
     * a small positive room keeps the count valid; only the overlap itself breaks it.
     */
    static double waveFourRoom(double wave1, double wave2, double wave3) {
        double waveThree = wave3 - wave2;
        double room = wave3 - wave1;
        if (!Double.isFinite(waveThree) || !Double.isFinite(room) || waveThree == 0.0
                || Math.signum(room) != Math.signum(waveThree)) return Double.NaN;
        return room / waveThree;
    }

    static double waveFourRoom(List<ElliottWaveDetectionService.ElliottWavePoint> points) {
        Double w1 = price(points, "I"), w2 = price(points, "II"), w3 = price(points, "III");
        return w1 == null || w2 == null || w3 == null ? Double.NaN : waveFourRoom(w1, w2, w3);
    }

    static boolean allowed(ElliottSignalStage stage, boolean bullish,
                           List<ElliottWaveDetectionService.ElliottWavePoint> points,
                           double low, double high) {
        return allowed(stage, bullish, points, low, high, false);
    }

    /**
     * With {@code logScale}, wave lengths are compared as percentage moves (log distances), matching
     * targets that were projected on a log scale; the ordering checks are the same on either scale.
     */
    static boolean allowed(ElliottSignalStage stage, boolean bullish,
                           List<ElliottWaveDetectionService.ElliottWavePoint> points,
                           double low, double high, boolean logScale) {
        if (!TradeRiskPolicy.positive(low) || !TradeRiskPolicy.positive(high) || low > high) return false;
        Double w0 = price(points, "0"), w1 = price(points, "I"), w2 = price(points, "II");
        if (w0 == null || w1 == null || w2 == null) return false;
        if (stage == ElliottSignalStage.WAVE_II_END) return bullish ? low > w1 : high < w1;
        if (stage == ElliottSignalStage.WAVE_III_END) return bullish ? low > w1 : high < w1;
        if (stage == ElliottSignalStage.WAVE_IV_END) {
            Double w3 = price(points, "III"), w4 = price(points, "IV");
            if (w3 == null || w4 == null || (bullish ? w4 <= w1 : w4 >= w1)) return false;
            if (logScale) {
                // Wave V may not travel further, in percentage terms, than Wave III when Wave I is longer.
                double three = Math.abs(Math.log(w3 / w2)), one = Math.abs(Math.log(w1 / w0));
                if (one > three && (bullish ? high > w4 * Math.exp(three) : low < w4 * Math.exp(-three))) return false;
            } else {
                double three = Math.abs(w3 - w2), one = Math.abs(w1 - w0);
                if (one > three && (bullish ? high > w4 + three : low < w4 - three)) return false;
            }
        }
        return true;
    }
    static Double price(List<ElliottWaveDetectionService.ElliottWavePoint> points, String label) {
        return points.stream().filter(p -> p != null && (label.equalsIgnoreCase(p.label()) || ("0".equals(label) && "".equals(p.label()))))
                .map(ElliottWaveDetectionService.ElliottWavePoint::price).filter(TradeRiskPolicy::positive)
                .findFirst().orElse(null);
    }

    /** Display precision matching the trade-plan price increment. */
    static String formatPrice(double price) {
        return String.format(java.util.Locale.ROOT, price >= 1 ? "%.2f" : "%.4f", price);
    }
}
