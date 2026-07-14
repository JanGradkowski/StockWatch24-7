package org.example.stockwatch247.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {
    private final long maximumLifetimeMillis;

    public AbsoluteSessionTimeoutFilter(Duration maximumLifetime) {
        this.maximumLifetimeMillis = Math.max(Duration.ofMinutes(5).toMillis(), maximumLifetime.toMillis());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && System.currentTimeMillis() - session.getCreationTime() >= maximumLifetimeMillis) {
            session.invalidate();
            if (request.getRequestURI().startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"Session expired. Please sign in again.\"}");
            } else {
                response.sendRedirect(request.getContextPath() + "/login?expired=true");
            }
            return;
        }
        filterChain.doFilter(request, response);
    }
}
