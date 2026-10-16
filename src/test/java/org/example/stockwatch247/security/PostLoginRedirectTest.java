package org.example.stockwatch247.security;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostLoginRedirectTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void keepsOnlySafePathsOnThisSite() {
        assertThat(PostLoginRedirect.safeTarget("https://stockwatch.example/stock/AAPL?continue", ""))
                .isEqualTo("/stock/AAPL");
        assertThat(PostLoginRedirect.safeTarget("http://localhost/alerts/signals/12?returnTo=%2Fsignals&continue", ""))
                .isEqualTo("/alerts/signals/12?returnTo=%2Fsignals");
        assertThat(PostLoginRedirect.safeTarget("https://evil.example//evil.example/x", "")).isEqualTo("/home");
        assertThat(PostLoginRedirect.safeTarget("http://localhost/api/stocks/AAPL/candles", "")).isEqualTo("/home");
        assertThat(PostLoginRedirect.safeTarget("http://localhost/", "")).isEqualTo("/home");
        assertThat(PostLoginRedirect.safeTarget("http://localhost/login?error", "")).isEqualTo("/home");
        assertThat(PostLoginRedirect.safeTarget("not a url", "")).isEqualTo("/home");
        assertThat(PostLoginRedirect.safeTarget(null, "")).isEqualTo("/home");
    }

    @Test
    void passwordLoginReturnsToThePageThatAskedForIt() throws Exception {
        UserRepository users = mock(UserRepository.class);
        User user = new User(); user.setId(7L); user.setEmail("owner@example.com");
        when(users.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(user));
        MockHttpServletRequest original = new MockHttpServletRequest("GET", "/stock/MSFT");
        MockHttpServletResponse ignored = new MockHttpServletResponse();
        new HttpSessionRequestCache().saveRequest(original, ignored);
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/login");
        login.setSession(original.getSession());
        MockHttpServletResponse response = new MockHttpServletResponse();
        var authentication = UsernamePasswordAuthenticationToken.authenticated("owner@example.com", null, java.util.List.of());

        new MfaAuthenticationSuccessHandler(users).onAuthenticationSuccess(login, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/stock/MSFT");
        assertThat(new HttpSessionRequestCache().getRequest(login, response)).isNull();
    }

    @Test
    void authenticatorLoginKeepsTheTargetForTheSecondStep() throws Exception {
        UserRepository users = mock(UserRepository.class);
        User user = new User(); user.setId(7L); user.setEmail("owner@example.com"); user.setMfaEnabled(true);
        when(users.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(user));
        MockHttpServletRequest original = new MockHttpServletRequest("GET", "/watchlists/3");
        new HttpSessionRequestCache().saveRequest(original, new MockHttpServletResponse());
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/login");
        login.setSession(original.getSession());
        MockHttpServletResponse response = new MockHttpServletResponse();
        var authentication = UsernamePasswordAuthenticationToken.authenticated("owner@example.com", null, java.util.List.of());

        new MfaAuthenticationSuccessHandler(users).onAuthenticationSuccess(login, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login/2fa");
        assertThat(login.getSession().getAttribute(AccountSession.MFA_PENDING_TARGET)).isEqualTo("/watchlists/3");
    }
}
