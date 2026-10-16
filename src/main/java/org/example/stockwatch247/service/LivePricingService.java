package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.repository.CandleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class LivePricingService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LivePricingService.class);
    private final TwelveDataService twelveDataService;
    private final CandleRepository candleRepository;
    private final SharedQuoteCache quoteCache;
    private final MarketDataSyncCoordinator syncCoordinator;
    private final long quoteCacheTtlSeconds;

    public LivePricingService(TwelveDataService twelveDataService,
                              CandleRepository candleRepository,
                              SharedQuoteCache quoteCache,
                              MarketDataSyncCoordinator syncCoordinator,
                              @Value("${twelve-data.quote-cache-ttl-seconds:60}") long quoteCacheTtlSeconds) {
        this.twelveDataService = twelveDataService;
        this.candleRepository = candleRepository;
        this.quoteCache = quoteCache;
        this.syncCoordinator = syncCoordinator;
        this.quoteCacheTtlSeconds = quoteCacheTtlSeconds;
    }

    public Map<String, Object> getLatestPrice(String rawSymbol) {
        String symbol = twelveDataService.normalizeSymbol(rawSymbol);
        var cached = quoteCache.getFresh(symbol, quoteCacheTtlSeconds);
        if (cached.isPresent()) {
            return cached.get();
        }

        MarketDataSyncCoordinator.Claim claim = syncCoordinator.tryClaim(
                symbol, "quote", quoteCacheTtlSeconds, 30L);
        if (!claim.acquired()) {
            return quoteCache.getFresh(symbol, quoteCacheTtlSeconds).orElseGet(() -> {
                Map<String, Object> stored = getStoredQuote(symbol);
                return stored.isEmpty() ? Map.of("status", org.example.stockwatch247.i18n.ServerMessages.translate("Quote refresh is in progress for " + symbol)) : stored;
            });
        }

        boolean completed = false;
        try {
            try {
                var latestQuote = twelveDataService.getQuote(symbol);
                if (latestQuote.isPresent()) {
                    TwelveDataService.TwelveDataQuote quoteData = latestQuote.get();
                    Map<String, Object> values = new java.util.LinkedHashMap<>();
                    values.put("symbol", symbol);
                    values.put("price", quoteData.price());
                    values.put("changePercent", quoteData.percentChange());
                    values.put("timestamp", quoteData.timestamp());
                    values.put("quotedAt", quoteData.quotedAt());
                    values.put("source", "Twelve Data");
                    if (quoteData.marketOpen() != null) values.put("marketOpen", quoteData.marketOpen());
                    Map<String, Object> quote = java.util.Collections.unmodifiableMap(values);
                    quoteCache.put(symbol, quote);
                    syncCoordinator.markSuccessful(claim);
                    completed = true;
                    return quote;
                }
            } catch (RuntimeException e) {
                log.warn("Twelve Data latest price unavailable for " + symbol + ": " + e.getMessage());
            }

            Map<String, Object> storedQuote = getStoredQuote(symbol);
            if (!storedQuote.isEmpty()) {
                quoteCache.put(symbol, storedQuote);
                syncCoordinator.markSuccessful(claim);
                completed = true;
                return storedQuote;
            }

            return Map.of("status", org.example.stockwatch247.i18n.ServerMessages.translate("Quote unavailable for " + symbol));
        } finally {
            if (!completed) {
                syncCoordinator.release(claim);
            }
        }
    }

    private Map<String, Object> getStoredQuote(String symbol) {
        List<Candle> latestCandles = candleRepository.findTop2BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, "1d");
        if (latestCandles.isEmpty()) {
            return Map.of();
        }

        Candle latest = latestCandles.get(0);
        double previousClose = latestCandles.size() > 1 ? latestCandles.get(1).getClosePrice() : latest.getOpenPrice();
        double changePercent = 0.0;
        if (previousClose > 0) {
            changePercent = ((latest.getClosePrice() - previousClose) / previousClose) * 100;
        }

        return Map.of(
                "symbol", symbol,
                "price", latest.getClosePrice(),
                "changePercent", changePercent,
                "timestamp", latest.getTimestamp(),
                "source", "Stored candle"
        );
    }
}
