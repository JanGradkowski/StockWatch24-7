package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.HistoricalCandlestickService;
import org.example.stockwatch247.service.AnalysisPreferencesService;
import org.example.stockwatch247.service.CandlePatternDetectionService;
import org.example.stockwatch247.service.CandlestickPatternPreferencesService;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.service.SignalScoringPreferencesService;
import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.Locale;

@Controller
public class HistoricalCandlestickPageController {
    private final UserRepository userRepository;
    private final HistoricalCandlestickService historicalCandlestickService;
    private final AnalysisPreferencesService analysisPreferences;
    private final SignalScoringPreferencesService scoringPreferences;
    private final CandlestickPatternPreferencesService patternPreferences;

    @Autowired
    public HistoricalCandlestickPageController(
            UserRepository userRepository,
            HistoricalCandlestickService historicalCandlestickService,
            AnalysisPreferencesService analysisPreferences,
            SignalScoringPreferencesService scoringPreferences,
            CandlestickPatternPreferencesService patternPreferences) {
        this.userRepository = userRepository;
        this.historicalCandlestickService = historicalCandlestickService;
        this.analysisPreferences = analysisPreferences;
        this.scoringPreferences = scoringPreferences;
        this.patternPreferences = patternPreferences;
    }

    HistoricalCandlestickPageController(UserRepository userRepository,
                                        HistoricalCandlestickService historicalCandlestickService) {
        this(userRepository, historicalCandlestickService, null, null, null);
    }

    @GetMapping("/stock/{symbol}/candlestick-patterns/{interval}/{timestamp}/{pattern}")
    public String historicalCandlestickDetail(
            @PathVariable String symbol,
            @PathVariable String interval,
            @PathVariable long timestamp,
            @PathVariable String pattern,
            @RequestParam(defaultValue = "false") boolean fullHistory,
            @RequestParam(required = false) Integer lookbackCandles,
            Principal principal,
            Model model,
            HttpServletResponse response) {
        String validatedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        if (timestamp <= 0L) {
            throw new IllegalArgumentException("Invalid candle timestamp.");
        }
        CandlePattern validatedPattern;
        try {
            validatedPattern = CandlePattern.valueOf(pattern.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid candlestick pattern.");
        }
        int selectedLookback = lookbackCandles == null
                ? historicalCandlestickService.defaultLookbackCandles(validatedInterval)
                : lookbackCandles;
        User currentUser = userRepository.findByEmailIgnoreCase(principal.getName()).orElse(null);
        model.addAttribute("firstName", currentUser == null ? "Trader" : currentUser.getFirstName());
        CandlePatternDetectionService.TrendDetectionRules trendRules = currentUser == null
                || analysisPreferences == null
                ? CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(
                toTimeInterval(validatedInterval))
                : analysisPreferences.trendDetectionRules(analysisPreferences.profile(
                currentUser, toTimeInterval(validatedInterval)));
        HistoricalCandlestickService.HistoricalSignal signal;
        CandlestickPatternPreferencesService.PreferencesView definitions = currentUser == null || patternPreferences == null
                ? CandlestickPatternPreferencesService.factoryPreferences() : patternPreferences.get(currentUser);
        if (analysisPreferences == null) {
            signal = fullHistory
                    ? historicalCandlestickService.findSignalInFullHistory(
                    validatedSymbol, validatedInterval, timestamp, validatedPattern)
                    : historicalCandlestickService.findSignal(
                    validatedSymbol, validatedInterval, timestamp, validatedPattern, selectedLookback);
        } else {
            signal = fullHistory
                    ? historicalCandlestickService.findSignalInFullHistory(
                    validatedSymbol, validatedInterval, timestamp, validatedPattern, trendRules, definitions)
                    : historicalCandlestickService.findSignal(
                    validatedSymbol, validatedInterval, timestamp, validatedPattern,
                    selectedLookback, trendRules, definitions);
        }
        model.addAttribute("signal", signal);
        model.addAttribute("displayScore", displayScore(currentUser, signal, toTimeInterval(validatedInterval)));
        HistoricalCandlestickService.HistoricalSignalChart chart =
                historicalCandlestickService.chartForSignal(signal);
        model.addAttribute("chart", chart);
        model.addAttribute("results", historicalCandlestickService.resultsForSignal(signal, chart));
        model.addAttribute("returnUrl", fullHistory
                ? "/stock/" + validatedSymbol
                        + "?historicalCandles=graphical&historicalInterval=" + validatedInterval
                : "/stock/" + validatedSymbol
                        + "?historicalCandles=true&historicalInterval=" + validatedInterval
                        + "&lookbackCandles=" + selectedLookback);
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        return "historical-candlestick-detail";
    }

    private TimeInterval toTimeInterval(String apiInterval) {
        return switch (apiInterval) {
            case "1wk", "weekly" -> TimeInterval.WEEKLY;
            case "1mo", "monthly" -> TimeInterval.MONTHLY;
            default -> TimeInterval.DAILY;
        };
    }

    private SignalScoringPreferencesService.DisplayScore displayScore(
            User user,
            HistoricalCandlestickService.HistoricalSignal signal,
            TimeInterval interval) {
        var evidence = signal.evidence().stream()
                .map(section -> new SignalScoringPreferencesService.EvidenceSection(
                        section.category(), section.scoreLabel(), section.statusLabel(), false,
                        section.scored(), section.details().stream()
                        .map(detail -> new SignalScoringPreferencesService.EvidenceDetail(
                                detail.label(), detail.text(), detail.scoreLabel()))
                        .toList()))
                .toList();
        if (scoringPreferences == null || user == null) {
            return new SignalScoringPreferencesService.DisplayScore(
                    signal.setupScore(), signal.setupBand(), signal.setupStrengthLabel(),
                    signal.setupExplanation(), false, evidence, !evidence.isEmpty(), "Factory scoring profile.");
        }
        return scoringPreferences.score(scoringPreferences.profile(
                user, AlertPatternFamily.CANDLESTICK, interval), signal.setupScore(), evidence);
    }
}
