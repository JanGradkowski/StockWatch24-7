package org.example.stockwatch247.i18n;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.AccountSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@AutoConfigureMockMvc
@Transactional
class LanguageSettingsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    private User account;

    @BeforeEach
    void account() {
        account = new User();
        account.setEmail("language-" + UUID.randomUUID() + "@example.com");
        account.setFirstName("Jan");
        account.setLastName("Test");
        account.setVerified(true);
        account.setPasswordHash(encoder.encode("Current-password-for-test42!"));
        users.saveAndFlush(account);
    }

    @Test
    void signedOutPagesFollowTheBrowserAndDefaultToEnglish() throws Exception {
        mvc.perform(get("/login").header("Accept-Language", "pl-PL,pl;q=0.9"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"pl\"")));
        mvc.perform(get("/login").header("Accept-Language", "de-DE,de;q=0.9"))
                .andExpect(content().string(containsString("lang=\"en\"")));
    }

    @Test
    void theLanguagePageIsInPolishForAPolishBrowserAndShowsWhatTheBrowserGives() throws Exception {
        mvc.perform(signedIn(get("/settings/language")).header("Accept-Language", "pl-PL"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"pl\"")))
                .andExpect(content().string(containsString("Język strony i wiadomości e-mail")))
                .andExpect(content().string(containsString("Twoja przeglądarka wskazuje obecnie: polski")))
                .andExpect(content().string(containsString("value=\"auto\" checked")));

        assertThat(jdbc.queryForObject("select detected_language from users where id = ?", String.class, account.getId()))
                .as("remembered for background emails").isEqualTo("pl");
    }

    @Test
    void choosingEnglishOverridesAPolishBrowserAndAutomaticHandsItBack() throws Exception {
        mvc.perform(signedIn(post("/settings/language")).param("language", "en").with(csrf()))
                .andExpect(redirectedUrl("/settings/language?languageSaved=true"));
        assertThat(storedChoice()).isEqualTo("en");

        account.setPreferredLanguage("en");
        mvc.perform(signedIn(get("/settings/language?languageSaved=true")).header("Accept-Language", "pl-PL"))
                .andExpect(content().string(containsString("lang=\"en\"")))
                .andExpect(content().string(containsString("Language saved.")))
                .andExpect(content().string(containsString("value=\"en\" checked")));

        mvc.perform(signedIn(post("/settings/language")).param("language", "auto").with(csrf()));
        assertThat(storedChoice()).isNull();
    }

    @Test
    void everySettingsTabRendersInPolishAndEnglish() throws Exception {
        String[][] tabs = {
                {"/settings", "Ustawienia ogólne", "General settings"},
                {"/settings/appearance", "Tryb kolorów", "Color mode"},
                {"/settings/language", "Język strony i wiadomości e-mail", "Website and email language"},
                {"/settings/analysis-alerts", "Wiadomości e-mail o rynku", "Market email delivery"},
                {"/settings/detection", "Wykrywanie trendu dla formacji świecowych", "Candlestick trend detection"},
                {"/settings/scoring", "Punktacja formacji świecowych", "Candlestick scoring"},
                {"/settings/candlestick-patterns", "Objęcie hossy", "Bullish Engulfing"},
                {"/settings/elliott-waves", "Zezwalaj na korekty płaskie pędzące", "Allow running flats"},
                {"/settings/harmonic-formations", "Nietoperz", "Alternate AB=CD"},
        };
        for (String[] tab : tabs) {
            mvc.perform(signedIn(get(tab[0])).header("Accept-Language", "pl-PL"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(tab[1])));
            mvc.perform(signedIn(get(tab[0])).header("Accept-Language", "en-GB"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(tab[2])));
        }
    }

    record LabelledRow(String patternLabel, String pattern) {
    }

    @Test
    void jsonResponsesUseTheDisplayTextConverterWhileTheSharedMapperStaysEnglish(
            @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter adapter,
            @Autowired tools.jackson.databind.json.JsonMapper sharedMapper) {
        // MVC writes a response with the first converter that can write the type as JSON.
        var converter = adapter.getMessageConverters().stream()
                .filter(candidate -> candidate.canWrite(LabelledRow.class, org.springframework.http.MediaType.APPLICATION_JSON))
                .findFirst()
                .map(org.springframework.http.converter.json.JacksonJsonHttpMessageConverter.class::cast)
                .orElseThrow();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(AppLanguage.PL.locale());
        try {
            LabelledRow row = new LabelledRow("Bullish Engulfing", "Bullish Engulfing");
            assertThat(converter.getMapper().writeValueAsString(row))
                    .isEqualTo("{\"patternLabel\":\"Objęcie hossy\",\"pattern\":\"Bullish Engulfing\"}");
            assertThat(sharedMapper.writeValueAsString(row)).as("stored JSON is never translated")
                    .isEqualTo("{\"patternLabel\":\"Bullish Engulfing\",\"pattern\":\"Bullish Engulfing\"}");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void alertEmailsUseTheRecipientsSavedLanguage(@Autowired LanguagePreferenceService preferences) {
        assertThat(preferences.localeForEmail(account.getEmail().toUpperCase())).isEqualTo(AppLanguage.EN.locale());
        jdbc.update("update users set detected_language = 'pl' where id = ?", account.getId());
        assertThat(preferences.localeForEmail(account.getEmail())).isEqualTo(AppLanguage.PL.locale());
        jdbc.update("update users set preferred_language = 'en' where id = ?", account.getId());
        assertThat(preferences.localeForEmail(account.getEmail())).isEqualTo(AppLanguage.EN.locale());
        assertThat(preferences.localeForEmail("nobody@example.com")).isEqualTo(AppLanguage.EN.locale());
    }

    @Test
    void anUnsupportedLanguageIsRefused() throws Exception {
        mvc.perform(signedIn(post("/settings/language")).param("language", "de").with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(storedChoice()).isNull();
    }

    @Test
    void theBrowserTranslationsAreServedToSignedOutVisitorsPerLanguage() throws Exception {
        mvc.perform(get("/i18n/messages.js").param("lang", "pl"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("javascript")))
                .andExpect(content().string(containsString("window.StockWatchI18n")))
                .andExpect(content().string(containsString("Ładowanie…")));
        mvc.perform(get("/i18n/messages.js").param("lang", "de"))
                .andExpect(content().string(containsString("Loading…")))
                .andExpect(content().string(not(containsString("Ładowanie"))));
    }

    private String storedChoice() {
        return jdbc.queryForObject("select preferred_language from users where id = ?", String.class, account.getId());
    }

    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AccountSession.SECURITY_VERSION, account.getSecurityVersion());
        return request.with(user(account.getEmail())).session(session);
    }
}
