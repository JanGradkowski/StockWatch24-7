package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.AccountSession;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.service.AccountSecurityService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;

@Controller
public class MfaLoginController {
    static final int FAILURES_PER_PASSWORD_ENTRY = 5;
    private java.time.Clock clock = java.time.Clock.systemUTC();
    @org.springframework.beans.factory.annotation.Autowired
    void setClock(java.time.Clock clock) { this.clock = clock; }

    private final UserRepository users;
    private final AccountSecurityService security;
    private final UserDetailsService userDetails;
    private final RequestRateLimiter rateLimiter;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.example.stockwatch247.security.KnownDeviceCookies knownDevices;

    public MfaLoginController(UserRepository users, AccountSecurityService security,
                              UserDetailsService userDetails, RequestRateLimiter rateLimiter) {
        this.users = users; this.security = security; this.userDetails = userDetails; this.rateLimiter = rateLimiter;
    }

    @GetMapping("/login/2fa")
    public String page(HttpSession session) {
        return pendingUserId(session) == null ? "redirect:/login" : "login-2fa";
    }

    @PostMapping("/login/2fa")
    public String verify(@RequestParam String code, HttpServletRequest request,
                         HttpServletResponse response, Model model) {
        Long userId = pendingUserId(request.getSession());
        if (userId == null) return "redirect:/login?expired=true";
        String client = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        if (!rateLimiter.tryAcquire("mfa-login:user:" + userId, 8, Duration.ofMinutes(10))
                || !rateLimiter.tryAcquire("mfa-login:client:" + client, 20, Duration.ofMinutes(10))) {
            model.addAttribute("error", "Too many attempts. Please wait before trying again."); return "login-2fa";
        }
        Object pendingVersion = request.getSession().getAttribute(AccountSession.MFA_PENDING_VERSION);
        Object pendingAt = request.getSession().getAttribute(AccountSession.MFA_PENDING_AT);
        if (!(pendingVersion instanceof Long version) || !(pendingAt instanceof Long started)) {
            request.getSession().invalidate();
            return "redirect:/login?expired=true";
        }
        if (security.loginFactorPaused(userId)) {
            clearPendingLogin(request.getSession());
            return "redirect:/login?mfaPaused=true";
        }
        var accepted = security.verifyLoginFactor(userId, version, started, code);
        if (accepted.isEmpty()) {
            Object previous = request.getSession().getAttribute(AccountSession.MFA_PENDING_FAILURES);
            int failures = (previous instanceof Integer count ? count : 0) + 1;
            boolean paused = security.loginFactorPaused(userId);
            if (paused || failures >= FAILURES_PER_PASSWORD_ENTRY) {
                // A fresh password entry is required, so a stolen session cannot keep guessing codes.
                clearPendingLogin(request.getSession());
                return paused ? "redirect:/login?mfaPaused=true" : "redirect:/login?mfaRetry=true";
            }
            request.getSession().setAttribute(AccountSession.MFA_PENDING_FAILURES, failures);
            model.addAttribute("error", "That authenticator or recovery code is invalid."); return "login-2fa";
        }
        var identity = accepted.get();
        var details = userDetails.loadUserByUsername(identity.email());
        if (!details.isEnabled() || !details.isAccountNonLocked() || !details.isAccountNonExpired()
                || !details.isCredentialsNonExpired()) {
            request.getSession().invalidate();
            return "redirect:/login?expired=true";
        }
        request.changeSessionId();
        Object pendingTarget = request.getSession().getAttribute(AccountSession.MFA_PENDING_TARGET);
        String target = pendingTarget instanceof String path
                ? org.example.stockwatch247.security.PostLoginRedirect.safeTarget(path, "")
                : org.example.stockwatch247.security.PostLoginRedirect.DEFAULT_TARGET;
        var authentication = UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication); SecurityContextHolder.setContext(context);
        clearPendingLogin(request.getSession());
        request.getSession().setAttribute(AccountSession.SECURITY_VERSION, identity.securityVersion());
        contextRepository.saveContext(context, request, response);
        if (knownDevices != null) knownDevices.remember(request, response, identity.email());
        return "redirect:" + target;
    }

    @PostMapping("/login/2fa/cancel")
    public String cancel(HttpSession session) { session.invalidate(); return "redirect:/login"; }

    private Long pendingUserId(HttpSession session) {
        Object id = session.getAttribute(AccountSession.MFA_PENDING_USER_ID);
        Object at = session.getAttribute(AccountSession.MFA_PENDING_AT);
        if (!(id instanceof Long userId) || !(at instanceof Long started)
                || started < clock.instant().minusSeconds(300).getEpochSecond()) {
            clearPendingLogin(session);
            return null;
        }
        return userId;
    }

    private void clearPendingLogin(HttpSession session) {
        session.removeAttribute(AccountSession.MFA_PENDING_USER_ID);
        session.removeAttribute(AccountSession.MFA_PENDING_AT);
        session.removeAttribute(AccountSession.MFA_PENDING_VERSION);
        session.removeAttribute(AccountSession.MFA_PENDING_FAILURES);
        session.removeAttribute(AccountSession.MFA_PENDING_TARGET);
    }
}
