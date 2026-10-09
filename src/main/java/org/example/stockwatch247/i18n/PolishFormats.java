package org.example.stockwatch247.i18n;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the English dates and numbers that services put into text ("5 Mar 2025", "March 2025",
 * "1,234.5%") in Polish conventions. Services keep producing English because their results are cached
 * and shared between users; the conversion happens where the text is shown.
 */
final class PolishFormats {
    private static final String[] SHORT_MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final String[] POLISH_SHORT_MONTHS = {"sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru"};
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December"};
    /** "5 marca 2025": a month after a day number. */
    private static final String[] POLISH_MONTHS_GENITIVE = {"stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
            "lipca", "sierpnia", "września", "października", "listopada", "grudnia"};
    /** "marzec 2025": a month on its own. */
    private static final String[] POLISH_MONTHS = {"styczeń", "luty", "marzec", "kwiecień", "maj", "czerwiec", "lipiec",
            "sierpień", "wrzesień", "październik", "listopad", "grudzień"};

    private static final String LONG = String.join("|", MONTHS);
    private static final String SHORT = String.join("|", SHORT_MONTHS);
    private static final Pattern DAY_LONG_MONTH = Pattern.compile("\\b(\\d{1,2}) (" + LONG + ")\\b");
    private static final Pattern LONG_MONTH_YEAR = Pattern.compile("\\b(" + LONG + ") (\\d{4})\\b");
    private static final Pattern SHORT_MONTH_DAY_YEAR = Pattern.compile("\\b(" + SHORT + ") (\\d{1,2}), (\\d{4})\\b");
    private static final Pattern DAY_SHORT_MONTH = Pattern.compile("\\b(\\d{1,2}) (" + SHORT + ")\\b");
    private static final Pattern NUMBER = Pattern.compile("([+\\-−]?)(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d+))?((?:\\s?(?:%|×|σ|x|[A-Z]{3}))?)");

    private PolishFormats() {
    }

    /** The text with every English day-month date and month-year written the Polish way. */
    static String dates(String text) {
        if (text == null || !text.matches("(?s).*\\d.*")) return text;
        String result = replace(SHORT_MONTH_DAY_YEAR, text, m -> m.group(2) + " " + POLISH_SHORT_MONTHS[index(SHORT_MONTHS, m.group(1))] + " " + m.group(3));
        result = replace(DAY_LONG_MONTH, result, m -> m.group(1) + " " + POLISH_MONTHS_GENITIVE[index(MONTHS, m.group(2))]);
        result = replace(LONG_MONTH_YEAR, result, m -> POLISH_MONTHS[index(MONTHS, m.group(1))] + " " + m.group(2));
        return replace(DAY_SHORT_MONTH, result, m -> m.group(1) + " " + POLISH_SHORT_MONTHS[index(SHORT_MONTHS, m.group(2))]);
    }

    /**
     * A value that is only a number (optionally signed, with %, ×, σ or a currency code) in Polish form:
     * decimal comma and, from five digits, a non-breaking space between thousands. Anything else is null.
     */
    static String number(String value) {
        if (value == null) return null;
        Matcher matcher = NUMBER.matcher(value.strip());
        if (!matcher.matches()) return null;
        String digits = matcher.group(2).replace(",", "");
        StringBuilder whole = new StringBuilder(digits);
        if (digits.length() >= 5) {
            for (int position = digits.length() - 3; position > 0; position -= 3) whole.insert(position, ' ');
        }
        String fraction = matcher.group(3) == null ? "" : "," + matcher.group(3);
        return matcher.group(1) + whole + fraction + matcher.group(4);
    }

    private static int index(String[] values, String value) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(value)) return index;
        }
        throw new IllegalArgumentException(value);
    }

    private static String replace(Pattern pattern, String text, java.util.function.Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.apply(matcher)));
        return matcher.appendTail(result).toString();
    }
}
