package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.HistoricalHarmonicFormationService;
import org.example.stockwatch247.service.HarmonicPatternDetectionService;
import org.example.stockwatch247.service.HarmonicPatternPreferencesService;
import org.example.stockwatch247.service.SignalScoringPreferencesService;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.security.Principal;

@Controller
public class HistoricalHarmonicFormationPageController {
    private final UserRepository userRepository;
    private final HistoricalHarmonicFormationService service;
    private HarmonicPatternPreferencesService harmonicPreferences;
    private HarmonicPatternDetectionService harmonicDetector;
    private SignalScoringPreferencesService scoringPreferences;

    public HistoricalHarmonicFormationPageController(
            UserRepository userRepository,
            HistoricalHarmonicFormationService service) {
        this.userRepository = userRepository;
        this.service = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configurePreferences(HarmonicPatternPreferencesService harmonicPreferences,
                              HarmonicPatternDetectionService harmonicDetector,
                              SignalScoringPreferencesService scoringPreferences) {
        this.harmonicPreferences = harmonicPreferences;
        this.harmonicDetector = harmonicDetector;
        this.scoringPreferences = scoringPreferences;
    }

    @GetMapping("/stock/{symbol}/harmonic-formations/{interval}/{pattern}/{endpointTimestamp}")
    public String historicalHarmonicDetail(
            @PathVariable String symbol,
            @PathVariable String interval,
            @PathVariable HarmonicPatternType pattern,
            @PathVariable long endpointTimestamp,
            Principal principal,
            Model model,
            HttpServletResponse response) {
        String validatedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        User user = principal == null ? null
                : userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        model.addAttribute("firstName", user == null ? "Trader" : user.getFirstName());
        HistoricalHarmonicFormationService.HistoricalHarmonicDetail harmonic = user == null
                || harmonicPreferences == null || harmonicDetector == null
                ? service.findDetail(validatedSymbol, validatedInterval, pattern, endpointTimestamp)
                : service.findDetail(validatedSymbol, validatedInterval, pattern, endpointTimestamp,
                harmonicPreferences.detector(user, harmonicDetector));
        model.addAttribute("harmonic", harmonic);
        model.addAttribute("displayScore", displayScore(user, harmonic));
        model.addAttribute("returnUrl", "/stock/" + validatedSymbol + "#general");
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        return "historical-harmonic-detail";
    }

    private SignalScoringPreferencesService.DisplayScore displayScore(
            User user,
            HistoricalHarmonicFormationService.HistoricalHarmonicDetail harmonic) {
        var sections = harmonic.scoreSections() == null ? java.util.List
                .<HistoricalHarmonicFormationService.ScoreSectionView>of() : harmonic.scoreSections();
        var evidence = sections.stream()
                .map(section -> new SignalScoringPreferencesService.EvidenceSection(
                        section.category(), section.scoreLabel(), section.status(), false, true,
                        section.details().stream().map(detail ->
                                new SignalScoringPreferencesService.EvidenceDetail(
                                        detail.label(), detail.text(), detail.score())).toList()))
                .toList();
        if (user != null && scoringPreferences != null) {
            return scoringPreferences.score(scoringPreferences.profile(
                    user, AlertPatternFamily.HARMONIC_FORMATION, harmonic.timeInterval()),
                    harmonic.qualityScore(), evidence);
        }
        int score = harmonic.qualityScore();
        return new SignalScoringPreferencesService.DisplayScore(score,
                score >= 85 ? "high" : score >= 75 ? "medium" : "low",
                score >= 85 ? "High compliance" : score >= 75 ? "Moderate compliance" : "Stretched geometry",
                "Hard structural rules passed; the score measures fit across the selected soft ratio categories.",
                false, evidence, !evidence.isEmpty(), "Factory harmonic scoring profile.");
    }
}
