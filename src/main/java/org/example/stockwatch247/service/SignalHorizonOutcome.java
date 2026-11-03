package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;

import java.util.List;

/**
 * The outcome of a signal that has no usable trade plan (no stop and target could be built, or the signal
 * predates stored plans): bought or sold at the open of the next candle and valued at the close a fixed
 * number of candles later, the family's own trade horizon. Until then it is valued at the latest
 * completed close. SignalArchiveQuery computes the same numbers in SQL for ordering.
 */
final class SignalHorizonOutcome {
    private SignalHorizonOutcome() {
    }

    /** The family's trade horizon (TradeRiskPolicy); other intervals use the daily one. */
    static int horizonCandles(AlertPatternFamily family, TimeInterval interval) {
        TimeInterval supported = interval == TimeInterval.WEEKLY || interval == TimeInterval.MONTHLY
                ? interval : TimeInterval.DAILY;
        TradeRiskPolicy.Profile profile = TradeRiskPolicy.profile(supported);
        return switch (family == null ? AlertPatternFamily.CANDLESTICK : family) {
            case HARMONIC_FORMATION -> profile.harmonicHorizon();
            case ELLIOTT_WAVE -> profile.elliottHorizon();
            default -> profile.candleHorizon();
        };
    }

    /**
     * @param complete whether the horizon has passed, so {@code exit} is final
     */
    record Measurement(double entry, double exit, int observed, boolean complete) {
    }

    /**
     * Measures the completed candles that follow the signal, in time order. Returns null when there is no
     * such candle yet or its prices are not valid.
     */
    static Measurement measure(List<Candle> following, int horizon) {
        if (following == null || following.isEmpty() || horizon < 1) return null;
        List<Candle> window = following.subList(0, Math.min(horizon, following.size()));
        Double entry = window.getFirst().getOpenPrice();
        Double exit = window.getLast().getClosePrice();
        if (!TradeRiskPolicy.positive(entry) || !TradeRiskPolicy.positive(exit)) return null;
        return new Measurement(entry, exit, window.size(), window.size() >= horizon);
    }
}
