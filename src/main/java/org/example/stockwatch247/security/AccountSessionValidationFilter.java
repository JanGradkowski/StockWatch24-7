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
    public AccountSessionValidationFilter(UserRepository users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
            users.findByEmailIgnoreCase(authentication.getName()).ifPresent(user -> {
                Object stored = request.getSession().getAttribute(AccountSession.SECURITY_VERSION);
                if (stored == null) request.getSession().setAttribute(AccountSession.SECURITY_VERSION, user.getSecurityVersion());
                else if (!(stored instanceof Long version) || version != user.getSecurityVersion()) {
                    SecurityContextHolder.clearContext(); request.getSession().invalidate();
                }
            });
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                response.sendRedirect(request.getContextPath() + "/login?expired=true"); return;
            }
        }
        chain.doFilter(request, response);
    }
}
