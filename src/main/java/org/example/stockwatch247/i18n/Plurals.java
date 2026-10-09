package org.example.stockwatch247.i18n;

import java.util.Locale;

/**
 * CLDR plural categories for the supported languages. A pluralised message is stored under
 * "key.one", "key.few", "key.many" and "key.other"; English needs only "one" and "other", Polish uses
 * "one" (1), "few" (2–4, 22–24, … but not 12–14) and "many" (0, 5–21, 25–31, …), with "other" for
 * fractions.
 */
public final class Plurals {
    private Plurals() {
    }

    public static String category(Locale locale, Number count) {
        double value = count == null ? 0 : count.doubleValue();
        boolean whole = value == Math.rint(value) && !Double.isInfinite(value);
        long n = Math.abs((long) value);
        if (AppLanguage.of(locale) == AppLanguage.PL) {
            if (!whole) return "other";
            if (n == 1) return "one";
            long lastDigit = n % 10, lastTwo = n % 100;
            if (lastDigit >= 2 && lastDigit <= 4 && (lastTwo < 12 || lastTwo > 14)) return "few";
            return "many";
        }
        return whole && n == 1 ? "one" : "other";
    }

    /** The bundle key for {@code count}: "key.one", "key.few", … (callers fall back to "key.other"). */
    public static String key(Locale locale, String baseKey, Number count) {
        return baseKey + "." + category(locale, count);
    }
}
