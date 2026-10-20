package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.AnalysisPreferencesService;
import org.example.stockwatch247.service.CandleCompletionService;
import org.example.stockwatch247.service.CandlestickPatternPreferencesService;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService.IndicatorBatchRequest;
import org.example.stockwatch247.service.ChartTechnicalIndicatorService.IndicatorConfiguration;
import org.example.stockwatch247.service.ElliottAlertInputs;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.ElliottWavePreferencesService;
import org.example.stockwatch247.service.HarmonicPatternDetectionService;
import org.example.stockwatch247.service.HarmonicPatternPreferencesService;
import org.example.stockwatch247.service.HistoricalCandlestickService;
import org.example.stockwatch247.service.TechnicalIndicatorEnrichmentService;
import org.example.stockwatch247.service.TechnicalOutlookService;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.Operand;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition.PatternCondition;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.ActivityHit;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.OutlookFact;
import org.example.stockwatch247.service.customalert.CustomAlertEvaluator.PatternHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads what a rule reads for one stock: completed candles, indicator series, pattern detections under the
 * owner's settings, the automated outlook and filings. Everything is read from stored data; the scheduled
 * check has already refreshed the candles. Results are shared by every alert on the stock in one check, and
 * pattern scans by every owner with the same detection settings.
 */
@Component
public class CustomAlertFactsLoader {
    private static final Logger log = LoggerFactory.getLogger(CustomAlertFactsLoader.class);
    /** Candles loaded per stock: enough history for long indicators, Elliott counts and the windows. */
    static final int HISTORY_CANDLES = 1000;
    /** Previews kept for re-runs while a rule is edited: detections depend on the stock and settings, not on the rule. */
    private static final int REMEMBERED_SCANS = 48;
    private static final Pattern AMOUNT = Pattern.compile("\\$?\\s*([0-9][0-9,]*)");

    private final CandleRepository candles;
    private final CandleCompletionService completion;
    private final JdbcTemplate db;
    private ChartTechnicalIndicatorService indicators;
    private HistoricalCandlestickService candlesticks;
    private AnalysisPreferencesService analysisPreferences;
    private CandlestickPatternPreferencesService candlestickPreferences;
    private ElliottWaveDetectionService elliott;
    private ElliottWavePreferencesService elliottPreferences;
    private HarmonicPatternDetectionService harmonics;
    private HarmonicPatternPreferencesService harmonicPreferences;
    private TechnicalIndicatorEnrichmentService enrichment;
    private TechnicalOutlookService outlooks;
    private org.example.stockwatch247.service.MarketDataService marketData;
    private ElliottHistoryStore elliottHistory;
    private final Map<List<Object>, RememberedScan> rememberedScans = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<List<Object>, RememberedScan> eldest) {
                    return size() > REMEMBERED_SCANS;
                }
            });

    /** A remembered scan and, for Elliott, the first candle its replay read as of the candle itself. */
    private record RememberedScan(List<PatternHit> hits, Long replayedFrom, String elliottStatus) { }

    /** First block of an Elliott replay, in candles; later blocks double up to the maximum. */
    private static final int ELLIOTT_REPLAY_BLOCK = 5;
    private static final int ELLIOTT_REPLAY_MAX_BLOCK = 80;

    public CustomAlertFactsLoader(CandleRepository candles, CandleCompletionService completion, JdbcTemplate db) {
        this.candles = candles;
        this.completion = completion;
        this.db = db;
    }

    @Autowired(required = false)
    void configure(ChartTechnicalIndicatorService indicators, HistoricalCandlestickService candlesticks,
                   AnalysisPreferencesService analysisPreferences, CandlestickPatternPreferencesService candlestickPreferences,
                   ElliottWaveDetectionService elliott, ElliottWavePreferencesService elliottPreferences,
                   HarmonicPatternDetectionService harmonics, HarmonicPatternPreferencesService harmonicPreferences,
                   TechnicalIndicatorEnrichmentService enrichment) {
        this.indicators = indicators;
        this.candlesticks = candlesticks;
        this.analysisPreferences = analysisPreferences;
        this.candlestickPreferences = candlestickPreferences;
        this.elliott = elliott;
        this.elliottPreferences = elliottPreferences;
        this.harmonics = harmonics;
        this.harmonicPreferences = harmonicPreferences;
        this.enrichment = enrichment;
    }

    @Autowired(required = false)
    void configureOutlook(TechnicalOutlookService outlooks) {
        this.outlooks = outlooks;
    }

    @Autowired(required = false)
    void configureElliott(org.example.stockwatch247.service.MarketDataService marketData, ElliottHistoryStore elliottHistory) {
        this.marketData = marketData;
        this.elliottHistory = elliottHistory;
    }

    private org.example.stockwatch247.service.HistoricalSignalCacheService store;

    /** The shared analysis store; null computes every scan directly. */
    @Autowired(required = false)
    void configureStore(org.example.stockwatch247.service.HistoricalSignalCacheService store) {
        this.store = store;
    }

    /** Pattern hits as the shared store keeps them; a harmonic hit keeps its formation for the "stop not breached" check. */
    public record StoredHits(List<StoredHit> hits) {
        static StoredHits of(List<PatternHit> hits) {
            return new StoredHits(hits.stream().map(hit -> new StoredHit(hit.family(), hit.code(), hit.type(), hit.bullish(), hit.score(),
                    hit.availableAt(), hit.anchorAt(), hit.status(), hit.resolvedAt(), Double.isFinite(hit.price()) ? hit.price() : null,
                    hit.knownAt(), hit.source() instanceof HarmonicPatternDetectionService.HarmonicFormation formation ? formation : null)).toList());
        }

        List<PatternHit> toHits() {
            return hits == null ? List.of() : hits.stream().map(hit -> new PatternHit(hit.family(), hit.code(), hit.type(), hit.bullish(),
                    hit.score(), hit.availableAt(), hit.anchorAt(), hit.status(), hit.resolvedAt(),
                    hit.price() == null ? Double.NaN : hit.price(), hit.formation(), hit.knownAt())).toList();
        }
    }

    public record StoredHit(String family, String code, String type, boolean bullish, int score, long availableAt, long anchorAt,
                            String status, Long resolvedAt, Double price, long knownAt,
                            HarmonicPatternDetectionService.HarmonicFormation formation) { }

    /** How many of the ascending {@code candles} have a timestamp at or before {@code timestamp}. */
    private static int countAtOrBefore(List<EnrichedCandle> candles, long timestamp) {
        int low = 0, high = candles.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (candles.get(middle).timestamp() <= timestamp) low = middle + 1; else high = middle;
        }
        return low;
    }

    static String apiInterval(TimeInterval interval) {
        return switch (interval) {
            case WEEKLY -> "1wk";
            case MONTHLY -> "1mo";
            default -> "1d";
        };
    }

    /**
     * The stock's data as of a check. {@code before} limits the candles to those that existed at a missed
     * check's original time; null means now.
     */
    public StockData load(StockAsset asset, TimeInterval interval, Long before) {
        String symbol = asset.getTickerSymbol();
        long boundary = completion.firstIncompleteCandleTimestamp(interval);
        long limit = before == null ? boundary : Math.min(boundary, before);
        List<Candle> loaded = new ArrayList<>(candles.findBySymbolAndTimeIntervalAndTimestampLessThanOrderByTimestampDesc(
                symbol, apiInterval(interval), limit, PageRequest.of(0, HISTORY_CANDLES)));
        loaded.removeIf(candle -> candle.getClosePrice() == null || !Double.isFinite(candle.getClosePrice())
                || candle.getTimestamp() == null);
        loaded.sort(Comparator.comparing(Candle::getTimestamp));
        return new StockData(asset, interval, List.copyOf(loaded), limit);
    }

    /** One stock's shared data; {@link #forOwner} adds an owner's detection settings and outlook profile. */
    public final class StockData {
        private final StockAsset asset;
        private final TimeInterval interval;
        private final List<Candle> series;
        private final Map<String, double[]> values = new HashMap<>();
        private final Map<String, List<ActivityHit>> activity = new HashMap<>();
        private final Map<Object, List<PatternHit>> patterns = new HashMap<>();
        /** How many recent candles candlestick detection covers: a scheduled check needs its windows, a preview its replay. */
        private int patternCandles = CustomAlertDefinition.MAX_WINDOW_CANDLES * 3;
        private boolean remember;
        /** First candle of a preview's replay; -1 for a scheduled check, which reads the latest candles only. */
        private int replayFrom = -1;
        private long replayBudgetMillis;
        /** The first candle an Elliott replay read as it stood on the candle (null: no replay). */
        private Long elliottReplayedFrom;
        /** READY, PREPARING or SHORT for a preview's Elliott replay (null: none ran). */
        private String elliottHistoryStatus;
        /** Where the next, still incomplete candle begins: no input of a count may come from it. */
        private final long boundary;
        private boolean refreshLowerDegree;
        /** Only the latest candle is evaluated: a wave count's first-shown candle is not needed (see {@link #evaluatesLatestOnly}). */
        private boolean latestOnly;
        /** The child candles were already refreshed in this check. */
        private boolean lowerDegreeRefreshed;
        private String seriesFingerprint;
        private final Map<String, ElliottInputs> elliottInputs = new HashMap<>();

        StockData(StockAsset asset, TimeInterval interval, List<Candle> series, long boundary) {
            this.asset = asset;
            this.interval = interval;
            this.series = series;
            this.boundary = boundary;
        }

        /** For a scheduled check: refreshes the child candles of Elliott subdivisions first when they are stale. */
        public void refreshLowerDegree() {
            refreshLowerDegree = true;
        }

        /**
         * Only the latest candle will be evaluated. A pattern counts when it was confirmed inside the window and
         * first shown by the evaluated candle; everything the latest count shows was shown by the latest candle,
         * so the previous candle's Elliott count (which tells when each wave was first shown) is not read.
         */
        public void evaluatesLatestOnly(boolean latestOnly) {
            this.latestOnly = latestOnly;
        }

        /** Works out each pattern family's snapshot with factory settings, which the shared store keeps for later readers. */
        public void warmSharedStore() {
            for (String family : CustomAlertDefinition.FAMILIES) patterns(family, null);
        }

        /** One family's hits under an owner's settings (null: factory), as rules read them. */
        List<PatternHit> hits(String family, User owner) {
            return patterns(family, owner);
        }

        /** Earlier candles will be evaluated after all: Elliott waves are read again with the candle that first showed them. */
        public void needEarlierCandles() {
            if (!latestOnly) return;
            latestOnly = false;
            patterns.keySet().removeIf(key -> key instanceof List<?> parts && "ELLIOTT_WAVE".equals(parts.getFirst()));
        }

        /**
         * How a preview's Elliott replay stands: READY (every replayed candle read as it stood), PREPARING (the
         * background history is being built; the replay covers the latest candles) or SHORT; null when the rule
         * has no Elliott condition.
         */
        public String elliottHistoryStatus() {
            return elliottHistoryStatus;
        }

        public List<Candle> candles() { return series; }

        public StockAsset asset() { return asset; }

        public TimeInterval interval() { return interval; }

        /** Detects candlestick patterns over at least the last {@code candles} candles (call before the first rule check). */
        public void coverPatterns(int candles) {
            patternCandles = Math.max(patternCandles, Math.min(HistoricalCandlestickService.MAX_LOOKBACK_CANDLES, candles));
        }

        /** Reuses recent pattern scans of the same candles and settings (previews re-run on every edit of a rule). */
        public void rememberScans() {
            remember = true;
        }

        /**
         * Marks the candles a preview replays. Elliott counts are redrawn as candles arrive, so for these candles
         * the waves are read as they stood on each candle rather than from today's count.
         */
        public void replayFrom(int index, long budgetMillis) {
            replayFrom = index;
            replayBudgetMillis = budgetMillis;
        }

        /**
         * The first candle whose Elliott count was read as it stood on that candle, when an Elliott replay ran out of
         * time before reaching the start of the preview's replay; null otherwise.
         */
        public Long elliottReplayedFrom() {
            return elliottReplayedFrom != null && replayFrom > 0 && replayFrom < series.size()
                    && elliottReplayedFrom > series.get(replayFrom).getTimestamp() ? elliottReplayedFrom : null;
        }

        /** Loads every indicator the rules read in one batch, so each distinct setting is computed once. */
        public void prepare(List<Operand> operands, int lookback) {
            if (series.isEmpty()) return;
            List<Operand> missing = operands.stream().filter(Operand::isIndicator)
                    .filter(operand -> !values.containsKey(operand.seriesKey())).toList();
            if (missing.isEmpty() || indicators == null) return;
            Map<String, IndicatorConfiguration> configurations = new java.util.LinkedHashMap<>();
            Map<String, String> configurationOf = new HashMap<>();
            for (Operand operand : missing) {
                String key = operand.type() + ":" + new java.util.TreeMap<>(operand.parameters());
                String id = configurationOf.computeIfAbsent(key, ignored -> "c" + configurations.size());
                configurations.putIfAbsent(id, new IndicatorConfiguration(id, operand.type(), operand.parameters()));
            }
            // Thirty-two indicators per request: larger sets are split.
            List<IndicatorConfiguration> all = List.copyOf(configurations.values());
            int from = Math.max(0, series.size() - lookback - 2);
            for (int start = 0; start < all.size(); start += 32) {
                var batch = indicators.calculate(asset.getTickerSymbol(), apiInterval(interval), new IndicatorBatchRequest(
                        series.get(from).getTimestamp(), series.getLast().getTimestamp(),
                        all.subList(start, Math.min(all.size(), start + 32))));
                for (Operand operand : missing) {
                    String id = configurationOf.get(operand.type() + ":" + new java.util.TreeMap<>(operand.parameters()));
                    batch.indicators().stream().filter(view -> view.id().equals(id)).findFirst().ifPresent(view -> {
                        double[] aligned = filled();
                        if (view.error() == null) {
                            Map<Long, Integer> index = indexByTime();
                            view.series().stream().filter(item -> item.key().equals(operand.output())).findFirst()
                                    .ifPresent(item -> item.points().forEach(point -> {
                                        Integer at = index.get(point.timestamp());
                                        if (at != null) aligned[at] = point.value();
                                    }));
                        }
                        values.put(operand.seriesKey(), aligned);
                    });
                }
            }
        }

        double[] series(Operand operand) {
            return values.computeIfAbsent(operand.seriesKey(), key -> switch (operand.kind()) {
                case "PRICE" -> series.stream().mapToDouble(candle -> switch (operand.field()) {
                    case "OPEN" -> number(candle.getOpenPrice());
                    case "HIGH" -> number(candle.getHighPrice());
                    case "LOW" -> number(candle.getLowPrice());
                    case "VOLUME" -> candle.getVolume() == null ? Double.NaN : candle.getVolume();
                    default -> number(candle.getClosePrice());
                }).toArray();
                case "INDICATOR" -> {
                    prepare(List.of(operand), CustomAlertDefinition.MAX_WINDOW_CANDLES * 2);
                    yield values.getOrDefault(key, filled());
                }
                default -> filled();
            });
        }

        List<ActivityHit> activity(String source) {
            // From the first loaded candle back by the longest window, so a replay of old candles sees their filings too.
            java.time.LocalDate first = series.isEmpty() ? java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                    : java.time.Instant.ofEpochSecond(series.getFirst().getTimestamp()).atZone(java.time.ZoneOffset.UTC).toLocalDate();
            return activity.computeIfAbsent(source, key -> "INSIDER".equals(key) ? insiderFilings(asset.getId(), first.minusDays(730))
                    : congressFilings(asset.getId(), first.minusDays(365)));
        }

        boolean stock() {
            return asset.getInstrumentType() == null || asset.getInstrumentType() == InstrumentType.EQUITY;
        }

        public CustomAlertEvaluator.Facts forOwner(User owner) {
            StockData data = this;
            return new CustomAlertEvaluator.Facts() {
                private OutlookFact outlook;
                private boolean outlookLoaded;

                @Override public TimeInterval interval() { return interval; }

                @Override public List<Candle> candles() { return series; }

                @Override public double[] series(Operand operand) { return data.series(operand); }

                @Override public List<PatternHit> patterns(PatternCondition condition) {
                    return data.patterns(condition.family(), owner);
                }

                @Override public boolean stillValid(PatternHit hit, int index) {
                    if (!(hit.source() instanceof HarmonicPatternDetectionService.HarmonicFormation formation)) return true;
                    return HarmonicPatternDetectionService.stillActionable(formation, series.subList(0, index + 1), interval,
                            CustomAlertDefinition.MAX_WINDOW_CANDLES);
                }

                @Override public OutlookFact outlook() {
                    if (!outlookLoaded) {
                        outlookLoaded = true;
                        outlook = data.outlook(owner);
                    }
                    return outlook;
                }

                @Override public List<ActivityHit> activity(String source) { return data.activity(source); }

                @Override public boolean stock() { return data.stock(); }
            };
        }

        private List<PatternHit> patterns(String family, User owner) {
            Object settings = settings(family, owner);
            return patterns.computeIfAbsent(List.of(family, settings), key -> {
                Candle last = series.isEmpty() ? null : series.getLast();
                List<Object> scan = remember && last != null ? Arrays.asList(asset.getId(), interval, series.size(), last.getTimestamp(),
                        last.getClosePrice(), family, settings,
                        "CANDLESTICK".equals(family) ? patternCandles : "ELLIOTT_WAVE".equals(family) ? List.of(elliottVersion(owner), latestOnly) : 0) : null;
                RememberedScan known = scan == null ? null : rememberedScans.get(scan);
                if (known != null) {
                    if ("ELLIOTT_WAVE".equals(family)) {
                        elliottReplayedFrom = known.replayedFrom();
                        elliottHistoryStatus = known.elliottStatus();
                    }
                    return known.hits();
                }
                try {
                    List<PatternHit> found = shared(family, owner, () -> switch (family) {
                        case "CANDLESTICK" -> candlestickHits(owner);
                        case "ELLIOTT_WAVE" -> elliottHits(owner);
                        default -> harmonicHits(owner);
                    });
                    if (scan != null) {
                        boolean elliottFamily = "ELLIOTT_WAVE".equals(family);
                        rememberedScans.put(scan, new RememberedScan(List.copyOf(found), elliottFamily ? elliottReplayedFrom : null,
                                elliottFamily ? elliottHistoryStatus : null));
                    }
                    return found;
                } catch (RuntimeException failure) {
                    log.warn("Custom alert {} patterns unavailable for {} {}", family, asset.getTickerSymbol(), interval, failure);
                    return List.of();
                }
            });
        }

        /** What a remembered Elliott replay depends on besides the candles: its start and the stored history's state. */
        private Object elliottVersion(User owner) {
            if (replayFrom <= 0 || elliottHistory == null) return replayFrom;
            ElliottInputs inputs = elliottInputs(owner);
            if (inputs == null) return replayFrom;
            return List.of(replayFrom, elliottHistory.coverage(asset.getId(), interval, inputs.settingsKey())
                    .map(coverage -> coverage.status() + ":" + coverage.coveredFrom() + ":" + coverage.coveredTo()).orElse("none"));
        }

        /**
         * The settings a family is detected with. No owner means the factory settings, exactly what someone who
         * never changed theirs gets, so the shared store files both under the same fingerprint.
         */
        private Object settings(String family, User owner) {
            return switch (family) {
                case "CANDLESTICK" -> List.of(candlestickDefinitions(owner), trendRules(owner));
                case "ELLIOTT_WAVE" -> String.valueOf(elliottRules(owner));
                default -> List.of(harmonicView(owner).rules(), harmonicView(owner).patternRules());
            };
        }

        private CandlestickPatternPreferencesService.PreferencesView candlestickDefinitions(User owner) {
            return owner == null || candlestickPreferences == null ? CandlestickPatternPreferencesService.factoryPreferences()
                    : candlestickPreferences.get(owner);
        }

        private org.example.stockwatch247.service.CandlePatternDetectionService.TrendDetectionRules trendRules(User owner) {
            if (analysisPreferences == null) {
                return org.example.stockwatch247.service.CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(interval);
            }
            return analysisPreferences.trendDetectionRules(owner == null ? AnalysisPreferencesService.factoryProfile(interval)
                    : analysisPreferences.profile(owner, interval));
        }

        private ElliottWaveDetectionService.DetectionRules elliottRules(User owner) {
            if (elliottPreferences == null) return null;
            return (owner == null ? ElliottWavePreferencesService.factoryPreferences() : elliottPreferences.get(owner)).profile(interval).rules();
        }

        private HarmonicPatternPreferencesService.PreferencesView harmonicView(User owner) {
            return owner == null || harmonicPreferences == null ? HarmonicPatternPreferencesService.factoryPreferences()
                    : harmonicPreferences.get(owner);
        }

        /** Fingerprint of the loaded candles' times, prices and volumes: what most scans read. */
        private String seriesFingerprint() {
            if (seriesFingerprint == null) {
                StringBuilder text = new StringBuilder(series.size() * 48);
                for (Candle candle : series) {
                    text.append(candle.getTimestamp()).append('|').append(candle.getOpenPrice()).append('|').append(candle.getHighPrice())
                            .append('|').append(candle.getLowPrice()).append('|').append(candle.getClosePrice()).append('|')
                            .append(candle.getVolume()).append(';');
                }
                seriesFingerprint = org.example.stockwatch247.service.HistoricalSignalCacheService.sha256(text.toString());
            }
            return seriesFingerprint;
        }

        /**
         * Pattern hits from the shared analysis store when a scheduled check, a baseline or the background job has
         * already read these exact inputs with these settings; otherwise computed and stored for the next reader.
         * Previews (which replay) and catch-ups (which need the candle that first showed each wave) compute directly.
         */
        private List<PatternHit> shared(String family, User owner, java.util.function.Supplier<List<PatternHit>> compute) {
            if (store == null || series.isEmpty() || replayFrom > 0) return compute.get();
            String symbol = asset.getTickerSymbol();
            org.example.stockwatch247.service.HistoricalSignalCacheService.Family stored;
            String version;
            Object settings;
            String inputs;
            switch (family) {
                case "ELLIOTT_WAVE" -> {
                    if (!latestOnly || elliott == null || series.size() < 30) return compute.get();
                    // The child candles are refreshed first, so the fingerprint describes what the count will read.
                    if (refreshLowerDegree && !lowerDegreeRefreshed) {
                        ElliottAlertInputs.refreshLowerDegree(candles, marketData, symbol, interval, java.time.Instant.now());
                        lowerDegreeRefreshed = true;
                    }
                    String child = ElliottAlertInputs.childApiInterval(interval);
                    stored = org.example.stockwatch247.service.HistoricalSignalCacheService.Family.ELLIOTT_LATEST;
                    version = ElliottWaveDetectionService.DETECTION_RULE_VERSION + "|" + ElliottWaveDetectionService.SETUP_SCORE_VERSION + "|latest-v1";
                    settings = List.of(String.valueOf(elliottRules(owner)), String.valueOf(child));
                    inputs = org.example.stockwatch247.service.HistoricalSignalCacheService.sha256(seriesFingerprint() + "|"
                            + (child == null ? "" : store.candleFingerprint(symbol, child, series.getFirst().getTimestamp(), boundary)));
                }
                case "CANDLESTICK" -> {
                    stored = org.example.stockwatch247.service.HistoricalSignalCacheService.Family.CANDLESTICK_RECENT;
                    version = HistoricalCandlestickService.SCORE_VERSION + "|recent-v1";
                    settings = List.of(candlestickDefinitions(owner), trendRules(owner), patternCandles);
                    inputs = seriesFingerprint();
                }
                default -> {
                    stored = org.example.stockwatch247.service.HistoricalSignalCacheService.Family.HARMONIC_ALERTS;
                    version = HarmonicPatternDetectionService.RULE_VERSION + "|alerts-v1";
                    settings = List.of(harmonicView(owner).rules(), harmonicView(owner).patternRules());
                    inputs = seriesFingerprint();
                }
            }
            var fresh = new java.util.concurrent.atomic.AtomicReference<List<PatternHit>>();
            try {
                List<PatternHit> hits = store.getOrCompute(stored, symbol, apiInterval(interval), version, settings, inputs, StoredHits.class,
                        () -> {
                            fresh.set(compute.get());
                            return StoredHits.of(fresh.get());
                        }).toHits();
                // A scan computed here is used as computed (the stored copy is for the next reader).
                return fresh.get() != null ? fresh.get() : hits;
            } catch (RuntimeException unavailable) {
                if (org.example.stockwatch247.service.InfrastructureRecovery.isDatabaseUnavailable(unavailable)) throw unavailable;
                log.warn("Shared analysis store unavailable for {} {} {}: {}", family, symbol, interval, unavailable.toString());
                return fresh.get() != null ? fresh.get() : compute.get();
            }
        }

        private List<PatternHit> candlestickHits(User owner) {
            if (candlesticks == null || series.isEmpty()) return List.of();
            int lookback = Math.min(HistoricalCandlestickService.MAX_LOOKBACK_CANDLES, patternCandles);
            // Stored candles only: the scheduled check refreshed them, and previews must not spend provider requests.
            var scan = candlesticks.scanStored(asset.getTickerSymbol(), apiInterval(interval), lookback, trendRules(owner),
                    candlestickDefinitions(owner));
            long last = series.getLast().getTimestamp();
            List<PatternHit> hits = new ArrayList<>();
            for (var signal : scan.signals()) {
                SignalLifecycleStatus status = signal.status();
                if (status == SignalLifecycleStatus.REJECTED || status == SignalLifecycleStatus.POTENTIAL) continue;
                Long detected = signal.lifecycle() == null ? null : signal.lifecycle().detectionCandleTimestamp();
                long available = detected != null ? detected : signal.signalTimestamp();
                if (available > last) continue;
                Long resolved = signal.lifecycle() == null ? null : signal.lifecycle().resolutionCandleTimestamp();
                hits.add(new PatternHit("CANDLESTICK", signal.pattern().name(), signal.pattern().name(),
                        signal.tradeSignal() == TradeSignal.BUY, signal.setupScore(), available, signal.signalTimestamp(),
                        status == null ? null : status.name(), resolved, signal.entryClose(), null));
            }
            return hits;
        }

        private List<PatternHit> elliottHits(User owner) {
            ElliottInputs inputs = elliottInputs(owner);
            if (inputs == null) return List.of();
            boolean replaying = replayFrom > 0 && replayFrom < series.size();
            List<PatternHit> latest = inputs.latest(replaying || !latestOnly);
            if (!replaying) return latest;
            return inputs.replay(latest);
        }

        /** A wave count confirmed on its candle; {@code knownAt} is the first candle whose count showed it. */
        private PatternHit elliottHit(ElliottWaveDetectionService.DevelopingImpulse impulse, long knownAt) {
            long anchor = impulse.points().isEmpty() || impulse.points().getLast().timestamp() == null
                    ? impulse.confirmationTimestamp() : impulse.points().getLast().timestamp();
            return new PatternHit("ELLIOTT_WAVE", impulse.pattern().name(), impulse.stage().name(),
                    impulse.pattern().name().contains("BULLISH"), impulse.confidenceScore(), impulse.confirmationTimestamp(),
                    anchor, null, null, impulse.endpointPrice(), null, knownAt);
        }

        /**
         * An owner's Elliott inputs on this stock, prepared as the built-in Elliott alerts prepare them (the parent
         * candles in their valid context, and the next-lower timeframe for subdivisions), and null when too few
         * candles are stored for a count.
         */
        public ElliottInputs elliottInputs(User owner) {
            if (elliott == null || enrichment == null || series.size() < 30) return null;
            ElliottWaveDetectionService.DetectionRules rules = elliottRules(owner);
            return elliottInputs.computeIfAbsent(String.valueOf(rules), key -> new ElliottInputs(owner, rules));
        }

        /** The counts of one owner's settings on this stock, read as of any of its candles. */
        public final class ElliottInputs {
            private final Long ownerId;
            private final ElliottWaveDetectionService detector;
            private final int minimum;
            private final List<EnrichedCandle> parents;
            private final List<EnrichedCandle> children;
            private final String settingsKey;

            ElliottInputs(User owner, ElliottWaveDetectionService.DetectionRules rules) {
                ownerId = owner == null ? null : owner.getId();
                detector = rules == null ? elliott : elliott.configured(rules);
                minimum = detector.minimumSignalConfidence();
                parents = ElliottAlertInputs.parents(enrichment, series, interval);
                // A scheduled check refreshes a stale child cache, as the built-in check does; previews and the
                // background history read stored candles only.
                children = parents.isEmpty() ? List.of() : ElliottAlertInputs.lowerDegree(candles,
                        refreshLowerDegree && !lowerDegreeRefreshed ? marketData : null, enrichment, asset.getTickerSymbol(), interval,
                        parents, refreshLowerDegree ? java.time.Instant.now() : null, boundary);
                if (refreshLowerDegree) lowerDegreeRefreshed = true;
                settingsKey = ElliottHistoryStore.settingsKey(rules == null ? "factory" : rules,
                        ElliottAlertInputs.childApiInterval(interval));
            }

            /** The fingerprint the stored history of these counts is filed under. */
            public String settingsKey() { return settingsKey; }

            /**
             * The count as it stood on candle {@code index}: the parent candles through it and the child candles before
             * the next parent candle began. {@code context} keeps settled legs between consecutive candles.
             */
            private List<ElliottWaveDetectionService.DevelopingImpulse> countAsOf(
                    int index, ElliottWaveDetectionService.DevelopingScanContext context) {
                long at = series.get(index).getTimestamp();
                long next = index + 1 < series.size() ? series.get(index + 1).getTimestamp() : boundary;
                List<EnrichedCandle> parentPrefix = parents.subList(0, countAtOrBefore(parents, at));
                List<EnrichedCandle> childPrefix = children.subList(0, countAtOrBefore(children, next - 1));
                if (parentPrefix.size() < 30 || parentPrefix.getLast().timestamp() != at) return List.of();
                if (context != null) return detector.findDevelopingImpulses(parentPrefix, childPrefix.isEmpty() ? null : childPrefix, context);
                return childPrefix.isEmpty() ? detector.findDevelopingImpulses(parentPrefix)
                        : detector.findDevelopingImpulses(parentPrefix, childPrefix);
            }

            /**
             * The latest candle's count, as a check on it reads it. A wave the previous candle's count did not show is
             * known from the latest candle, whatever its own confirmation date (the built-in alerts' rule).
             */
            List<PatternHit> latest() {
                return latest(true);
            }

            /**
             * The latest count; with {@code resolveKnownAt} false the previous candle's count is not read, and each wave
             * counts as known from its confirmation (enough when only the latest candle is evaluated).
             */
            List<PatternHit> latest(boolean resolveKnownAt) {
                int last = series.size() - 1;
                long at = series.get(last).getTimestamp();
                // The previous candle's count reuses the legs the latest count validated (and the built-in check's, in a job).
                var context = detector.sharedScanContext();
                List<ElliottWaveDetectionService.DevelopingImpulse> previous = null;
                List<PatternHit> hits = new ArrayList<>();
                for (var impulse : countAsOf(last, context)) {
                    if (impulse.confidenceScore() < minimum) continue;
                    long knownAt = impulse.confirmationTimestamp();
                    if (resolveKnownAt && knownAt < at) {
                        if (previous == null) previous = last > 0 ? countAsOf(last - 1, context) : List.of();
                        boolean shownBefore = previous.stream().anyMatch(seen -> seen.pattern() == impulse.pattern()
                                && seen.points().equals(impulse.points()));
                        if (!shownBefore) knownAt = at;
                    }
                    hits.add(elliottHit(impulse, knownAt));
                }
                return hits;
            }

            /**
             * Reads each candle's own count from {@code from} through {@code to}, oldest first with one context, until
             * the deadline (at least one candle per call). Signals confirmed before {@code confirmedFrom} are left out.
             * Returns the last candle read.
             */
            public int readForward(int from, int to, long deadline, long confirmedFrom, java.util.function.Consumer<PatternHit> sink) {
                var context = new ElliottWaveDetectionService.DevelopingScanContext();
                int done = from - 1;
                for (int index = from; index <= to; index++) {
                    if (index > from && System.nanoTime() > deadline) break;
                    long at = series.get(index).getTimestamp();
                    for (var impulse : countAsOf(index, context)) {
                        long confirmed = impulse.confirmationTimestamp();
                        if (impulse.confidenceScore() >= minimum && confirmed >= confirmedFrom) {
                            sink.accept(elliottHit(impulse, Math.max(confirmed, at)));
                        }
                    }
                    done = index;
                }
                return done;
            }

            /**
             * Newest first, down to {@code stop}: growing blocks, each read forward, until the deadline. A block the
             * deadline cuts short is dropped. Returns the first candle of the covered range ({@code top} when none).
             */
            private int readBackward(int top, int stop, long deadline, java.util.function.Consumer<PatternHit> sink) {
                int covered = top;
                int block = ELLIOTT_REPLAY_BLOCK;
                while (covered > stop && System.nanoTime() < deadline) {
                    int start = Math.max(stop, covered - block);
                    long end = series.get(covered).getTimestamp();
                    List<PatternHit> inBlock = new ArrayList<>();
                    int done = readForward(start, covered - 1, deadline, series.get(start).getTimestamp(), hit -> {
                        if (hit.availableAt() < end) inBlock.add(hit);
                    });
                    if (done < covered - 1) break;
                    inBlock.forEach(sink);
                    covered = start;
                    block = Math.min(block * 2, ELLIOTT_REPLAY_MAX_BLOCK);
                }
                return covered;
            }

            /**
             * A preview's replay: what each replayed candle's own count showed. The background history supplies what
             * it has stored; the candles after it are read here, newest first, within the time budget. When the
             * history is missing or behind, a background build is requested and the replay covers what is ready.
             */
            List<PatternHit> replay(List<PatternHit> latest) {
                int last = series.size() - 1;
                long latestAt = series.get(last).getTimestamp();
                Map<List<Object>, PatternHit> found = new LinkedHashMap<>();
                java.util.function.Consumer<PatternHit> keep = hit -> found.merge(List.of(hit.code(), hit.type(), hit.availableAt()), hit,
                        (kept, other) -> other.knownAt() < kept.knownAt() ? other : kept);
                latest.stream().filter(hit -> hit.knownAt() >= latestAt).forEach(keep);

                ElliottHistoryStore.Coverage stored = elliottHistory == null ? null
                        : elliottHistory.coverage(asset.getId(), interval, settingsKey).orElse(null);
                int storedFrom = stored == null || stored.coveredFrom() == null ? -1 : firstIndexFrom(stored.coveredFrom());
                int storedTo = stored == null || stored.coveredTo() == null ? -1 : countAtOrBeforeSeries(stored.coveredTo()) - 1;
                boolean usable = storedFrom >= 0 && storedTo >= storedFrom && storedTo >= replayFrom - 1;
                int stop = usable ? Math.max(replayFrom, storedTo + 1) : replayFrom;
                long deadline = System.nanoTime() + replayBudgetMillis * 1_000_000L;
                int covered = stop < last ? readBackward(last, stop, deadline, keep) : last;
                // The stored range joins only when the candles after it were all read here.
                if (usable && (covered <= stop || stop >= last)) {
                    int from = Math.max(storedFrom, replayFrom);
                    elliottHistory.signals(asset.getId(), interval, settingsKey, series.get(from).getTimestamp(),
                            series.get(Math.min(storedTo, last)).getTimestamp()).forEach(keep);
                    covered = Math.min(covered, from);
                }
                elliottReplayedFrom = series.get(covered).getTimestamp();
                boolean complete = covered <= replayFrom;

                if (elliottHistory != null) {
                    // Keep the history close to the latest candle so a preview reads only a candle or two itself.
                    boolean behind = stored == null || storedTo < last - 2 || storedFrom < 0 || storedFrom > replayFrom;
                    if (behind) elliottHistory.request(asset.getId(), interval, settingsKey, ownerId);
                    else elliottHistory.touch(asset.getId(), interval, settingsKey);
                    boolean building = stored == null || stored.building() || behind && !"FAILED".equals(stored.status());
                    elliottHistoryStatus = complete ? "READY" : building ? "PREPARING" : "SHORT";
                } else {
                    elliottHistoryStatus = complete ? "READY" : "SHORT";
                }
                // Before the replayed range only today's count is known; it serves the candle before the replay.
                long start = elliottReplayedFrom;
                List<PatternHit> replayed = new ArrayList<>(latest.stream().filter(hit -> hit.availableAt() < start).toList());
                replayed.addAll(found.values());
                return replayed;
            }
        }

        private int countAtOrBeforeSeries(long timestamp) {
            int low = 0, high = series.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (series.get(middle).getTimestamp() <= timestamp) low = middle + 1; else high = middle;
            }
            return low;
        }

        private int firstIndexFrom(long timestamp) {
            int index = countAtOrBeforeSeries(timestamp - 1);
            return index < series.size() ? index : -1;
        }

        private List<PatternHit> harmonicHits(User owner) {
            if (harmonics == null || series.size() < 10) return List.of();
            HarmonicPatternPreferencesService.PreferencesView view = harmonicView(owner);
            HarmonicPatternDetectionService detector = harmonics.configured(view.rules(), view.patternRules());
            List<PatternHit> hits = new ArrayList<>();
            for (var formation : detector.forAlerts().detectHistorical(series)) {
                long anchor = formation.points().getLast().timestamp();
                hits.add(new PatternHit("HARMONIC_FORMATION", HarmonicPatternDetectionService.signalPattern(formation.pattern()).name(),
                        formation.pattern().name(), formation.tradeSignal() == TradeSignal.BUY, formation.qualityScore(),
                        formation.confirmationTimestamp(), anchor, null, null, formation.points().getLast().price(), formation));
            }
            return hits;
        }

        private OutlookFact outlook(User owner) {
            if (outlooks == null || owner == null || series.isEmpty()) return null;
            try {
                var view = outlooks.getTrackingOutlook(owner, asset.getTickerSymbol(), apiInterval(interval), false);
                if (!view.available() || view.candleTimestamp() == null) return null;
                return new OutlookFact(view.candleTimestamp(), view.headlineScore().classification(), view.headlineScore().normalizedScore());
            } catch (RuntimeException unavailable) {
                log.debug("Outlook unavailable for custom alert on {}", asset.getTickerSymbol(), unavailable);
                return null;
            }
        }

        private Map<Long, Integer> indexByTime() {
            Map<Long, Integer> index = new HashMap<>();
            for (int at = 0; at < series.size(); at++) index.put(series.get(at).getTimestamp(), at);
            return index;
        }

        private double[] filled() {
            double[] result = new double[series.size()];
            Arrays.fill(result, Double.NaN);
            return result;
        }
    }

    private List<ActivityHit> insiderFilings(long assetId, java.time.LocalDate since) {
        return db.query("""
                select id, filing_date, insider_name, owner_role, transaction_type, shares, transaction_price
                from insider_trades where stock_asset_id = ? and filing_date >= ?
                order by filing_date desc, id desc limit 20000
                """, (row, index) -> {
            BigDecimal shares = row.getBigDecimal("shares");
            BigDecimal price = row.getBigDecimal("transaction_price");
            Double value = shares == null || price == null ? null : shares.multiply(price).abs().doubleValue();
            return new ActivityHit(row.getLong("id"), row.getDate("filing_date").toLocalDate(), row.getString("insider_name"),
                    row.getString("owner_role"), row.getString("transaction_type"), value,
                    value == null ? null : "$" + String.format(java.util.Locale.ROOT, "%,.0f", value));
        }, assetId, java.sql.Date.valueOf(since));
    }

    private List<ActivityHit> congressFilings(long assetId, java.time.LocalDate since) {
        return db.query("""
                select id, disclosure_date, member_name, chamber, transaction_type, amount_range
                from congressional_trades where stock_asset_id = ? and disclosure_date >= ?
                order by disclosure_date desc, id desc limit 20000
                """, (row, index) -> new ActivityHit(row.getLong("id"), row.getDate("disclosure_date").toLocalDate(),
                row.getString("member_name"), row.getString("chamber"), row.getString("transaction_type"),
                lowerBound(row.getString("amount_range")), row.getString("amount_range")), assetId, java.sql.Date.valueOf(since));
    }

    /** "$1,001 - $15,000" reads as 1001: a disclosure only promises the lower end of its range. */
    static Double lowerBound(String range) {
        if (range == null) return null;
        Matcher matcher = AMOUNT.matcher(range);
        if (!matcher.find()) return null;
        try {
            return Double.parseDouble(matcher.group(1).replace(",", ""));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static double number(Double value) {
        return value == null ? Double.NaN : value;
    }
}
