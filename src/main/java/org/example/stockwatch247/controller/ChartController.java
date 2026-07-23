package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.LivePricingService;
import org.example.stockwatch247.service.MarketDataService;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.TechnicalIndicatorEnrichmentService;
import org.example.stockwatch247.service.TwelveDataService;
import org.example.stockwatch247.service.YahooFinanceService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Locale;

@RestController
@RequestMapping("/api/stocks")
public class ChartController {
    private static final int LATEST_WAVE_CANDLES = 100;

    private final MarketDataService marketDataService;
    private final CandleRepository candleRepository;
    private final StockAssetRepository stockAssetRepository;
    private final LivePricingService livePricingService;
    private final TwelveDataService twelveDataService;
    private final YahooFinanceService yahooFinanceService;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final ElliottWaveDetectionService elliottWaveDetectionService;

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

    @GetMapping("/{symbol}/live")
    public Map<String, Object> getLivePrices(@PathVariable String symbol) {
        return livePricingService.getLatestPrice(SecurityInputValidator.requireMarketSymbol(symbol));
    }

    @GetMapping("/{symbol}/elliott-waves")
    public ElliottWaveOverlay getElliottWaves(@PathVariable String symbol,
                                              @RequestParam String interval) {
        symbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        if (!"1wk".equals(validatedInterval) && !"1mo".equals(validatedInterval)) {
            throw new IllegalArgumentException("Elliott Wave overlays require a weekly or monthly interval.");
        }

        MarketDataService.CandleSyncResult syncResult = marketDataService.syncCandles(symbol, validatedInterval, null);
        if (!syncResult.successful()) {
            throw new IllegalStateException("Candle refresh failed.");
        }
        List<Candle> candles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampDesc(
                        symbol,
                        validatedInterval,
                        PageRequest.of(0, enrichmentService.requiredInputCandles(LATEST_WAVE_CANDLES))
                );
        var structure = elliottWaveDetectionService
                .findLatestWaveStructure(enrichmentService.enrich(candles, LATEST_WAVE_CANDLES));
        if (structure.isEmpty()) {
            return new ElliottWaveOverlay(validatedInterval, labelStyle(validatedInterval), "none", null,
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

    @GetMapping("/{symbol}/elliott-waves/history")
    public ElliottWaveHistoryOverlay getHistoricalElliottWaves(@PathVariable String symbol,
                                                               @RequestParam String interval,
                                                               @RequestParam(required = false) Long from) {
        symbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        from = SecurityInputValidator.requireBeforeTimestamp(from);
        if (!"1wk".equals(validatedInterval) && !"1mo".equals(validatedInterval)) {
            throw new IllegalArgumentException("Historical Elliott Waves require a weekly or monthly interval.");
        }
        List<Candle> candles = from == null
                ? candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, validatedInterval)
                : candleRepository.findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        symbol, validatedInterval, from);
        List<ElliottWaveOverlay> structures = elliottWaveDetectionService
                .findHistoricalWaveStructures(enrichmentService.enrich(candles, candles.size()))
                .stream()
                .map(structure -> new ElliottWaveOverlay(
                        validatedInterval,
                        labelStyle(validatedInterval),
                        structureId(structure),
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
        if (isGenericMetadata(asset, symbol)) {
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

    private String formatWaveLabel(String label, String interval) {
        return "1wk".equals(interval) ? label.toLowerCase(Locale.ROOT) : label;
    }

    private String labelStyle(String interval) {
        return "1wk".equals(interval) ? "LOWERCASE" : "UPPERCASE";
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
}
