package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.HistoricalElliottWaveService;
import org.example.stockwatch247.service.ElliottWavePreferencesService;
import org.example.stockwatch247.service.SignalScoringPreferencesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;

@Controller
public class HistoricalElliottWavePageController {
    private final UserRepository userRepository;
    private final HistoricalElliottWaveService historicalElliottWaveService;
    private final SignalScoringPreferencesService scoringPreferences;
    private ElliottWavePreferencesService elliottWavePreferences;

    @Autowired
    public HistoricalElliottWavePageController(
            UserRepository userRepository,
            HistoricalElliottWaveService historicalElliottWaveService,
            SignalScoringPreferencesService scoringPreferences) {
        this.userRepository = userRepository;
        this.historicalElliottWaveService = historicalElliottWaveService;
        this.scoringPreferences = scoringPreferences;
    }

    HistoricalElliottWavePageController(
            UserRepository userRepository,
            HistoricalElliottWaveService historicalElliottWaveService) {
        this(userRepository, historicalElliottWaveService, null);
    }

    @Autowired
    void configureElliottWavePreferences(ElliottWavePreferencesService elliottWavePreferences) {
        this.elliottWavePreferences = elliottWavePreferences;
    }

    @GetMapping("/stock/{symbol}/elliott-waves/{interval}/{stage}/{endpointTimestamp}")
    public String historicalElliottWaveDetail(
            @PathVariable String symbol,
            @PathVariable String interval,
            @PathVariable ElliottSignalStage stage,
            @PathVariable long endpointTimestamp,
            @RequestParam(required = false) String cycleKey,
            Principal principal,
            Model model,
            HttpServletResponse response) {
        String validatedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        TimeInterval timeInterval = elliottInterval(validatedInterval);
        User currentUser = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName()).orElse(null);
        model.addAttribute("firstName", currentUser == null ? "Trader" : currentUser.getFirstName());
        HistoricalElliottWaveService.HistoricalElliottWaveDetail wave =
                currentUser == null || elliottWavePreferences == null
                        ? historicalElliottWaveService.findDetail(
                                validatedSymbol, validatedInterval, stage, endpointTimestamp, cycleKey)
                        : historicalElliottWaveService.findDetail(
                                validatedSymbol, validatedInterval, stage, endpointTimestamp, cycleKey,
                                elliottWavePreferences.get(currentUser).profile(timeInterval).rules());
        return renderDetail(validatedSymbol, currentUser, wave, model, response);
    }

    @GetMapping("/stock/{symbol}/elliott-waves/{interval}/candidate/{endpointTimestamp}")
    public String historicalElliottCandidateDetail(
            @PathVariable String symbol,
            @PathVariable String interval,
            @PathVariable long endpointTimestamp,
            @RequestParam String key,
            Principal principal,
            Model model,
            HttpServletResponse response) {
        String validatedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        TimeInterval timeInterval = elliottInterval(validatedInterval);
        User currentUser = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName()).orElse(null);
        model.addAttribute("firstName", currentUser == null ? "Trader" : currentUser.getFirstName());
        HistoricalElliottWaveService.HistoricalElliottWaveDetail wave = historicalElliottWaveService.findCandidateDetail(
                validatedSymbol, validatedInterval, endpointTimestamp, key,
                currentUser == null || elliottWavePreferences == null
                        ? null
                        : elliottWavePreferences.get(currentUser).profile(timeInterval).rules());
        return renderDetail(validatedSymbol, currentUser, wave, model, response);
    }

    private String renderDetail(String symbol, User currentUser,
                                HistoricalElliottWaveService.HistoricalElliottWaveDetail wave,
                                Model model, HttpServletResponse response) {
        model.addAttribute("wave", wave);
        model.addAttribute("displayScore", displayScore(currentUser, wave));
        model.addAttribute("returnUrl", "/stock/" + symbol + "#general");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        return "historical-elliott-detail";
    }

    private TimeInterval elliottInterval(String interval) {
        return switch (interval) {
            case "1d" -> TimeInterval.DAILY;
            case "1wk" -> TimeInterval.WEEKLY;
            case "1mo" -> TimeInterval.MONTHLY;
            default -> throw new IllegalArgumentException(
                    "Historical Elliott details require a daily, weekly, or monthly interval.");
        };
    }

    private SignalScoringPreferencesService.DisplayScore displayScore(
            User user,
            HistoricalElliottWaveService.HistoricalElliottWaveDetail wave) {
        var evidence = wave.scoreSections().stream()
                .map(section -> new SignalScoringPreferencesService.EvidenceSection(
                        section.category(), section.scoreLabel(), section.status(), false, !wave.candidate(),
                        section.details().stream()
                        .map(detail -> new SignalScoringPreferencesService.EvidenceDetail(
                                detail.label(), detail.text(), detail.score()))
                        .toList()))
                .toList();
        // Custom weights apply to the seven scored Elliott categories, which a shape-only candidate lacks.
        if (scoringPreferences == null || user == null || wave.candidate()) {
            int score = wave.qualityScore();
            String band = score >= 85 ? "high" : score >= 75 ? "medium" : "low";
            String strength = wave.candidate()
                    ? score >= 85 ? "Strong fit" : score >= 75 ? "Moderate fit" : "Weak fit"
                    : score >= 85 ? "High confluence" : score >= 75 ? "Moderate confluence" : "Low confluence";
            return new SignalScoringPreferencesService.DisplayScore(score, band, strength,
                    wave.candidate()
                            ? "The candidate score rates the pattern's shape: leg proportions, retracements, and how cleanly the pivots fit."
                            : "The score combines structural Elliott rules, proportions, momentum, and confirmation evidence.",
                    false, evidence, !evidence.isEmpty(), "Factory scoring profile.");
        }
        return scoringPreferences.score(scoringPreferences.profile(
                user, AlertPatternFamily.ELLIOTT_WAVE, wave.timeInterval()), wave.qualityScore(), evidence);
    }
}
