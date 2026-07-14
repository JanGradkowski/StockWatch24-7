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
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Locale;

@RestController
@RequestMapping("/api/stocks")
public class ChartController {

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
    public List<CandleResponse> getHistoricalCandles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1d") String interval,
            @RequestParam(required = false) Long before) {
        symbol = SecurityInputValidator.requireMarketSymbol(symbol);
        interval = SecurityInputValidator.requireInterval(interval);
        before = SecurityInputValidator.requireBeforeTimestamp(before);
        marketDataService.syncCandles(symbol, interval, before);
        List<Candle> candles = before == null
                ? candleRepository.findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, interval)
                : candleRepository.findTop100BySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(symbol, interval, before);
        java.util.Collections.reverse(candles);
        return candles.stream().map(CandleResponse::from).toList();
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
                .findTop100BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, validatedInterval);
        var structure = elliottWaveDetectionService
                .findLatestWaveStructure(enrichmentService.enrich(candles, 100));
        if (structure.isEmpty()) {
            return new ElliottWaveOverlay(validatedInterval, labelStyle(validatedInterval), null,
                    false, List.of(), null, 0);
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
                detected.direction(),
                detected.correctionComplete(),
                points,
                detected.confirmationTimestamp(),
                detected.qualityScore());
    }

    @GetMapping("/{symbol}/elliott-waves/history")
    public ElliottWaveHistoryOverlay getHistoricalElliottWaves(@PathVariable String symbol,
                                                               @RequestParam String interval) {
        symbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        if (!"1wk".equals(validatedInterval) && !"1mo".equals(validatedInterval)) {
            throw new IllegalArgumentException("Historical Elliott Waves require a weekly or monthly interval.");
        }
        MarketDataService.CandleSyncResult syncResult = marketDataService.syncCandles(symbol, validatedInterval, null);
        if (!syncResult.successful()) {
            throw new IllegalStateException("Candle refresh failed.");
        }

        List<Candle> cachedCandles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, validatedInterval);
        int firstIndex = Math.max(0, cachedCandles.size() - 2_000);
        List<Candle> candles = List.copyOf(cachedCandles.subList(firstIndex, cachedCandles.size()));
        List<ElliottWaveOverlay> structures = elliottWaveDetectionService
                .findHistoricalWaveStructures(enrichmentService.enrich(candles, candles.size()))
                .stream()
                .map(structure -> new ElliottWaveOverlay(
                        validatedInterval,
                        labelStyle(validatedInterval),
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
                        structure.qualityScore()))
                .toList();
        return new ElliottWaveHistoryOverlay(
                validatedInterval,
                labelStyle(validatedInterval),
                candles.stream().map(CandleResponse::from).toList(),
                structures);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> searchTickers(@RequestParam String q) {
        return twelveDataService.searchSymbols(SecurityInputValidator.requireSearchQuery(q));
    }

    @GetMapping("/{symbol}/meta")
    public Map<String, String> getStockMetadata(@PathVariable String symbol) {
        symbol = SecurityInputValidator.requireMarketSymbol(symbol);
        StockAsset asset = twelveDataService.refreshStockAssetMetadata(symbol);
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
                "currency", asset.getCurrency() != null ? asset.getCurrency() : "USD",
                "instrumentType", asset.getInstrumentType() != null
                        ? asset.getInstrumentType().name()
                        : org.example.stockwatch247.model.enums.InstrumentType.EQUITY.name()
        );
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

    public record ElliottWavePointView(String label, Long timestamp, double price, String pivotType) {
    }

    public record ElliottWaveOverlay(String interval,
                                     String labelStyle,
                                     String direction,
                                     boolean correctionComplete,
                                     List<ElliottWavePointView> points,
                                     Long confirmationTimestamp,
                                     int qualityScore) {
    }

    public record ElliottWaveHistoryOverlay(String interval,
                                             String labelStyle,
                                             List<CandleResponse> candles,
                                             List<ElliottWaveOverlay> structures) {
    }
}
