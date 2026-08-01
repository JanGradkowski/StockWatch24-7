package org.example.stockwatch247.controller;

import org.example.stockwatch247.security.SecurityInputValidator;
import org.example.stockwatch247.service.HistoricalCandlestickService;
import org.example.stockwatch247.service.HistoricalCandlestickService.HistoricalScan;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
public class HistoricalCandlestickController {
    private final HistoricalCandlestickService historicalCandlestickService;

    public HistoricalCandlestickController(HistoricalCandlestickService historicalCandlestickService) {
        this.historicalCandlestickService = historicalCandlestickService;
    }

    @GetMapping("/{symbol}/candlestick-patterns/history")
    public ResponseEntity<HistoricalScan> historicalCandlestickPatterns(
            @PathVariable String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) Integer lookbackCandles) {
        String validatedSymbol = SecurityInputValidator.requireMarketSymbol(symbol);
        String validatedInterval = SecurityInputValidator.requireInterval(interval);
        int selectedLookback = lookbackCandles == null
                ? historicalCandlestickService.defaultLookbackCandles(validatedInterval)
                : lookbackCandles;
        HistoricalScan scan = historicalCandlestickService.scan(
                validatedSymbol,
                validatedInterval,
                selectedLookback
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(scan);
    }
}
