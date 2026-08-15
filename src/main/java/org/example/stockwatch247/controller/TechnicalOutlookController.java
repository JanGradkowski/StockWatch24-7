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
        model.addAttribute("firstName", user.getFirstName());
        model.addAttribute("symbol", SecurityInputValidator.requireMarketSymbol(symbol));
        return "technical-outlook";
    }

    @GetMapping("/api/stocks/{symbol}/technical-outlook")
    @ResponseBody
    public TechnicalOutlookService.OutlookView outlook(@PathVariable String symbol,
                                                       @RequestParam(defaultValue = "1d") String interval,
                                                       Principal principal) {
        return outlookService.getOutlook(
                requireUser(principal),
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
        return outlookService.getOutlook(user, normalizedSymbol, interval);
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
