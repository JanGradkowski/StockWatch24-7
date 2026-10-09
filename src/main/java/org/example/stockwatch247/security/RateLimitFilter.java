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
import java.util.function.Consumer;

public class RateLimitFilter extends OncePerRequestFilter {
    private final RequestRateLimiter rateLimiter;
    private final KnownDeviceCookies knownDevices;
    private final Consumer<String> accountLimitReached;

    public RateLimitFilter(RequestRateLimiter rateLimiter) {
        this(rateLimiter, null, account -> { });
    }

    public RateLimitFilter(RequestRateLimiter rateLimiter, KnownDeviceCookies knownDevices,
                           Consumer<String> accountLimitReached) {
        this.rateLimiter = rateLimiter;
        this.knownDevices = knownDevices;
        this.accountLimitReached = accountLimitReached;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requester = requesterKey(request);
        if (isInsiderProviderRequest(request)) {
            if (!tryAcquire(
                    response,
                    new Limit("insider-activity-minute", 5, Duration.ofMinutes(1)),
                    requester)
                    || !tryAcquire(
                    response,
                    new Limit("insider-activity-hour", 100, Duration.ofHours(1)),
                    requester)) {
                return;
            }
        }

        Limit limit = limitFor(request);
        if (limit != null && !tryAcquire(response, limit, requester)) {
            return;
        }
        // Provider-backed search shares a small external allowance across every account.
        if (request.getRequestURI().equals("/api/stocks/search")
                && !tryAcquire(response, new Limit("stock-search-hour", 100, Duration.ofHours(1)), requester)) {
            return;
        }

        String account = accountKey(request);
        if (isLogin(request) && account != null && !acquireLogin(request, response, account)) {
            return;
        }
        if (isVerificationResend(request) && account != null
                && !tryAcquire(response, new Limit("verification-resend-account", 3, Duration.ofHours(1)),
                "account:" + account)) {
            return;
        }
        if (isPasswordReset(request) && account != null
                && !tryAcquire(response, new Limit("reset-account", 8, Duration.ofMinutes(15)), "account:" + account)) return;
        if (isSensitivePasswordAction(request)
                && !tryAcquire(response, new Limit("settings-password-client", 20, Duration.ofMinutes(10)),
                    "ip:" + request.getRemoteAddr())) return;
        filterChain.doFilter(request, response);
    }

    /**
     * One address guessing at an account is stopped quickly, while the account-wide ceiling is high
     * enough that many addresses are needed to reach it, and browsers that already signed in to the
     * account skip it entirely. A flood of failures elsewhere therefore cannot lock the owner out.
     */
    private boolean acquireLogin(HttpServletRequest request, HttpServletResponse response, String account)
            throws IOException {
        Limit perClient = new Limit("login-account-client", 10, Duration.ofMinutes(15));
        if (!rateLimiter.tryAcquire(perClient.bucket() + ":account:" + account + ":ip:" + request.getRemoteAddr(),
                perClient.requests(), perClient.window())) {
            rejectLogin(request, response, perClient);
            return false;
        }
        if (knownDevices != null && knownDevices.recognizes(request, account)) {
            return true;
        }
        Limit perAccount = new Limit("login-account", 200, Duration.ofHours(1));
        if (!rateLimiter.tryAcquire(perAccount.bucket() + ":account:" + account,
                perAccount.requests(), perAccount.window())) {
            accountLimitReached.accept(account);
            rejectLogin(request, response, perAccount);
            return false;
        }
        return true;
    }

    private void rejectLogin(HttpServletRequest request, HttpServletResponse response, Limit limit) throws IOException {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/html")) {
            response.setStatus(303);
            response.setHeader("Location", request.getContextPath() + "/login?locked=true");
            return;
        }
        reject(response, limit);
    }

    private boolean tryAcquire(HttpServletResponse response, Limit limit, String requester) throws IOException {
        String key = limit.bucket() + ':' + requester;
        if (rateLimiter.tryAcquire(key, limit.requests(), limit.window())) {
            return true;
        }
        reject(response, limit);
        return false;
    }

    private void reject(HttpServletResponse response, Limit limit) throws IOException {
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", Long.toString(Math.max(1L, limit.window().toSeconds())));
        response.getWriter().write("{\"error\":\"Too many requests. Please try again later.\"}");
    }

    private Limit limitFor(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (isPasswordReset(request)) return new Limit("reset-client", 20, Duration.ofMinutes(15));
        if (isSensitivePasswordAction(request)) return new Limit("settings-password", 8, Duration.ofMinutes(10));
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
        if (path.equals("/api/stocks/search/local")) {
            return new Limit("stock-search-local", 120, Duration.ofMinutes(1));
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
        if (path.startsWith("/api/congressional-activity/")) {
            return new Limit("congressional-activity", 30, Duration.ofMinutes(1));
        }
        return null;
    }

    private boolean isInsiderProviderRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return ("POST".equals(request.getMethod())
                && path.startsWith("/api/insider-activity/")
                && path.endsWith("/history/refresh"))
                || ("PUT".equals(request.getMethod())
                && path.startsWith("/api/insider-activity/")
                && path.endsWith("/subscription"));
    }

    private boolean isLogin(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && "/login".equals(request.getRequestURI());
    }

    private boolean isVerificationResend(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && "/resend-verification".equals(request.getRequestURI());
    }

    private boolean isPasswordReset(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && "/reset-password".equals(request.getServletPath().isEmpty()
                ? request.getRequestURI() : request.getServletPath());
    }

    private boolean isSensitivePasswordAction(HttpServletRequest request) {
        String path = request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
        return "POST".equals(request.getMethod()) && (path.equals("/settings/password")
                || path.equals("/settings/password/code") || path.equals("/settings/mfa/start"));
    }

    private String accountKey(HttpServletRequest request) {
        if (!isLogin(request) && !isVerificationResend(request) && !isPasswordReset(request)) {
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
