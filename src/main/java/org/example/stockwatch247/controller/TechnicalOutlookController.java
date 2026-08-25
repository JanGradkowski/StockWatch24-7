package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.AnalysisPreferencesService;
import org.example.stockwatch247.service.TechnicalOutlookService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public TechnicalOutlookController(UserRepository userRepository,
                                      TechnicalOutlookService outlookService,
                                      AnalysisPreferencesService preferencesService) {
        this.userRepository = userRepository;
        this.outlookService = outlookService;
        this.preferencesService = preferencesService;
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
        return userRepository.findByEmailIgnoreCase(principal.getName())
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
}
