package org.example.stockwatch247.i18n;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.security.AccountSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class StockWatchLocaleResolverTest {
    private final LanguagePreferenceService preferences = mock(LanguagePreferenceService.class);
    private final StockWatchLocaleResolver resolver = new StockWatchLocaleResolver(preferences);

    @Test
    void theBrowserDecidesPolishOrEnglishAndEverythingElseIsEnglish() {
        assertThat(language("pl-PL,pl;q=0.9,en;q=0.8")).isEqualTo("pl");
        assertThat(language("en-GB,en;q=0.9")).isEqualTo("en");
        assertThat(language("de-DE,de;q=0.9")).isEqualTo("en");
        assertThat(language("fr")).isEqualTo("en");
        assertThat(language(null)).isEqualTo("en");
    }

    @Test
    void theFirstSupportedLanguageInTheBrowsersOrderWins() {
        assertThat(language("de-DE,pl;q=0.8,en;q=0.5")).isEqualTo("pl");
        assertThat(language("de-DE,en;q=0.9,pl;q=0.8")).isEqualTo("en");
    }

    @Test
    void aSavedChoiceOverridesTheBrowserAndIsNotRecordedAsDetected() {
        User user = account(1L, "en");
        MockHttpServletRequest request = request("pl-PL", user);

        assertThat(resolver.resolveLocale(request).getLanguage()).isEqualTo("en");
        verify(preferences, never()).rememberDetected(anyLong(), any());
    }

    @Test
    void anAutomaticAccountRemembersItsBrowserLanguageOncePerSessionForEmails() {
        User user = account(2L, null);
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest first = request("pl-PL", user);
        first.setSession(session);
        MockHttpServletRequest second = request("pl-PL", user);
        second.setSession(session);

        assertThat(resolver.resolveLocale(first).getLanguage()).isEqualTo("pl");
        assertThat(resolver.resolveLocale(second).getLanguage()).isEqualTo("pl");

        verify(preferences, times(1)).rememberDetected(2L, AppLanguage.PL);
        assertThat(user.getDetectedLanguage()).isEqualTo("pl");
    }

    @Test
    void emailsUseTheSavedChoiceThenTheLastBrowserLanguageThenEnglish() {
        User saved = account(3L, "pl");
        User detected = account(4L, null);
        detected.setDetectedLanguage("pl");
        User unknown = account(5L, null);

        assertThat(LanguagePreferenceService.localeFor(saved).getLanguage()).isEqualTo("pl");
        assertThat(LanguagePreferenceService.localeFor(detected).getLanguage()).isEqualTo("pl");
        assertThat(LanguagePreferenceService.localeFor(unknown)).isEqualTo(Locale.ENGLISH);
        assertThat(LanguagePreferenceService.settingsChoice(unknown)).isEqualTo("auto");
    }

    private String language(String acceptLanguage) {
        return resolver.resolveLocale(request(acceptLanguage, null)).getLanguage();
    }

    private static MockHttpServletRequest request(String acceptLanguage, User user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (acceptLanguage != null) request.addHeader("Accept-Language", acceptLanguage);
        if (user != null) request.setAttribute(AccountSession.REQUEST_USER, user);
        return request;
    }

    private static User account(long id, String preferred) {
        User user = new User();
        user.setId(id);
        user.setEmail("language-" + id + "@example.com");
        user.setPreferredLanguage(preferred);
        return user;
    }
}
