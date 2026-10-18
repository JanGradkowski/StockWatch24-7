package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.Candle;
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
    private final Map<List<Object>, List<PatternHit>> rememberedScans = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<List<Object>, List<PatternHit>> eldest) {
                    return size() > REMEMBERED_SCANS;
                }
            });

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
        return new StockData(asset, interval, List.copyOf(loaded));
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

        StockData(StockAsset asset, TimeInterval interval, List<Candle> series) {
            this.asset = asset;
            this.interval = interval;
            this.series = series;
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
            return activity.computeIfAbsent(source, key -> "INSIDER".equals(key) ? insiderFilings(asset.getId()) : congressFilings(asset.getId()));
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
                        last.getClosePrice(), family, settings, "CANDLESTICK".equals(family) ? patternCandles : 0) : null;
                List<PatternHit> known = scan == null ? null : rememberedScans.get(scan);
                if (known != null) return known;
                try {
                    List<PatternHit> found = switch (family) {
                        case "CANDLESTICK" -> candlestickHits(owner);
                        case "ELLIOTT_WAVE" -> elliottHits(owner);
                        default -> harmonicHits(owner);
                    };
                    if (scan != null) rememberedScans.put(scan, List.copyOf(found));
                    return found;
                } catch (RuntimeException failure) {
                    log.warn("Custom alert {} patterns unavailable for {} {}", family, asset.getTickerSymbol(), interval, failure);
                    return List.of();
                }
            });
        }

        private Object settings(String family, User owner) {
            if (owner == null) return "factory";
            return switch (family) {
                case "CANDLESTICK" -> candlestickPreferences == null || analysisPreferences == null ? "factory"
                        : List.of(candlestickPreferences.get(owner), analysisPreferences.trendDetectionRules(analysisPreferences.profile(owner, interval)));
                case "ELLIOTT_WAVE" -> elliottPreferences == null ? "factory" : elliottPreferences.get(owner).profile(interval).rules();
                default -> harmonicPreferences == null ? "factory"
                        : List.of(harmonicPreferences.get(owner).rules(), harmonicPreferences.get(owner).patternRules());
            };
        }

        private List<PatternHit> candlestickHits(User owner) {
            if (candlesticks == null || series.isEmpty()) return List.of();
            int lookback = Math.min(HistoricalCandlestickService.MAX_LOOKBACK_CANDLES, patternCandles);
            // Stored candles only: the scheduled check refreshed them, and previews must not spend provider requests.
            var scan = owner == null || candlestickPreferences == null || analysisPreferences == null
                    ? candlesticks.scanStored(asset.getTickerSymbol(), apiInterval(interval), lookback,
                    org.example.stockwatch247.service.CandlePatternDetectionService.TrendDetectionRules.adaptiveFactory(interval),
                    CandlestickPatternPreferencesService.factoryPreferences())
                    : candlesticks.scanStored(asset.getTickerSymbol(), apiInterval(interval), lookback,
                    analysisPreferences.trendDetectionRules(analysisPreferences.profile(owner, interval)),
                    candlestickPreferences.get(owner));
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
            if (elliott == null || enrichment == null || series.size() < 30) return List.of();
            ElliottWaveDetectionService detector = owner == null || elliottPreferences == null ? elliott
                    : elliott.configured(elliottPreferences.get(owner).profile(interval).rules());
            var enriched = enrichment.enrichForElliott(series, series.size(), interval);
            var developing = detector.findDevelopingImpulses(enriched);
            int minimum = detector.minimumSignalConfidence();
            List<PatternHit> hits = new ArrayList<>();
            for (var impulse : developing) {
                if (impulse.confidenceScore() < minimum) continue;
                long anchor = impulse.points().isEmpty() || impulse.points().getLast().timestamp() == null
                        ? impulse.confirmationTimestamp() : impulse.points().getLast().timestamp();
                hits.add(new PatternHit("ELLIOTT_WAVE", impulse.pattern().name(), impulse.stage().name(),
                        impulse.pattern().name().contains("BULLISH"), impulse.confidenceScore(), impulse.confirmationTimestamp(),
                        anchor, null, null, impulse.endpointPrice(), null));
            }
            return hits;
        }

        private List<PatternHit> harmonicHits(User owner) {
            if (harmonics == null || series.size() < 10) return List.of();
            HarmonicPatternDetectionService detector = owner == null || harmonicPreferences == null ? harmonics
                    : harmonicPreferences.detector(owner, harmonics);
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

    private List<ActivityHit> insiderFilings(long assetId) {
        return db.query("""
                select id, filing_date, insider_name, owner_role, transaction_type, shares, transaction_price
                from insider_trades where stock_asset_id = ? and filing_date >= current_date - 800
                order by filing_date desc, id desc limit 500
                """, (row, index) -> {
            BigDecimal shares = row.getBigDecimal("shares");
            BigDecimal price = row.getBigDecimal("transaction_price");
            Double value = shares == null || price == null ? null : shares.multiply(price).abs().doubleValue();
            return new ActivityHit(row.getLong("id"), row.getDate("filing_date").toLocalDate(), row.getString("insider_name"),
                    row.getString("owner_role"), row.getString("transaction_type"), value,
                    value == null ? null : "$" + String.format(java.util.Locale.ROOT, "%,.0f", value));
        }, assetId);
    }

    private List<ActivityHit> congressFilings(long assetId) {
        return db.query("""
                select id, disclosure_date, member_name, chamber, transaction_type, amount_range
                from congressional_trades where stock_asset_id = ? and disclosure_date >= current_date - 400
                order by disclosure_date desc, id desc limit 500
                """, (row, index) -> new ActivityHit(row.getLong("id"), row.getDate("disclosure_date").toLocalDate(),
                row.getString("member_name"), row.getString("chamber"), row.getString("transaction_type"),
                lowerBound(row.getString("amount_range")), row.getString("amount_range")), assetId);
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
