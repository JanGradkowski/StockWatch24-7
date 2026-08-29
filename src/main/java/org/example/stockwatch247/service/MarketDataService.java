package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.market.MarketIndexCatalog;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private static final long FAILED_SYNC_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(30L);

    private final CandleRepository candleRepository;
    private final StockAssetRepository stockAssetRepository;
    private final TwelveDataService twelveDataService;
    private final YahooFinanceService yahooFinanceService;
    private final long intradayCooldownSeconds;
    private final long dailyCooldownSeconds;
    private final long higherIntervalCooldownSeconds;
    private final long syncLeaseSeconds;
    private final MarketDataSyncCoordinator syncCoordinator;
    private final MarketDataHistoryStateStore historyStateStore;
    private final CandleBatchStore candleBatchStore;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<SyncKey, FailedSync> recentFailedSyncs = new ConcurrentHashMap<>();

    @Autowired
    public MarketDataService(CandleRepository candleRepository,
                             StockAssetRepository stockAssetRepository,
                             TwelveDataService twelveDataService,
                             YahooFinanceService yahooFinanceService,
                             MarketDataSyncCoordinator syncCoordinator,
                             MarketDataHistoryStateStore historyStateStore,
                             CandleBatchStore candleBatchStore,
                             ApplicationEventPublisher eventPublisher,
                             @Value("${market-data.refresh-cooldown.intraday-seconds:60}") long intradayCooldownSeconds,
                             @Value("${market-data.refresh-cooldown.daily-seconds:600}") long dailyCooldownSeconds,
                             @Value("${market-data.refresh-cooldown.higher-interval-seconds:3600}") long higherIntervalCooldownSeconds,
                             @Value("${market-data.sync-lease-seconds:180}") long syncLeaseSeconds) {
        this.candleRepository = candleRepository;
        this.stockAssetRepository = stockAssetRepository;
        this.twelveDataService = twelveDataService;
        this.yahooFinanceService = yahooFinanceService;
        this.syncCoordinator = syncCoordinator;
        this.historyStateStore = historyStateStore;
        this.candleBatchStore = candleBatchStore;
        this.eventPublisher = eventPublisher;
        this.intradayCooldownSeconds = Math.max(0L, intradayCooldownSeconds);
        this.dailyCooldownSeconds = Math.max(0L, dailyCooldownSeconds);
        this.higherIntervalCooldownSeconds = Math.max(0L, higherIntervalCooldownSeconds);
        this.syncLeaseSeconds = Math.max(1L, syncLeaseSeconds);
    }

    /** Test/backward-compatible constructor; production uses the batched store above. */
    MarketDataService(CandleRepository candleRepository,
                      StockAssetRepository stockAssetRepository,
                      TwelveDataService twelveDataService,
                      YahooFinanceService yahooFinanceService,
                      MarketDataSyncCoordinator syncCoordinator,
                      MarketDataHistoryStateStore historyStateStore,
                      long intradayCooldownSeconds,
                      long dailyCooldownSeconds,
                      long higherIntervalCooldownSeconds,
                      long syncLeaseSeconds) {
        this(candleRepository, stockAssetRepository, twelveDataService, yahooFinanceService,
                syncCoordinator, historyStateStore, null, null, intradayCooldownSeconds, dailyCooldownSeconds,
                higherIntervalCooldownSeconds, syncLeaseSeconds);
    }

    public CandleSyncResult syncCandles(String rawSymbol, String interval, Long beforeTimestamp) {
        return syncCandles(rawSymbol, interval, beforeTimestamp, false);
    }

    public CandleSyncResult syncCandles(String rawSymbol, String interval, Long beforeTimestamp, boolean forceRefresh) {
        return syncCandles(rawSymbol, interval, beforeTimestamp, forceRefresh, 1_000, false);
    }

    /**
     * Bootstraps only enough history to warm the selected analysis profile. Once that
     * history exists, normal refreshes ask providers for a small latest-candle delta.
     */
    public CandleSyncResult syncCandlesForAnalysis(String rawSymbol,
                                                   String rawInterval,
                                                   int requiredCandles) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        String interval = SecurityInputValidator.requireInterval(rawInterval);
        int required = Math.max(2, Math.min(1_000, requiredCandles));
        long cached = candleRepository.countBySymbolAndTimeInterval(symbol, interval);
        boolean bootstrapRequired = cached < required;
        int outputSize = bootstrapRequired ? required : 10;
        return syncCandles(symbol, interval, null, false, outputSize, bootstrapRequired);
    }

    private CandleSyncResult syncCandles(String rawSymbol,
                                         String interval,
                                         Long beforeTimestamp,
                                         boolean forceRefresh,
                                         int requestedOutputSize,
                                         boolean bootstrapRequired) {
        long syncStarted = System.nanoTime();
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        interval = SecurityInputValidator.requireInterval(interval);
        int outputSize = Math.max(1, Math.min(1_000, requestedOutputSize));
        SyncKey syncKey = new SyncKey(symbol, interval);

        if (!forceRefresh) {
            CandleSyncResult recentFailure = recentFailedSync(syncKey);
            if (recentFailure != null) {
                return recentFailure;
            }
        }

        // Older chart scrolling reads from the database cache. The normal refresh keeps
        // the most recent page current without burning provider calls during pagination.
        if (beforeTimestamp != null) {
            return new CandleSyncResult(CandleSource.CACHE, 0, null);
        }

        MarketDataSyncCoordinator.Claim claim = syncCoordinator.tryClaim(
                symbol, interval, bootstrapRequired ? 0L : getProviderCooldownSeconds(interval), syncLeaseSeconds);
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
                    bars = twelveDataService.getTimeSeries(symbol, timeframe, outputSize);
                    if (bars.isEmpty()) {
                        throw new IllegalStateException("Twelve Data returned no candles.");
                    }
                    source = CandleSource.TWELVE_DATA;
                } catch (Exception e) {
                    twelveDataFailure = failureMessage(e);
                    log.warn("Twelve Data candle sync unavailable for {} {}: {}. Trying Yahoo Finance.",
                            symbol, interval, twelveDataFailure);
                    try {
                        bars = yahooFinanceService.getTimeSeries(symbol, interval, outputSize);
                        if (bars.isEmpty()) {
                            throw new IllegalStateException("Yahoo Finance returned no candles.");
                        }
                        source = CandleSource.YAHOO_FINANCE;
                    } catch (Exception yahooFailure) {
                        String failure = "Twelve Data: " + twelveDataFailure
                                + "; Yahoo Finance: " + yahooFailure.getMessage();
                        log.error("Failed syncing candles for {} {} from all providers: {}", symbol, interval, failure);
                        rememberFailedSync(syncKey, failure);
                        return new CandleSyncResult(CandleSource.NONE, 0, failure);
                    }
                }

                ensureAsset(symbol);
                long persistenceStarted = System.nanoTime();
                int persistedCandles = persistChangedCandles(symbol, interval, bars);
                long persistenceMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - persistenceStarted);
                syncCoordinator.markSuccessful(claim);
                recentFailedSyncs.remove(syncKey);
                successful = true;
                if (persistedCandles > 0 && eventPublisher != null) {
                    eventPublisher.publishEvent(new CandleDataChangedEvent(symbol, interval, persistedCandles));
                }
                System.out.println("Fetched " + bars.size() + " " + interval + " candles for " + symbol + " from "
                        + source + "; persisted " + persistedCandles + " new or changed candles.");
                log.info("Market data sync timing symbol={} interval={} requested={} returned={} source={} persisted={} persistenceMs={} totalMs={}",
                        symbol, interval, outputSize, bars.size(), source, persistedCandles,
                        persistenceMillis, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - syncStarted));
                return new CandleSyncResult(source, persistedCandles, null);
        } finally {
            if (!successful) {
                syncCoordinator.release(claim);
            }
        }
    }

    public CandlePage loadCandlePage(String rawSymbol, String rawInterval, Long beforeTimestamp, int requestedLimit) {
        String symbol = SecurityInputValidator.requireMarketSymbol(rawSymbol);
        String interval = SecurityInputValidator.requireInterval(rawInterval);
        Long before = SecurityInputValidator.requireBeforeTimestamp(beforeTimestamp);
        int limit = Math.max(1, Math.min(1_000, requestedLimit));

        CandleSyncResult syncResult;
        if (before == null) {
            syncResult = syncCandles(symbol, interval, null);
        } else {
            List<Candle> cached = queryDescending(symbol, interval, before, limit + 1);
            if (cached.size() <= limit && !historyStateStore.isEndReached(symbol, interval)) {
                long providerCursor = cached.isEmpty()
                        ? before
                        : cached.getLast().getTimestamp();
                syncResult = syncHistoricalCandles(symbol, interval, providerCursor, limit);
            } else {
                syncResult = new CandleSyncResult(CandleSource.CACHE, 0, null);
            }
        }

        List<Candle> descending = queryDescending(symbol, interval, before, limit + 1);
        boolean moreCached = descending.size() > limit;
        List<Candle> page = new ArrayList<>(descending.subList(0, Math.min(limit, descending.size())));
        Collections.reverse(page);
        boolean endReached = historyStateStore.isEndReached(symbol, interval);
        boolean hasMore = moreCached || !endReached && (before != null || !page.isEmpty());
        Long nextCursor = page.isEmpty() ? null : page.getFirst().getTimestamp();
        return new CandlePage(List.copyOf(page), nextCursor, hasMore,
                syncResult.source(), syncResult.failureMessage());
    }

    private List<Candle> queryDescending(String symbol, String interval, Long before, int limit) {
        PageRequest page = PageRequest.of(0, limit);
        List<Candle> candles = before == null
                ? candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(symbol, interval, page)
                : candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                        symbol, interval, before, page);
        return candles.stream()
                .filter(this::hasValidTimestamp)
                .toList();
    }

    private CandleSyncResult syncHistoricalCandles(String symbol,
                                                   String interval,
                                                   long beforeExclusive,
                                                   int outputSize) {
        String coordinationInterval = interval + "-history";
        MarketDataSyncCoordinator.Claim claim = syncCoordinator.tryClaim(
                symbol, coordinationInterval, 0L, syncLeaseSeconds);
        if (!claim.acquired()) {
            CandleSource source = claim.status() == MarketDataSyncCoordinator.ClaimStatus.IN_PROGRESS
                    ? CandleSource.CACHE_REFRESH_IN_PROGRESS
                    : CandleSource.CACHE;
            return new CandleSyncResult(source, 0, null);
        }

        boolean successful = false;
        try {
            List<MarketDataBar> twelveDataBars = null;
            List<MarketDataBar> yahooBars = null;
            String twelveDataFailure = null;
            String yahooFailure = null;
            try {
                twelveDataBars = historicalBarsBefore(twelveDataService.getTimeSeriesBefore(
                        symbol, toTwelveDataInterval(interval), outputSize, beforeExclusive), beforeExclusive);
            } catch (Exception twelveDataError) {
                twelveDataFailure = twelveDataError.getMessage();
            }

            if (twelveDataBars == null || twelveDataBars.size() < outputSize) {
                try {
                    yahooBars = historicalBarsBefore(yahooFinanceService.getTimeSeriesBefore(
                            symbol, interval, outputSize, beforeExclusive), beforeExclusive);
                } catch (Exception yahooError) {
                    yahooFailure = yahooError.getMessage();
                }
            }

            if (twelveDataBars == null && yahooBars == null) {
                String failure = "Twelve Data: " + twelveDataFailure
                        + "; Yahoo Finance: " + yahooFailure;
                return new CandleSyncResult(CandleSource.NONE, 0, failure);
            }
            if (twelveDataBars != null && twelveDataBars.isEmpty() && yahooBars == null) {
                String failure = "Twelve Data returned no historical candles; Yahoo Finance: " + yahooFailure;
                return new CandleSyncResult(CandleSource.NONE, 0, failure);
            }

            boolean useYahoo = yahooBars != null
                    && (twelveDataBars == null || twelveDataBars.isEmpty()
                    || yahooBars.size() > twelveDataBars.size());
            List<MarketDataBar> historicalBars = useYahoo ? yahooBars : twelveDataBars;
            CandleSource source = useYahoo ? CandleSource.YAHOO_FINANCE : CandleSource.TWELVE_DATA;
            long oldestTimestamp = historicalBars.stream()
                    .mapToLong(MarketDataBar::timestamp)
                    .min()
                    .orElse(beforeExclusive);
            boolean endReached = historicalBars.size() < outputSize && yahooBars != null;
            historyStateStore.recordProgress(symbol, interval, oldestTimestamp, endReached);

            ensureAsset(symbol);
            int persisted = persistChangedCandles(symbol, interval, historicalBars);
            syncCoordinator.markSuccessful(claim);
            successful = true;
            return new CandleSyncResult(source, persisted, null);
        } finally {
            if (!successful) {
                syncCoordinator.release(claim);
            }
        }
    }

    private List<MarketDataBar> historicalBarsBefore(List<MarketDataBar> bars, long beforeExclusive) {
        Map<Long, MarketDataBar> uniqueByTimestamp = new LinkedHashMap<>();
        if (bars != null) {
            bars.stream()
                    .filter(this::hasValidTimestamp)
                    .filter(bar -> bar.timestamp() < beforeExclusive)
                    .forEach(bar -> uniqueByTimestamp.put(bar.timestamp(), bar));
        }
        return List.copyOf(uniqueByTimestamp.values());
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

    private String failureMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").trim();
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
        List<MarketDataBar> validBars = bars.stream()
                .filter(this::hasValidTimestamp)
                .toList();
        if (validBars.isEmpty()) {
            return 0;
        }
        List<Long> timestamps = validBars.stream()
                .map(MarketDataBar::timestamp)
                .distinct()
                .toList();
        Map<Long, Candle> existingByTimestamp = new LinkedHashMap<>();
        candleRepository.findBySymbolAndTimeIntervalAndTimestampIn(symbol, interval, timestamps)
                .forEach(candle -> existingByTimestamp.put(candle.getTimestamp(), candle));

        Map<Long, Candle> changedByTimestamp = new LinkedHashMap<>();
        for (MarketDataBar bar : validBars) {
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
            List<Candle> changed = List.copyOf(changedByTimestamp.values());
            if (candleBatchStore == null) candleRepository.saveAll(changed);
            else candleBatchStore.upsert(changed);
        }
        return changedByTimestamp.size();
    }

    private boolean hasValidTimestamp(MarketDataBar bar) {
        return bar != null && bar.timestamp() > 0L;
    }

    private boolean hasValidTimestamp(Candle candle) {
        return candle != null && candle.getTimestamp() != null && candle.getTimestamp() > 0L;
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

    private CandleSyncResult recentFailedSync(SyncKey key) {
        FailedSync failure = recentFailedSyncs.get(key);
        if (failure == null) {
            return null;
        }
        if (System.nanoTime() < failure.retryAfterNanos()) {
            return new CandleSyncResult(CandleSource.NONE, 0, failure.message());
        }
        recentFailedSyncs.remove(key, failure);
        return null;
    }

    private void rememberFailedSync(SyncKey key, String failureMessage) {
        recentFailedSyncs.put(key, new FailedSync(
                failureMessage,
                System.nanoTime() + FAILED_SYNC_COOLDOWN_NANOS));
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

    private record SyncKey(String symbol, String interval) {
    }

    private record FailedSync(String message, long retryAfterNanos) {
    }

    public record CandlePage(List<Candle> candles,
                             Long nextCursor,
                             boolean hasMore,
                             CandleSource source,
                             String failureMessage) {
    }
}
