package org.example.stockwatch247.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

@Component
public class MfaAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
    private java.time.Clock clock = java.time.Clock.systemUTC();
    @org.springframework.beans.factory.annotation.Autowired
    void setClock(java.time.Clock clock) { this.clock = clock; }

    private final UserRepository users;
    public MfaAuthenticationSuccessHandler(UserRepository users) { this.users = users; }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        User user = users.findByEmailIgnoreCase(authentication.getName()).orElseThrow();
        if (authentication.getPrincipal() instanceof AccountPrincipal principal
                && (principal.securityVersion() != user.getSecurityVersion() || user.getDeletionRequestedAt() != null)) {
            SecurityContextHolder.clearContext();
            request.getSession().invalidate();
            response.sendRedirect(request.getContextPath() + "/login?expired=true");
            return;
        }
        if (user.isMfaEnabled()) {
            request.getSession().setAttribute(AccountSession.MFA_PENDING_USER_ID, user.getId());
            request.getSession().setAttribute(AccountSession.MFA_PENDING_VERSION, user.getSecurityVersion());
            request.getSession().setAttribute(AccountSession.MFA_PENDING_AT, clock.instant().getEpochSecond());
            SecurityContextHolder.clearContext();
            request.getSession().removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            response.sendRedirect(request.getContextPath() + "/login/2fa");
            return;
        }
        request.getSession().setAttribute(AccountSession.SECURITY_VERSION, user.getSecurityVersion());
        response.sendRedirect(request.getContextPath() + "/home");
    }
}
