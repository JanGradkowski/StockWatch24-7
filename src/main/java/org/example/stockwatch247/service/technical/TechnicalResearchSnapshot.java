package org.example.stockwatch247.service.technical;

/** Research-only values; no field in this record is consumed by production signal scoring. */
public record TechnicalResearchSnapshot(
        long timestamp,
        double adx,
        double plusDi,
        double minusDi,
        double stochasticK,
        double stochasticD,
        double stochasticRsi,
        double obv,
        double moneyFlowIndex,
        double donchianLower,
        double donchianMiddle,
        double donchianUpper,
        double keltnerLower,
        double keltnerMiddle,
        double keltnerUpper,
        boolean upTrend,
        boolean downTrend,
        double volumeProfileKdeMode
) {
}
