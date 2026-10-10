package org.example.stockwatch247.controller;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@AutoConfigureMockMvc
@Transactional
class LearningModeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    private User account;

    @BeforeEach
    void account() {
        account = new User();
        account.setEmail("learning-" + UUID.randomUUID() + "@example.com");
        account.setFirstName("Jan");
        account.setLastName("Test");
        account.setVerified(true);
        account.setPasswordHash(encoder.encode("Current-password-for-test42!"));
        users.saveAndFlush(account);
    }

    @Test
    void learningModeStartsOffAndTheSidebarOffersTheSwitch() throws Exception {
        assertThat(storedChoice()).isFalse();
        mvc.perform(signedIn(get("/settings/language")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-learning-mode=\"on\""))))
                .andExpect(content().string(containsString("id=\"learningModeToggle\"")))
                .andExpect(content().string(containsString("aria-pressed=\"false\"")))
                .andExpect(content().string(containsString("/js/learning-mode")));
    }

    @Test
    void switchingItOnIsSavedToTheAccountAndRenderedOnEveryPage() throws Exception {
        mvc.perform(signedIn(post("/api/settings/learning-mode")).param("enabled", "true").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        assertThat(storedChoice()).isTrue();

        mvc.perform(signedIn(get("/settings/language")))
                .andExpect(content().string(containsString("data-learning-mode=\"on\"")))
                .andExpect(content().string(containsString("aria-pressed=\"true\"")));
        mvc.perform(signedIn(get("/settings/export")))
                .andExpect(jsonPath("$.learningMode").value(true));

        mvc.perform(signedIn(post("/api/settings/learning-mode")).param("enabled", "false").with(csrf()))
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(storedChoice()).isFalse();
    }

    @Test
    void theSwitchRequiresTheCsrfToken() throws Exception {
        mvc.perform(signedIn(post("/api/settings/learning-mode")).param("enabled", "true"))
                .andExpect(status().isForbidden());
        assertThat(storedChoice()).isFalse();
    }

    @Test
    void theAboutGlossaryShowsTheLearningModeDefinitions() throws Exception {
        mvc.perform(signedIn(get("/about")))
                .andExpect(content().string(containsString(
                        "How much technical evidence supports a pattern, out of 100. It is not the chance of a profitable trade.")));
    }

    private boolean storedChoice() {
        users.flush();
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select learning_mode from users where id = ?", Boolean.class, account.getId()));
    }

    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AccountSession.SECURITY_VERSION, account.getSecurityVersion());
        return request.with(user(account.getEmail())).session(session);
    }
}
