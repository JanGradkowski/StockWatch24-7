package org.example.stockwatch247.controller;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.AlertRuleService;
import org.example.stockwatch247.service.ActivityArchiveQuery;
import org.example.stockwatch247.service.SignalArchiveFilter;
import org.example.stockwatch247.service.EmailVerificationService;
import org.example.stockwatch247.service.SignalScoringPreferencesService;
import org.example.stockwatch247.service.TechnicalOutlookTrackingService;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;



@Controller
public class AuthController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);
    @Autowired
    private org.example.stockwatch247.service.WatchlistService watchlists;
    private static final int ACTIVITY_ARCHIVE_PAGE_SIZE = 25;
    private final UserRepository userRepository;
    private final AlertRuleService alertRuleService;
    private final CongressionalActivityService congressionalActivityService;
    private final InsiderActivityService insiderActivityService;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final SignalScoringPreferencesService scoringPreferencesService;
    private TechnicalOutlookTrackingService technicalOutlookTrackingService;
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

    @Autowired
    void configureTechnicalOutlookTracking(TechnicalOutlookTrackingService trackingService) {
        this.technicalOutlookTrackingService = trackingService;
    }

    @Autowired(required = false)
    private org.example.stockwatch247.service.ChartAlertService chartAlertService;
    @Autowired(required = false)
    private ActivityArchiveQuery activityArchiveQuery;
    @Autowired(required = false)
    private org.example.stockwatch247.service.UnreadCountService unreadCounts;
    /** Already signed in, the login and signup forms have nothing to offer, so the dashboard opens. */
    @GetMapping("/login")
    public String loginPage(Principal principal) {return principal == null ? "login" : "redirect:/home";}

    @GetMapping("/signup")
    public String signupPage(Principal principal) {return principal == null ? "signup" : "redirect:/home";}

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
            keepSignupInput(model, firstName, lastName, email, elliottMotiveColor, elliottCorrectiveColor);
            return "signup";
        }

        // Both outcomes hash the password and queue one email, so response time does not reveal
        // whether the address already has an account.
        String passwordHash = passwordEncoder.encode(password);
        User existing = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        if (existing != null) {
            notifyExistingAccountOwner(existing);
            return registrationRedirect();
        }

        User user = new User();
        user.setFirstName(normalizedFirstName);
        user.setLastName(normalizedLastName);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordHash);
        user.setElliottMotiveColor(normalizedMotiveColor);
        user.setElliottCorrectiveColor(normalizedCorrectiveColor);
        user.setVerified(false);
        // The verification email is sent before any signed-in visit, so remember the signup page's language.
        user.setDetectedLanguage(org.example.stockwatch247.i18n.AppLanguage.of(
                org.example.stockwatch247.i18n.Messages.current()).code());
        try {
            emailVerificationService.registerNewUser(user);
        } catch (org.springframework.dao.DataIntegrityViolationException concurrentSignup) {
            // Another request registered this address first; answer exactly as for an existing account.
            return registrationRedirect();
        } catch (IllegalStateException ignored) {
            model.addAttribute("error", "Verification email could not be sent. Please try again later.");
            keepSignupInput(model, firstName, lastName, email, elliottMotiveColor, elliottCorrectiveColor);
            return "signup";
        }
        return registrationRedirect();
    }

    /** A rejected form comes back with what was typed (never the password), trimmed to the field limits. */
    private static void keepSignupInput(Model model, String firstName, String lastName, String email,
                                        String motiveColor, String correctiveColor) {
        model.addAttribute("signupFirstName", clip(firstName, 100));
        model.addAttribute("signupLastName", clip(lastName, 100));
        model.addAttribute("signupEmail", clip(email, 254));
        model.addAttribute("signupMotiveColor", validColor(motiveColor));
        model.addAttribute("signupCorrectiveColor", validColor(correctiveColor));
    }

    private static String clip(String value, int length) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= length ? trimmed : trimmed.substring(0, length);
    }

    private static String validColor(String color) {
        try {
            return SecurityInputValidator.requireHexColor(color);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    @Autowired(required = false)
    private org.example.stockwatch247.security.RequestRateLimiter rateLimiter;
    @Autowired(required = false)
    private org.example.stockwatch247.service.AlertNotificationService notificationService;
    @org.springframework.beans.factory.annotation.Value("${app.public-base-url:http://localhost:8080}")
    private String publicBaseUrl = "http://localhost:8080";

    private void notifyExistingAccountOwner(User existing) {
        if (notificationService == null) return;
        if (rateLimiter != null && !rateLimiter.tryAcquire(
                "signup-existing:" + existing.getId(), 1, java.time.Duration.ofHours(1))) return;
        try {
            notificationService.sendExistingAccountSignupNotice(existing,
                    publicBaseUrl.replaceAll("/+$", "") + "/login");
        } catch (RuntimeException unavailable) {
            // The response must not differ when mail delivery is unavailable.
        }
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
                log.warn("Verification email resend failed.");
            }
        }
        return "redirect:/login?verificationResendRequested=true";
    }

    @GetMapping("/verify-email")
    public String verifyEmail(@RequestParam(required = false) String token) {
        // A link cut short by a mail client arrives without its token; that is an invalid link too.
        return token != null && !token.isBlank() && emailVerificationService.verify(token)
                ? "redirect:/login?verified=true"
                : "redirect:/login?verificationError=true";
    }
    /** Insider and congressional filings get their own page, built from the same dashboard data. */
    @GetMapping("/ticker-alerts")
    public String tickerAlertsPage(Model model, Principal principal) {
        model.addAttribute("dashboardView", "alerts");
        return homePage(model, principal);
    }

    @GetMapping("/home")
    public String homePage(Model model, Principal principal) {
        if (!model.containsAttribute("dashboardView")) model.addAttribute("dashboardView", "technical");
        // We can fetch the full user object using the email from Principal
        User currentUser = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName()).orElse(null);

        if (currentUser != null) {
            var trackedCompanies = watchlists == null ? alertRuleService.getActiveCompanyViews(currentUser)
                    : java.util.List.<AlertRuleService.TrackedCompanyView>of();
            var latestSignals = alertRuleService.getLatestSignalViews(currentUser);
            var latestOutlookChanges = technicalOutlookTrackingService == null
                    ? java.util.List.<TechnicalOutlookTrackingService.LatestOutlookChangeView>of()
                    : technicalOutlookTrackingService.latestUnread(currentUser, 8);
            var latestChartAlerts = chartAlertService == null
                    ? java.util.List.<org.example.stockwatch247.service.ChartAlertService.LatestChartAlertView>of()
                    : chartAlertService.latestUnread(currentUser.getId(), 8);
            var latestSignalItems = Stream.of(
                            latestSignals.stream().map(DashboardLatestSignalView::technical),
                            latestOutlookChanges.stream().map(DashboardLatestSignalView::outlook),
                            latestChartAlerts.stream().map(DashboardLatestSignalView::chartAlert))
                    .flatMap(stream -> stream)
                    .sorted(Comparator.comparing(
                            DashboardLatestSignalView::occurredAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(8)
                    .toList();
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
            if (watchlists != null) {
                java.util.Set<String> keys = new java.util.HashSet<>();
                latestSignalItems.forEach(item -> {
                    if (item.technicalSignal() != null) keys.add("TECHNICAL:" + item.technicalSignal().id());
                    else if (item.outlookChange() != null) keys.add("OUTLOOK:" + item.outlookChange().id());
                });
                latestTickerNotifications.forEach(item -> keys.add(("INSIDER".equals(item.source()) ? "INSIDER:" : "CONGRESS:") + item.id()));
                model.addAttribute("watchlistOrigins", watchlists.notificationOrigins(currentUser, keys));
            }
            model.addAttribute("firstName", currentUser.getFirstName());
            model.addAttribute("latestSignals", latestSignals);
            model.addAttribute("latestSignalItems", latestSignalItems);
            // The list shows the newest few; the counts say how many are waiting in total.
            var unread = unreadCounts == null ? null : unreadCounts.counts(currentUser);
            model.addAttribute("latestSignalUnreadCount", unread == null ? latestSignalItems.size() : unread.latestSignals());
            model.addAttribute("technicalUnreadCount", unread == null ? latestSignalItems.size() : unread.signals());
            // Custom alerts get their own panel: the newest unread triggers, or the alerts themselves when all is read.
            model.addAttribute("customAlertUnreadCount", unread == null ? 0L : unread.custom());
            model.addAttribute("customAlertTriggers", customAlerts == null ? java.util.List.of() : customAlerts.latestUnread(currentUser, 5));
            model.addAttribute("customAlertSummaries", customAlerts == null ? java.util.List.of() : customAlerts.list(currentUser).stream().limit(6).toList());
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
            model.addAttribute("activeRuleCount", trackedCompanies.stream()
                    .mapToInt(AlertRuleService.TrackedCompanyView::ruleCount)
                    .sum());
            model.addAttribute("technicalInstrumentCount", trackedCompanies.size());
            if (watchlists != null) {
                var summary = watchlists.technicalSummary(currentUser);
                model.addAttribute("technicalInstrumentCount", summary.get("instruments"));
                model.addAttribute("activeRuleCount", summary.get("rules"));
                model.addAttribute("trackedInstrumentCount", summary.get("tracked"));
            }
        } else {
            model.addAttribute("firstName", "Trader");
            model.addAttribute("technicalInstrumentCount", 0);
            model.addAttribute("latestSignals", java.util.List.of());
            model.addAttribute("latestSignalItems", java.util.List.of());
            model.addAttribute("latestSignalUnreadCount", 0L);
            model.addAttribute("technicalUnreadCount", 0L);
            model.addAttribute("customAlertUnreadCount", 0L);
            model.addAttribute("customAlertTriggers", java.util.List.of());
            model.addAttribute("customAlertSummaries", java.util.List.of());
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
            model.addAttribute("activeRuleCount", 0);
        }

        return "home";
    }

    @GetMapping("/alerts/{alertRuleId}")
    public String alertHistoryPage(@PathVariable Long alertRuleId,
                                   @RequestParam(defaultValue = "date") String sort,
                                   @RequestParam(defaultValue = "desc") String direction,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "all") String state,
                                   @RequestParam(defaultValue = "") String ticker,
                                   Model model,
                                   Principal principal) {
        User currentUser = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName()).orElse(null);
        if (currentUser == null) {
            return "redirect:/login";
        }
        var filter = new SignalArchiveFilter(state, ticker);
        model.addAttribute("firstName", currentUser.getFirstName());
        AlertRuleService.CompanySignalArchive companyArchive = alertRuleService.getCompanySignalArchive(
                currentUser, alertRuleId, sort, direction, page, filter);
        model.addAttribute("companyArchive", companyArchive);
        model.addAttribute("archive", companyArchive.archive());
        archiveNavigation(model, companyArchive.archive(), filter, "/alerts/" + alertRuleId);
        return "all-signals";
    }

    @GetMapping("/alerts/signals/{alertEventId}")
    public String signalDetailPage(@PathVariable Long alertEventId, Model model, Principal principal,
                                   @RequestParam(defaultValue = "") String returnTo) {
        if (returnTo.length() <= 1024 && returnTo.matches("^/(signals|alerts/[0-9]+)(\\?[^\\r\\n#]*)?$")) {
            model.addAttribute("archiveReturnUrl", returnTo);
        }
        User currentUser = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName()).orElse(null);
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
                                 @RequestParam(defaultValue = "all") String state,
                                 @RequestParam(defaultValue = "") String ticker,
                                 @RequestParam(required = false) Long watchlistId,
                                 @RequestParam(required = false) List<String> type,
                                 @RequestParam(required = false) Long customAlertId,
                                 Model model,
                                 Principal principal) {
        User currentUser = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName()).orElse(null);
        if (currentUser == null) {
            return "redirect:/login";
        }
        if (watchlistId != null) {
            watchlists.requireOwned(currentUser, watchlistId);
            model.addAttribute("watchlistName", watchlists.name(currentUser, watchlistId));
        }
        if (customAlertId != null && customAlerts != null) {
            model.addAttribute("customAlert", customAlerts.archiveHeader(currentUser, customAlertId, watchlistId));
        }
        var filter = new SignalArchiveFilter(state, ticker, watchlistId,
                type == null ? java.util.Set.of() : java.util.Set.copyOf(type), customAlertId);
        model.addAttribute("firstName", currentUser.getFirstName());
        var archive = alertRuleService.getSignalArchive(currentUser, sort, direction, page, filter);
        model.addAttribute("archive", archive);
        archiveNavigation(model, archive, filter, "/signals");
        model.addAttribute("archiveTypes", customAlertId != null ? List.of()
                : watchlistId != null ? List.of("candlestick", "elliott", "harmonic", "custom", "congress", "insider")
                : List.of("candlestick", "elliott", "harmonic", "chart", "custom"));
        return "all-signals";
    }

    @Autowired(required = false)
    private org.example.stockwatch247.service.customalert.CustomAlertService customAlerts;

    private void archiveNavigation(Model model, AlertRuleService.SignalArchivePage archive,
                                   SignalArchiveFilter filter, String path) {
        model.addAttribute("archiveFilter", filter);
        var location = org.springframework.web.util.UriComponentsBuilder.fromPath(path);
        if (filter.watchlistId() != null) location.queryParam("watchlistId", filter.watchlistId());
        if (filter.customAlertId() != null) location.queryParam("customAlertId", filter.customAlertId());
        model.addAttribute("archiveClearUrl", location.build().encode().toUriString());
        location.queryParam("sort", archive.sort()).queryParam("direction", archive.direction())
                .queryParam("page", archive.page()).queryParam("state", filter.state())
                .queryParam("ticker", filter.ticker());
        if (!filter.types().isEmpty()) location.queryParam("type", filter.typeParameter());
        model.addAttribute("archiveReturnUrl", location.build().encode().toUriString());
    }

    @GetMapping("/activity-signals")
    public String allActivitySignalsPage(@RequestParam(defaultValue = "date") String sort,
                                         @RequestParam(defaultValue = "desc") String direction,
                                         @RequestParam(defaultValue = "0") int page,
                                         Model model,
                                         Principal principal) {
        User currentUser = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName()).orElse(null);
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
        User currentUser = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName()).orElse(null);
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
        if (activityArchiveQuery != null) {
            // The database orders and pages the notifications; only the shown page is loaded.
            var result = activityArchiveQuery.page(user.getId(), sort, "asc".equals(direction),
                    requestedPage, ACTIVITY_ARCHIVE_PAGE_SIZE);
            java.util.Map<String, TickerNotificationView> byKey = new java.util.HashMap<>();
            congressionalActivityService.getActivity(user, idsOf(result, ActivityArchiveQuery.CONGRESSIONAL))
                    .forEach(view -> byKey.put(ActivityArchiveQuery.CONGRESSIONAL + ":" + view.id(), tickerNotification(view)));
            insiderActivityService.getActivity(user, idsOf(result, ActivityArchiveQuery.INSIDER))
                    .forEach(view -> byKey.put(ActivityArchiveQuery.INSIDER + ":" + view.id(), tickerNotification(view)));
            List<TickerNotificationView> rows = result.rows().stream()
                    .map(row -> byKey.get(row.kind() + ":" + row.id()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            return new ActivitySignalArchivePage(rows, result.page(), result.pages(), result.count(),
                    sort, direction, result.page() > 0, result.page() + 1 < result.pages());
        }
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

    private static List<Long> idsOf(ActivityArchiveQuery.Page page, String kind) {
        return page.rows().stream().filter(row -> kind.equals(row.kind())).map(ActivityArchiveQuery.Row::id).toList();
    }

    private String normalizeActivitySort(String requestedSort) {
        if (requestedSort == null) {
            return "date";
        }
        return switch (requestedSort.toLowerCase(Locale.ROOT)) {
            case "ticker", "company", "transaction", "type", "actor" ->
                    requestedSort.toLowerCase(Locale.ROOT);
            default -> "date";
        };
    }

    private Comparator<TickerNotificationView> activityComparator(String sort, String direction) {
        Comparator<String> textOrder = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        Comparator<TickerNotificationView> primary = switch (sort) {
            case "ticker" -> Comparator.comparing(TickerNotificationView::symbol, textOrder);
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

    public record DashboardLatestSignalView(
            String kind,
            LocalDateTime occurredAt,
            AlertRuleService.LatestSignalView technicalSignal,
            TechnicalOutlookTrackingService.LatestOutlookChangeView outlookChange,
            org.example.stockwatch247.service.ChartAlertService.LatestChartAlertView chartAlert) {
        private static DashboardLatestSignalView technical(AlertRuleService.LatestSignalView signal) {
            return new DashboardLatestSignalView("TECHNICAL", signal.sentAt(), signal, null, null);
        }

        private static DashboardLatestSignalView outlook(
                TechnicalOutlookTrackingService.LatestOutlookChangeView change) {
            return new DashboardLatestSignalView("OUTLOOK", change.createdAt(), null, change, null);
        }

        private static DashboardLatestSignalView chartAlert(
                org.example.stockwatch247.service.ChartAlertService.LatestChartAlertView alert) {
            return new DashboardLatestSignalView("CHART_ALERT", alert.createdAt(), null, null, alert);
        }
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
                case "ticker" -> signal.symbol().toLowerCase(Locale.ROOT);
                case "company" -> signal.companyName().toLowerCase(Locale.ROOT);
                case "transaction" -> signal.transactionType();
                case "type" -> signal.source();
                case "actor" -> signal.actorName().toLowerCase(Locale.ROOT);
                default -> signal.transactionDate().toString();
            };
        }

        public String groupLabel(TickerNotificationView signal) {
            return switch (sort) {
                case "ticker" -> signal.symbol();
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
                case "ticker" -> signal.companyName();
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
