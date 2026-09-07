package org.example.stockwatch247.controller;

import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.AnalysisPreferencesService;
import org.example.stockwatch247.service.CandlePatternDetectionService;
import org.example.stockwatch247.service.CandlestickPatternPreferencesService;
import org.example.stockwatch247.service.HistoricalCandlestickService;
import org.example.stockwatch247.service.HistoricalCandlestickService.HistoricalScan;
import org.example.stockwatch247.service.HistoricalSignalCacheService;
import org.example.stockwatch247.service.MarketDataService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.Principal;

@RestController
@RequestMapping("/api/stocks")
public class HistoricalCandlestickController {
    private final HistoricalCandlestickService historicalCandlestickService;
    private final UserRepository userRepository;
    private final AnalysisPreferencesService analysisPreferences;
    private final CandlestickPatternPreferencesService patternPreferences;
    private HistoricalSignalCacheService historicalSignalCacheService;
    private MarketDataService marketDataService;

    @Autowired
    public HistoricalCandlestickController(HistoricalCandlestickService historicalCandlestickService,
                                           UserRepository userRepository,
                                           AnalysisPreferencesService analysisPreferences,
                                           CandlestickPatternPreferencesService patternPreferences) {
        this.historicalCandlestickService = historicalCandlestickService;
        this.userRepository = userRepository;
        this.analysisPreferences = analysisPreferences;
        this.patternPreferences = patternPreferences;
    }

    HistoricalCandlestickController(HistoricalCandlestickService historicalCandlestickService) {
        this(historicalCandlestickService, null, null, null);
    }

    @Autowired
    void configureHistoricalSignalCache(HistoricalSignalCacheService cacheService,
                                        MarketDataService marketDataService) {
        this.historicalSignalCacheService = cacheService;
        this.marketDataService = marketDataService;
    }

    @GetMapping("/{symbol}/candlestick-patterns/history")
    public ResponseEntity<HistoricalScan> historicalCandlestickPatterns(
            @PathVariable String symbol,
            @RequestParam String interval,
            @RequestParam(defaultValue = "false") boolean fullHistory,
            @RequestParam(required = false) Integer lookbackCandles,
            Principal principal) {
        String validatedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        CandlePatternDetectionService.TrendDetectionRules trendRules = trendRules(principal, validatedInterval);
        CandlestickPatternPreferencesService.PreferencesView definitions = patternDefinitions(principal);
        int selectedLookback = lookbackCandles == null
                ? historicalCandlestickService.defaultLookbackCandles(validatedInterval)
                : lookbackCandles;
        java.util.function.Supplier<HistoricalScan> calculation = () -> {
            if (fullHistory) {
                return analysisPreferences == null
                        ? historicalCandlestickService.scanAll(validatedSymbol, validatedInterval)
                        : historicalCandlestickService.scanAll(
                                validatedSymbol, validatedInterval, trendRules, definitions);
            }
            return analysisPreferences == null
                    ? historicalCandlestickService.scan(validatedSymbol, validatedInterval, selectedLookback)
                    : historicalCandlestickService.scan(
                            validatedSymbol, validatedInterval, selectedLookback, trendRules, definitions);
        };
        HistoricalScan scan;
        if (historicalSignalCacheService == null) {
            scan = calculation.get();
        } else {
            if (marketDataService != null) {
                marketDataService.syncCandles(validatedSymbol, validatedInterval, null);
            }
            scan = historicalSignalCacheService.getOrCompute(
                    HistoricalSignalCacheService.Family.CANDLESTICK,
                    validatedSymbol,
                    validatedInterval,
                    HistoricalCandlestickService.SCORE_VERSION,
                    new CandlestickCacheProfile(
                            fullHistory,
                            selectedLookback,
                            trendRules,
                            definitions.version(),
                            definitions.profiles(),
                            definitions.rewardRiskRatios(),
                            definitions.circuitBreakers()),
                    HistoricalScan.class,
                    calculation);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(scan);
    }

    ResponseEntity<HistoricalScan> historicalCandlestickPatterns(String symbol,
                                                                  String interval,
                                                                  boolean fullHistory,
                                                                  Integer lookbackCandles) {
        return historicalCandlestickPatterns(symbol, interval, fullHistory, lookbackCandles, null);
    }

    private CandlePatternDetectionService.TrendDetectionRules trendRules(Principal principal,
                                                                          String apiInterval) {
        TimeInterval interval = switch (apiInterval) {
            case "1wk", "weekly" -> TimeInterval.WEEKLY;
            case "1mo", "monthly" -> TimeInterval.MONTHLY;
            default -> TimeInterval.DAILY;
        };
        if (principal == null || userRepository == null || analysisPreferences == null) {
            return CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(interval);
        }
        User user = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
        return analysisPreferences.trendDetectionRules(analysisPreferences.profile(user, interval));
    }

    private CandlestickPatternPreferencesService.PreferencesView patternDefinitions(Principal principal) {
        if (principal == null || userRepository == null || patternPreferences == null) {
            return CandlestickPatternPreferencesService.factoryPreferences();
        }
        User user = org.example.stockwatch247.security.CurrentAccount.find(userRepository, principal.getName())
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
        return patternPreferences.get(user);
    }

    private record CandlestickCacheProfile(
            boolean fullHistory,
            int lookbackCandles,
            CandlePatternDetectionService.TrendDetectionRules trendRules,
            String definitionVersion,
            java.util.List<CandlestickPatternPreferencesService.PatternProfile> patternProfiles,
            java.util.Map<TimeInterval, Double> rewardRiskRatios,
            java.util.Map<TimeInterval, CandlestickPatternPreferencesService.CircuitBreakerSettings>
                    circuitBreakers) { }
}
