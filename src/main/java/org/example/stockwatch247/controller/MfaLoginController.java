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
    private final UserRepository users;
    private final AccountSecurityService security;
    private final UserDetailsService userDetails;
    private final RequestRateLimiter rateLimiter;
    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

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
        if (!security.verifyLoginFactor(userId, code)) {
            model.addAttribute("error", "That authenticator or recovery code is invalid."); return "login-2fa";
        }
        User user = users.findById(userId).orElseThrow();
        var details = userDetails.loadUserByUsername(user.getEmail());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication); SecurityContextHolder.setContext(context);
        request.getSession().removeAttribute(AccountSession.MFA_PENDING_USER_ID);
        request.getSession().removeAttribute(AccountSession.MFA_PENDING_AT);
        request.getSession().setAttribute(AccountSession.SECURITY_VERSION, user.getSecurityVersion());
        contextRepository.saveContext(context, request, response);
        return "redirect:/home";
    }

    @PostMapping("/login/2fa/cancel")
    public String cancel(HttpSession session) { session.invalidate(); return "redirect:/login"; }

    private Long pendingUserId(HttpSession session) {
        Object id = session.getAttribute(AccountSession.MFA_PENDING_USER_ID);
        Object at = session.getAttribute(AccountSession.MFA_PENDING_AT);
        if (!(id instanceof Long userId) || !(at instanceof Long started)
                || started < Instant.now().minusSeconds(300).getEpochSecond()) {
            session.removeAttribute(AccountSession.MFA_PENDING_USER_ID);
            session.removeAttribute(AccountSession.MFA_PENDING_AT);
            return null;
        }
        return userId;
    }
}
