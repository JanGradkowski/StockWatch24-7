package org.example.stockwatch247.service;

import java.util.List;
import org.example.stockwatch247.model.enums.ElliottSignalStage;

/** Standard-impulse target constraints shared by trade plans, detector previews and scenarios.
 * Diagonals require an explicit different policy; they must not bypass these checks implicitly. */
final class ElliottTargetRules {
    static boolean allowed(ElliottSignalStage stage, boolean bullish,
                           List<ElliottWaveDetectionService.ElliottWavePoint> points,
                           double low, double high) {
        if (!TradeRiskPolicy.positive(low) || !TradeRiskPolicy.positive(high) || low > high) return false;
        Double w0 = price(points, "0"), w1 = price(points, "I"), w2 = price(points, "II");
        if (w0 == null || w1 == null || w2 == null) return false;
        if (stage == ElliottSignalStage.WAVE_II_END) return bullish ? low > w1 : high < w1;
        if (stage == ElliottSignalStage.WAVE_III_END) return bullish ? low > w1 : high < w1;
        if (stage == ElliottSignalStage.WAVE_IV_END) {
            Double w3 = price(points, "III"), w4 = price(points, "IV");
            if (w3 == null || w4 == null || (bullish ? w4 <= w1 : w4 >= w1)) return false;
            double three = Math.abs(w3 - w2), one = Math.abs(w1 - w0);
            if (one > three && (bullish ? high > w4 + three : low < w4 - three)) return false;
        }
        return true;
    }
    static Double price(List<ElliottWaveDetectionService.ElliottWavePoint> points, String label) {
        return points.stream().filter(p -> p != null && (label.equalsIgnoreCase(p.label()) || ("0".equals(label) && "".equals(p.label()))))
                .map(ElliottWaveDetectionService.ElliottWavePoint::price).filter(TradeRiskPolicy::positive)
                .findFirst().orElse(null);
    }
}
