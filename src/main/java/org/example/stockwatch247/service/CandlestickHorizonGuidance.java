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
                    "10\u201330 trading sessions",
                    "Historical testing was most informative over roughly 10\u201330 trading sessions. "
                            + "Ten sessions is the primary directional calibration window; "
                            + "20\u201330 sessions capture slower follow-through.",
                    DISCLAIMER
            ));
            case WEEKLY -> Optional.of(new Guidance(
                    "8\u201312 weeks",
                    "The calibrated setup score separated historical outcomes most clearly over "
                            + "8\u201312 weeks; the 4-week result was less useful.",
                    DISCLAIMER
            ));
            case MONTHLY -> Optional.of(new Guidance(
                    "About 6 months",
                    "Six months is the primary research horizon. The 3-month result was weak, "
                            + "while the 9-month result is preliminary because it has too few actionable observations.",
                    DISCLAIMER
            ));
            default -> Optional.empty();
        };
    }

    public record Guidance(String label, String summary, String disclaimer) {
    }
}
