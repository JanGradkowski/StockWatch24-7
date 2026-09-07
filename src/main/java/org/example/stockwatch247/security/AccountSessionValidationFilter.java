package org.example.stockwatch247.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.stockwatch247.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AccountSessionValidationFilter extends OncePerRequestFilter {
    private final UserRepository users;
    private final boolean verificationRequired;
    public AccountSessionValidationFilter(UserRepository users) { this(users, true); }
    @org.springframework.beans.factory.annotation.Autowired
    public AccountSessionValidationFilter(UserRepository users,
            @org.springframework.beans.factory.annotation.Value("${security.email-verification.required:true}") boolean verificationRequired) {
        this.users = users;
        this.verificationRequired = verificationRequired;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/") || path.startsWith("/webjars/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
            var user = users.findByEmailIgnoreCase(authentication.getName()).orElse(null);
            var session = request.getSession(false);
            Object stored = session == null ? null : session.getAttribute(AccountSession.SECURITY_VERSION);
            if (user == null || !(stored instanceof Long version) || version != user.getSecurityVersion()
                    || user.getDeletionRequestedAt() != null || verificationRequired && !user.isVerified()) {
                SecurityContextHolder.clearContext();
                if (session != null) session.invalidate();
                if (request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/")) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Session expired. Please sign in again.\"}");
                } else response.sendRedirect(request.getContextPath() + "/login?expired=true");
                return;
            }
            request.setAttribute(AccountSession.REQUEST_USER, user);
        }
        chain.doFilter(request, response);
    }
}
