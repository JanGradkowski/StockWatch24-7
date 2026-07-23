package org.example.stockwatch247.security;

import org.example.stockwatch247.market.MarketIndexCatalog;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SecurityInputValidator {
    private static final Pattern MARKET_SYMBOL = Pattern.compile("[A-Z0-9^][A-Z0-9.^=_-]{0,19}");
    private static final Pattern SEARCH_QUERY = Pattern.compile("[\\p{L}\\p{N} .&'^_-]{1,64}");
    private static final Pattern MIC_CODE = Pattern.compile("[A-Z0-9]{4,12}");
    private static final Pattern PERSON_NAME = Pattern.compile("[\\p{L}\\p{M} .'-]{1,100}");
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> INTERVALS = Set.of(
            "1d", "1wk", "1mo", "1min", "5min", "15min", "30min", "60min");

    private SecurityInputValidator() {
    }

    public static String requireMarketSymbol(String rawSymbol) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (!MARKET_SYMBOL.matcher(symbol).matches()) {
            throw new IllegalArgumentException("Invalid market symbol.");
        }
        return MarketIndexCatalog.canonicalTickerSymbol(symbol);
    }

    public static String requireSearchQuery(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (!SEARCH_QUERY.matcher(query).matches()) {
            throw new IllegalArgumentException("Invalid search query.");
        }
        return query;
    }

    public static String requireOptionalMicCode(String rawMicCode) {
        if (rawMicCode == null || rawMicCode.isBlank()) {
            return null;
        }
        String micCode = rawMicCode.trim().toUpperCase(Locale.ROOT);
        if (!MIC_CODE.matcher(micCode).matches()) {
            throw new IllegalArgumentException("Invalid market identifier code.");
        }
        return micCode;
    }

    public static String requireInterval(String interval) {
        if (interval == null || !INTERVALS.contains(interval)) {
            throw new IllegalArgumentException("Unsupported candle interval.");
        }
        return interval;
    }

    public static String requirePersonName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (!PERSON_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Name contains unsupported characters.");
        }
        return name;
    }

    public static String requireEmail(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        return email;
    }

    public static String requirePassword(String password) {
        if (password == null || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Password must be at least 12 characters and at most 72 UTF-8 bytes.");
        }
        return password;
    }

    public static Long requireBeforeTimestamp(Long before) {
        if (before != null && before <= 0L) {
            throw new IllegalArgumentException("Invalid candle timestamp.");
        }
        return before;
    }
}
