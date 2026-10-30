package org.example.stockwatch247.security;

import jakarta.servlet.http.Cookie;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sessions live in PostgreSQL (the other tests keep in-memory sessions, see src/test/resources/config), so a signed-in
 * visitor stays signed in across restarts; "Keep me signed in" makes the session last 30 days with a persistent cookie.
 */
// A real server port: Spring Boot applies the server.servlet.session.cookie settings only with an embedded server.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"alerts.schedule.enabled=false", "spring.autoconfigure.exclude="})
@AutoConfigureMockMvc
class SessionPersistenceIntegrationTest {
    private static final String PASSWORD = "Session-store-password-42!";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    private User account;

    @BeforeEach void account() {
        account = new User();
        account.setEmail("session-" + UUID.randomUUID() + "@example.com");
        account.setFirstName("Session"); account.setLastName("Store"); account.setVerified(true);
        account.setPasswordHash(encoder.encode(PASSWORD));
        users.saveAndFlush(account);
    }

    @AfterEach void cleanup() {
        jdbc.update("delete from spring_session where principal_name = ?", account.getEmail());
        users.deleteById(account.getId());
    }

    @Test void aRememberedSignInIsStoredWithAThirtyDayLifetimeAndAPersistentCookie() throws Exception {
        MockHttpServletResponse login = signIn(true);

        String cookie = sessionCookie(login);
        assertThat(cookie).contains("Max-Age=").contains("HttpOnly").contains("SameSite=Lax");
        assertThat(jdbc.queryForObject("select max_inactive_interval from spring_session where principal_name = ?",
                Integer.class, account.getEmail())).isEqualTo((int) RememberedSessions.LIFETIME.toSeconds());
        // The stored session alone signs the next request in, as it would after a restart.
        mvc.perform(get("/home").cookie(new Cookie("JSESSIONID", cookieValue(cookie)))).andExpect(status().isOk());
    }

    @Test void anOrdinarySignInKeepsTheIdleTimeoutAndABrowserSessionCookie() throws Exception {
        MockHttpServletResponse login = signIn(false);

        String cookie = sessionCookie(login);
        assertThat(cookie).doesNotContain("Max-Age=").contains("HttpOnly").contains("SameSite=Lax");
        assertThat(jdbc.queryForObject("select max_inactive_interval from spring_session where principal_name = ?",
                Integer.class, account.getEmail())).isEqualTo(1800);
        mvc.perform(get("/home").cookie(new Cookie("JSESSIONID", cookieValue(cookie)))).andExpect(status().isOk());
    }

    private MockHttpServletResponse signIn(boolean remember) throws Exception {
        var request = post("/login").with(csrf()).param("email", account.getEmail()).param("password", PASSWORD);
        if (remember) request.param(RememberedSessions.PARAMETER, "true");
        return mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn().getResponse();
    }

    private static String sessionCookie(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream().filter(header -> header.startsWith("JSESSIONID="))
                .reduce((first, second) -> second).orElseThrow(() -> new AssertionError("No session cookie; redirect "
                        + response.getRedirectedUrl() + ", cookies " + response.getHeaders("Set-Cookie")));
    }

    private static String cookieValue(String header) {
        return header.substring("JSESSIONID=".length(), header.indexOf(';'));
    }
}
