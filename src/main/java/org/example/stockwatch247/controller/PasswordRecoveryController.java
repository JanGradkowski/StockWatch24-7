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
            security.resetPassword(user.getId(), code, newPassword, codes);
            return "redirect:/login?passwordReset=true";
        } catch (IllegalArgumentException exception) {
            model.addAttribute("error", exception.getMessage()); return "reset-password";
        }
    }

    @GetMapping("/cancel-account-deletion")
    public String cancelDeletionPage(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "cancel-account-deletion";
    }

    @PostMapping("/cancel-account-deletion")
    public String cancelDeletion(@RequestParam String token) {
        return deletion.cancel(token) ? "redirect:/login?deletionCancelled=true" : "redirect:/login?deletionCancelError=true";
    }
}
