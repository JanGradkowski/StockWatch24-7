package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.CandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Elliott Wave inputs exactly as the built-in Elliott alerts prepare them, so every check that reads wave
 * counts (built-in follows and custom alerts) sees the same count: the parent candles with their integrity
 * context, and the next-lower timeframe that validates each leg's subdivisions (60-minute candles for a
 * daily count, daily for weekly, weekly for monthly).
 */
public final class ElliottAlertInputs {
    private static final Logger log = LoggerFactory.getLogger(ElliottAlertInputs.class);
    /** Child candles kept per symbol for subdivision checks. */
    public static final int LOWER_DEGREE_CACHE_CANDLES = 1_000;

    private ElliottAlertInputs() { }

    /** The parent candles a wave count reads: the latest completed candles, enriched, in their valid context. */
    public static List<EnrichedCandle> parents(TechnicalIndicatorEnrichmentService enrichment, List<Candle> completed,
                                               TimeInterval interval) {
        return SignalInputPreparation.elliottInput(enrichment, completed, interval);
    }

    /** The provider interval of the next-lower timeframe, or null when a count has none. */
    public static String childApiInterval(TimeInterval parent) {
        return switch (parent) {
            case DAILY -> "60min";
            case WEEKLY -> "1d";
            case MONTHLY -> "1wk";
            default -> null;
        };
    }

    private static TimeInterval childInterval(TimeInterval parent) {
        return switch (parent) {
            case DAILY -> TimeInterval.ONE_HOUR;
            case WEEKLY -> TimeInterval.DAILY;
            default -> TimeInterval.WEEKLY;
        };
    }

    /**
     * The next-lower timeframe's candles from the first parent candle on, enriched for subdivision checks.
     * {@code marketData} refreshes a stale child cache first (scheduled checks); null reads stored candles only
     * (previews and background replays must not spend provider requests). Candles at or after
     * {@code asOfExclusive} are left out, so a count never reads a child bar from after its own parent candle.
     * Empty when no usable child candles exist: the count then validates subdivisions on the parent series.
     */
    public static List<EnrichedCandle> lowerDegree(CandleRepository candles, MarketDataService marketData,
                                                   TechnicalIndicatorEnrichmentService enrichment, String symbol,
                                                   TimeInterval parentInterval, List<EnrichedCandle> parents,
                                                   Instant scheduledFor, long asOfExclusive) {
        String childApiInterval = childApiInterval(parentInterval);
        if (parents.isEmpty() || childApiInterval == null) return List.of();
        if (marketData != null) refreshLowerDegree(candles, marketData, symbol, parentInterval, scheduledFor);
        long firstParentTimestamp = parents.getFirst().timestamp();
        List<Candle> stored = candles.findBySymbolAndTimeIntervalAndTimestampGreaterThanEqualOrderByTimestampAsc(
                symbol, childApiInterval, firstParentTimestamp);
        if (stored == null || stored.isEmpty()) return List.of();
        long limit = Math.min(asOfExclusive, scheduledFor == null ? Long.MAX_VALUE : scheduledFor.getEpochSecond());
        List<Candle> childCandles = stored.stream()
                .filter(candle -> candle.getTimestamp() < limit)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        long firstParentPeriodEnd = parents.size() > 1 ? parents.get(1).timestamp() : Long.MAX_VALUE;
        if (childCandles.isEmpty() || childCandles.getFirst().getTimestamp() >= firstParentPeriodEnd) {
            return List.of();
        }
        try {
            return enrichment.enrichForElliott(childCandles, childCandles.size(), childInterval(parentInterval));
        } catch (RuntimeException exception) {
            AnalysisWork.rethrowInfrastructure(exception);
            // Child data improves structural validation, but a bad optional child
            // cache must not abort every alert family for the parent symbol.
            log.warn("Lower-degree Elliott enrichment failed for " + symbol + " "
                    + childApiInterval + ": " + exception.getMessage()
                    + ". Falling back to parent-series subdivision validation.");
            return List.of();
        }
    }

    /**
     * Refreshes a stale child cache, as a scheduled check does before reading it. Callers that fingerprint the
     * child candles (the shared analysis store) refresh first, so the fingerprint describes what the count reads.
     */
    public static void refreshLowerDegree(CandleRepository candles, MarketDataService marketData, String symbol,
                                          TimeInterval parentInterval, Instant scheduledFor) {
        String childApiInterval = childApiInterval(parentInterval);
        if (marketData == null || childApiInterval == null
                || hasFreshLowerDegreeCache(candles, symbol, childApiInterval, scheduledFor)) return;
        try {
            // Bootstrap a persisted child-series cache once. Later scans request only
            // a small latest-candle delta and share the normal provider cooldown.
            MarketDataService.CandleSyncResult sync = marketData.syncCandlesForAnalysis(
                    symbol, childApiInterval, LOWER_DEGREE_CACHE_CANDLES);
            if (sync != null && !sync.successful()
                    && sync.source() != MarketDataService.CandleSource.CACHE_REFRESH_IN_PROGRESS) {
                log.warn("Lower-degree Elliott refresh failed for " + symbol + " "
                        + childApiInterval + ": " + sync.failureMessage());
            }
        } catch (RuntimeException exception) {
            AnalysisWork.rethrowInfrastructure(exception);
            log.warn("Lower-degree Elliott refresh failed for " + symbol + " "
                    + childApiInterval + ": " + exception.getMessage());
        }
    }

    private static boolean hasFreshLowerDegreeCache(CandleRepository candles, String symbol, String childApiInterval,
                                                    Instant scheduledFor) {
        if (scheduledFor == null
                || candles.countBySymbolAndTimeInterval(symbol, childApiInterval) < LOWER_DEGREE_CACHE_CANDLES) {
            return false;
        }
        List<Candle> latest = candles.findTop1BySymbolAndTimeIntervalOrderByTimestampDesc(symbol, childApiInterval);
        if (latest == null || latest.isEmpty()) return false;
        long toleratedAgeSeconds = switch (childApiInterval) {
            case "60min" -> Duration.ofDays(2).toSeconds();
            case "1d" -> Duration.ofDays(4).toSeconds();
            case "1wk" -> Duration.ofDays(14).toSeconds();
            default -> 0L;
        };
        return latest.getFirst().getTimestamp() >= scheduledFor.getEpochSecond() - toleratedAgeSeconds;
    }
}
