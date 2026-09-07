package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.PasswordSecurityCode;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.PasswordSecurityCodeRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.AccountSession;
import org.example.stockwatch247.service.PasswordSecurityCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@AutoConfigureMockMvc
@Transactional
class UsabilityFlowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordSecurityCodeRepository codes;
    @Autowired PasswordEncoder encoder;
    @Autowired Clock clock;
    private User account;

    @BeforeEach void account() {
        account = new User(); account.setEmail("ux-" + UUID.randomUUID() + "@example.com");
        account.setFirstName("Review"); account.setLastName("User"); account.setVerified(true);
        account.setPasswordHash(encoder.encode("Current-password-for-test42!"));
        users.saveAndFlush(account);
    }

    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(user(account.getEmail()))
                .sessionAttr(AccountSession.SECURITY_VERSION, account.getSecurityVersion());
    }

    @Test void sharedNavigationRendersOnceAndHighlightsEachApplicationSection() throws Exception {
        String[][] pages = {{"/home", "home"}, {"/signals", "signals"}, {"/activity-signals", "alerts"},
                {"/virtual-trades", "demo"}, {"/settings", "settings"}, {"/settings/appearance", "settings"}, {"/about", "help"}};
        for (String[] page : pages) {
            String html = mvc.perform(signedIn(get(page[0]))).andExpect(status().isOk())
                    .andExpect(model().attribute("navigationSection", page[1]))
                    .andReturn().getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(html).containsOnlyOnce("id=\"appSidebar\"")
                    .containsOnlyOnce("id=\"globalTickerSearch\"")
                    .containsOnlyOnce("/js/app-navigation");
            String sidebar = html.substring(html.indexOf("<dialog"), html.indexOf("</dialog>") + 9);
            org.assertj.core.api.Assertions.assertThat(sidebar)
                    .containsOnlyOnce("aria-current=\"page\"")
                    .containsPattern("data-nav-section=\"" + page[1] + "\"[^>]*aria-current=\"page\"")
                    .contains("href=\"/home#ticker-alerts\"");
            org.assertj.core.api.Assertions.assertThat(java.util.regex.Pattern.compile("data-nav-section=")
                    .matcher(sidebar).results().count()).isEqualTo(6);

        }
    }

    @Test void anonymousPagesKeepTheirPublicNavigation() throws Exception {
        for (String path : new String[]{"/", "/about", "/login", "/signup"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("id=\"appSidebar\""))));
        }
    }

    @Test void passwordStepFollowsActualCodeValidityAndSurvivesValidationErrors() throws Exception {
        mvc.perform(signedIn(get("/settings"))).andExpect(status().isOk())
                .andExpect(model().attribute("passwordCodePending", false))
                .andExpect(content().string(not(containsString("class=\"settings-form password-change-form\""))));
        var code = new PasswordSecurityCode(); code.setUser(account); code.setPurpose(PasswordSecurityCodeService.CHANGE);
        code.setCodeHash(encoder.encode("12345678")); code.setLastSentAt(LocalDateTime.now(clock));
        code.setExpiresAt(LocalDateTime.now(clock).plusMinutes(5)); codes.saveAndFlush(code);
        mvc.perform(signedIn(get("/settings"))).andExpect(status().isOk())
                .andExpect(model().attribute("passwordCodePending", true))
                .andExpect(content().string(containsString("class=\"settings-form password-change-form\"")))
                .andExpect(content().string(containsString("data-expires-at=")));
        mvc.perform(signedIn(post("/settings/password")).with(csrf())
                .param("currentPassword", "Current-password-for-test42!").param("code", "12345678")
                .param("newPassword", "New-password-for-test42!").param("confirmPassword", "Different-password-for-test42!"))
                .andExpect(redirectedUrl("/settings#password"))
                .andExpect(flash().attribute("error", "The new passwords do not match."));
        mvc.perform(signedIn(get("/settings"))).andExpect(model().attribute("passwordCodePending", true));
        code.setFailedAttempts(5); codes.saveAndFlush(code);
        mvc.perform(signedIn(get("/settings"))).andExpect(model().attribute("passwordCodePending", false));
        code.setFailedAttempts(0); code.setExpiresAt(LocalDateTime.now(clock).minusSeconds(1)); codes.saveAndFlush(code);
        mvc.perform(signedIn(get("/settings"))).andExpect(model().attribute("passwordCodePending", false));
    }

    @Test void filteredEmptyArchiveRetainsItsControlsAndExplainsHowToRecover() throws Exception {
        mvc.perform(signedIn(get("/signals")).param("state", "unread").param("ticker", " aapl ")
                .param("sort", "confidence").param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No signals match these filters")))
                .andExpect(content().string(containsString("value=\"AAPL\"")))
                .andExpect(content().string(containsString("Clear filters")))
                .andExpect(content().string(not(containsString("No signals have been received yet"))))
                .andExpect(model().attribute("archiveReturnUrl", "/signals?sort=confidence&direction=asc&page=0&state=unread&ticker=AAPL"));
        mvc.perform(signedIn(get("/signals"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("No signals have been received yet")))
                .andExpect(content().string(not(containsString("No signals match these filters"))));
    }
}
