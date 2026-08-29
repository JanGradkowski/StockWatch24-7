package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.LivePricingService;
import org.example.stockwatch247.service.MarketDataService;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.ElliottWavePreferencesService;
import org.example.stockwatch247.service.HarmonicPatternDetectionService;
import org.example.stockwatch247.service.HarmonicPatternPreferencesService;
import org.example.stockwatch247.service.HistoricalSignalCacheService;
import org.example.stockwatch247.service.TechnicalIndicatorEnrichmentService;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService;
import org.example.stockwatch247.service.TwelveDataService;
import org.example.stockwatch247.service.YahooFinanceService;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/stocks")
public class ChartController {
    private static final int LATEST_WAVE_CANDLES = 100;
    private static final int HARMONIC_CONTEXT_CANDLES = 750;

    private final MarketDataService marketDataService;
    private final CandleRepository candleRepository;
    private final StockAssetRepository stockAssetRepository;
    private final LivePricingService livePricingService;
    private final TwelveDataService twelveDataService;
    private final YahooFinanceService yahooFinanceService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final ElliottWaveDetectionService elliottWaveDetectionService;
    private UserRepository userRepository;
    private ElliottWavePreferencesService elliottWavePreferencesService;
    private HarmonicPatternDetectionService harmonicPatternDetectionService =
            new HarmonicPatternDetectionService();
    private HarmonicPatternPreferencesService harmonicPatternPreferencesService;
    private HistoricalSignalCacheService historicalSignalCacheService;
    private ChartTechnicalIndicatorService chartTechnicalIndicatorService;

    public ChartController(CandleRepository candleRepository,
                           LivePricingService livePricingService, MarketDataService marketDataService,
                           StockAssetRepository stockAssetRepository,
                           TwelveDataService twelveDataService,
                           YahooFinanceService yahooFinanceService,
                           TechnicalIndicatorEnrichmentService enrichmentService,
                           ElliottWaveDetectionService elliottWaveDetectionService) {
        this.candleRepository = candleRepository;
        this.livePricingService = livePricingService;
        this.marketDataService = marketDataService;
        this.stockAssetRepository = stockAssetRepository;
        this.twelveDataService = twelveDataService;
        this.yahooFinanceService = yahooFinanceService;
        this.enrichmentService = enrichmentService;
        this.elliottWaveDetectionService = elliottWaveDetectionService;
    }

    @Autowired(required = false)
    void configureElliottWavePreferences(UserRepository userRepository,
                                         ElliottWavePreferencesService elliottWavePreferencesService) {
        this.userRepository = userRepository;
        this.elliottWavePreferencesService = elliottWavePreferencesService;
    }

    @Autowired(required = false)
    void configureHarmonicPatterns(HarmonicPatternDetectionService harmonicPatternDetectionService) {
        if (harmonicPatternDetectionService != null) {
            this.harmonicPatternDetectionService = harmonicPatternDetectionService;
        }
    }

    @Autowired(required = false)
    void configureHarmonicPatternPreferences(HarmonicPatternPreferencesService preferencesService) {
        this.harmonicPatternPreferencesService = preferencesService;
    }

    @Autowired(required = false)
    void configureHistoricalSignalCache(HistoricalSignalCacheService cacheService) {
        this.historicalSignalCacheService = cacheService;
    }

    @Autowired
    void configureChartTechnicalIndicators(ChartTechnicalIndicatorService service) {
        this.chartTechnicalIndicatorService = service;
    }

    @GetMapping("/{symbol}/candles")
    public CandlePageResponse getHistoricalCandles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            @RequestParam(required = false) Long before,
            @RequestParam(defaultValue = "1000") int limit) {
        symbol = SecurityInputValidator.requireMarketSymbol(symbol);
        interval = SecurityInputValidator.requireInterval(interval);
        before = SecurityInputValidator.requireBeforeTimestamp(before);
        MarketDataService.CandlePage page = marketDataService.loadCandlePage(symbol, interval, before, limit);
        return new CandlePageResponse(
                page.candles().stream().map(CandleResponse::from).toList(),
                page.nextCursor(),
                page.hasMore(),
                page.source().name(),
                page.failureMessage());
    }

    @PostMapping("/{symbol}/chart-indicators")
    public ChartTechnicalIndicatorService.IndicatorBatchView getChartIndicators(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            @RequestBody ChartTechnicalIndicatorService.IndicatorBatchRequest request) {
        symbol = SecurityInputValidator.requireMarketSymbol(symbol);
        interval = SecurityInputValidator.requireInterval(interval);
        if (chartTechnicalIndicatorService == null) {
            throw new IllegalStateException("Configurable chart indicators are unavailable.");
        }
        return chartTechnicalIndicatorService.calculate(symbol, interval, request);
    }

    @GetMapping("/{symbol}/live")
    public Map<String, Object> getLivePrices(@PathVariable String symbol) {
        return livePricingService.getLatestPrice(SecurityInputValidator.requireMarketSymbol(symbol));
    }

    @GetMapping("/{symbol}/elliott-waves")
    public ElliottWaveOverlay getElliottWaves(@PathVariable String symbol,
                                              @RequestParam String interval,
                                              Principal principal) {
        symbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        TimeInterval waveInterval = elliottInterval(validatedInterval);

        MarketDataService.CandleSyncResult syncResult = marketDataService.syncCandles(symbol, validatedInterval, null);
        if (!syncResult.successful()) {
            throw new IllegalStateException("Candle refresh failed.");
        }
        List<Candle> candles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampDesc(
                        symbol,
                        validatedInterval,
                        PageRequest.of(
                                0,
                                enrichmentService.requiredElliottInputCandles(
                                        LATEST_WAVE_CANDLES,
                                        waveInterval
                                )
                        )
                );
        ElliottWaveDetectionService detector = detector(principal, waveInterval);
        var structure = detector
                .findLatestWaveStructure(
                        enrichmentService.enrichForElliott(
                                candles,
                                LATEST_WAVE_CANDLES,
                                waveInterval
                        )
                );
        if (structure.isEmpty()) {
            return new ElliottWaveOverlay(validatedInterval, labelStyle(validatedInterval), "none", null, null,
                    false, List.of(), null, 0, 0.0, false, 0.0, 0.0,
                    ElliottWaveDetectionService.ImpulseVariant.STANDARD,
                    ElliottWaveDetectionService.CorrectionVariant.NONE, 0.0, 0.0, List.of());
        }

        ElliottWaveDetectionService.ElliottWaveStructure detected = structure.get();
        List<ElliottWavePointView> points = detected.points().stream()
                .map(point -> new ElliottWavePointView(
                        formatWaveLabel(point.label(), validatedInterval),
                        point.timestamp(),
                        point.price(),
                        point.pivotType()))
                .toList();
        return new ElliottWaveOverlay(
                validatedInterval,
                labelStyle(validatedInterval),
                structureId(detected),
                detector.lifecycleCycleKey(detected).orElse(null),
                detected.direction(),
                detected.correctionComplete(),
                points,
                detected.confirmationTimestamp(),
                detected.qualityScore(),
                detected.waveTwoRetracement(),
                detected.deepWaveTwo(),
                detected.waveThreeToOneRatio(),
                detected.waveFourRetracement(),
                detected.impulseVariant(),
                detected.correctionVariant(),
                detected.correctionRetracement(),
                detected.waveCToARatio(),
                detected.qualityWarnings());
    }

    public ElliottWaveOverlay getElliottWaves(String symbol, String interval) {
        return getElliottWaves(symbol, interval, null);
    }

    @GetMapping("/{symbol}/elliott-waves/history")
    public ElliottWaveHistoryOverlay getHistoricalElliottWaves(@PathVariable String symbol,
                                                               @RequestParam String interval,
                                                               @RequestParam(required = false) Long from,
                                                               Principal principal) {
        symbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String cacheSymbol = symbol;
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        from = SecurityInputValidator.requireBeforeTimestamp(from);
        TimeInterval waveInterval = elliottInterval(validatedInterval);
        if (historicalSignalCacheService == null) {
            return calculateHistoricalElliottWaves(symbol, validatedInterval, from, principal, waveInterval);
        }
        marketDataService.syncCandles(symbol, validatedInterval, null);
        ElliottWaveHistoryOverlay completeHistory = historicalSignalCacheService.getOrCompute(
                HistoricalSignalCacheService.Family.ELLIOTT_WAVE,
                symbol,
                validatedInterval,
                ElliottWaveDetectionService.SETUP_SCORE_VERSION,
                elliottCacheSettings(principal, waveInterval),
                ElliottWaveHistoryOverlay.class,
                () -> calculateHistoricalElliottWaves(
                        cacheSymbol, validatedInterval, null, principal, waveInterval));
        if (from == null) {
            return completeHistory;
        }
        long requestedFrom = from;
        List<ElliottWaveOverlay> requestedStructures = completeHistory.structures().stream()
                .filter(structure -> !structure.points().isEmpty()
                        && structure.points().getFirst().timestamp() >= requestedFrom)
                .toList();
        return new ElliottWaveHistoryOverlay(
                validatedInterval, labelStyle(validatedInterval), from, requestedStructures);
    }

    private ElliottWaveHistoryOverlay calculateHistoricalElliottWaves(
            String symbol,
            String validatedInterval,
            Long from,
            Principal principal,
            TimeInterval waveInterval) {
        List<Candle> candles = from == null
                ? candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, validatedInterval)
                : candleRepository.findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        symbol, validatedInterval, from);
        ElliottWaveDetectionService detector = detector(principal, waveInterval);
        List<ElliottWaveOverlay> structures = detector
                .findHistoricalWaveStructures(
                        enrichmentService.enrichForElliott(
                                candles,
                                candles.size(),
                                waveInterval
                        )
                )
                .stream()
                .map(structure -> new ElliottWaveOverlay(
                        validatedInterval,
                        labelStyle(validatedInterval),
                        structureId(structure),
                        detector.lifecycleCycleKey(structure).orElse(null),
                        structure.direction(),
                        structure.correctionComplete(),
                        structure.points().stream()
                                .map(point -> new ElliottWavePointView(
                                        formatWaveLabel(point.label(), validatedInterval),
                                        point.timestamp(),
                                        point.price(),
                                        point.pivotType()))
                                .toList(),
                        structure.confirmationTimestamp(),
                        structure.qualityScore(),
                        structure.waveTwoRetracement(),
                        structure.deepWaveTwo(),
                        structure.waveThreeToOneRatio(),
                        structure.waveFourRetracement(),
                        structure.impulseVariant(),
                        structure.correctionVariant(),
                        structure.correctionRetracement(),
                        structure.waveCToARatio(),
                        structure.qualityWarnings()))
                .toList();
        return new ElliottWaveHistoryOverlay(
                validatedInterval,
                labelStyle(validatedInterval),
                from,
                structures);
    }

    public ElliottWaveHistoryOverlay getHistoricalElliottWaves(String symbol, String interval, Long from) {
        return getHistoricalElliottWaves(symbol, interval, from, null);
    }

    @GetMapping("/{symbol}/harmonic-formations/history")
    public HarmonicHistoryOverlay getHistoricalHarmonicFormations(
            @PathVariable String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) Long from,
            Principal principal) {
        symbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String cacheSymbol = symbol;
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        harmonicInterval(validatedInterval);
        from = SecurityInputValidator.requireBeforeTimestamp(from);
        if (historicalSignalCacheService == null) {
            return calculateHistoricalHarmonicFormations(symbol, validatedInterval, from, principal);
        }
        marketDataService.syncCandles(symbol, validatedInterval, null);
        HarmonicHistoryOverlay completeHistory = historicalSignalCacheService.getOrCompute(
                HistoricalSignalCacheService.Family.HARMONIC_FORMATION,
                symbol,
                validatedInterval,
                HarmonicPatternDetectionService.RULE_VERSION,
                harmonicCacheSettings(principal),
                HarmonicHistoryOverlay.class,
                () -> calculateHistoricalHarmonicFormations(
                        cacheSymbol, validatedInterval, null, principal));
        if (from == null) {
            return completeHistory;
        }
        long requestedFrom = from;
        return new HarmonicHistoryOverlay(
                validatedInterval,
                from,
                HarmonicPatternDetectionService.RULE_VERSION,
                completeHistory.formations().stream()
                        .filter(formation -> formation.points().getLast().timestamp() >= requestedFrom)
                        .toList());
    }

    private HarmonicHistoryOverlay calculateHistoricalHarmonicFormations(
            String symbol,
            String validatedInterval,
            Long from,
            Principal principal) {
        Long requestedFrom = from;
        List<Candle> candles = harmonicDetectionCandles(symbol, validatedInterval, from);
        List<HarmonicPatternDetectionService.HarmonicFormation> formations =
                harmonicDetector(principal).detectHistorical(candles).stream()
                        .filter(formation -> requestedFrom == null
                                || formation.points().getLast().timestamp() >= requestedFrom)
                        .toList();
        return new HarmonicHistoryOverlay(
                validatedInterval,
                from,
                HarmonicPatternDetectionService.RULE_VERSION,
                formations
        );
    }

    public HarmonicHistoryOverlay getHistoricalHarmonicFormations(
            String symbol, String interval, Long from) {
        return getHistoricalHarmonicFormations(symbol, interval, from, null);
    }

    private List<Candle> harmonicDetectionCandles(String symbol, String interval, Long from) {
        if (from == null) {
            return candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, interval);
        }
        List<Candle> context = candleRepository
                .findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                        symbol, interval, from, PageRequest.of(0, HARMONIC_CONTEXT_CANDLES));
        List<Candle> requested = candleRepository
                .findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        symbol, interval, from);
        List<Candle> combined = new ArrayList<>(context.size() + requested.size());
        combined.addAll(context);
        combined.addAll(requested);
        combined.sort(Comparator.comparing(Candle::getTimestamp));
        return List.copyOf(combined);
    }

    private HarmonicPatternDetectionService harmonicDetector(Principal principal) {
        if (principal == null || userRepository == null || harmonicPatternPreferencesService == null) {
            return harmonicPatternDetectionService;
        }
        return userRepository.findByEmailIgnoreCase(principal.getName())
                .map(user -> harmonicPatternPreferencesService.detector(
                        user, harmonicPatternDetectionService))
                .orElse(harmonicPatternDetectionService);
    }

    private Object harmonicCacheSettings(Principal principal) {
        if (principal == null || userRepository == null || harmonicPatternPreferencesService == null) {
            return "factory";
        }
        return userRepository.findByEmailIgnoreCase(principal.getName())
                .<Object>map(user -> {
                    HarmonicPatternPreferencesService.PreferencesView preferences =
                            harmonicPatternPreferencesService.get(user);
                    return List.of(preferences.version(), preferences.rules(), preferences.patternRules());
                })
                .orElse("factory");
    }

    private Object elliottCacheSettings(Principal principal, TimeInterval interval) {
        if (principal == null || userRepository == null || elliottWavePreferencesService == null) {
            ElliottWavePreferencesService.PreferencesView preferences =
                    ElliottWavePreferencesService.factoryPreferences();
            return List.of(preferences.version(), preferences.profile(interval).rules());
        }
        return userRepository.findByEmailIgnoreCase(principal.getName())
                .<Object>map(user -> {
                    ElliottWavePreferencesService.PreferencesView preferences =
                            elliottWavePreferencesService.get(user);
                    return List.of(preferences.version(), preferences.profile(interval).rules());
                })
                .orElseGet(() -> {
                    ElliottWavePreferencesService.PreferencesView preferences =
                            ElliottWavePreferencesService.factoryPreferences();
                    return List.of(preferences.version(), preferences.profile(interval).rules());
                });
    }

    private ElliottWaveDetectionService detector(Principal principal, TimeInterval interval) {
        if (principal == null || userRepository == null || elliottWavePreferencesService == null) {
            return elliottWaveDetectionService;
        }
        return userRepository.findByEmailIgnoreCase(principal.getName())
                .map(user -> elliottWaveDetectionService.configured(
                        elliottWavePreferencesService.get(user).profile(interval).rules()))
                .orElse(elliottWaveDetectionService);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> searchTickers(@RequestParam String q) {
        return twelveDataService.searchSymbols(SecurityInputValidator.requireSearchQuery(q));
    }

    @GetMapping("/search/local")
    public List<Map<String, Object>> searchLocalTickers(@RequestParam String q) {
        return twelveDataService.searchLocalSymbols(SecurityInputValidator.requireSearchQuery(q));
    }

    @GetMapping("/{symbol}/meta")
    public Map<String, String> getStockMetadata(@PathVariable String symbol,
                                                @RequestParam(required = false) String micCode) {
        symbol = SecurityInputValidator.requireMarketSymbol(symbol);
        micCode = SecurityInputValidator.requireOptionalMicCode(micCode);
        StockAsset asset = twelveDataService.refreshStockAssetMetadata(symbol, micCode);
        if (isGenericMetadata(asset, symbol) || isInconsistentUsMetadata(asset, symbol)) {
            try {
                asset = yahooFinanceService.refreshStockAssetMetadata(symbol);
            } catch (RuntimeException e) {
                System.err.println("Yahoo Finance metadata refresh unavailable for " + symbol + ": " + e.getMessage());
            }
        }
        return Map.of(
                "symbol", asset.getTickerSymbol(),
                "name", asset.getCompanyName(),
                "exchange", asset.getExchange(),
                "micCode", asset.getMicCode() == null ? "" : asset.getMicCode(),
                "country", asset.getCountry() == null ? "" : asset.getCountry(),
                "currency", asset.getCurrency() != null ? asset.getCurrency() : "USD",
                "instrumentType", asset.getInstrumentType() != null
                        ? asset.getInstrumentType().name()
                        : org.example.stockwatch247.model.enums.InstrumentType.EQUITY.name()
        );
    }

    public Map<String, String> getStockMetadata(String symbol) {
        return getStockMetadata(symbol, null);
    }

    private boolean isGenericMetadata(StockAsset asset, String symbol) {
        String name = asset.getCompanyName();
        String exchange = asset.getExchange();
        return name == null || name.isBlank() || name.equalsIgnoreCase(symbol)
                || exchange == null || exchange.isBlank() || "UNKNOWN".equalsIgnoreCase(exchange);
    }

    private boolean isInconsistentUsMetadata(StockAsset asset, String symbol) {
        if (asset == null || symbol == null || !symbol.matches("[A-Z0-9-]+")) {
            return false;
        }
        String mic = asset.getMicCode() == null
                ? ""
                : asset.getMicCode().trim().toUpperCase(Locale.ROOT);
        String country = asset.getCountry() == null
                ? ""
                : asset.getCountry().trim().toUpperCase(Locale.ROOT);
        boolean usIdentity = Set.of(
                "XNAS", "XNCM", "XNMS", "XNGS", "XNYS",
                "XASE", "ARCX", "BATS", "IEXG", "OTCM").contains(mic)
                || country.equals("US")
                || country.equals("USA")
                || country.contains("UNITED STATES");
        return usIdentity && !"USD".equalsIgnoreCase(asset.getCurrency());
    }

    private String formatWaveLabel(String label, String interval) {
        if ("1wk".equals(interval)) return label.toLowerCase(Locale.ROOT);
        if (!"1d".equals(interval)) return label;
        return switch (label == null ? "" : label.toUpperCase(Locale.ROOT)) {
            case "I" -> "1";
            case "II" -> "2";
            case "III" -> "3";
            case "IV" -> "4";
            case "V" -> "5";
            default -> label;
        };
    }

    private String labelStyle(String interval) {
        return switch (interval) {
            case "1d" -> "NUMERIC";
            case "1wk" -> "LOWERCASE";
            default -> "UPPERCASE";
        };
    }

    private TimeInterval elliottInterval(String interval) {
        return switch (interval) {
            case "1d" -> TimeInterval.DAILY;
            case "1wk" -> TimeInterval.WEEKLY;
            case "1mo" -> TimeInterval.MONTHLY;
            default -> throw new IllegalArgumentException(
                    "Elliott Wave overlays require a daily, weekly, or monthly interval.");
        };
    }

    private TimeInterval harmonicInterval(String interval) {
        return switch (interval) {
            case "1d" -> TimeInterval.DAILY;
            case "1wk" -> TimeInterval.WEEKLY;
            case "1mo" -> TimeInterval.MONTHLY;
            default -> throw new IllegalArgumentException(
                    "Harmonic overlays require a daily, weekly, or monthly interval.");
        };
    }

    private String structureId(ElliottWaveDetectionService.ElliottWaveStructure structure) {
        ElliottWaveDetectionService.ElliottWavePoint first = structure.points().getFirst();
        ElliottWaveDetectionService.ElliottWavePoint last = structure.points().getLast();
        return structure.direction() + ':' + first.timestamp() + ':' + last.label() + ':' + last.timestamp();
    }

    public record ElliottWavePointView(String label, Long timestamp, double price, String pivotType) {
    }

    public record CandlePageResponse(List<CandleResponse> candles,
                                     Long nextCursor,
                                     boolean hasMore,
                                     String source,
                                     String failureMessage) {
    }

    public record ElliottWaveOverlay(String interval,
                                     String labelStyle,
                                     String structureId,
                                     String cycleKey,
                                     String direction,
                                     boolean correctionComplete,
                                     List<ElliottWavePointView> points,
                                     Long confirmationTimestamp,
                                     int qualityScore,
                                     double waveTwoRetracement,
                                     boolean deepWaveTwo,
                                     double waveThreeToOneRatio,
                                     double waveFourRetracement,
                                     ElliottWaveDetectionService.ImpulseVariant impulseVariant,
                                     ElliottWaveDetectionService.CorrectionVariant correctionVariant,
                                     double correctionRetracement,
                                     double waveCToARatio,
                                     List<String> qualityWarnings) {
    }

    public record ElliottWaveHistoryOverlay(String interval,
                                             String labelStyle,
                                             Long fromTimestamp,
                                             List<ElliottWaveOverlay> structures) {
    }

    public record HarmonicHistoryOverlay(
            String interval,
            Long fromTimestamp,
            String ruleVersion,
            List<HarmonicPatternDetectionService.HarmonicFormation> formations) {
    }
}
