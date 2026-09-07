package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.service.AccountSecurityService;
import org.example.stockwatch247.service.PasswordSecurityCodeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;

@Controller
public class PasswordRecoveryController {
    private final UserRepository users;
    private final PasswordSecurityCodeService codes;
    private final AccountSecurityService security;
    private final org.example.stockwatch247.service.AccountDeletionService deletion;
    private final RequestRateLimiter rateLimiter;

    public PasswordRecoveryController(UserRepository users, PasswordSecurityCodeService codes,
                                      AccountSecurityService security,
                                      org.example.stockwatch247.service.AccountDeletionService deletion,
                                      RequestRateLimiter rateLimiter) {
        this.users = users; this.codes = codes; this.security = security; this.deletion = deletion;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/forgot-password") public String forgotPage() { return "forgot-password"; }

    @PostMapping("/forgot-password")
    public String request(@RequestParam String email, HttpServletRequest request) {
        if (!rateLimiter.tryAcquire("forgot-password:client:" + request.getRemoteAddr(), 5, Duration.ofMinutes(15)))
            return "redirect:/reset-password?requested=true";
        try {
            String normalized = SecurityInputValidator.requireEmail(email);
            User user = users.findByEmailIgnoreCase(normalized).orElse(null);
            if (user != null && user.isVerified() && user.getDeletionRequestedAt() == null)
                codes.issue(user, PasswordSecurityCodeService.RESET, request.getRemoteAddr());
        } catch (RuntimeException ignored) {
            // Always return the same response; this endpoint must not reveal account existence or mail state.
        }
        return "redirect:/reset-password?requested=true";
    }

    @GetMapping("/reset-password") public String resetPage() { return "reset-password"; }

    @PostMapping("/reset-password")
    public String reset(@RequestParam String email, @RequestParam String code,
                        @RequestParam String newPassword, @RequestParam String confirmPassword, Model model) {
        if (!newPassword.equals(confirmPassword)) { model.addAttribute("error", "The new passwords do not match."); return "reset-password"; }
        try {
            User user = users.findByEmailIgnoreCase(SecurityInputValidator.requireEmail(email))
                    .orElseThrow(() -> new IllegalArgumentException("The code or account details are invalid."));
            if (!security.resetPassword(user.getId(), code, newPassword, codes).successful()) {
                model.addAttribute("error", "The code or account details are invalid.");
                return "reset-password";
            }
            return "redirect:/login?passwordReset=true";
        } catch (IllegalArgumentException exception) {
            model.addAttribute("error", "The code or account details are invalid."); return "reset-password";
        }
    }

    @GetMapping("/cancel-account-deletion")
    public String cancelDeletionPage(@RequestParam(required = false) String token, jakarta.servlet.http.HttpSession session) {
        if (token != null) {
            if (!token.matches("[A-Za-z0-9_-]{40,80}")) return "redirect:/login?deletionCancelError=true";
            session.setAttribute("deletionCancellationToken", token);
            session.setAttribute("deletionCancellationExpires", java.time.Instant.now().plusSeconds(600).getEpochSecond());
            return "redirect:/cancel-account-deletion";
        }
        return session.getAttribute("deletionCancellationToken") == null ? "redirect:/login?deletionCancelError=true" : "cancel-account-deletion";
    }

    @PostMapping("/cancel-account-deletion")
    public String cancelDeletion(jakarta.servlet.http.HttpSession session) {
        Object token = session.getAttribute("deletionCancellationToken");
        Object expires = session.getAttribute("deletionCancellationExpires");
        session.removeAttribute("deletionCancellationToken"); session.removeAttribute("deletionCancellationExpires");
        return token instanceof String value && expires instanceof Long deadline && deadline > java.time.Instant.now().getEpochSecond()
                && deletion.cancel(value) ? "redirect:/login?deletionCancelled=true" : "redirect:/login?deletionCancelError=true";
    }
}
