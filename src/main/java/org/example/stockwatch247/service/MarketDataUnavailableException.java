package org.example.stockwatch247.service;

import java.util.Locale;

/**
 * Signals that an analysis request cannot proceed because no usable candle
 * source or completed cached candle is available.
 */
public class MarketDataUnavailableException extends IllegalStateException {
    private final String symbol;
    private final String interval;
    private final String diagnosticMessage;

    public MarketDataUnavailableException(String symbol, String interval, String diagnosticMessage) {
        super(intervalLabel(interval) + " candle data is temporarily unavailable for " + symbol + ".");
        this.symbol = symbol;
        this.interval = interval;
        this.diagnosticMessage = diagnosticMessage;
    }

    public String symbol() {
        return symbol;
    }

    public String interval() {
        return interval;
    }

    public String diagnosticMessage() {
        return diagnosticMessage;
    }

    private static String intervalLabel(String interval) {
        if (interval == null) {
            return "Requested";
        }
        return switch (interval.toLowerCase(Locale.ROOT)) {
            case "1d", "daily" -> "Daily";
            case "1wk", "weekly" -> "Weekly";
            case "1mo", "monthly" -> "Monthly";
            default -> "Requested";
        };
    }
}
