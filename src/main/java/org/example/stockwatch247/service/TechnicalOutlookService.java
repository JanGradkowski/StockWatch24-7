package org.example.stockwatch247.service;

import org.example.stockwatch247.market.MarketIndexCatalog;
import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.CongressionalTradeDelivery;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.InsiderTradeDelivery;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.technical.TechnicalResearchSnapshot;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.CongressionalTradeDeliveryRepository;
import org.example.stockwatch247.repository.InsiderTradeDeliveryRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.ToDoubleFunction;

@Service
public class TechnicalOutlookService {
    private static final Logger log = LoggerFactory.getLogger(TechnicalOutlookService.class);
    private static final int CHART_CANDLES = 260;
    private static final int ANALYSIS_CANDLES = 320;
    private static final int INDICATOR_SERIES_POINTS = 160;
    private static final int OUTLOOK_CACHE_SIZE = 500;
    private static final long OUTLOOK_CACHE_TTL_SECONDS = 300;
    private static final int MARKET_HISTORY_CANDLES = 420;

    private final MarketDataService marketDataService;
    private final CandleRepository candleRepository;
    private final StockAssetRepository stockAssetRepository;
    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final AlertEventRepository alertEventRepository;
    private final CongressionalTradeDeliveryRepository congressionalDeliveryRepository;
    private final InsiderTradeDeliveryRepository insiderDeliveryRepository;
    private final AnalysisPreferencesService preferencesService;
    private final BoundedTtlCache<OutlookCacheKey, OutlookView> outlookCache =
            new BoundedTtlCache<>(OUTLOOK_CACHE_SIZE, OUTLOOK_CACHE_TTL_SECONDS);
    private final BoundedTtlCache<MarketCacheKey, MarketComparisonView> marketCache =
            new BoundedTtlCache<>(OUTLOOK_CACHE_SIZE, OUTLOOK_CACHE_TTL_SECONDS);
    private final Set<RefreshKey> refreshesInFlight = ConcurrentHashMap.newKeySet();
    private final Map<RefreshKey, RefreshStatusView> refreshStatuses = new ConcurrentHashMap<>();
    private Executor technicalOutlookExecutor;

    @Autowired
    public TechnicalOutlookService(MarketDataService marketDataService,
                                   CandleRepository candleRepository,
                                   StockAssetRepository stockAssetRepository,
                                   TechnicalIndicatorEnrichmentService enrichmentService,
                                   AlertEventRepository alertEventRepository,
                                   CongressionalTradeDeliveryRepository congressionalDeliveryRepository,
                                   InsiderTradeDeliveryRepository insiderDeliveryRepository,
                                   AnalysisPreferencesService preferencesService) {
        this.marketDataService = marketDataService;
        this.candleRepository = candleRepository;
        this.stockAssetRepository = stockAssetRepository;
        this.enrichmentService = enrichmentService;
        this.alertEventRepository = alertEventRepository;
        this.congressionalDeliveryRepository = congressionalDeliveryRepository;
        this.insiderDeliveryRepository = insiderDeliveryRepository;
        this.preferencesService = preferencesService;
    }

    TechnicalOutlookService(MarketDataService marketDataService,
                            CandleRepository candleRepository,
                            StockAssetRepository stockAssetRepository,
                            TechnicalIndicatorEnrichmentService enrichmentService,
                            AlertEventRepository alertEventRepository,
                            CongressionalTradeDeliveryRepository congressionalDeliveryRepository,
                            InsiderTradeDeliveryRepository insiderDeliveryRepository) {
        this(marketDataService, candleRepository, stockAssetRepository, enrichmentService,
                alertEventRepository, congressionalDeliveryRepository, insiderDeliveryRepository, null);
    }

    @Autowired(required = false)
    void configureTechnicalOutlookExecutor(
            @Qualifier("technicalOutlookExecutor") Executor technicalOutlookExecutor) {
        this.technicalOutlookExecutor = technicalOutlookExecutor;
    }

    public OutlookView getOutlook(User user, String symbol, String rawInterval) {
        return buildOutlook(user, symbol, rawInterval, true);
    }

    public OutlookView getSummaryOutlook(User user, String symbol, String rawInterval) {
        return buildOutlook(user, symbol, rawInterval, false);
    }

    /**
     * Loads one older chart page and enriches it with the active user profile.
     * The extra candles requested before the visible page are indicator warm-up
     * input only, so every returned point continues the existing overlays
     * instead of restarting EMA, SMA, Bollinger, and VWAP at the page boundary.
     */
    public HistoricalChartPageView getHistoricalChartPage(User user,
                                                           String rawSymbol,
                                                           String rawInterval,
                                                           long beforeTimestamp,
                                                           int requestedLimit) {
        IntervalDefinition interval = IntervalDefinition.parse(rawInterval);
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(1_000, requestedLimit));
        MarketDataService.CandlePage page = marketDataService.loadCandlePage(
                symbol, interval.apiValue(), beforeTimestamp, limit);
        if (page.candles().isEmpty()) {
            return new HistoricalChartPageView(
                    List.of(), page.nextCursor(), page.hasMore(), page.source(), page.failureMessage());
        }

        AnalysisPreferencesService.PreferencesView preferences = preferencesService == null
                ? AnalysisPreferencesService.factoryPreferences()
                : preferencesService.get(user);
        TechnicalIndicatorProfile profile = preferencesService == null
                ? TechnicalIndicatorProfile.forInterval(interval.timeInterval())
                : preferencesService.technicalProfile(preferences.profile(interval.timeInterval()));
        int pageSize = page.candles().size();
        int requiredInput = enrichmentService.requiredInputCandles(pageSize, profile);
        List<Candle> descending = new ArrayList<>(
                candleRepository.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                        symbol, interval.apiValue(), beforeTimestamp,
                        PageRequest.of(0, Math.max(pageSize, requiredInput))));
        Collections.reverse(descending);
        List<EnrichedCandle> enriched = enrichmentService.analyze(
                descending, pageSize, profile, false).candles();
        List<ChartCandleView> chartCandles = enriched.stream()
                .map(candle -> chartCandle(candle, false))
                .toList();
        return new HistoricalChartPageView(
                chartCandles, page.nextCursor(), page.hasMore(), page.source(), page.failureMessage());
    }

    private OutlookView buildOutlook(User user,
                                     String symbol,
                                     String rawInterval,
                                     boolean detailed) {
        long totalStarted = System.nanoTime();
        IntervalDefinition interval = IntervalDefinition.parse(rawInterval);
        AnalysisPreferencesService.PreferencesView preferences = preferencesService == null
                ? AnalysisPreferencesService.factoryPreferences()
                : preferencesService.get(user);
        AnalysisPreferencesService.IntervalProfile rules = preferences.profile(interval.timeInterval());
        TechnicalIndicatorProfile technicalProfile = preferencesService == null
                ? TechnicalIndicatorProfile.forInterval(interval.timeInterval())
                : preferencesService.technicalProfile(rules);
        String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
        OutlookCacheKey cacheKey = new OutlookCacheKey(
                userKey(user), normalizedSymbol, interval.apiValue(), rules, detailed);
        long now = Instant.now().getEpochSecond();
        OutlookView cached = outlookCache.get(cacheKey, now);
        if (cached != null) {
            log.debug("Technical outlook cache hit symbol={} interval={} detail={} totalMs={}",
                    normalizedSymbol, interval.apiValue(), detailed, elapsedMillis(totalStarted));
            return cached;
        }

        long candlesStarted = System.nanoTime();
        int requiredInput = enrichmentService.requiredInputCandles(ANALYSIS_CANDLES, technicalProfile);
        List<Candle> rawCandles = latestCandles(normalizedSymbol, interval.apiValue(), requiredInput);
        long candlesMs = elapsedMillis(candlesStarted);
        if (rawCandles.isEmpty()) {
            return OutlookView.unavailable(normalizedSymbol, interval);
        }

        long analysisStarted = System.nanoTime();
        TechnicalIndicatorEnrichmentService.AnalysisResult analysis = enrichmentService.analyze(
                rawCandles,
                Math.min(ANALYSIS_CANDLES, rawCandles.size()),
                technicalProfile,
                detailed);
        long analysisMs = elapsedMillis(analysisStarted);
        List<EnrichedCandle> candles = analysis.candles();
        if (candles.isEmpty()) {
            return OutlookView.unavailable(normalizedSymbol, interval);
        }

        StockAsset asset = stockAssetRepository.findByTickerSymbolIgnoreCase(normalizedSymbol).orElse(null);
        long relatedStarted = System.nanoTime();
        MarketComparisonView market = marketComparison(
                normalizedSymbol, asset, rules.marketRelativeThresholdPercent(), false);
        List<RecentSignalView> recentSignals = recentSignals(user, normalizedSymbol, interval.timeInterval());
        long relatedMs = elapsedMillis(relatedStarted);
        long viewsStarted = System.nanoTime();
        List<IndicatorView> indicators = indicatorViews(candles, analysis.research(), rules);

        List<VoteInput> rawVotes = new ArrayList<>();
        indicators.stream()
                .filter(IndicatorView::scored)
                .forEach(indicator -> rawVotes.add(new VoteInput(
                        indicator.label(), indicator.category(), indicator.vote())));
        if (market.available() && rules.scoreMarketRelative()) {
            rawVotes.add(new VoteInput("Market-relative strength", "MARKET_RELATIVE", market.vote()));
        }
        if (rules.scoreCandlestickSignals()) {
            signalVote(recentSignals, AlertPatternFamily.CANDLESTICK)
                    .ifPresent(vote -> rawVotes.add(new VoteInput("Recent candlestick signals", "CANDLESTICK", vote)));
        }
        if (rules.scoreElliottSignals()) {
            signalVote(recentSignals, AlertPatternFamily.ELLIOTT_WAVE)
                    .ifPresent(vote -> rawVotes.add(new VoteInput("Recent Elliott signals", "ELLIOTT", vote)));
        }

        ScoreView rawScore = score(rawVotes.stream().map(VoteInput::vote).toList(), rules);
        List<CategoryView> categories = categoryViews(rawVotes, rules);
        ScoreView categoryScore = score(categories.stream().map(CategoryView::vote).toList(), rules);
        ScoreView headlineScore = rules.categoryBalancedHeadline() ? categoryScore : rawScore;
        List<IndicatorView> responseIndicators = detailed ? indicators : indicators.stream()
                .map(TechnicalOutlookService::withoutSeries)
                .toList();
        List<ChartCandleView> chart = candles.stream()
                .skip(Math.max(0, candles.size() - CHART_CANDLES))
                .map(candle -> chartCandle(candle, detailed))
                .toList();
        List<ActivityMarkerView> activity = activityMarkers(user, normalizedSymbol, chart);
        EnrichedCandle latest = candles.getLast();

        OutlookView outlook = new OutlookView(
                normalizedSymbol,
                asset == null ? normalizedSymbol : asset.getCompanyName(),
                interval.apiValue(),
                interval.label(),
                preferences.profileLabel(),
                true,
                latest.timestamp(),
                freshness(latest.timestamp(), interval),
                headlineScore,
                rawScore,
                categories,
                responseIndicators,
                chart,
                recentSignals,
                activity,
                market,
                IndicatorSettingsView.from(rules),
                new MethodologyView(
                        "+1 buy, 0 neutral, -1 sell; unavailable inputs are excluded.",
                        "Each category receives one equal vote after its available inputs are averaged.",
                        "Neutral: |score| < %.0f%%; slight until %.0f%%; moderate until %.0f%%; strong thereafter."
                                .formatted(rules.neutralScorePercent(), rules.moderateScorePercent(), rules.strongScorePercent()),
                        "These are symmetric descriptive rules, not probabilities or investment advice."));
        outlookCache.put(cacheKey, outlook, now);
        long viewsMs = elapsedMillis(viewsStarted);
        long totalMs = elapsedMillis(totalStarted);
        if (totalMs >= 500) {
            log.info("Technical outlook built symbol={} interval={} detail={} rawCandles={} totalMs={} candlesMs={} analysisMs={} relatedDataMs={} viewsMs={}",
                    normalizedSymbol, interval.apiValue(), detailed, rawCandles.size(), totalMs,
                    candlesMs, analysisMs, relatedMs, viewsMs);
        } else {
            log.debug("Technical outlook built symbol={} interval={} detail={} totalMs={}",
                    normalizedSymbol, interval.apiValue(), detailed, totalMs);
        }
        return outlook;
    }

    public ScoreReportView getScoreReport(User user, String symbol, String rawInterval) {
        OutlookView outlook = getOutlook(user, symbol, rawInterval);
        return new ScoreReportView(outlook.available(), outlook.rawScore(), outlook.indicators(),
                outlook.recentSignals(), outlook.methodology());
    }

    public MarketComparisonView getMarketReport(User user, String symbol, String rawInterval) {
        IntervalDefinition interval = IntervalDefinition.parse(rawInterval);
        AnalysisPreferencesService.PreferencesView preferences = preferencesService == null
                ? AnalysisPreferencesService.factoryPreferences()
                : preferencesService.get(user);
        AnalysisPreferencesService.IntervalProfile rules = preferences.profile(interval.timeInterval());
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        StockAsset asset = stockAssetRepository.findByTickerSymbolIgnoreCase(normalized).orElse(null);
        return marketComparison(normalized, asset, rules.marketRelativeThresholdPercent(), true);
    }

    /**
     * Queues provider refreshes outside the HTTP request that builds the page.
     * The request itself always renders from completed candles already stored
     * locally; a later request sees any newly persisted data.
     */
    public void requestBackgroundRefresh(String rawSymbol, String rawInterval) {
        IntervalDefinition interval = IntervalDefinition.parse(rawInterval);
        int required = enrichmentService.requiredInputCandles(ANALYSIS_CANDLES, interval.timeInterval());
        requestBackgroundRefresh(null, rawSymbol, interval, required);
    }

    public void requestBackgroundRefresh(User user, String rawSymbol, String rawInterval) {
        IntervalDefinition interval = IntervalDefinition.parse(rawInterval);
        AnalysisPreferencesService.PreferencesView preferences = preferencesService == null
                ? AnalysisPreferencesService.factoryPreferences()
                : preferencesService.get(user);
        TechnicalIndicatorProfile profile = preferencesService == null
                ? TechnicalIndicatorProfile.forInterval(interval.timeInterval())
                : preferencesService.technicalProfile(preferences.profile(interval.timeInterval()));
        int required = enrichmentService.requiredInputCandles(ANALYSIS_CANDLES, profile);
        requestBackgroundRefresh(user, rawSymbol, interval, required);
    }

    private void requestBackgroundRefresh(User user,
                                          String rawSymbol,
                                          IntervalDefinition interval,
                                          int requiredCandles) {
        if (technicalOutlookExecutor == null) return;
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        RefreshKey refreshKey = new RefreshKey(symbol, interval.apiValue());
        if (!refreshesInFlight.add(refreshKey)) return;
        refreshStatuses.put(refreshKey, RefreshStatusView.queuedStatus());
        try {
            technicalOutlookExecutor.execute(() -> {
                long started = System.nanoTime();
                refreshStatuses.put(refreshKey, RefreshStatusView.runningStatus());
                try {
                    boolean changed = refresh(symbol, interval.apiValue(), requiredCandles);
                    if (changed) invalidate(symbol, interval.apiValue());
                    if (user != null) {
                        // Precompute the compact response while data is hot so
                        // interval buttons usually become cache-only reads.
                        buildOutlook(user, symbol, interval.apiValue(), false);
                    }
                    refreshStatuses.put(refreshKey, RefreshStatusView.readyStatus(elapsedMillis(started)));
                } catch (RuntimeException exception) {
                    log.warn("Technical outlook refresh failed symbol={} interval={}: {}",
                            symbol, interval.apiValue(), exception.getMessage());
                    refreshStatuses.put(refreshKey, RefreshStatusView.failedStatus(elapsedMillis(started)));
                } finally {
                    refreshesInFlight.remove(refreshKey);
                }
            });
            requestBenchmarkRefresh(symbol);
        } catch (RejectedExecutionException exception) {
            refreshesInFlight.remove(refreshKey);
            refreshStatuses.put(refreshKey, RefreshStatusView.failedStatus(0));
        }
    }

    private void requestBenchmarkRefresh(String symbol) {
        StockAsset asset = stockAssetRepository.findByTickerSymbolIgnoreCase(symbol).orElse(null);
        Benchmark benchmark = benchmarkFor(asset);
        if (benchmark.symbol().equalsIgnoreCase(symbol)) return;
        RefreshKey key = new RefreshKey(benchmark.symbol(), "1d");
        if (!refreshesInFlight.add(key)) return;
        try {
            technicalOutlookExecutor.execute(() -> {
                try {
                    if (refresh(benchmark.symbol(), "1d", MARKET_HISTORY_CANDLES)) {
                        outlookCache.removeIf(cacheKey -> cacheKey.symbol().equals(symbol));
                    }
                } catch (RuntimeException exception) {
                    log.warn("Technical outlook benchmark refresh failed benchmark={}: {}",
                            benchmark.symbol(), exception.getMessage());
                } finally {
                    refreshesInFlight.remove(key);
                }
            });
        } catch (RejectedExecutionException exception) {
            refreshesInFlight.remove(key);
        }
    }

    public RefreshStatusView refreshStatus(String rawSymbol, String rawInterval) {
        IntervalDefinition interval = IntervalDefinition.parse(rawInterval);
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        RefreshKey key = new RefreshKey(symbol, interval.apiValue());
        RefreshStatusView status = refreshStatuses.get(key);
        if (status != null) return status;
        boolean available = !candleRepository.findTop1BySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, interval.apiValue()).isEmpty();
        return available ? RefreshStatusView.readyStatus(0) : RefreshStatusView.idleStatus();
    }

    public void invalidate(User user, String rawSymbol, String rawInterval) {
        IntervalDefinition interval = IntervalDefinition.parse(rawInterval);
        long userKey = userKey(user);
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        outlookCache.removeIf(key -> key.userKey() == userKey
                && key.symbol().equals(symbol)
                && key.interval().equals(interval.apiValue()));
    }

    @EventListener
    public void candleDataChanged(CandleDataChangedEvent event) {
        if (event == null) return;
        String symbol = event.symbol().toUpperCase(Locale.ROOT);
        invalidate(symbol, event.interval());
        marketCache.removeIf(key -> key.symbol().equals(symbol));
        if ("1d".equals(event.interval()) && MarketIndexCatalog.findBySymbol(symbol).isPresent()) {
            // A benchmark update can affect many stock outlooks, so discard the
            // small bounded cache instead of serving stale relative scores.
            outlookCache.removeIf(key -> true);
            marketCache.removeIf(key -> true);
        }
    }

    private void invalidate(String symbol, String interval) {
        outlookCache.removeIf(key -> key.symbol().equals(symbol) && key.interval().equals(interval));
    }

    private boolean refresh(String symbol, String interval, int requiredCandles) {
        return marketDataService.syncCandlesForAnalysis(symbol, interval, requiredCandles).candlesSynced() > 0;
    }

    private long userKey(User user) {
        if (user == null) return 0L;
        return user.getId() == null ? -System.identityHashCode(user) : user.getId();
    }

    private List<IndicatorView> indicatorViews(List<EnrichedCandle> candles,
                                               List<TechnicalResearchSnapshot> research,
                                               AnalysisPreferencesService.IntervalProfile rules) {
        List<IndicatorView> views = new ArrayList<>();
        String rsiLabel = "RSI " + rules.rsiPeriod();
        String fastEmaLabel = "EMA " + rules.fastEmaPeriod();
        String slowEmaLabel = "EMA " + rules.slowEmaPeriod();
        String emaPairLabel = fastEmaLabel + " / " + slowEmaLabel;
        String longSmaLabel = "SMA " + rules.longSmaPeriod();
        String macdLabel = "MACD %d/%d/%d".formatted(
                rules.macdFastPeriod(), rules.macdSlowPeriod(), rules.macdSignalPeriod());
        String cciLabel = "CCI " + rules.cciPeriod();
        String bollingerLabel = "Bollinger Bands %d, %.1fσ".formatted(
                rules.bollingerPeriod(), rules.bollingerDeviation());
        String atrLabel = "ATR " + rules.atrPeriod();
        String vwapLabel = "Rolling VWAP " + rules.vwapPeriod();
        String relativeVolumeLabel = "Relative volume " + rules.volumePeriod();
        String volumeProfileLabel = "Volume profile " + rules.volumeProfilePeriod();

        views.add(indicator(candles, "rsi", rsiLabel, "MOMENTUM", "index points", false,
                EnrichedCandle::rsi,
                (candle, value) -> value <= rules.rsiBuyThreshold() ? 1
                        : value >= rules.rsiSellThreshold() ? -1 : 0,
                value -> value <= rules.rsiBuyThreshold()
                        ? rsiLabel + " is inside your configured buy region."
                        : value >= rules.rsiSellThreshold() ? rsiLabel + " is inside your configured sell region."
                        : rsiLabel + " is between the oversold and overbought thresholds.",
                "Buy when %s <= %.1f; sell when %s >= %.1f; otherwise neutral."
                        .formatted(rsiLabel, rules.rsiBuyThreshold(), rsiLabel, rules.rsiSellThreshold()),
                List.of(rules.rsiBuyThreshold(), 50.0, rules.rsiSellThreshold())));
        views.add(indicator(candles, "ema", emaPairLabel, "TREND", "% difference", true,
                candle -> percentDifference(candle.fastEma(), candle.slowEma()),
                (candle, value) -> value > rules.emaThresholdPercent() ? 1
                        : value < -rules.emaThresholdPercent() ? -1 : 0,
                value -> value > rules.emaThresholdPercent() ? fastEmaLabel + " is clearly above " + slowEmaLabel + "."
                        : value < -rules.emaThresholdPercent() ? fastEmaLabel + " is clearly below " + slowEmaLabel + "."
                        : emaPairLabel + " are within the neutral tolerance band.",
                "Buy when %s is >+%.2f%% above %s; sell below -%.2f%%; otherwise neutral."
                        .formatted(fastEmaLabel, rules.emaThresholdPercent(), slowEmaLabel, rules.emaThresholdPercent()),
                List.of(0.0)));
        views.add(indicator(candles, "sma", "Price vs " + longSmaLabel, "TREND", "%", true,
                candle -> percentDifference(candle.close(), candle.longSma()),
                (candle, value) -> value > rules.longSmaThresholdPercent() ? 1
                        : value < -rules.longSmaThresholdPercent() ? -1 : 0,
                value -> value > rules.longSmaThresholdPercent() ? "Price is trading above its long-term average."
                        : value < -rules.longSmaThresholdPercent() ? "Price is trading below its long-term average."
                        : "Price is close to its long-term average.",
                "Buy above +%.2f%% from %s; sell below -%.2f%%; otherwise neutral."
                        .formatted(rules.longSmaThresholdPercent(), longSmaLabel, rules.longSmaThresholdPercent()),
                List.of(0.0)));
        views.add(indicator(candles, "macd", macdLabel + " histogram", "TREND", "% of price", false,
                candle -> candle.close() == 0 ? Double.NaN : candle.macdHistogram() / candle.close() * 100.0,
                (candle, value) -> value > rules.macdThresholdPercent() ? 1
                        : value < -rules.macdThresholdPercent() ? -1 : 0,
                value -> value > rules.macdThresholdPercent() ? macdLabel + " momentum is positive."
                        : value < -rules.macdThresholdPercent() ? macdLabel + " momentum is negative."
                        : macdLabel + " is close to its signal line.",
                "%s buys above +%.3f%% of price and sells below -%.3f%%; otherwise neutral."
                        .formatted(macdLabel, rules.macdThresholdPercent(), rules.macdThresholdPercent()),
                List.of(0.0)));
        views.add(indicator(candles, "cci", cciLabel, "MOMENTUM", "index points", false,
                EnrichedCandle::cci,
                (candle, value) -> value <= rules.cciBuyThreshold() ? 1
                        : value >= rules.cciSellThreshold() ? -1 : 0,
                value -> value <= rules.cciBuyThreshold() ? cciLabel + " indicates an unusually low price location."
                        : value >= rules.cciSellThreshold() ? cciLabel + " indicates an unusually high price location."
                        : cciLabel + " remains inside its central range.",
                "Buy when %s <= %.1f; sell when %s >= %.1f; otherwise neutral."
                        .formatted(cciLabel, rules.cciBuyThreshold(), cciLabel, rules.cciSellThreshold()),
                List.of(rules.cciBuyThreshold(), 0.0, rules.cciSellThreshold())));
        views.add(indicator(candles, "bollinger", bollingerLabel, "VOLATILITY", "% band position", true,
                candle -> bandPosition(candle.close(), candle.lowerBollinger(), candle.upperBollinger()),
                (candle, value) -> value <= 0 ? 1 : value >= 100 ? -1 : 0,
                value -> value <= 0 ? "Price closed at or below the lower " + bollingerLabel + " band."
                        : value >= 100 ? "Price closed at or above the upper " + bollingerLabel + " band."
                        : "Price remains inside the " + bollingerLabel + " envelope.",
                bollingerLabel + " buys at or below the lower band and sells at or above the upper band; otherwise neutral.",
                List.of(0.0, 50.0, 100.0)));
        views.add(indicator(candles, "atr", atrLabel, "VOLATILITY", "% of price", false,
                candle -> candle.close() == 0 ? Double.NaN : candle.atr() / candle.close() * 100.0,
                (candle, value) -> 0,
                value -> atrLabel + " measures movement size, not direction, so it is shown as neutral context.",
                atrLabel + " is always neutral in the directional vote; it describes volatility only.",
                List.of()));
        views.add(indicator(candles, "vwap", vwapLabel, "VOLUME", "%", true,
                candle -> percentDifference(candle.close(), candle.rollingVwap()),
                (candle, value) -> value > rules.vwapThresholdPercent() ? 1
                        : value < -rules.vwapThresholdPercent() ? -1 : 0,
                value -> value > rules.vwapThresholdPercent() ? "Price is holding above " + vwapLabel + "."
                        : value < -rules.vwapThresholdPercent() ? "Price is below " + vwapLabel + "."
                        : "Price is close to " + vwapLabel + ".",
                "Buy above +%.2f%% from %s; sell below -%.2f%%; otherwise neutral."
                        .formatted(rules.vwapThresholdPercent(), vwapLabel, rules.vwapThresholdPercent()),
                List.of(0.0)));
        views.add(indicator(candles, "relativeVolume", relativeVolumeLabel, "VOLUME", "× average", false,
                candle -> candle.averageVolume() == 0 ? Double.NaN
                        : candle.volume() / candle.averageVolume()
                        * (candle.close() > candle.open() ? 1 : candle.close() < candle.open() ? -1 : 0),
                (candle, value) -> value >= rules.relativeVolumeThreshold() ? 1
                        : value <= -rules.relativeVolumeThreshold() ? -1 : 0,
                value -> Math.abs(value) >= rules.relativeVolumeThreshold() ? "Volume is elevated and confirms the latest candle direction."
                        : "Volume is not elevated enough to cast a directional vote.",
                "%s buys or sells with the candle direction at >=%.2fx its average; otherwise neutral. Negative values represent red candles."
                        .formatted(relativeVolumeLabel, rules.relativeVolumeThreshold()),
                List.of(-rules.relativeVolumeThreshold(), 0.0, rules.relativeVolumeThreshold())));
        views.add(indicator(candles, "volumeProfile", volumeProfileLabel, "PRICE_LOCATION", "% value-area position", true,
                candle -> bandPosition(candle.close(), candle.volumeProfileValueAreaLow(), candle.volumeProfileValueAreaHigh()),
                (candle, value) -> value < 0 ? 1 : value > 100 ? -1 : 0,
                value -> value < 0 ? "Price is below the estimated value area."
                        : value > 100 ? "Price is above the estimated value area."
                        : "Price is inside the estimated value area.",
                volumeProfileLabel + " buys below estimated VAL and sells above estimated VAH; otherwise neutral.",
                List.of(0.0, 50.0, 100.0)));
        views.add(supportResistance(candles, rules));
        addResearchIndicators(views, candles, research, rules);
        return views.stream().map(view -> withScoringState(view, scoringEnabled(view.key(), rules))).toList();
    }

    private void addResearchIndicators(List<IndicatorView> views,
                                       List<EnrichedCandle> candles,
                                       List<TechnicalResearchSnapshot> research,
                                       AnalysisPreferencesService.IntervalProfile rules) {
        if (research == null || research.isEmpty()) return;
        Map<Long, EnrichedCandle> candleByTimestamp = candles.stream().collect(
                java.util.stream.Collectors.toMap(
                        EnrichedCandle::timestamp, candle -> candle, (left, right) -> right));
        views.add(researchIndicator("adx", "ADX / directional movement " + rules.atrPeriod(),
                "TREND", "index points", false,
                research.stream().map(item -> new ResearchValue(item.timestamp(), item.adx())).toList(),
                value -> 0,
                value -> value >= 25 ? "TA4J ADX identifies a comparatively strong directional move."
                        : value >= 20 ? "TA4J ADX identifies a developing directional move."
                        : "TA4J ADX identifies weak or non-directional movement.",
                "Research context only: ADX measures trend strength and never contributes a production vote.",
                List.of(20.0, 25.0)));
        views.add(researchIndicator("dmi", "+DI minus -DI " + rules.atrPeriod(),
                "TREND", "index points", false,
                research.stream().map(item -> new ResearchValue(
                        item.timestamp(), item.plusDi() - item.minusDi())).toList(),
                value -> value > 0 ? 1 : value < 0 ? -1 : 0,
                value -> value > 0 ? "+DI is above -DI in the TA4J research calculation."
                        : value < 0 ? "-DI is above +DI in the TA4J research calculation."
                        : "+DI and -DI are balanced.",
                "Research-only direction: positive when +DI exceeds -DI; this is excluded from scoring.",
                List.of(0.0)));
        views.add(researchIndicator("stochastic", "Stochastic %K " + rules.rsiPeriod(),
                "MOMENTUM", "index points", false,
                research.stream().map(item -> new ResearchValue(item.timestamp(), item.stochasticK())).toList(),
                TechnicalOutlookService::boundedOscillatorVote,
                TechnicalOutlookService::boundedOscillatorExplanation,
                "Research-only context: <=20 is oversold and >=80 is overbought; excluded from scoring.",
                List.of(20.0, 50.0, 80.0)));
        views.add(researchIndicator("stochasticRsi", "Stochastic RSI " + rules.rsiPeriod(),
                "MOMENTUM", "index points", false,
                research.stream().map(item -> new ResearchValue(item.timestamp(), item.stochasticRsi())).toList(),
                TechnicalOutlookService::boundedOscillatorVote,
                TechnicalOutlookService::boundedOscillatorExplanation,
                "Research-only context: <=20 is oversold and >=80 is overbought; excluded from scoring.",
                List.of(20.0, 50.0, 80.0)));
        views.add(researchIndicator("obv", "On-balance volume", "VOLUME", "volume units", false,
                research.stream().map(item -> new ResearchValue(item.timestamp(), item.obv())).toList(),
                value -> 0,
                value -> "TA4J OBV is displayed as cumulative participation context.",
                "Research context only: raw OBV is not assigned a directional production vote.", List.of()));
        views.add(researchIndicator("mfi", "Money Flow Index " + rules.rsiPeriod(),
                "VOLUME", "index points", false,
                research.stream().map(item -> new ResearchValue(item.timestamp(), item.moneyFlowIndex())).toList(),
                TechnicalOutlookService::boundedOscillatorVote,
                value -> value <= 20 ? "TA4J MFI is in its lower research region."
                        : value >= 80 ? "TA4J MFI is in its upper research region."
                        : "TA4J MFI is inside its central region.",
                "Research-only context: <=20 and >=80 are descriptive boundaries; excluded from scoring.",
                List.of(20.0, 50.0, 80.0)));
        views.add(researchIndicator("donchian", "Donchian Channel " + rules.bollingerPeriod(),
                "PRICE_LOCATION", "% band position", true,
                research.stream().map(item -> new ResearchValue(item.timestamp(), bandPosition(
                        closeAt(candleByTimestamp, item.timestamp()), item.donchianLower(), item.donchianUpper()))).toList(),
                value -> value <= 0 ? 1 : value >= 100 ? -1 : 0,
                value -> "TA4J Donchian location is shown for breakout research only.",
                "Research-only channel location; excluded from production scoring.",
                List.of(0.0, 50.0, 100.0)));
        views.add(researchIndicator("keltner", "Keltner Channel " + rules.bollingerPeriod(),
                "VOLATILITY", "% band position", true,
                research.stream().map(item -> new ResearchValue(item.timestamp(), bandPosition(
                        closeAt(candleByTimestamp, item.timestamp()), item.keltnerLower(), item.keltnerUpper()))).toList(),
                value -> value <= 0 ? 1 : value >= 100 ? -1 : 0,
                value -> "TA4J Keltner location is shown for volatility research only.",
                "Research-only channel location; excluded from production scoring.",
                List.of(0.0, 50.0, 100.0)));
        views.add(researchIndicator("ta4jTrend", "TA4J uptrend / downtrend",
                "TREND", "state", false,
                research.stream().map(item -> new ResearchValue(item.timestamp(),
                        item.upTrend() == item.downTrend() ? 0.0 : item.upTrend() ? 1.0 : -1.0)).toList(),
                value -> value > 0 ? 1 : value < 0 ? -1 : 0,
                value -> value > 0 ? "TA4J independently classifies the current window as an uptrend."
                        : value < 0 ? "TA4J independently classifies the current window as a downtrend."
                        : "TA4J does not classify the current window as directionally clear.",
                "Independent research label only; it does not replace candlestick or Elliott trend logic.",
                List.of(-1.0, 0.0, 1.0)));
        views.add(researchIndicator("volumeProfileKde", "TA4J KDE volume-profile mode",
                "PRICE_LOCATION", "% from mode", true,
                research.stream().map(item -> new ResearchValue(item.timestamp(), percentDifference(
                        closeAt(candleByTimestamp, item.timestamp()), item.volumeProfileKdeMode()))).toList(),
                value -> value > 0 ? 1 : value < 0 ? -1 : 0,
                value -> "Price is %.2f%% from TA4J's causal KDE mode estimate."
                        .formatted(value),
                "Side-by-side research value only; the established volume profile remains the production input.",
                List.of(0.0)));
    }

    private static int boundedOscillatorVote(double value) {
        return value <= 20 ? 1 : value >= 80 ? -1 : 0;
    }

    private static String boundedOscillatorExplanation(double value) {
        return value <= 20 ? "The TA4J oscillator is in its lower research region."
                : value >= 80 ? "The TA4J oscillator is in its upper research region."
                : "The TA4J oscillator is inside its central region.";
    }

    private static double closeAt(Map<Long, EnrichedCandle> candles, long timestamp) {
        EnrichedCandle candle = candles.get(timestamp);
        return candle == null ? Double.NaN : candle.close();
    }

    private IndicatorView supportResistance(List<EnrichedCandle> candles,
                                            AnalysisPreferencesService.IntervalProfile rules) {
        String levelLabel = "Support / resistance " + rules.supportResistancePeriod();
        String atrLabel = "ATR " + rules.atrPeriod();
        List<ValueAtCandle> values = new ArrayList<>();
        ArrayDeque<Integer> minimumLows = new ArrayDeque<>();
        ArrayDeque<Integer> maximumHighs = new ArrayDeque<>();
        int lookback = rules.supportResistancePeriod();
        for (int index = 0; index < candles.size(); index++) {
            EnrichedCandle candle = candles.get(index);
            while (!minimumLows.isEmpty()
                    && candles.get(minimumLows.getLast()).low() >= candle.low()) minimumLows.removeLast();
            minimumLows.addLast(index);
            while (!maximumHighs.isEmpty()
                    && candles.get(maximumHighs.getLast()).high() <= candle.high()) maximumHighs.removeLast();
            maximumHighs.addLast(index);
            int expired = index - lookback;
            while (!minimumLows.isEmpty() && minimumLows.getFirst() <= expired) minimumLows.removeFirst();
            while (!maximumHighs.isEmpty() && maximumHighs.getFirst() <= expired) maximumHighs.removeFirst();
            if (index + 1 < lookback || !Double.isFinite(candle.atr())) {
                values.add(new ValueAtCandle(candle, Double.NaN));
                continue;
            }
            double support = candles.get(minimumLows.getFirst()).low();
            double resistance = candles.get(maximumHighs.getFirst()).high();
            double supportDistance = (candle.close() - support) / candle.atr();
            double resistanceDistance = (resistance - candle.close()) / candle.atr();
            double value = supportDistance <= resistanceDistance ? supportDistance : -resistanceDistance;
            values.add(new ValueAtCandle(candle, value));
        }
        return indicatorFromValues("supportResistance", levelLabel, "PRICE_LOCATION",
                atrLabel + " distance", true, values,
                value -> value >= 0 && value <= rules.supportResistanceAtrDistance() ? 1
                        : value < 0 && value >= -rules.supportResistanceAtrDistance() ? -1 : 0,
                value -> value >= 0 && value <= rules.supportResistanceAtrDistance() ? "Price is near configured support."
                        : value < 0 && value >= -rules.supportResistanceAtrDistance() ? "Price is near configured resistance."
                        : "Price is not close enough to either configured boundary.",
                "%s buys within %.2f %s of support and sells within %.2f %s of resistance; otherwise neutral."
                        .formatted(levelLabel, rules.supportResistanceAtrDistance(), atrLabel,
                                rules.supportResistanceAtrDistance(), atrLabel),
                List.of(-rules.supportResistanceAtrDistance(), 0.0, rules.supportResistanceAtrDistance()));
    }

    private boolean scoringEnabled(String key, AnalysisPreferencesService.IntervalProfile rules) {
        return switch (key) {
            case "rsi" -> rules.scoreRsi();
            case "ema" -> rules.scoreEma();
            case "sma" -> rules.scoreLongSma();
            case "macd" -> rules.scoreMacd();
            case "cci" -> rules.scoreCci();
            case "bollinger" -> rules.scoreBollinger();
            case "vwap" -> rules.scoreVwap();
            case "relativeVolume" -> rules.scoreRelativeVolume();
            case "volumeProfile" -> rules.scoreVolumeProfile();
            case "supportResistance" -> rules.scoreSupportResistance();
            case "atr" -> true;
            default -> false;
        };
    }

    private IndicatorView withScoringState(IndicatorView view, boolean enabled) {
        return new IndicatorView(view.key(), view.label(), view.category(), view.unit(), view.currentValue(),
                view.vote(), view.classification(), view.scored() && enabled, view.explanation(), view.rule(),
                view.stateChangedAt(), view.candlesSinceStateChange(), view.overlay(), view.series(),
                view.referenceLines());
    }

    private IndicatorView indicator(List<EnrichedCandle> candles,
                                    String key,
                                    String label,
                                    String category,
                                    String unit,
                                    boolean overlay,
                                    ToDoubleFunction<EnrichedCandle> extractor,
                                    CandleVoteRule rule,
                                    Explanation explanation,
                                    String ruleText,
                                    List<Double> references) {
        List<ValueAtCandle> values = candles.stream()
                .map(candle -> new ValueAtCandle(candle, extractor.applyAsDouble(candle)))
                .toList();
        List<ValueAtCandle> available = values.stream().filter(item -> Double.isFinite(item.value())).toList();
        if (available.isEmpty()) {
            return new IndicatorView(key, label, category, unit, null, 0, "UNAVAILABLE", false,
                    "Not enough completed candles are available for this indicator.", ruleText,
                    null, null, overlay, List.of(), references);
        }
        ValueAtCandle latest = available.getLast();
        int latestVote = rule.vote(latest.candle(), latest.value());
        int changedIndex = available.size() - 1;
        while (changedIndex > 0) {
            ValueAtCandle previous = available.get(changedIndex - 1);
            if (rule.vote(previous.candle(), previous.value()) != latestVote) {
                break;
            }
            changedIndex--;
        }
        ValueAtCandle changed = available.get(changedIndex);
        List<IndicatorPointView> series = available.stream()
                .skip(Math.max(0, available.size() - INDICATOR_SERIES_POINTS))
                .map(item -> new IndicatorPointView(item.candle().timestamp(), finite(item.value()),
                        rule.vote(item.candle(), item.value())))
                .toList();
        return new IndicatorView(key, label, category, unit, finite(latest.value()), latestVote,
                voteLabel(latestVote), true, explanation.text(latest.value()), ruleText,
                changed.candle().timestamp(), available.size() - changedIndex - 1,
                overlay, series, references);
    }

    private IndicatorView indicatorFromValues(String key,
                                              String label,
                                              String category,
                                              String unit,
                                              boolean overlay,
                                              List<ValueAtCandle> values,
                                              ValueVoteRule rule,
                                              Explanation explanation,
                                              String ruleText,
                                              List<Double> references) {
        List<ValueAtCandle> available = values.stream().filter(item -> Double.isFinite(item.value())).toList();
        if (available.isEmpty()) {
            return new IndicatorView(key, label, category, unit, null, 0, "UNAVAILABLE", false,
                    "Not enough completed candles are available for this indicator.", ruleText,
                    null, null, overlay, List.of(), references);
        }
        ValueAtCandle latest = available.getLast();
        int latestVote = rule.vote(latest.value());
        int changedIndex = available.size() - 1;
        while (changedIndex > 0 && rule.vote(available.get(changedIndex - 1).value()) == latestVote) {
            changedIndex--;
        }
        ValueAtCandle changed = available.get(changedIndex);
        List<IndicatorPointView> series = available.stream()
                .skip(Math.max(0, available.size() - INDICATOR_SERIES_POINTS))
                .map(item -> new IndicatorPointView(item.candle().timestamp(), finite(item.value()),
                        rule.vote(item.value())))
                .toList();
        return new IndicatorView(key, label, category, unit, finite(latest.value()), latestVote,
                voteLabel(latestVote), true, explanation.text(latest.value()), ruleText,
                changed.candle().timestamp(), available.size() - changedIndex - 1,
                overlay, series, references);
    }

    private IndicatorView researchIndicator(String key,
                                            String label,
                                            String category,
                                            String unit,
                                            boolean overlay,
                                            List<ResearchValue> values,
                                            ValueVoteRule rule,
                                            Explanation explanation,
                                            String ruleText,
                                            List<Double> references) {
        List<ResearchValue> available = values.stream()
                .filter(item -> Double.isFinite(item.value()))
                .toList();
        if (available.isEmpty()) {
            return new IndicatorView(key, label, category, unit, null, 0, "UNAVAILABLE", false,
                    "Not enough completed candles are available for this TA4J research indicator.",
                    ruleText, null, null, overlay, List.of(), references);
        }
        ResearchValue latest = available.getLast();
        int latestVote = rule.vote(latest.value());
        int changedIndex = available.size() - 1;
        while (changedIndex > 0 && rule.vote(available.get(changedIndex - 1).value()) == latestVote) {
            changedIndex--;
        }
        ResearchValue changed = available.get(changedIndex);
        List<IndicatorPointView> series = available.stream()
                .skip(Math.max(0, available.size() - INDICATOR_SERIES_POINTS))
                .map(item -> new IndicatorPointView(item.timestamp(), finite(item.value()), rule.vote(item.value())))
                .toList();
        return new IndicatorView(key, label, category, unit, finite(latest.value()), latestVote,
                voteLabel(latestVote), false, explanation.text(latest.value()), ruleText,
                changed.timestamp(), available.size() - changedIndex - 1,
                overlay, series, references);
    }

    private List<CategoryView> categoryViews(List<VoteInput> votes,
                                             AnalysisPreferencesService.IntervalProfile rules) {
        Map<String, List<VoteInput>> grouped = new LinkedHashMap<>();
        for (String category : List.of("TREND", "MOMENTUM", "VOLATILITY", "VOLUME",
                "PRICE_LOCATION", "MARKET_RELATIVE", "CANDLESTICK", "ELLIOTT")) {
            grouped.put(category, new ArrayList<>());
        }
        votes.forEach(vote -> grouped.computeIfAbsent(vote.category(), ignored -> new ArrayList<>()).add(vote));
        return grouped.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> {
                    double average = entry.getValue().stream().mapToInt(VoteInput::vote).average().orElse(0);
                    double neutralLimit = rules.neutralScorePercent() / 100.0;
                    int vote = average > neutralLimit ? 1 : average < -neutralLimit ? -1 : 0;
                    return new CategoryView(entry.getKey(), categoryLabel(entry.getKey()), vote,
                            voteLabel(vote), average,
                            entry.getValue().stream().map(VoteInput::label).toList());
                }).toList();
    }

    private java.util.Optional<Integer> signalVote(List<RecentSignalView> signals,
                                                   AlertPatternFamily family) {
        return signals.stream()
                .filter(signal -> signal.family().equals(family.name()))
                .max(Comparator.comparingLong(RecentSignalView::timestamp))
                .map(signal -> signal.direction().equals("BUY") ? 1
                        : signal.direction().equals("SELL") ? -1 : 0);
    }

    private List<RecentSignalView> recentSignals(User user,
                                                 String symbol,
                                                 TimeInterval outlookInterval) {
        long cutoff = cutoff(symbol, apiInterval(outlookInterval), 10);
        if (cutoff == Long.MAX_VALUE) return List.of();
        return alertEventRepository.findRecentForTechnicalOutlook(
                        user, symbol, outlookInterval, cutoff, PageRequest.of(0, 100)).stream()
                .map(event -> new RecentSignalView(
                        event.getId(),
                        event.getAlertRule().getPatternFamily().name(),
                        humanize(event.getPattern().name()),
                        event.getTradeSignal().name(),
                        event.getAlertRule().getInterval().name(),
                        intervalLabel(event.getAlertRule().getInterval()),
                        event.getSignalCandleTimestamp(),
                        event.getConfidenceScore(),
                        event.getSignalStrength() == null ? "Unrated" : humanize(event.getSignalStrength().name()),
                        humanize(event.getLifecycleStatus().name()),
                        observedResult(event),
                        "/alerts/signals/" + event.getId()))
                .toList();
    }

    private String apiInterval(TimeInterval interval) {
        return switch (interval) {
            case DAILY -> "1d";
            case WEEKLY -> "1wk";
            case MONTHLY -> "1mo";
            default -> throw new IllegalArgumentException("Technical outlook supports daily, weekly, and monthly intervals.");
        };
    }

    private long cutoff(String symbol, String interval, int candles) {
        List<Candle> values = candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                symbol, interval, PageRequest.of(0, Math.max(1, candles)));
        if (values.isEmpty()) {
            return Long.MAX_VALUE;
        }
        return values.getLast().getTimestamp();
    }

    private String observedResult(AlertEvent event) {
        if (event.getResolutionClosePrice() == null || event.getClosePrice() == null || event.getClosePrice() == 0) {
            return "Pending observation";
        }
        double raw = (event.getResolutionClosePrice() - event.getClosePrice()) / event.getClosePrice() * 100.0;
        double directional = event.getTradeSignal().name().equals("SELL") ? -raw : raw;
        return String.format(Locale.ROOT, "%+.2f%% directional", directional);
    }

    private List<ActivityMarkerView> activityMarkers(User user,
                                                     String symbol,
                                                     List<ChartCandleView> chart) {
        if (chart.isEmpty()) {
            return List.of();
        }
        LocalDate firstDate = Instant.ofEpochSecond(chart.getFirst().timestamp())
                .atZone(ZoneOffset.UTC).toLocalDate();
        List<ActivityMarkerView> markers = new ArrayList<>();
        congressionalDeliveryRepository.findForTechnicalOutlook(
                        user, symbol, firstDate, PageRequest.of(0, 200)).stream()
                .forEach(delivery -> markers.add(congressionalMarker(delivery)));
        insiderDeliveryRepository.findForTechnicalOutlook(
                        user, symbol, firstDate, PageRequest.of(0, 200)).stream()
                .forEach(delivery -> markers.add(insiderMarker(delivery)));
        return markers.stream().sorted(Comparator.comparing(ActivityMarkerView::date)).toList();
    }

    private ActivityMarkerView congressionalMarker(CongressionalTradeDelivery delivery) {
        var trade = delivery.getTrade();
        return new ActivityMarkerView(trade.getTransactionDate().toString(), "CONGRESSIONAL",
                trade.getTransactionType().name().contains("PURCHASE") ? "BUY" : "SELL",
                trade.getMemberName(), humanize(trade.getTransactionType().name()),
                "/activity-signals/congressional/" + delivery.getId());
    }

    private ActivityMarkerView insiderMarker(InsiderTradeDelivery delivery) {
        var trade = delivery.getTrade();
        String type = trade.getTransactionType().name();
        return new ActivityMarkerView(trade.getTransactionDate().toString(), "INSIDER",
                type.contains("PURCHASE") || type.contains("BUY") ? "BUY" : "SELL",
                trade.getInsiderName(), humanize(type),
                "/activity-signals/insider/" + delivery.getId());
    }

    private MarketComparisonView marketComparison(String symbol, StockAsset asset,
                                                  double voteThresholdPercent,
                                                  boolean includeRatioSeries) {
        Benchmark benchmark = benchmarkFor(asset);
        MarketCacheKey cacheKey = new MarketCacheKey(
                symbol, benchmark.symbol(), voteThresholdPercent, includeRatioSeries);
        long now = Instant.now().getEpochSecond();
        MarketComparisonView cached = marketCache.get(cacheKey, now);
        if (cached != null) return cached;
        List<Candle> stock = latestCandles(symbol, "1d", MARKET_HISTORY_CANDLES);
        List<Candle> market = latestCandles(benchmark.symbol(), "1d", MARKET_HISTORY_CANDLES);
        if (stock.size() < 2 || market.size() < 2) {
            MarketComparisonView unavailable = MarketComparisonView.unavailable(benchmark);
            marketCache.put(cacheKey, unavailable, now);
            return unavailable;
        }
        LocalDate latestDate = min(date(stock.getLast()), date(market.getLast()));
        List<HorizonView> horizons = List.of(
                horizon("1 month", latestDate.minusMonths(1), stock, market),
                horizon("3 months", latestDate.minusMonths(3), stock, market),
                horizon("6 months", latestDate.minusMonths(6), stock, market),
                horizon("1 year", latestDate.minusYears(1), stock, market));
        Map<LocalDate, Candle> marketByDate = new LinkedHashMap<>();
        market.forEach(candle -> marketByDate.put(date(candle), candle));
        List<RawRatio> allRatios = stock.stream()
                .filter(candle -> !date(candle).isAfter(latestDate))
                .filter(candle -> marketByDate.containsKey(date(candle)))
                .map(candle -> new RawRatio(candle.getTimestamp(),
                        candle.getClosePrice() / marketByDate.get(date(candle)).getClosePrice()))
                .filter(value -> Double.isFinite(value.ratio()) && value.ratio() > 0)
                .toList();
        List<RawRatio> ratios = allRatios.stream()
                .skip(Math.max(0, allRatios.size() - CHART_CANDLES))
                .toList();
        if (ratios.size() < 2) {
            MarketComparisonView unavailable = MarketComparisonView.unavailable(benchmark);
            marketCache.put(cacheKey, unavailable, now);
            return unavailable;
        }
        double base = ratios.getFirst().ratio();
        List<RatioPointView> normalized = ratios.stream()
                .map(point -> new RatioPointView(point.timestamp(), point.ratio() / base * 100.0))
                .toList();
        int trendLookback = Math.min(20, normalized.size() - 1);
        double ratioChange = percentDifference(normalized.getLast().value(),
                normalized.get(normalized.size() - 1 - trendLookback).value());
        String trend = ratioChange > voteThresholdPercent ? "IMPROVING"
                : ratioChange < -voteThresholdPercent ? "DETERIORATING" : "STABLE";
        HorizonView threeMonth = horizons.get(1);
        int vote = threeMonth.available() && threeMonth.excessReturn() > voteThresholdPercent
                && trend.equals("IMPROVING") ? 1
                : threeMonth.available() && threeMonth.excessReturn() < -voteThresholdPercent
                && trend.equals("DETERIORATING") ? -1 : 0;
        MarketComparisonView result = new MarketComparisonView(
                true, benchmark.symbol(), benchmark.name(), trend, ratioChange,
                vote, voteLabel(vote), horizons, includeRatioSeries ? normalized : List.of());
        marketCache.put(cacheKey, result, now);
        return result;
    }

    private HorizonView horizon(String label,
                                LocalDate cutoff,
                                List<Candle> stock,
                                List<Candle> market) {
        LocalDate latestCommonDate = min(date(stock.getLast()), date(market.getLast()));
        Candle latestStock = atOrBefore(stock, latestCommonDate);
        Candle latestMarket = atOrBefore(market, latestCommonDate);
        Candle startStock = atOrBefore(stock, cutoff);
        Candle startMarket = atOrBefore(market, cutoff);
        if (startStock == null || startMarket == null || startStock.getClosePrice() == 0 || startMarket.getClosePrice() == 0) {
            return new HorizonView(label, false, null, null, null);
        }
        double stockReturn = percentDifference(latestStock.getClosePrice(), startStock.getClosePrice());
        double marketReturn = percentDifference(latestMarket.getClosePrice(), startMarket.getClosePrice());
        return new HorizonView(label, true, stockReturn, marketReturn, stockReturn - marketReturn);
    }

    private Candle atOrBefore(List<Candle> candles, LocalDate cutoff) {
        int low = 0;
        int high = candles.size() - 1;
        Candle result = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            Candle candidate = candles.get(middle);
            if (!date(candidate).isAfter(cutoff)) {
                result = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    private List<Candle> latestCandles(String symbol, String interval, int limit) {
        List<Candle> descending = new ArrayList<>(
                candleRepository.findBySymbolAndTimeIntervalOrderByTimestampDesc(
                        symbol, interval, PageRequest.of(0, Math.max(1, limit))));
        Collections.reverse(descending);
        return List.copyOf(descending);
    }

    private Benchmark benchmarkFor(StockAsset asset) {
        String country = asset == null || asset.getCountry() == null ? "" : asset.getCountry().toUpperCase(Locale.ROOT);
        String currency = asset == null || asset.getCurrency() == null ? "" : asset.getCurrency().toUpperCase(Locale.ROOT);
        if (country.contains("UNITED KINGDOM") || country.equals("UK") || currency.equals("GBP")) return new Benchmark("^FTSE", "FTSE 100");
        if (country.contains("GERMAN")) return new Benchmark("^GDAXI", "DAX Performance Index");
        if (country.contains("FRANCE")) return new Benchmark("^FCHI", "CAC 40");
        if (country.contains("JAPAN") || currency.equals("JPY")) return new Benchmark("^N225", "Nikkei 225");
        if (country.contains("HONG KONG") || currency.equals("HKD")) return new Benchmark("^HSI", "Hang Seng Index");
        if (currency.equals("EUR") || country.contains("BELGI") || country.contains("NETHERLAND")) return new Benchmark("^STOXX50E", "EURO STOXX 50");
        return new Benchmark("^GSPC", "S&P 500");
    }

    private static ScoreView score(List<Integer> votes,
                                   AnalysisPreferencesService.IntervalProfile rules) {
        int buy = (int) votes.stream().filter(vote -> vote > 0).count();
        int neutral = (int) votes.stream().filter(vote -> vote == 0).count();
        int sell = (int) votes.stream().filter(vote -> vote < 0).count();
        int net = votes.stream().mapToInt(Integer::intValue).sum();
        int denominator = votes.size();
        double normalized = denominator == 0 ? 0 : (double) net / denominator;
        return new ScoreView(buy, neutral, sell, net, denominator, normalized,
                classification(normalized, rules));
    }

    private static String classification(double score,
                                         AnalysisPreferencesService.IntervalProfile rules) {
        double magnitude = Math.abs(score);
        if (magnitude < rules.neutralScorePercent() / 100.0) return "Neutral outlook";
        String direction = score > 0 ? "buy" : "sell";
        if (magnitude >= rules.strongScorePercent() / 100.0) return "Strong " + direction + " outlook";
        if (magnitude >= rules.moderateScorePercent() / 100.0) return "Moderate " + direction + " outlook";
        return "Slight " + direction + " outlook";
    }

    private static ChartCandleView chartCandle(EnrichedCandle candle, boolean includeVolumeProfile) {
        return new ChartCandleView(candle.timestamp(), candle.open(), candle.high(), candle.low(), candle.close(),
                candle.volume(), finite(candle.fastEma()), finite(candle.slowEma()), finite(candle.longSma()),
                finite(candle.lowerBollinger()), finite(candle.bollingerMiddle()), finite(candle.upperBollinger()),
                finite(candle.rollingVwap()),
                includeVolumeProfile ? finite(candle.volumeProfileValueAreaLow()) : null,
                includeVolumeProfile ? finite(candle.volumeProfilePointOfControl()) : null,
                includeVolumeProfile ? finite(candle.volumeProfileValueAreaHigh()) : null);
    }

    private static IndicatorView withoutSeries(IndicatorView view) {
        return new IndicatorView(view.key(), view.label(), view.category(), view.unit(), view.currentValue(),
                view.vote(), view.classification(), view.scored(), view.explanation(), view.rule(),
                view.stateChangedAt(), view.candlesSinceStateChange(), view.overlay(), List.of(),
                view.referenceLines());
    }

    private static String freshness(long timestamp, IntervalDefinition interval) {
        long ageSeconds = Math.max(0, Instant.now().getEpochSecond() - timestamp);
        long expected = switch (interval.timeInterval()) {
            case DAILY -> 4L * 86_400;
            case WEEKLY -> 12L * 86_400;
            case MONTHLY -> 40L * 86_400;
            default -> Long.MAX_VALUE;
        };
        String date = Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm 'UTC'"));
        return (ageSeconds <= expected ? "Current completed candle · " : "Potentially stale · ") + date;
    }

    private static String voteLabel(int vote) {
        return vote > 0 ? "BUY" : vote < 0 ? "SELL" : "NEUTRAL";
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "MARKET_RELATIVE" -> "Market-relative strength";
            case "PRICE_LOCATION" -> "Price location";
            case "CANDLESTICK" -> "Candlestick signals";
            case "ELLIOTT" -> "Elliott signals";
            default -> humanize(category);
        };
    }

    private static String intervalLabel(TimeInterval interval) {
        return switch (interval) {
            case DAILY -> "Daily";
            case WEEKLY -> "Weekly";
            case MONTHLY -> "Monthly";
            default -> humanize(interval.name());
        };
    }

    private static String humanize(String value) {
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static double percentDifference(double value, double baseline) {
        return !Double.isFinite(value) || !Double.isFinite(baseline) || baseline == 0
                ? Double.NaN : (value - baseline) / baseline * 100.0;
    }

    private static double bandPosition(double value, double lower, double upper) {
        return !Double.isFinite(value) || !Double.isFinite(lower) || !Double.isFinite(upper) || upper == lower
                ? Double.NaN : (value - lower) / (upper - lower) * 100.0;
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static LocalDate date(Candle candle) {
        return Instant.ofEpochSecond(candle.getTimestamp()).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private interface CandleVoteRule { int vote(EnrichedCandle candle, double value); }
    private interface ValueVoteRule { int vote(double value); }
    private interface Explanation { String text(double value); }
    private record ValueAtCandle(EnrichedCandle candle, double value) { }
    private record VoteInput(String label, String category, int vote) { }
    private record RawRatio(long timestamp, double ratio) { }
    private record ResearchValue(long timestamp, double value) { }
    private record Benchmark(String symbol, String name) { }
    private record RefreshKey(String symbol, String interval) { }
    private record OutlookCacheKey(long userKey, String symbol, String interval,
                                   AnalysisPreferencesService.IntervalProfile profile,
                                   boolean detailed) { }
    private record MarketCacheKey(String symbol, String benchmarkSymbol,
                                  double threshold, boolean includeRatioSeries) { }

    public record RefreshStatusView(String state, boolean running, boolean ready, long elapsedMillis) {
        private static RefreshStatusView idleStatus() { return new RefreshStatusView("IDLE", false, false, 0); }
        private static RefreshStatusView queuedStatus() { return new RefreshStatusView("QUEUED", true, false, 0); }
        private static RefreshStatusView runningStatus() { return new RefreshStatusView("RUNNING", true, false, 0); }
        private static RefreshStatusView readyStatus(long elapsed) { return new RefreshStatusView("READY", false, true, elapsed); }
        private static RefreshStatusView failedStatus(long elapsed) { return new RefreshStatusView("FAILED", false, false, elapsed); }
    }

    private static long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private record IntervalDefinition(String apiValue, String label, TimeInterval timeInterval) {
        private static IntervalDefinition parse(String raw) {
            String value = raw == null ? "1d" : raw.trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "1d", "daily" -> new IntervalDefinition("1d", "Daily", TimeInterval.DAILY);
                case "1wk", "1w", "weekly" -> new IntervalDefinition("1wk", "Weekly", TimeInterval.WEEKLY);
                case "1mo", "monthly" -> new IntervalDefinition("1mo", "Monthly", TimeInterval.MONTHLY);
                default -> throw new IllegalArgumentException("Technical outlook supports daily, weekly, and monthly intervals.");
            };
        }
    }

    public record OutlookView(String symbol, String companyName, String interval, String intervalLabel,
                              String profileLabel,
                              boolean available, Long candleTimestamp, String freshness,
                              ScoreView headlineScore, ScoreView rawScore, List<CategoryView> categories,
                              List<IndicatorView> indicators, List<ChartCandleView> candles,
                              List<RecentSignalView> recentSignals, List<ActivityMarkerView> activityMarkers,
                              MarketComparisonView marketComparison, IndicatorSettingsView indicatorSettings,
                              MethodologyView methodology) {
        public OutlookView(String symbol, String companyName, String interval, String intervalLabel,
                           boolean available, Long candleTimestamp, String freshness,
                           ScoreView headlineScore, ScoreView rawScore, List<CategoryView> categories,
                           List<IndicatorView> indicators, List<ChartCandleView> candles,
                           List<RecentSignalView> recentSignals, List<ActivityMarkerView> activityMarkers,
                           MarketComparisonView marketComparison, MethodologyView methodology) {
            this(symbol, companyName, interval, intervalLabel, "Factory profile", available, candleTimestamp,
                    freshness, headlineScore, rawScore, categories, indicators, candles, recentSignals,
                    activityMarkers, marketComparison, IndicatorSettingsView.factory(interval), methodology);
        }

        private static OutlookView unavailable(String symbol, IntervalDefinition interval) {
            AnalysisPreferencesService.IntervalProfile rules = AnalysisPreferencesService.factoryProfile(interval.timeInterval());
            ScoreView score = TechnicalOutlookService.score(List.of(), rules);
            return new OutlookView(symbol, symbol, interval.apiValue(), interval.label(), "Factory profile", false, null,
                    "No completed candles are available", score, score, List.of(), List.of(), List.of(),
                    List.of(), List.of(), MarketComparisonView.unavailable(new Benchmark("^GSPC", "S&P 500")),
                    IndicatorSettingsView.from(rules),
                    new MethodologyView("Unavailable inputs are excluded.", "Categories are equally weighted.",
                            "Thresholds are symmetric.", "Descriptive rules only."));
        }
    }

    public record ScoreView(int buy, int neutral, int sell, int net, int denominator,
                            double normalizedScore, String classification) { }
    public record CategoryView(String key, String label, int vote, String classification,
                               double averageInputVote, List<String> inputs) { }
    public record IndicatorView(String key, String label, String category, String unit, Double currentValue,
                                int vote, String classification, boolean scored, String explanation,
                                String rule, Long stateChangedAt, Integer candlesSinceStateChange,
                                boolean overlay, List<IndicatorPointView> series, List<Double> referenceLines) { }
    public record IndicatorPointView(long timestamp, Double value, int vote) { }
    public record ScoreReportView(boolean available, ScoreView rawScore,
                                  List<IndicatorView> indicators,
                                  List<RecentSignalView> recentSignals,
                                  MethodologyView methodology) { }
    public record ChartCandleView(long timestamp, double open, double high, double low, double close,
                                  double volume, Double fastEma, Double slowEma, Double longSma,
                                  Double bollingerLower, Double bollingerMiddle, Double bollingerUpper,
                                  Double vwap, Double valueAreaLow, Double pointOfControl, Double valueAreaHigh) { }
    public record HistoricalChartPageView(List<ChartCandleView> candles,
                                          Long nextCursor,
                                          boolean hasMore,
                                          MarketDataService.CandleSource source,
                                          String failureMessage) { }
    public record RecentSignalView(Long id, String family, String label, String direction, String interval,
                                   String intervalLabel, long timestamp, Integer score, String strength,
                                   String lifecycle, String observedResult, String detailUrl) { }
    public record ActivityMarkerView(String date, String source, String direction, String person,
                                     String label, String detailUrl) { }
    public record HorizonView(String label, boolean available, Double stockReturn, Double benchmarkReturn,
                              Double excessReturn) { }
    public record RatioPointView(long timestamp, double value) { }
    public record MarketComparisonView(boolean available, String benchmarkSymbol, String benchmarkName,
                                       String relativeTrend, double ratioChange, int vote,
                                       String classification, List<HorizonView> horizons,
                                       List<RatioPointView> ratioSeries) {
        private static MarketComparisonView unavailable(Benchmark benchmark) {
            return new MarketComparisonView(false, benchmark.symbol(), benchmark.name(), "UNAVAILABLE",
                    0, 0, "UNAVAILABLE", List.of(), List.of());
        }
    }
    public record IndicatorSettingsView(
            int rsiPeriod,
            int atrPeriod,
            int fastEmaPeriod,
            int slowEmaPeriod,
            int longSmaPeriod,
            int macdFastPeriod,
            int macdSlowPeriod,
            int macdSignalPeriod,
            int cciPeriod,
            int bollingerPeriod,
            double bollingerDeviation,
            int volumePeriod,
            int vwapPeriod,
            int volumeProfilePeriod,
            int supportResistancePeriod) {

        private static IndicatorSettingsView from(AnalysisPreferencesService.IntervalProfile profile) {
            return new IndicatorSettingsView(
                    profile.rsiPeriod(), profile.atrPeriod(),
                    profile.fastEmaPeriod(), profile.slowEmaPeriod(), profile.longSmaPeriod(),
                    profile.macdFastPeriod(), profile.macdSlowPeriod(), profile.macdSignalPeriod(),
                    profile.cciPeriod(), profile.bollingerPeriod(), profile.bollingerDeviation(),
                    profile.volumePeriod(), profile.vwapPeriod(), profile.volumeProfilePeriod(),
                    profile.supportResistancePeriod());
        }

        private static IndicatorSettingsView factory(String interval) {
            TimeInterval timeInterval = switch (interval == null ? "" : interval.toLowerCase(Locale.ROOT)) {
                case "1wk", "weekly" -> TimeInterval.WEEKLY;
                case "1mo", "monthly" -> TimeInterval.MONTHLY;
                default -> TimeInterval.DAILY;
            };
            return from(AnalysisPreferencesService.factoryProfile(timeInterval));
        }
    }
    public record MethodologyView(String rawVoteRule, String categoryRule, String thresholds,
                                  String disclaimer) { }
}
