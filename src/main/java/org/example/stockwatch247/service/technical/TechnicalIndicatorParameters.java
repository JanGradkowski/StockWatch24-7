package org.example.stockwatch247.service.technical;

/**
 * Immutable calculation parameters passed into the TA4J boundary. Production
 * scoring profiles remain owned by StockWatch; this record only describes the
 * mathematics that TA4J should calculate.
 */
public record TechnicalIndicatorParameters(
        int rsiPeriod,
        int atrPeriod,
        int fastEmaPeriod,
        int slowEmaPeriod,
        int longSmaPeriod,
        int macdFastPeriod,
        int macdSlowPeriod,
        int macdSignalPeriod,
        int cciPeriod,
        int bollingerPeriod,
        double bollingerDeviation,
        int volumePeriod,
        int vwapPeriod,
        int volumeProfilePeriod
) {
    public int adxPeriod() {
        return atrPeriod;
    }

    public int stochasticPeriod() {
        return rsiPeriod;
    }

    public int moneyFlowPeriod() {
        return rsiPeriod;
    }

    public int channelPeriod() {
        return bollingerPeriod;
    }

    public int trendPeriod() {
        return Math.max(3, fastEmaPeriod);
    }
}
