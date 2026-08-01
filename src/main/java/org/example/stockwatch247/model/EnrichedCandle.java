package org.example.stockwatch247.model;

public record EnrichedCandle(
        Long timestamp,
        double open,
        double high,
        double low,
        double close,
        double volume,
        double averageVolume,
        double rsi,
        double fastEma,
        double slowEma,
        double longSma,
        double macdLine,
        double macdSignal,
        double macdHistogram,
        double cci,
        double bollingerMiddle,
        double lowerBollinger,
        double upperBollinger,
        double atr,
        double rollingVwap,
        double volumeProfilePointOfControl,
        double volumeProfileValueAreaLow,
        double volumeProfileValueAreaHigh
) {
    /**
     * Compatibility constructor for fixtures created while the V4 indicator
     * set did not yet expose its OHLCV volume-at-price approximation.
     */
    public EnrichedCandle(Long timestamp,
                          double open,
                          double high,
                          double low,
                          double close,
                          double volume,
                          double averageVolume,
                          double rsi,
                          double fastEma,
                          double slowEma,
                          double longSma,
                          double macdLine,
                          double macdSignal,
                          double macdHistogram,
                          double cci,
                          double bollingerMiddle,
                          double lowerBollinger,
                          double upperBollinger,
                          double atr,
                          double rollingVwap) {
        this(
                timestamp,
                open,
                high,
                low,
                close,
                volume,
                averageVolume,
                rsi,
                fastEma,
                slowEma,
                longSma,
                macdLine,
                macdSignal,
                macdHistogram,
                cci,
                bollingerMiddle,
                lowerBollinger,
                upperBollinger,
                atr,
                rollingVwap,
                Double.NaN,
                Double.NaN,
                Double.NaN
        );
    }

    /**
     * Compatibility constructor for existing hand-built detector fixtures. New
     * production enrichment uses the canonical constructor above.
     */
    public EnrichedCandle(Long timestamp,
                          double open,
                          double high,
                          double low,
                          double close,
                          double volume,
                          double averageVolume,
                          double rsi,
                          double fastEma,
                          double longSma,
                          double lowerBollinger,
                          double upperBollinger,
                          double atr) {
        this(
                timestamp,
                open,
                high,
                low,
                close,
                volume,
                averageVolume,
                rsi,
                fastEma,
                Double.NaN,
                longSma,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                bollingerMidpoint(lowerBollinger, upperBollinger),
                lowerBollinger,
                upperBollinger,
                atr,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN
        );
    }

    /**
     * Legacy accessors retained while callers migrate to interval-neutral
     * names. Their values now use the active interval profile and therefore are
     * not necessarily 20, 14, 20, 200, or 14 period values.
     */
    @Deprecated
    public double averageVolume20() {
        return averageVolume;
    }

    @Deprecated
    public double rsi14() {
        return rsi;
    }

    @Deprecated
    public double ema20() {
        return fastEma;
    }

    @Deprecated
    public double sma200() {
        return longSma;
    }

    @Deprecated
    public double atr14() {
        return atr;
    }

    /**
     * Kept for source compatibility. The underlying value is a rolling VWAP
     * based on typical price and volume, not a close-price VWMA.
     */
    @Deprecated
    public double rollingVwma() {
        return rollingVwap;
    }

    private static double bollingerMidpoint(double lower, double upper) {
        if (!Double.isFinite(lower) || !Double.isFinite(upper)) {
            return Double.NaN;
        }
        return (lower + upper) / 2.0;
    }
}
