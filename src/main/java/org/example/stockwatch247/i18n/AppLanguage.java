package org.example.stockwatch247.i18n;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.Locale;

/** Languages the interface is translated into. English is the default for every other language. */
public enum AppLanguage {
    EN("en", Locale.ENGLISH),
    PL("pl", Locale.forLanguageTag("pl-PL"));

    public static final AppLanguage DEFAULT = EN;

    private final String code;
    private final Locale locale;

    AppLanguage(String code, Locale locale) {
        this.code = code;
        this.locale = locale;
    }

    public String code() {
        return code;
    }

    public Locale locale() {
        return locale;
    }

    /** The stored code, or null for "follow the browser" and anything unrecognised. */
    public static AppLanguage fromCode(String code) {
        if (code == null) return null;
        for (AppLanguage language : values()) {
            if (language.code.equalsIgnoreCase(code.trim())) return language;
        }
        return null;
    }

    public static AppLanguage of(Locale locale) {
        if (locale == null) return DEFAULT;
        AppLanguage language = fromCode(locale.getLanguage());
        return language == null ? DEFAULT : language;
    }

    /**
     * The first language in the browser's preference order that we support, so "de, pl, en" gives
     * Polish and "de, en" gives English. Without an Accept-Language header the answer is English:
     * the servlet container would otherwise report the server's own locale.
     */
    public static AppLanguage fromBrowser(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) return DEFAULT;
        for (Locale locale : Collections.list(request.getLocales())) {
            AppLanguage language = fromCode(locale.getLanguage());
            if (language != null) return language;
        }
        return DEFAULT;
    }
}
