package org.example.stockwatch247.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.HistoricalCandlestickService;
import org.springframework.stereotype.Controller;
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

    public HistoricalCandlestickPageController(
            UserRepository userRepository,
            HistoricalCandlestickService historicalCandlestickService) {
        this.userRepository = userRepository;
        this.historicalCandlestickService = historicalCandlestickService;
    }

    @GetMapping("/stock/{symbol}/candlestick-patterns/{interval}/{timestamp}/{pattern}")
    public String historicalCandlestickDetail(
            @PathVariable String symbol,
            @PathVariable String interval,
            @PathVariable long timestamp,
            @PathVariable String pattern,
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
        HistoricalCandlestickService.HistoricalSignal signal = historicalCandlestickService.findSignal(
                validatedSymbol,
                validatedInterval,
                timestamp,
                validatedPattern,
                selectedLookback
        );
        model.addAttribute("signal", signal);
        HistoricalCandlestickService.HistoricalSignalChart chart =
                historicalCandlestickService.chartForSignal(signal);
        model.addAttribute("chart", chart);
        model.addAttribute("results", historicalCandlestickService.resultsForSignal(signal, chart));
        model.addAttribute(
                "returnUrl",
                "/stock/" + validatedSymbol
                        + "?historicalCandles=true&historicalInterval=" + validatedInterval
                        + "&lookbackCandles=" + selectedLookback
        );
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        return "historical-candlestick-detail";
    }
}
