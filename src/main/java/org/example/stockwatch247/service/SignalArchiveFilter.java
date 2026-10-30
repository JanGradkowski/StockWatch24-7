package org.example.stockwatch247.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Normalized, bounded URL filters shared by archive queries and navigation.
 *
 * <p>{@code types} narrows the archive to signal types (empty means every type the page shows);
 * {@code customAlertId} shows the triggers of one custom alert.</p>
 */
public record SignalArchiveFilter(String state, String ticker, Long watchlistId, Set<String> types, Long customAlertId) {
    /** Every type the archive can filter by, in the order the filter shows them. */
    public static final List<String> TYPES = List.of("candlestick", "elliott", "harmonic", "outlook", "chart", "custom", "congress", "insider");

    public SignalArchiveFilter(String state, String ticker) { this(state, ticker, null, Set.of(), null); }

    public SignalArchiveFilter(String state, String ticker, Long watchlistId) { this(state, ticker, watchlistId, Set.of(), null); }

    public SignalArchiveFilter {
        state = state == null ? "all" : state.toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("all", "unread", "active", "completed").contains(state)) state = "all";
        ticker = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
        if (ticker.contains(",")) {
            ticker = java.util.Arrays.stream(ticker.split(",", -1))
                    .map(org.example.stockwatch247.security.SecurityInputValidator::requireMarketSymbol)
                    .distinct().collect(java.util.stream.Collectors.joining(","));
        } else if (ticker.length() > 32) ticker = ticker.substring(0, 32);
        Set<String> known = new LinkedHashSet<>();
        if (types != null) {
            for (String type : types) {
                if (type == null) continue;
                Arrays.stream(type.split(",")).map(value -> value.trim().toLowerCase(Locale.ROOT))
                        .filter(TYPES::contains).forEach(known::add);
            }
        }
        // Every type selected is the same as no type filter.
        types = known.size() == TYPES.size() ? Set.of() : Set.copyOf(known);
        if (customAlertId != null && customAlertId <= 0) customAlertId = null;
    }

    public boolean applied() {
        return watchlistId != null || !"all".equals(state) || !ticker.isEmpty() || !types.isEmpty() || customAlertId != null;
    }

    /** True when the archive shows this type: no type filter, or the type is selected. */
    public boolean shows(String type) { return types.isEmpty() || types.contains(type); }

    /** The selected types joined for a URL, in display order; empty for no filter. */
    public String typeParameter() {
        return String.join(",", TYPES.stream().filter(types::contains).toList());
    }
}
