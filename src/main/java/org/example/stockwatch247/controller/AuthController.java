package org.example.stockwatch247.controller;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.AlertRuleService;
import org.example.stockwatch247.service.EmailVerificationService;
import org.example.stockwatch247.service.SignalScoringPreferencesService;
import org.example.stockwatch247.service.congress.CongressionalActivityService;
import org.example.stockwatch247.service.insider.InsiderActivityService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;



@Controller
public class AuthController {
    private static final int ACTIVITY_ARCHIVE_PAGE_SIZE = 25;
    private final UserRepository userRepository;
    private final AlertRuleService alertRuleService;
    private final CongressionalActivityService congressionalActivityService;
    private final InsiderActivityService insiderActivityService;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final SignalScoringPreferencesService scoringPreferencesService;
    private final String dummyPasswordHash;
    @Autowired
    public AuthController(UserRepository userRepository,
                          AlertRuleService alertRuleService,
                          CongressionalActivityService congressionalActivityService,
                          InsiderActivityService insiderActivityService,
                          PasswordEncoder passwordEncoder,
                          EmailVerificationService emailVerificationService,
                          SignalScoringPreferencesService scoringPreferencesService) {
        this.userRepository = userRepository;
        this.alertRuleService = alertRuleService;
        this.congressionalActivityService = congressionalActivityService;
        this.insiderActivityService = insiderActivityService;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.scoringPreferencesService = scoringPreferencesService;
        this.dummyPasswordHash = passwordEncoder.encode("nonexistent-account-timing-equalizer");
    }

    public AuthController(UserRepository userRepository,
                          AlertRuleService alertRuleService,
                          CongressionalActivityService congressionalActivityService,
                          InsiderActivityService insiderActivityService,
                          PasswordEncoder passwordEncoder,
                          EmailVerificationService emailVerificationService) {
        this(userRepository, alertRuleService, congressionalActivityService, insiderActivityService,
                passwordEncoder, emailVerificationService, null);
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
            long congressionalUnreadCount = congressionalActivityService
                    .unreadActivityCount(currentUser);
            long insiderUnreadCount = insiderActivityService
                    .unreadActivityCount(currentUser);
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
            model.addAttribute("congressionalUnreadCount", congressionalUnreadCount);
            model.addAttribute("congressionalFollowedStocks", congressionalFollowedStocks);
            model.addAttribute("congressionalFollowedCount", congressionalFollowedStocks.size());
            model.addAttribute("insiderActivities", insiderActivities);
            model.addAttribute("insiderUnreadCount", insiderUnreadCount);
            model.addAttribute("insiderFollowedStocks", insiderFollowedStocks);
            model.addAttribute("insiderFollowedCount", insiderFollowedStocks.size());
            model.addAttribute("latestTickerNotifications", latestTickerNotifications);
            model.addAttribute("tickerNotificationUnreadCount",
                    congressionalUnreadCount + insiderUnreadCount);
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
            model.addAttribute("congressionalUnreadCount", 0L);
            model.addAttribute("congressionalFollowedStocks", java.util.List.of());
            model.addAttribute("congressionalFollowedCount", 0);
            model.addAttribute("insiderActivities", java.util.List.of());
            model.addAttribute("insiderUnreadCount", 0L);
            model.addAttribute("insiderFollowedStocks", java.util.List.of());
            model.addAttribute("insiderFollowedCount", 0);
            model.addAttribute("latestTickerNotifications", java.util.List.of());
            model.addAttribute("tickerNotificationUnreadCount", 0L);
            model.addAttribute("trackedInstrumentCount", 0L);
            model.addAttribute("stockCompanyCount", 0L);
            model.addAttribute("indexEtfCompanyCount", 0L);
            model.addAttribute("activeRuleCount", 0);
        }

        return "home";
    }

    @GetMapping("/alerts/{alertRuleId}")
    public String alertHistoryPage(@PathVariable Long alertRuleId,
                                   @RequestParam(defaultValue = "date") String sort,
                                   @RequestParam(defaultValue = "desc") String direction,
                                   @RequestParam(defaultValue = "0") int page,
                                   Model model,
                                   Principal principal) {
        User currentUser = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        if (currentUser == null) {
            return "redirect:/login";
        }
        model.addAttribute("firstName", currentUser.getFirstName());
        AlertRuleService.CompanySignalArchive companyArchive = alertRuleService.getCompanySignalArchive(
                currentUser, alertRuleId, sort, direction, page);
        model.addAttribute("companyArchive", companyArchive);
        model.addAttribute("archive", companyArchive.archive());
        return "all-signals";
    }

    @GetMapping("/alerts/signals/{alertEventId}")
    public String signalDetailPage(@PathVariable Long alertEventId, Model model, Principal principal) {
        User currentUser = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        if (currentUser == null) {
            return "redirect:/login";
        }
        model.addAttribute("firstName", currentUser.getFirstName());
        AlertRuleService.SignalDetailView signal = alertRuleService.getSignalDetail(currentUser, alertEventId);
        model.addAttribute("signal", signal);
        model.addAttribute("displayScore", displayScore(currentUser, signal));
        return "signal-detail";
    }

    private SignalScoringPreferencesService.DisplayScore displayScore(
            User user,
            AlertRuleService.SignalDetailView signal) {
        List<SignalScoringPreferencesService.EvidenceSection> evidence = signal.reasons().stream()
                .map(reason -> new SignalScoringPreferencesService.EvidenceSection(
                        reason.category(), reason.scoreLabel(), reason.statusLabel(), reason.caution(),
                        reason.scored(), reason.details().stream()
                        .map(detail -> new SignalScoringPreferencesService.EvidenceDetail(
                                detail.label(), detail.text(), detail.scoreLabel()))
                        .toList()))
                .toList();
        if (scoringPreferencesService == null || signal.setupScore() == null) {
            int fallback = signal.setupScore() == null ? 0 : signal.setupScore();
            return new SignalScoringPreferencesService.DisplayScore(
                    fallback, signal.setupBand(), signal.setupStrengthLabel(), signal.setupExplanation(),
                    false, evidence, !evidence.isEmpty(), "Factory scoring profile.");
        }
        return scoringPreferencesService.score(
                scoringPreferencesService.profile(user, signal.patternFamily(), signal.interval()),
                signal.setupScore(), evidence);
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

    @GetMapping("/activity-signals")
    public String allActivitySignalsPage(@RequestParam(defaultValue = "date") String sort,
                                         @RequestParam(defaultValue = "desc") String direction,
                                         @RequestParam(defaultValue = "0") int page,
                                         Model model,
                                         Principal principal) {
        User currentUser = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        if (currentUser == null) {
            return "redirect:/login";
        }
        model.addAttribute("firstName", currentUser.getFirstName());
        model.addAttribute("archive", getActivityArchive(currentUser, sort, direction, page));
        return "all-activity-signals";
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
                activity.id(),
                "CONGRESSIONAL",
                "Congressional",
                activity.symbol(),
                activity.companyName(),
                activity.memberName(),
                activity.chamber(),
                activity.transactionType(),
                activity.transactionTypeLabel(),
                activity.amountRange(),
                activity.returnPercent(),
                activity.returnAsOf(),
                activity.transactionDate(),
                activity.disclosureDate(),
                activity.detectedAt(),
                activity.deliveryStatus(),
                activity.sourceUrl(),
                activity.hasBeenRead());
    }

    private TickerNotificationView tickerNotification(
            InsiderActivityService.DashboardActivityView activity) {
        String amountLabel = activity.transactionValue() != null
                ? "$" + String.format(Locale.ROOT, "%,.0f", activity.transactionValue())
                : activity.shares() != null
                ? activity.shares().stripTrailingZeros().toPlainString() + " shares"
                : "Value not reported";
        return new TickerNotificationView(
                activity.id(),
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
                activity.sourceUrl(),
                activity.hasBeenRead());
    }

    private ActivitySignalArchivePage getActivityArchive(User user,
                                                         String requestedSort,
                                                         String requestedDirection,
                                                         int requestedPage) {
        String sort = normalizeActivitySort(requestedSort);
        String direction = "asc".equalsIgnoreCase(requestedDirection) ? "asc" : "desc";
        List<TickerNotificationView> notifications = Stream.concat(
                        congressionalActivityService.getAllActivity(user).stream().map(this::tickerNotification),
                        insiderActivityService.getAllActivity(user).stream().map(this::tickerNotification))
                .sorted(activityComparator(sort, direction))
                .toList();
        int totalPages = (notifications.size() + ACTIVITY_ARCHIVE_PAGE_SIZE - 1)
                / ACTIVITY_ARCHIVE_PAGE_SIZE;
        int lastPage = Math.max(0, totalPages - 1);
        int page = Math.min(Math.max(0, requestedPage), lastPage);
        int fromIndex = Math.min(page * ACTIVITY_ARCHIVE_PAGE_SIZE, notifications.size());
        int toIndex = Math.min(fromIndex + ACTIVITY_ARCHIVE_PAGE_SIZE, notifications.size());
        return new ActivitySignalArchivePage(
                notifications.subList(fromIndex, toIndex),
                page,
                totalPages,
                notifications.size(),
                sort,
                direction,
                page > 0,
                page + 1 < totalPages);
    }

    private String normalizeActivitySort(String requestedSort) {
        if (requestedSort == null) {
            return "date";
        }
        return switch (requestedSort.toLowerCase(Locale.ROOT)) {
            case "company", "transaction", "type", "actor" ->
                    requestedSort.toLowerCase(Locale.ROOT);
            default -> "date";
        };
    }

    private Comparator<TickerNotificationView> activityComparator(String sort, String direction) {
        Comparator<String> textOrder = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        Comparator<TickerNotificationView> primary = switch (sort) {
            case "company" -> Comparator.comparing(TickerNotificationView::companyName, textOrder)
                    .thenComparing(TickerNotificationView::symbol, textOrder);
            case "transaction" -> Comparator.comparing(
                    TickerNotificationView::transactionTypeLabel, textOrder);
            case "type" -> Comparator.comparing(TickerNotificationView::sourceLabel, textOrder);
            case "actor" -> Comparator.comparing(TickerNotificationView::actorName, textOrder);
            default -> Comparator.comparing(
                    TickerNotificationView::transactionDate,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        if ("desc".equals(direction)) {
            primary = primary.reversed();
        }
        return primary
                .thenComparing(TickerNotificationView::detectedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(TickerNotificationView::source)
                .thenComparing(TickerNotificationView::id, Comparator.reverseOrder());
    }

    public record TickerNotificationView(
            Long id,
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
            String sourceUrl,
            boolean hasBeenRead) {
        public String notificationKey() {
            return source + "-" + id;
        }

        public String readEndpoint() {
            return "INSIDER".equals(source)
                    ? "/api/insider-activity/notifications/" + id + "/read"
                    : "/api/congressional-activity/notifications/" + id + "/read";
        }

        public String detailUrl() {
            return "/activity-signals/" + source.toLowerCase(Locale.ROOT) + "/" + id;
        }
    }

    public record ActivitySignalArchivePage(
            List<TickerNotificationView> signals,
            int page,
            int totalPages,
            long totalSignals,
            String sort,
            String direction,
            boolean hasPrevious,
            boolean hasNext) {
        public int displayPage() {
            return totalPages == 0 ? 0 : page + 1;
        }

        public String groupKey(TickerNotificationView signal) {
            return switch (sort) {
                case "company" -> signal.companyName().toLowerCase(Locale.ROOT);
                case "transaction" -> signal.transactionType();
                case "type" -> signal.source();
                case "actor" -> signal.actorName().toLowerCase(Locale.ROOT);
                default -> signal.transactionDate().toString();
            };
        }

        public String groupLabel(TickerNotificationView signal) {
            return switch (sort) {
                case "company" -> signal.companyName();
                case "transaction" -> signal.transactionTypeLabel();
                case "type" -> signal.sourceLabel();
                case "actor" -> signal.actorName();
                default -> signal.transactionDate().format(
                        DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH));
            };
        }

        public String groupDetail(TickerNotificationView signal) {
            return switch (sort) {
                case "company" -> signal.symbol();
                case "transaction" -> "Transaction direction";
                case "type" -> "Activity signal source";
                case "actor" -> signal.actorRole() != null
                        ? signal.actorRole()
                        : signal.companyName();
                default -> null;
            };
        }
    }
}
