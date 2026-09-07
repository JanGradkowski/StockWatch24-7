package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.CandlePattern;

import java.util.Locale;
import java.util.Map;

/**
 * Frozen higher-interval calibration learned on candles ending before 2020.
 *
 * <p>The stored precision is a Bayesian-shrunk descriptive rate, not a
 * probability forecast. Each pattern is shrunk toward the matching BUY or SELL
 * baseline with 30 prior actionable trades. Keeping this table frozen makes the
 * later 2020+ validation segment genuinely chronological and prevents live
 * signals from learning from their own future outcomes.</p>
 */
final class CandlestickPatternCalibration {
    static final int MAX_POINTS = 10;
    static final int NEUTRAL_POINTS = 5;

    private static final String DEVELOPMENT_WINDOW = "pre-2020 30-stock development sample";
    private static final Map<CandlePattern, Profile> WEEKLY = Map.ofEntries(
            entry(CandlePattern.MORNING_STAR, 19, 10, 77.4, 5.58),
            entry(CandlePattern.PIERCING_LINE, 28, 12, 76.1, 5.57),
            entry(CandlePattern.BULLISH_HARAMI, 52, 20, 75.9, 5.52),
            entry(CandlePattern.HAMMER, 73, 28, 70.6, 4.09),
            entry(CandlePattern.INVERTED_HAMMER, 70, 31, 63.9, 3.21),
            entry(CandlePattern.BULLISH_ENGULFING, 94, 44, 62.1, 2.40),
            entry(CandlePattern.BEARISH_ENGULFING, 175, 80, 27.5, -4.44),
            entry(CandlePattern.DARK_CLOUD_COVER, 59, 22, 27.4, -3.37),
            entry(CandlePattern.HANGING_MAN, 151, 59, 23.9, -3.49),
            entry(CandlePattern.BEARISH_HARAMI, 122, 44, 23.3, -2.51),
            entry(CandlePattern.SHOOTING_STAR, 97, 40, 21.8, -4.47),
            entry(CandlePattern.EVENING_STAR, 48, 22, 17.8, -5.39)
    );
    private static final Map<CandlePattern, Profile> MONTHLY = Map.ofEntries(
            entry(CandlePattern.INVERTED_HAMMER, 38, 24, 67.8, 12.25),
            entry(CandlePattern.BULLISH_HARAMI, 35, 19, 64.5, 12.39),
            entry(CandlePattern.HAMMER, 42, 18, 63.8, 6.40),
            entry(CandlePattern.BULLISH_ENGULFING, 74, 36, 60.0, 3.79),
            entry(CandlePattern.MORNING_STAR, 19, 12, 56.2, 1.84),
            entry(CandlePattern.HANGING_MAN, 117, 65, 32.6, -7.51),
            entry(CandlePattern.BEARISH_HARAMI, 67, 32, 30.6, -7.16),
            entry(CandlePattern.DARK_CLOUD_COVER, 31, 15, 26.7, -11.19),
            entry(CandlePattern.BEARISH_ENGULFING, 150, 74, 24.0, -9.13),
            entry(CandlePattern.SHOOTING_STAR, 107, 67, 23.7, -11.39),
            entry(CandlePattern.EVENING_STAR, 31, 17, 21.3, -12.01)
    );

    private CandlestickPatternCalibration() {
    }

    static Assessment assess(CandlePattern pattern, Timeframe timeframe) {
        Map<CandlePattern, Profile> profiles = switch (timeframe) {
            case WEEKLY -> WEEKLY;
            case MONTHLY -> MONTHLY;
        };
        Profile profile = profiles.get(pattern);
        if (profile == null || profile.actionableOutcomes() < 10) {
            return new Assessment(
                    NEUTRAL_POINTS,
                    "neutral: too few frozen " + timeframe.label()
                            + " outcomes for this pattern; " + DEVELOPMENT_WINDOW
            );
        }

        int points = pointsFor(profile.shrunkPrecisionPercent());
        String detail = String.format(Locale.ROOT,
                "%s calibration from %s: n=%d signals/%d actionable, "
                        + "Bayesian-shrunk precision %.1f%% and shrunk average return %+.2f%%; "
                        + "this is a ranking prior, not a probability",
                timeframe.label(),
                DEVELOPMENT_WINDOW,
                profile.signals(),
                profile.actionableOutcomes(),
                profile.shrunkPrecisionPercent(),
                profile.shrunkAverageReturnPercent());
        return new Assessment(points, detail);
    }

    private static int pointsFor(double shrunkPrecisionPercent) {
        if (shrunkPrecisionPercent >= 70.0) {
            return 10;
        }
        if (shrunkPrecisionPercent >= 65.0) {
            return 9;
        }
        if (shrunkPrecisionPercent >= 60.0) {
            return 8;
        }
        if (shrunkPrecisionPercent >= 55.0) {
            return 7;
        }
        if (shrunkPrecisionPercent >= 45.0) {
            return 5;
        }
        if (shrunkPrecisionPercent >= 35.0) {
            return 3;
        }
        if (shrunkPrecisionPercent >= 30.0) {
            return 2;
        }
        if (shrunkPrecisionPercent >= 25.0) {
            return 1;
        }
        return 0;
    }

    private static Map.Entry<CandlePattern, Profile> entry(CandlePattern pattern,
                                                            int signals,
                                                            int actionableOutcomes,
                                                            double shrunkPrecisionPercent,
                                                            double shrunkAverageReturnPercent) {
        return Map.entry(pattern, new Profile(
                signals,
                actionableOutcomes,
                shrunkPrecisionPercent,
                shrunkAverageReturnPercent
        ));
    }

    enum Timeframe {
        WEEKLY("weekly 8-candle/8%"),
        MONTHLY("monthly 6-candle/12%");

        private final String label;

        Timeframe(String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }
    }

    record Assessment(int points, String detail) {
    }

    private record Profile(int signals,
                           int actionableOutcomes,
                           double shrunkPrecisionPercent,
                           double shrunkAverageReturnPercent) {
    }
}
