package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;

/**
 * Frozen V4 indicator parameters. Periods are expressed in native bars, so a
 * period of ten means ten sessions, ten weeks, or ten months depending on the
 * selected interval.
 */
record TechnicalIndicatorProfile(
        TimeInterval interval,
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
    private static final TechnicalIndicatorProfile DAILY = new TechnicalIndicatorProfile(
            TimeInterval.DAILY,
            14,
            14,
            20,
            50,
            200,
            12,
            26,
            9,
            20,
            20,
            2.0,
            20,
            20,
            60
    );
    private static final TechnicalIndicatorProfile WEEKLY = new TechnicalIndicatorProfile(
            TimeInterval.WEEKLY,
            10,
            10,
            8,
            21,
            40,
            8,
            21,
            5,
            14,
            13,
            2.0,
            13,
            13,
            26
    );
    private static final TechnicalIndicatorProfile MONTHLY = new TechnicalIndicatorProfile(
            TimeInterval.MONTHLY,
            9,
            9,
            6,
            12,
            24,
            6,
            12,
            4,
            12,
            12,
            2.0,
            12,
            12,
            24
    );

    TechnicalIndicatorProfile {
        if (interval == null) {
            throw new IllegalArgumentException("Indicator interval is required.");
        }
        if (rsiPeriod < 2
                || atrPeriod < 2
                || fastEmaPeriod < 2
                || slowEmaPeriod <= fastEmaPeriod
                || longSmaPeriod < slowEmaPeriod
                || macdFastPeriod < 2
                || macdSlowPeriod <= macdFastPeriod
                || macdSignalPeriod < 2
                || cciPeriod < 2
                || bollingerPeriod < 2
                || bollingerDeviation <= 0.0
                || volumePeriod < 2
                || vwapPeriod < 2
                || volumeProfilePeriod < 2) {
            throw new IllegalArgumentException("Indicator profile periods are invalid.");
        }
    }

    static TechnicalIndicatorProfile forInterval(TimeInterval interval) {
        return switch (interval) {
            case DAILY -> DAILY;
            case WEEKLY -> WEEKLY;
            case MONTHLY -> MONTHLY;
            default -> throw new IllegalArgumentException(
                    "Candlestick V4 scoring supports DAILY, WEEKLY, and MONTHLY intervals only.");
        };
    }

    /**
     * Elliott Wave has an independently benchmarked indicator layer. Keep its
     * original periods frozen while candlestick V4 uses interval profiles.
     */
    static TechnicalIndicatorProfile forElliott(TimeInterval interval) {
        if (interval != TimeInterval.WEEKLY && interval != TimeInterval.MONTHLY) {
            throw new IllegalArgumentException(
                    "Elliott indicator enrichment supports WEEKLY and MONTHLY intervals only.");
        }
        return new TechnicalIndicatorProfile(
                interval,
                14,
                14,
                20,
                50,
                200,
                12,
                26,
                9,
                20,
                20,
                2.0,
                20,
                20,
                60
        );
    }

    int warmupBars() {
        return Math.max(
                longSmaPeriod,
                Math.max(
                        macdSlowPeriod + macdSignalPeriod - 1,
                        Math.max(
                                rsiPeriod + 1,
                                Math.max(
                                        atrPeriod + 1,
                                        Math.max(
                                                cciPeriod,
                                                Math.max(
                                                        bollingerPeriod,
                                                        Math.max(
                                                                volumePeriod,
                                                                Math.max(vwapPeriod, volumeProfilePeriod)
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }

    String shortLabel() {
        return interval.name().toLowerCase(java.util.Locale.ROOT);
    }
}
