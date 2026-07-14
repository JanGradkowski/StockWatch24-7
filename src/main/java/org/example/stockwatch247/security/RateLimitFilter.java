package org.example.stockwatch247.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

public class RateLimitFilter extends OncePerRequestFilter {
    private final RequestRateLimiter rateLimiter;

    public RateLimitFilter(RequestRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Limit limit = limitFor(request);
        if (limit != null && !tryAcquire(response, limit, requesterKey(request))) {
            return;
        }

        String account = accountKey(request);
        if (isLogin(request) && account != null
                && !tryAcquire(response, new Limit("login-account", 30, Duration.ofMinutes(15)), "account:" + account)) {
            return;
        }
        if (isVerificationResend(request) && account != null
                && !tryAcquire(response, new Limit("verification-resend-account", 3, Duration.ofHours(1)),
                "account:" + account)) {
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean tryAcquire(HttpServletResponse response, Limit limit, String requester) throws IOException {
        String key = limit.bucket() + ':' + requester;
        if (rateLimiter.tryAcquire(key, limit.requests(), limit.window())) {
            return true;
        }
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", Long.toString(Math.max(1L, limit.window().toSeconds())));
        response.getWriter().write("{\"error\":\"Too many requests. Please try again later.\"}");
        return false;
    }

    private Limit limitFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (isLogin(request)) {
            return new Limit("login", 10, Duration.ofMinutes(1));
        }
        if ("POST".equals(method) && "/signup".equals(path)) {
            return new Limit("signup", 5, Duration.ofHours(1));
        }
        if ("GET".equals(method) && "/verify-email".equals(path)) {
            return new Limit("verify-email", 20, Duration.ofMinutes(10));
        }
        if (isVerificationResend(request)) {
            return new Limit("verification-resend", 5, Duration.ofHours(1));
        }
        if (path.startsWith("/api/stocks/search")) {
            return new Limit("stock-search", 30, Duration.ofMinutes(1));
        }
        if (path.startsWith("/api/stocks/")) {
            return new Limit("stock-data", 120, Duration.ofMinutes(1));
        }
        if (path.startsWith("/api/alerts/") && path.endsWith("/check")) {
            return new Limit("signal-check", 30, Duration.ofMinutes(1));
        }
        if (path.startsWith("/api/alerts/")) {
            return new Limit("alert-management", 60, Duration.ofMinutes(1));
        }
        return null;
    }

    private boolean isLogin(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && "/login".equals(request.getRequestURI());
    }

    private boolean isVerificationResend(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && "/resend-verification".equals(request.getRequestURI());
    }

    private String accountKey(HttpServletRequest request) {
        if (!isLogin(request) && !isVerificationResend(request)) {
            return null;
        }
        String email = request.getParameter("email");
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.length() > 254 ? null : normalized;
    }

    private String requesterKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            return "user:" + authentication.getName().toLowerCase(Locale.ROOT);
        }
        return "ip:" + request.getRemoteAddr();
    }

    private record Limit(String bucket, int requests, Duration window) {
    }
}
