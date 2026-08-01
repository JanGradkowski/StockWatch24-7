package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;

import java.util.Optional;

/**
 * Human-readable evaluation windows derived from the frozen candlestick backtests.
 * These values are presentation metadata only and must never gate alert emission.
 */
public final class CandlestickHorizonGuidance {
    private static final String DISCLAIMER =
            "Historical evaluation window only \u2014 not a recommended holding period, price target, or guarantee.";

    private CandlestickHorizonGuidance() {
    }

    public static Optional<Guidance> forSignal(AlertPatternFamily family, TimeInterval interval) {
        if (family == AlertPatternFamily.ELLIOTT_WAVE || interval == null) {
            return Optional.empty();
        }

        return switch (interval) {
            case DAILY -> Optional.of(new Guidance(
                    "10 trading sessions",
                    "The current V4 diagnostic measures daily outcomes after 10 trading sessions. "
                            + "This is a fixed research measurement window, not evidence that the "
                            + "experimental score predicts returns.",
                    DISCLAIMER
            ));
            case WEEKLY -> Optional.of(new Guidance(
                    "4, 8, and 12 weeks",
                    "V4 is reported at 4-, 8-, and 12-week research windows. The score ordering "
                            + "was not stable across all three windows and top-score samples were small.",
                    DISCLAIMER
            ));
            case MONTHLY -> Optional.of(new Guidance(
                    "3, 6, and 9 months",
                    "V4 is reported at 3-, 6-, and 9-month research windows. Monthly validation "
                            + "is exploratory because the sample is small and aggregate results were weak.",
                    DISCLAIMER
            ));
            default -> Optional.empty();
        };
    }

    public record Guidance(String label, String summary, String disclaimer) {
    }
}
