package org.example.stockwatch247.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.security.AccountSession;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * A signed-in user's saved language wins; otherwise the browser decides (Polish or English), and
 * anything else falls back to English. The account comes from the session filter, so resolving the
 * language costs no extra query.
 */
public class StockWatchLocaleResolver implements LocaleResolver {
    static final String RESOLVED = StockWatchLocaleResolver.class.getName() + ".locale";
    static final String DETECTED_IN_SESSION = StockWatchLocaleResolver.class.getName() + ".detected";

    private final LanguagePreferenceService preferences;

    public StockWatchLocaleResolver(LanguagePreferenceService preferences) {
        this.preferences = preferences;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        if (request.getAttribute(RESOLVED) instanceof Locale cached) return cached;
        AppLanguage browser = AppLanguage.fromBrowser(request);
        AppLanguage language = browser;
        if (request.getAttribute(AccountSession.REQUEST_USER) instanceof User user) {
            AppLanguage saved = AppLanguage.fromCode(user.getPreferredLanguage());
            if (saved != null) language = saved;
            else rememberBrowserLanguage(request, user, browser);
        }
        request.setAttribute(RESOLVED, language.locale());
        return language.locale();
    }

    /** Background emails cannot ask a browser, so keep the last one seen; written once per session. */
    private void rememberBrowserLanguage(HttpServletRequest request, User user, AppLanguage browser) {
        HttpSession session = request.getSession(false);
        if (session == null || browser.code().equals(session.getAttribute(DETECTED_IN_SESSION))) return;
        if (!browser.code().equals(user.getDetectedLanguage()) && user.getId() != null) {
            preferences.rememberDetected(user.getId(), browser);
            user.setDetectedLanguage(browser.code());
        }
        session.setAttribute(DETECTED_IN_SESSION, browser.code());
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        throw new UnsupportedOperationException("The language is changed in Settings, not per request.");
    }
}
