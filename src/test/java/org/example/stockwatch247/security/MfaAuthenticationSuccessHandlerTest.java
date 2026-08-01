package org.example.stockwatch247.security;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MfaAuthenticationSuccessHandlerTest {
    @Test
    void passwordSuccessForMfaAccountCreatesOnlyAPendingFiveMinuteChallenge() throws Exception {
        UserRepository users = mock(UserRepository.class);
        User user = new User(); user.setId(7L); user.setEmail("owner@example.com"); user.setMfaEnabled(true);
        when(users.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(user));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, "must-go");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("owner@example.com", null, java.util.List.of()));

        new MfaAuthenticationSuccessHandler(users).onAuthenticationSuccess(request, response,
                SecurityContextHolder.getContext().getAuthentication());

        assertThat(response.getRedirectedUrl()).isEqualTo("/login/2fa");
        assertThat(request.getSession().getAttribute(AccountSession.MFA_PENDING_USER_ID)).isEqualTo(7L);
        assertThat(request.getSession().getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
