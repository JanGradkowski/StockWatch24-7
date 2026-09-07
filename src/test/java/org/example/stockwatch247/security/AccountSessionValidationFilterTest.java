package org.example.stockwatch247.security;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AccountSessionValidationFilterTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void missingDeletedUnverifiedAndUnversionedSessionsFailClosed() throws Exception {
        for (int scenario = 0; scenario < 5; scenario++) {
            User account = new User(); account.setVerified(true); account.setSecurityVersion(2);
            if (scenario == 1) account.setDeletionRequestedAt(java.time.LocalDateTime.now());
            if (scenario == 2) account.setVerified(false);
            UserRepository users = mock(UserRepository.class);
            when(users.findByEmailIgnoreCase("account@example.com")).thenReturn(scenario == 0 ? Optional.empty() : Optional.of(account));
            var request = new MockHttpServletRequest("GET", "/api/stocks/AAPL");
            var response = new MockHttpServletResponse();
            if (scenario != 3) request.getSession().setAttribute(AccountSession.SECURITY_VERSION, scenario == 4 ? 1L : 2L);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("account@example.com", null, List.of()));
            new AccountSessionValidationFilter(users).doFilter(request, response, (req, res) -> { throw new AssertionError("Rejected session reached controller"); });
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }
    @Test void validSessionSuppliesARequestScopedAccount() throws Exception {
        User account = new User(); account.setVerified(true); account.setSecurityVersion(2);
        UserRepository users = mock(UserRepository.class);
        when(users.findByEmailIgnoreCase("account@example.com")).thenReturn(Optional.of(account));
        var request = new MockHttpServletRequest("GET", "/home");
        request.getSession().setAttribute(AccountSession.SECURITY_VERSION, 2L);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("account@example.com", null, List.of()));
        new AccountSessionValidationFilter(users).doFilter(request, new MockHttpServletResponse(),
                (req, res) -> assertThat(req.getAttribute(AccountSession.REQUEST_USER)).isSameAs(account));
    }
}
