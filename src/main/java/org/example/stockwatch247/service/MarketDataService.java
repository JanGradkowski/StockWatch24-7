package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.market.MarketIndexCatalog;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MarketDataService {

    private final CandleRepository candleRepository;
    private final StockAssetRepository stockAssetRepository;
    private final TwelveDataService twelveDataService;
    private final YahooFinanceService yahooFinanceService;
    private final long intradayCooldownSeconds;
    private final long dailyCooldownSeconds;
    private final long higherIntervalCooldownSeconds;
    private final long syncLeaseSeconds;
    private final MarketDataSyncCoordinator syncCoordinator;

    @Autowired
    public MarketDataService(CandleRepository candleRepository,
                             StockAssetRepository stockAssetRepository,
                             TwelveDataService twelveDataService,
                             YahooFinanceService yahooFinanceService,
                             MarketDataSyncCoordinator syncCoordinator,
                             @Value("${market-data.refresh-cooldown.intraday-seconds:60}") long intradayCooldownSeconds,
                             @Value("${market-data.refresh-cooldown.daily-seconds:600}") long dailyCooldownSeconds,
                             @Value("${market-data.refresh-cooldown.higher-interval-seconds:3600}") long higherIntervalCooldownSeconds,
                             @Value("${market-data.sync-lease-seconds:180}") long syncLeaseSeconds) {
        this.candleRepository = candleRepository;
        this.stockAssetRepository = stockAssetRepository;
        this.twelveDataService = twelveDataService;
        this.yahooFinanceService = yahooFinanceService;
        this.syncCoordinator = syncCoordinator;
        this.intradayCooldownSeconds = Math.max(0L, intradayCooldownSeconds);
        this.dailyCooldownSeconds = Math.max(0L, dailyCooldownSeconds);
        this.higherIntervalCooldownSeconds = Math.max(0L, higherIntervalCooldownSeconds);
        this.syncLeaseSeconds = Math.max(1L, syncLeaseSeconds);
    }

    public CandleSyncResult syncCandles(String rawSymbol, String interval, Long beforeTimestamp) {
        return syncCandles(rawSymbol, interval, beforeTimestamp, false);
    }

    public CandleSyncResult syncCandles(String rawSymbol, String interval, Long beforeTimestamp, boolean forceRefresh) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        interval = SecurityInputValidator.requireInterval(interval);

        // Older chart scrolling reads from the database cache. The normal refresh keeps
        // the most recent page current without burning provider calls during pagination.
        if (beforeTimestamp != null) {
            return new CandleSyncResult(CandleSource.CACHE, 0, null);
        }

        MarketDataSyncCoordinator.Claim claim = syncCoordinator.tryClaim(
                symbol, interval, getProviderCooldownSeconds(interval), syncLeaseSeconds);
        if (!claim.acquired()) {
            String reason = claim.status() == MarketDataSyncCoordinator.ClaimStatus.RECENT_SUCCESS
                    ? "recent successful sync"
                    : "sync already running on another worker";
            System.out.println("Reused candle data for " + symbol + " " + interval + " from CACHE (" + reason + ").");
            CandleSource cacheSource = claim.status() == MarketDataSyncCoordinator.ClaimStatus.IN_PROGRESS
                    ? CandleSource.CACHE_REFRESH_IN_PROGRESS
                    : CandleSource.CACHE;
            return new CandleSyncResult(cacheSource, 0, null);
        }

        boolean successful = false;
        try {
                List<MarketDataBar> bars;
                CandleSource source;
                String twelveDataFailure = null;
                try {
                    String timeframe = toTwelveDataInterval(interval);
                    bars = twelveDataService.getTimeSeries(symbol, timeframe, 1000);
                    if (bars.isEmpty()) {
                        throw new IllegalStateException("Twelve Data returned no candles.");
                    }
                    source = CandleSource.TWELVE_DATA;
                } catch (Exception e) {
                    twelveDataFailure = e.getMessage();
                    System.err.println("Twelve Data candle sync unavailable for " + symbol + ": " + twelveDataFailure
                            + ". Trying Yahoo Finance.");
                    try {
                        bars = yahooFinanceService.getTimeSeries(symbol, interval, 1000);
                        if (bars.isEmpty()) {
                            throw new IllegalStateException("Yahoo Finance returned no candles.");
                        }
                        source = CandleSource.YAHOO_FINANCE;
                    } catch (Exception yahooFailure) {
                        String failure = "Twelve Data: " + twelveDataFailure
                                + "; Yahoo Finance: " + yahooFailure.getMessage();
                        System.err.println("Failed syncing candles for " + symbol + " from all providers: " + failure);
                        return new CandleSyncResult(CandleSource.NONE, 0, failure);
                    }
                }

                ensureAsset(symbol);
                int persistedCandles = persistChangedCandles(symbol, interval, bars);
                syncCoordinator.markSuccessful(claim);
                successful = true;
                System.out.println("Fetched " + bars.size() + " " + interval + " candles for " + symbol + " from "
                        + source + "; persisted " + persistedCandles + " new or changed candles.");
                return new CandleSyncResult(source, persistedCandles, null);
        } finally {
            if (!successful) {
                syncCoordinator.release(claim);
            }
        }
    }

    private long getProviderCooldownSeconds(String interval) {
        return switch (interval) {
            case "1wk", "1mo" -> higherIntervalCooldownSeconds;
            case "1min", "5min", "15min", "30min", "60min" -> intradayCooldownSeconds;
            default -> dailyCooldownSeconds;
        };
    }

    private String toTwelveDataInterval(String interval) {
        return switch (interval) {
            case "1wk" -> "1week";
            case "1mo" -> "1month";
            case "1min" -> "1min";
            case "5min" -> "5min";
            case "15min" -> "15min";
            case "30min" -> "30min";
            case "60min" -> "1h";
            default -> "1day";
        };
    }

    private void ensureAsset(String symbol) {
        if (stockAssetRepository.findByTickerSymbolIgnoreCase(symbol).isPresent()) {
            return;
        }

        StockAsset asset = new StockAsset();
        asset.setTickerSymbol(symbol);
        MarketIndexCatalog.findBySymbol(symbol).ifPresentOrElse(index -> {
            asset.setCompanyName(index.name());
            asset.setExchange(index.exchange());
            asset.setCurrency(index.currency());
            asset.setInstrumentType(InstrumentType.INDEX);
        }, () -> {
            asset.setCompanyName(symbol);
            asset.setExchange("UNKNOWN");
            asset.setCurrency("USD");
            asset.setInstrumentType(InstrumentType.EQUITY);
        });
        stockAssetRepository.save(asset);
    }

    private int persistChangedCandles(String symbol, String interval, List<MarketDataBar> bars) {
        List<Long> timestamps = bars.stream()
                .map(MarketDataBar::timestamp)
                .distinct()
                .toList();
        Map<Long, Candle> existingByTimestamp = new LinkedHashMap<>();
        candleRepository.findBySymbolAndTimeIntervalAndTimestampIn(symbol, interval, timestamps)
                .forEach(candle -> existingByTimestamp.put(candle.getTimestamp(), candle));

        Map<Long, Candle> changedByTimestamp = new LinkedHashMap<>();
        for (MarketDataBar bar : bars) {
            Candle candle = existingByTimestamp.get(bar.timestamp());
            if (candle != null && hasSameValues(candle, bar)) {
                continue;
            }
            if (candle == null) {
                candle = new Candle();
                existingByTimestamp.put(bar.timestamp(), candle);
            }
            applyBar(candle, symbol, interval, bar);
            changedByTimestamp.put(bar.timestamp(), candle);
        }

        if (!changedByTimestamp.isEmpty()) {
            candleRepository.saveAll(changedByTimestamp.values());
        }
        return changedByTimestamp.size();
    }

    private void applyBar(Candle candle, String symbol, String interval, MarketDataBar bar) {
        candle.setSymbol(symbol);
        candle.setTimestamp(bar.timestamp());
        candle.setTimeInterval(interval);
        candle.setOpenPrice(bar.open());
        candle.setHighPrice(bar.high());
        candle.setLowPrice(bar.low());
        candle.setClosePrice(bar.close());
        candle.setVolume(bar.volume());
    }

    private boolean hasSameValues(Candle candle, MarketDataBar bar) {
        return sameDouble(candle.getOpenPrice(), bar.open())
                && sameDouble(candle.getHighPrice(), bar.high())
                && sameDouble(candle.getLowPrice(), bar.low())
                && sameDouble(candle.getClosePrice(), bar.close())
                && Objects.equals(candle.getVolume(), bar.volume());
    }

    private boolean sameDouble(Double stored, double fetched) {
        return stored != null && Double.compare(stored, fetched) == 0;
    }

    public enum CandleSource {
        TWELVE_DATA,
        YAHOO_FINANCE,
        CACHE,
        CACHE_REFRESH_IN_PROGRESS,
        NONE
    }

    public record CandleSyncResult(CandleSource source, int candlesSynced, String failureMessage) {
        public boolean successful() {
            return source != CandleSource.NONE;
        }
    }
}
