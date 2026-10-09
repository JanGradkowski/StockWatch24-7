package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import java.util.List;

/** Shared, causal risk checks. Defaults are conservative guardrails, not fitted probabilities. */
final class TradeRiskPolicy {
    private TradeRiskPolicy() {}
    record Profile(double maximumRiskAtr, double maximumRiskPercent, double bufferAtr,
                   int candleHorizon, int harmonicHorizon, int elliottHorizon) {}
    static Profile profile(TimeInterval interval) {
        return switch (interval) {
            case DAILY -> new Profile(3, 12.5, .20, 8, 12, 20);
            case WEEKLY -> new Profile(4, 20, .20, 8, 10, 16);
            case MONTHLY -> new Profile(5, 30, .25, 6, 8, 12);
            default -> throw new IllegalArgumentException("Unsupported trade-plan interval: " + interval);
        };
    }
    static boolean positive(Double price) { return price != null && Double.isFinite(price) && price > 0; }
    static boolean valid(Candle c) {
        return c != null && positive(c.getOpenPrice()) && positive(c.getHighPrice())
                && positive(c.getLowPrice()) && positive(c.getClosePrice())
                && c.getLowPrice() <= Math.min(c.getOpenPrice(), c.getClosePrice())
                && c.getHighPrice() >= Math.max(c.getOpenPrice(), c.getClosePrice());
    }
    // Precision floor only; execution venues may require a coarser instrument-specific tick.
    static double increment(double price) { return price >= 1 ? .01 : .0001; }
    static double roundStop(double price, TradeSignal side, double entry) {
        double tick = increment(entry);
        return java.math.BigDecimal.valueOf(price).divide(java.math.BigDecimal.valueOf(tick), 0,
                side == TradeSignal.BUY ? java.math.RoundingMode.FLOOR : java.math.RoundingMode.CEILING)
                .multiply(java.math.BigDecimal.valueOf(tick)).doubleValue();
    }
    static double buffer(double entry, double atr, TimeInterval interval) {
        return Math.max(increment(entry), positive(atr) ? atr * profile(interval).bufferAtr() : entry * .001);
    }
    static double zoneWidth(double target, double atr) {
        return Math.min(target * .015, Math.max(increment(target), positive(atr) ? atr * .25 : target * .001));
    }
    static final String AWAITING_FILL = "AWAITING_FILL";
    static final String FILLED = "FILLED";
    static final String NOT_FILLED = "NOT_FILLED";

    /**
     * Signals are found after the signal candle has closed, so the first tradable price is the next
     * candle's open. That open fills the entry unless it is already at or beyond the stop (the trade
     * would be stopped before it existed) or the target (the move happened before any entry).
     * Returns the reason the plan was not filled, or null when the open is a valid fill.
     */
    static String unfilledReason(TradeSignal side, double open, double stop, Double target) {
        if (!Double.isFinite(open) || open <= 0) return "Not filled: the next candle has no valid opening price.";
        boolean buy = side == TradeSignal.BUY;
        if (buy ? open <= stop : open >= stop)
            return "Not filled: the next candle opened at or beyond the stop, so no entry was taken.";
        if (positive(target) && (buy ? open >= target : open <= target))
            return "Not filled: the next candle opened at or beyond the target, so the move happened before any entry.";
        return null;
    }

    /** Stop below and target above the entry for a buy; the reverse for a sell. */
    static boolean validSides(TradeSignal side, double entry, double stop, double target) {
        if (!Double.isFinite(entry) || !Double.isFinite(stop) || !Double.isFinite(target)) return false;
        if (side == TradeSignal.BUY) return stop < entry && entry < target;
        if (side == TradeSignal.SELL) return target < entry && entry < stop;
        return false;
    }
    static double risk(TradeSignal side, double entry, double stop) {
        return side == TradeSignal.BUY ? entry - stop : stop - entry;
    }
    static double reward(TradeSignal side, double entry, double target) {
        return side == TradeSignal.BUY ? target - entry : entry - target;
    }
    record Qualification(boolean actionable, String reason, Double riskAtr, double riskPercent) {}
    static Qualification qualify(TradeSignal side, double entry, double stop, double target,
                                 double atr, TimeInterval interval, double requiredRr) {
        double risk = risk(side, entry, stop);
        double percent = positive(entry) && Double.isFinite(risk) ? risk / entry * 100 : 0;
        Double units = positive(atr) && Double.isFinite(risk) ? risk / atr : null;
        String reason = null;
        if ((side != TradeSignal.BUY && side != TradeSignal.SELL) || !positive(entry)
                || !positive(stop) || !positive(target) || risk < increment(entry) * .999)
            reason = "Projection only: invalid prices or stop closer than one price increment.";
        else if (!positive(atr)) reason = "Projection only: insufficient valid volatility history.";
        else if (percent > profile(interval).maximumRiskPercent() || units > profile(interval).maximumRiskAtr())
            reason = "Valid pattern; entry risk too wide. Structural stop has been preserved.";
        else if (reward(side, entry, target) / risk + .000001 < requiredRr)
            reason = "Projection only: the primary target does not meet the minimum reward/risk.";
        return new Qualification(reason == null, reason == null
                ? "Qualified against the primary target and interval risk limits." : reason, units, percent);
    }
    /** Wilder ATR seeded from the first complete period; never reads beyond the supplied index. */
    static double atr(List<Candle> candles, int through, int period) {
        if (candles == null || period < 2 || through < period - 1 || through >= candles.size()) return Double.NaN;
        double value = 0;
        for (int i = 0; i <= through; i++) {
            Candle c = candles.get(i);
            if (!valid(c)) return Double.NaN;
            double tr = c.getHighPrice() - c.getLowPrice();
            if (i > 0) tr = Math.max(tr, Math.max(Math.abs(c.getHighPrice() - candles.get(i-1).getClosePrice()),
                    Math.abs(c.getLowPrice() - candles.get(i-1).getClosePrice())));
            if (i < period) value += tr / period;
            else value = (value * (period - 1) + tr) / period;
        }
        return positive(value) ? value : Double.NaN;
    }
    /** Confirmed two-sided pivots only; entry candle and future candles cannot create resistance/support. */
    static double nearestObjective(List<Candle> candles, int through, TradeSignal side, double entry, double fallback) {
        double result = fallback;
        if (candles == null) return result;
        for (int i = Math.max(2, through - 60); i + 2 <= through && i + 2 < candles.size(); i++) {
            if (!valid(candles.get(i))) continue;
            double level = side == TradeSignal.BUY ? candles.get(i).getHighPrice() : candles.get(i).getLowPrice();
            boolean pivot = true;
            for (int j = i - 2; j <= i + 2; j++) {
                if (!valid(candles.get(j))) { pivot = false; break; }
                if (side == TradeSignal.BUY ? candles.get(j).getHighPrice() > level : candles.get(j).getLowPrice() < level) pivot = false;
            }
            double reward = reward(side, entry, level);
            if (pivot && reward > increment(entry) && reward < reward(side, entry, result)) result = level;
        }
        return result;
    }
}
