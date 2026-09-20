package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TradeSignal;

/** Modeled protective-order fills, independent of pattern validity. */
final class TradeOutcomePolicy {
    enum Kind { STOPPED, TARGET_REACHED, TIME_STOPPED }
    record Outcome(Kind kind, double price, String reason) {}
    static Outcome evaluate(TradeSignal side, double stop, Double target, Candle candle) {
        if ((side != TradeSignal.BUY && side != TradeSignal.SELL) || !TradeRiskPolicy.valid(candle) || !TradeRiskPolicy.positive(stop)) return null;
        boolean buy = side == TradeSignal.BUY;
        double open = candle.getOpenPrice();
        if (buy ? open <= stop : open >= stop)
            return new Outcome(Kind.STOPPED, open, "Opening gap crossed the protective stop; modeled exit at the open, before costs.");
        if (TradeRiskPolicy.positive(target) && (buy ? open >= target : open <= target))
            return new Outcome(Kind.TARGET_REACHED, target, "Target crossed at the open; conservative limit-price exit, before costs.");
        boolean stopped = buy ? candle.getLowPrice() <= stop : candle.getHighPrice() >= stop;
        boolean reached = TradeRiskPolicy.positive(target) && (buy ? candle.getHighPrice() >= target : candle.getLowPrice() <= target);
        if (stopped) return new Outcome(Kind.STOPPED, stop, reached
                ? "Both levels touched; intrabar order unknown, so stop-first is assumed, before costs."
                : "Protective stop touched; modeled stop-price exit, before costs.");
        return reached ? new Outcome(Kind.TARGET_REACHED, target, "Primary target touched; modeled limit-price exit, before costs.") : null;
    }
}
