package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.AnalysisPreferencesService;
import org.example.stockwatch247.service.TechnicalOutlookService;
import org.example.stockwatch247.service.TechnicalOutlookTrackingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;

@Controller
@RequestMapping
public class TechnicalOutlookController {
    private final UserRepository userRepository;
    private final TechnicalOutlookService outlookService;
    private final AnalysisPreferencesService preferencesService;
    private final TechnicalOutlookTrackingService trackingService;

    public TechnicalOutlookController(UserRepository userRepository,
                                      TechnicalOutlookService outlookService,
                                      AnalysisPreferencesService preferencesService,
                                      TechnicalOutlookTrackingService trackingService) {
        this.userRepository = userRepository;
        this.outlookService = outlookService;
        this.preferencesService = preferencesService;
        this.trackingService = trackingService;
    }

    @GetMapping("/stock/{symbol}/technical-outlook")
    public String page(@PathVariable String symbol, Model model, Principal principal) {
        User user = requireUser(principal);
        String normalizedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
        model.addAttribute("firstName", user.getFirstName());
        model.addAttribute("symbol", normalizedSymbol);
        // Warm all supported native intervals while the General tab opens so
        // later interval switches normally hit completed local data.
        outlookService.requestBackgroundRefresh(user, normalizedSymbol, "1d");
        outlookService.requestBackgroundRefresh(user, normalizedSymbol, "1wk");
        outlookService.requestBackgroundRefresh(user, normalizedSymbol, "1mo");
        return "technical-outlook";
    }

    @GetMapping("/api/stocks/{symbol}/technical-outlook")
    @ResponseBody
    public TechnicalOutlookService.OutlookView outlook(@PathVariable String symbol,
                                                       @RequestParam(defaultValue = "1d") String interval,
                                                       Principal principal) {
        User user = requireUser(principal);
        String normalizedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String normalizedInterval = SecurityInputValidator.requireInterval(interval);
        outlookService.requestBackgroundRefresh(user, normalizedSymbol, normalizedInterval);
        return outlookService.getSummaryOutlook(user, normalizedSymbol, normalizedInterval);
    }

    @GetMapping("/api/stocks/{symbol}/technical-outlook/score-report")
    @ResponseBody
    public TechnicalOutlookService.ScoreReportView scoreReport(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            Principal principal) {
        return outlookService.getScoreReport(
                requireUser(principal),
                SecurityInputValidator.requireMarketSymbol(symbol),
                SecurityInputValidator.requireInterval(interval));
    }

    @GetMapping("/api/stocks/{symbol}/technical-outlook/history")
    @ResponseBody
    public TechnicalOutlookService.HistoricalChartPageView historicalChartPage(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            @RequestParam long before,
            @RequestParam(defaultValue = "500") int limit,
            Principal principal) {
        return outlookService.getHistoricalChartPage(
                requireUser(principal),
                SecurityInputValidator.requireMarketSymbol(symbol),
                SecurityInputValidator.requireInterval(interval),
                SecurityInputValidator.requireBeforeTimestamp(before),
                limit);
    }

    @GetMapping("/api/stocks/{symbol}/technical-outlook/market-comparison")
    @ResponseBody
    public TechnicalOutlookService.MarketComparisonView marketComparison(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            Principal principal) {
        return outlookService.getMarketReport(
                requireUser(principal),
                SecurityInputValidator.requireMarketSymbol(symbol),
                SecurityInputValidator.requireInterval(interval));
    }

    @GetMapping("/api/stocks/{symbol}/technical-outlook/refresh-status")
    @ResponseBody
    public TechnicalOutlookService.RefreshStatusView refreshStatus(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            Principal principal) {
        requireUser(principal);
        return outlookService.refreshStatus(
                SecurityInputValidator.requireMarketSymbol(symbol),
                SecurityInputValidator.requireInterval(interval));
    }

    @PostMapping("/api/stocks/{symbol}/technical-outlook/indicator-periods")
    @ResponseBody
    public TechnicalOutlookService.OutlookView updateIndicatorPeriods(
            @PathVariable String symbol,
            @RequestBody IndicatorPeriodUpdateRequest request,
            Principal principal) {
        User user = requireUser(principal);
        String normalizedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String interval = SecurityInputValidator.requireInterval(request.interval());
        preferencesService.updateIndicatorPeriods(
                user,
                timeInterval(interval),
                request.periods());
        outlookService.invalidate(user, normalizedSymbol, interval);
        return outlookService.getSummaryOutlook(user, normalizedSymbol, interval);
    }

    @GetMapping("/api/stocks/{symbol}/technical-outlook/subscriptions")
    @ResponseBody
    public TechnicalOutlookTrackingService.SubscriptionStateView subscriptionState(
            @PathVariable String symbol, Principal principal) {
        return trackingService.getState(
                requireUser(principal), SecurityInputValidator.requireMarketSymbol(symbol));
    }

    @PutMapping("/api/stocks/{symbol}/technical-outlook/subscriptions")
    @ResponseBody
    public TechnicalOutlookTrackingService.SubscriptionStateView updateSubscription(
            @PathVariable String symbol,
            @RequestBody OutlookSubscriptionRequest request,
            Principal principal) {
        if (request == null || request.interval() == null) {
            throw new IllegalArgumentException("A Daily, Weekly, or Monthly interval is required.");
        }
        TimeInterval interval;
        try {
            interval = TimeInterval.valueOf(request.interval().trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("A Daily, Weekly, or Monthly interval is required.");
        }
        return trackingService.setSubscription(
                requireUser(principal), SecurityInputValidator.requireMarketSymbol(symbol),
                interval, request.active());
    }

    @GetMapping("/technical-outlook/changes/{notificationId}")
    public String changePage(@PathVariable Long notificationId,
                             Model model,
                             Principal principal) {
        User user = requireUser(principal);
        TechnicalOutlookTrackingService.OutlookChangeDetailView change =
                trackingService.detail(user, notificationId);
        model.addAttribute("firstName", user.getFirstName());
        model.addAttribute("change", change);
        return "technical-outlook-change";
    }

    private TimeInterval timeInterval(String interval) {
        return switch (interval) {
            case "1wk" -> TimeInterval.WEEKLY;
            case "1mo" -> TimeInterval.MONTHLY;
            default -> TimeInterval.DAILY;
        };
    }

    private User requireUser(Principal principal) {
        if (principal == null) {
            throw new IllegalStateException("An authenticated user is required.");
        }
        return org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user was not found."));
    }

    public record IndicatorPeriodUpdateRequest(
            String interval,
            int rsiPeriod,
            int atrPeriod,
            int fastEmaPeriod,
            int slowEmaPeriod,
            int longSmaPeriod,
            int macdFastPeriod,
            int macdSlowPeriod,
            int macdSignalPeriod,
            int cciPeriod,
            int bollingerPeriod,
            int volumePeriod,
            int vwapPeriod,
            int volumeProfilePeriod,
            int supportResistancePeriod) {

        private AnalysisPreferencesService.IndicatorPeriods periods() {
            return new AnalysisPreferencesService.IndicatorPeriods(
                    rsiPeriod, atrPeriod, fastEmaPeriod, slowEmaPeriod, longSmaPeriod,
                    macdFastPeriod, macdSlowPeriod, macdSignalPeriod, cciPeriod,
                    bollingerPeriod, volumePeriod, vwapPeriod, volumeProfilePeriod,
                    supportResistancePeriod);
        }
    }

    public record OutlookSubscriptionRequest(String interval, boolean active) { }
}
