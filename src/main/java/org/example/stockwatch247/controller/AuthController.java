package org.example.stockwatch247.controller;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.AlertRuleService;
import org.example.stockwatch247.service.EmailVerificationService;
import org.example.stockwatch247.service.congress.CongressionalActivityService;
import org.example.stockwatch247.service.insider.InsiderActivityService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;



@Controller
public class AuthController {
    private final UserRepository userRepository;
    private final AlertRuleService alertRuleService;
    private final CongressionalActivityService congressionalActivityService;
    private final InsiderActivityService insiderActivityService;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final String dummyPasswordHash;
    public AuthController(UserRepository userRepository,
                          AlertRuleService alertRuleService,
                          CongressionalActivityService congressionalActivityService,
                          InsiderActivityService insiderActivityService,
                          PasswordEncoder passwordEncoder,
                          EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.alertRuleService = alertRuleService;
        this.congressionalActivityService = congressionalActivityService;
        this.insiderActivityService = insiderActivityService;
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
            @RequestParam(defaultValue = User.DEFAULT_ELLIOTT_MOTIVE_COLOR) String elliottMotiveColor,
            @RequestParam(defaultValue = User.DEFAULT_ELLIOTT_CORRECTIVE_COLOR) String elliottCorrectiveColor,
            Model model
    ){
        final String normalizedFirstName;
        final String normalizedLastName;
        final String normalizedEmail;
        final String normalizedMotiveColor;
        final String normalizedCorrectiveColor;
        try {
            normalizedFirstName = SecurityInputValidator.requirePersonName(firstName);
            normalizedLastName = SecurityInputValidator.requirePersonName(lastName);
            normalizedEmail = SecurityInputValidator.requireEmail(email);
            SecurityInputValidator.requirePassword(password);
            normalizedMotiveColor = SecurityInputValidator.requireHexColor(elliottMotiveColor);
            normalizedCorrectiveColor = SecurityInputValidator.requireHexColor(elliottCorrectiveColor);
            if (normalizedMotiveColor.equals(normalizedCorrectiveColor)) {
                throw new IllegalArgumentException("Choose two different Elliott Wave colors.");
            }
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
        user.setElliottMotiveColor(normalizedMotiveColor);
        user.setElliottCorrectiveColor(normalizedCorrectiveColor);
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
            var congressionalActivities = congressionalActivityService
                    .getLatestDashboardActivity(currentUser, 10);
            var congressionalFollowedStocks = congressionalActivityService
                    .getFollowedStocks(currentUser);
            var insiderActivities = insiderActivityService
                    .getLatestDashboardActivity(currentUser, 10);
            var insiderFollowedStocks = insiderActivityService
                    .getFollowedStocks(currentUser);
            var latestTickerNotifications = Stream.concat(
                            congressionalActivities.stream().map(this::tickerNotification),
                            insiderActivities.stream().map(this::tickerNotification))
                    .sorted(Comparator.comparing(
                            TickerNotificationView::detectedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(10)
                    .toList();
            model.addAttribute("firstName", currentUser.getFirstName());
            model.addAttribute("trackedCompanies", trackedCompanies);
            model.addAttribute("latestSignals", latestSignals);
            model.addAttribute("congressionalActivities", congressionalActivities);
            model.addAttribute("congressionalFollowedStocks", congressionalFollowedStocks);
            model.addAttribute("congressionalFollowedCount", congressionalFollowedStocks.size());
            model.addAttribute("insiderActivities", insiderActivities);
            model.addAttribute("insiderFollowedStocks", insiderFollowedStocks);
            model.addAttribute("insiderFollowedCount", insiderFollowedStocks.size());
            model.addAttribute("latestTickerNotifications", latestTickerNotifications);
            model.addAttribute("trackedInstrumentCount", Stream.of(
                            trackedCompanies.stream().map(AlertRuleService.TrackedCompanyView::symbol),
                            congressionalFollowedStocks.stream()
                                    .map(CongressionalActivityService.FollowedStockView::symbol),
                            insiderFollowedStocks.stream()
                                    .map(InsiderActivityService.FollowedStockView::symbol))
                    .flatMap(stream -> stream)
                    .map(String::toUpperCase)
                    .distinct()
                    .count());
            model.addAttribute("stockCompanyCount", trackedCompanies.stream()
                    .filter(company -> company.instrumentGroup().equals("stocks"))
                    .count());
            model.addAttribute("indexEtfCompanyCount", trackedCompanies.stream()
                    .filter(company -> company.instrumentGroup().equals("funds"))
                    .count());
            model.addAttribute("activeRuleCount", trackedCompanies.stream()
                    .mapToInt(AlertRuleService.TrackedCompanyView::ruleCount)
                    .sum());
        } else {
            model.addAttribute("firstName", "Trader");
            model.addAttribute("trackedCompanies", java.util.List.of());
            model.addAttribute("latestSignals", java.util.List.of());
            model.addAttribute("congressionalActivities", java.util.List.of());
            model.addAttribute("congressionalFollowedStocks", java.util.List.of());
            model.addAttribute("congressionalFollowedCount", 0);
            model.addAttribute("insiderActivities", java.util.List.of());
            model.addAttribute("insiderFollowedStocks", java.util.List.of());
            model.addAttribute("insiderFollowedCount", 0);
            model.addAttribute("latestTickerNotifications", java.util.List.of());
            model.addAttribute("trackedInstrumentCount", 0L);
            model.addAttribute("stockCompanyCount", 0L);
            model.addAttribute("indexEtfCompanyCount", 0L);
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

    @GetMapping("/signals")
    public String allSignalsPage(@RequestParam(defaultValue = "date") String sort,
                                 @RequestParam(defaultValue = "desc") String direction,
                                 @RequestParam(defaultValue = "0") int page,
                                 Model model,
                                 Principal principal) {
        User currentUser = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        if (currentUser == null) {
            return "redirect:/login";
        }
        model.addAttribute("firstName", currentUser.getFirstName());
        model.addAttribute("archive", alertRuleService.getSignalArchive(
                currentUser,
                sort,
                direction,
                page
        ));
        return "all-signals";
    }

    @GetMapping("/stock/{symbol}")
    public String stockPage(@PathVariable String symbol,
                            @RequestParam(required = false) String mic,
                            Model model,
                            Principal principal) {
        User currentUser = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        if (currentUser != null) {
            model.addAttribute("firstName", currentUser.getFirstName());
        }
        model.addAttribute("symbol", SecurityInputValidator.requireMarketSymbol(symbol));
        model.addAttribute("selectedMic", SecurityInputValidator.requireOptionalMicCode(mic));
        return "stock";
    }

    private String registrationRedirect() {
        return emailVerificationService.isRequired()
                ? "redirect:/login?verificationSent=true"
                : "redirect:/login?registered=true";
    }

    private TickerNotificationView tickerNotification(
            CongressionalActivityService.DashboardActivityView activity) {
        return new TickerNotificationView(
                "CONGRESSIONAL",
                "Congressional",
                activity.symbol(),
                activity.companyName(),
                activity.memberName(),
                activity.chamber(),
                activity.transactionType(),
                activity.transactionTypeLabel(),
                activity.amountRange(),
                null,
                null,
                activity.transactionDate(),
                activity.disclosureDate(),
                activity.detectedAt(),
                activity.deliveryStatus(),
                activity.sourceUrl());
    }

    private TickerNotificationView tickerNotification(
            InsiderActivityService.DashboardActivityView activity) {
        String amountLabel = activity.transactionValue() != null
                ? "$" + String.format(Locale.ROOT, "%,.0f", activity.transactionValue())
                : activity.shares() != null
                ? activity.shares().stripTrailingZeros().toPlainString() + " shares"
                : "Value not reported";
        return new TickerNotificationView(
                "INSIDER",
                "Corporate insider",
                activity.symbol(),
                activity.companyName(),
                activity.insiderName(),
                activity.ownerRole(),
                activity.transactionType(),
                activity.transactionTypeLabel(),
                amountLabel,
                activity.returnPercent(),
                activity.returnAsOf(),
                activity.transactionDate(),
                activity.filingDate(),
                activity.detectedAt(),
                activity.deliveryStatus(),
                activity.sourceUrl());
    }

    public record TickerNotificationView(
            String source,
            String sourceLabel,
            String symbol,
            String companyName,
            String actorName,
            String actorRole,
            String transactionType,
            String transactionTypeLabel,
            String amountLabel,
            BigDecimal returnPercent,
            LocalDate returnAsOf,
            LocalDate transactionDate,
            LocalDate filingDate,
            Instant detectedAt,
            String deliveryStatus,
            String sourceUrl) {
    }
}
