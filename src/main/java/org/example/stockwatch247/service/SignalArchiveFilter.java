package org.example.stockwatch247.service;

import java.util.Locale;

/** Normalized, bounded URL filters shared by archive queries and navigation. */
public record SignalArchiveFilter(String state, String ticker) {
    public SignalArchiveFilter {
        state = state == null ? "all" : state.toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("all", "unread", "active", "completed").contains(state)) state = "all";
        ticker = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
        if (ticker.length() > 32) ticker = ticker.substring(0, 32);
    }

    public boolean applied() { return !"all".equals(state) || !ticker.isEmpty(); }
}
