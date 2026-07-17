package org.example.stockwatch247.controller;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.AlertRuleService;
import org.example.stockwatch247.service.EmailVerificationService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.nio.charset.StandardCharsets;



@Controller
public class AuthController {
    private final UserRepository userRepository;
    private final AlertRuleService alertRuleService;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final String dummyPasswordHash;
    public AuthController(UserRepository userRepository,
                          AlertRuleService alertRuleService,
                          PasswordEncoder passwordEncoder,
                          EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.alertRuleService = alertRuleService;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.dummyPasswordHash = passwordEncoder.encode("nonexistent-account-timing-equalizer");
    }
    @GetMapping("/login")
    public String loginPage() {return "login";}

    @GetMapping("/signup")
    public String signupPage() {return "signup";}

    @GetMapping("/resend-verification")
    public String resendVerificationPage() {return "resend-verification";}

    @PostMapping("/signup")
    public String processSignup(
            @RequestParam String firstName,
            @RequestParam String lastName,
            @RequestParam String email,
            @RequestParam String password,
            Model model
    ){
        final String normalizedFirstName;
        final String normalizedLastName;
        final String normalizedEmail;
        try {
            normalizedFirstName = SecurityInputValidator.requirePersonName(firstName);
            normalizedLastName = SecurityInputValidator.requirePersonName(lastName);
            normalizedEmail = SecurityInputValidator.requireEmail(email);
            SecurityInputValidator.requirePassword(password);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "signup";
        }

        User existing = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        if (existing != null) {
            return registrationRedirect();
        }

        User user = new User();
        user.setFirstName(normalizedFirstName);
        user.setLastName(normalizedLastName);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setVerified(false);
        try {
            emailVerificationService.registerNewUser(user);
        } catch (IllegalStateException ignored) {
            model.addAttribute("error", "Verification email could not be sent. Please try again later.");
            return "signup";
        }
        return registrationRedirect();
    }

    @PostMapping("/resend-verification")
    public String processVerificationResend(@RequestParam String email,
                                            @RequestParam String password) {
        String normalizedEmail = null;
        try {
            normalizedEmail = SecurityInputValidator.requireEmail(email);
        } catch (IllegalArgumentException ignored) {
            // Keep the response indistinguishable for invalid and unknown accounts.
        }

        User user = normalizedEmail == null
                ? null
                : userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        String storedHash = user == null ? dummyPasswordHash : user.getPasswordHash();
        String candidate = password != null
                && password.getBytes(StandardCharsets.UTF_8).length <= 72
                ? password
                : "invalid-password-timing-equalizer";
        boolean passwordMatches = passwordEncoder.matches(candidate, storedHash);

        if (user != null && passwordMatches && !user.isVerified()) {
            try {
                emailVerificationService.resendAfterPasswordConfirmation(user.getId());
            } catch (IllegalStateException exception) {
                System.err.println("Verification email resend failed.");
            }
        }
        return "redirect:/login?verificationResendRequested=true";
    }

    @GetMapping("/verify-email")
    public String verifyEmail(@RequestParam String token) {
        return emailVerificationService.verify(token)
                ? "redirect:/login?verified=true"
                : "redirect:/login?verificationError=true";
    }
    @GetMapping("/home")
    public String homePage(Model model, Principal principal) {
        // We can fetch the full user object using the email from Principal
        User currentUser = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);

        if (currentUser != null) {
            var trackedCompanies = alertRuleService.getActiveCompanyViews(currentUser);
            var latestSignals = alertRuleService.getLatestSignalViews(currentUser);
            model.addAttribute("firstName", currentUser.getFirstName());
            model.addAttribute("trackedCompanies", trackedCompanies);
            model.addAttribute("latestSignals", latestSignals);
            model.addAttribute("activeRuleCount", trackedCompanies.stream()
                    .mapToInt(AlertRuleService.TrackedCompanyView::ruleCount)
                    .sum());
        } else {
            model.addAttribute("firstName", "Trader");
            model.addAttribute("trackedCompanies", java.util.List.of());
            model.addAttribute("latestSignals", java.util.List.of());
            model.addAttribute("activeRuleCount", 0);
        }

        return "home";
    }

    @GetMapping("/alerts/{alertRuleId}")
    public String alertHistoryPage(@PathVariable Long alertRuleId, Model model, Principal principal) {
        User currentUser = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        if (currentUser == null) {
            return "redirect:/login";
        }
        model.addAttribute("firstName", currentUser.getFirstName());
        model.addAttribute("history", alertRuleService.getCompanySignalHistory(currentUser, alertRuleId));
        return "alert-history";
    }

    @GetMapping("/alerts/signals/{alertEventId}")
    public String signalDetailPage(@PathVariable Long alertEventId, Model model, Principal principal) {
        User currentUser = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        if (currentUser == null) {
            return "redirect:/login";
        }
        model.addAttribute("firstName", currentUser.getFirstName());
        model.addAttribute("signal", alertRuleService.getSignalDetail(currentUser, alertEventId));
        return "signal-detail";
    }

    @GetMapping("/stock/{symbol}")
    public String stockPage(@PathVariable String symbol, Model model, Principal principal) {
        User currentUser = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        if (currentUser != null) {
            model.addAttribute("firstName", currentUser.getFirstName());
        }
        model.addAttribute("symbol", SecurityInputValidator.requireMarketSymbol(symbol));
        return "stock";
    }

    private String registrationRedirect() {
        return emailVerificationService.isRequired()
                ? "redirect:/login?verificationSent=true"
                : "redirect:/login?registered=true";
    }
}
