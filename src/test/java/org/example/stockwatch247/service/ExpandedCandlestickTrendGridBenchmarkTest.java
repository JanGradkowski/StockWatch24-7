package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.GeometricPatternCandidate;
import org.example.stockwatch247.service.CandlePatternDetectionService.PriorTrendAssessment;
import org.example.stockwatch247.service.CandlePatternDetectionService.TrendDetectionRules;
import org.example.stockwatch247.service.CandlestickSignalLifecyclePolicy.LifecycleResolution;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.RegressionEvidence;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.RegressionParameters;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.StructureEvidence;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.SwingParameters;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.TrendAssessment;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.TrendPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in 2,193-symbol research run for the two-stage one-candle gate and the
 * configurable prior-trend rules. Geometry is calculated once per candle;
 * every trend configuration then uses the production trend classifier.
 */
@EnabledIfSystemProperty(named = "backtest.candlestick.trend-grid.enabled", matches = "true")
class ExpandedCandlestickTrendGridBenchmarkTest {
    private static final Path DEFAULT_MANIFEST = Path.of(
            "target/expanded-backtest-data/expanded-candlestick-power-universe.tsv");
    private static final Path DEFAULT_DATA = Path.of(
            "target/expanded-backtest-data/expanded-power-candles.csv.gz");
    private static final Path OUTPUT = Path.of(
            "docs/candlestick-one-candle-trend-grid-results.md");
    private static final Path EXISTING_REPORT = Path.of(
            "docs/candlestick-signal-backtest-results.md");
    private static final String EXISTING_REPORT_START =
            "<!-- BEGIN GENERATED ONE-CANDLE TREND-GRID RESULTS -->";
    private static final String EXISTING_REPORT_END =
            "<!-- END GENERATED ONE-CANDLE TREND-GRID RESULTS -->";
    private static final int EXPECTED_SYMBOLS = 2_193;
    private static final int EXPECTED_DAILY_CANDLES = 7_802_979;

    private static final List<WindowRule> WINDOWS = List.of(
            new WindowRule(2, 4),
            new WindowRule(3, 4),
            new WindowRule(2, 5),
            new WindowRule(3, 5),
            new WindowRule(4, 5),
            new WindowRule(3, 6),
            new WindowRule(4, 6),
            new WindowRule(5, 6),
            new WindowRule(4, 8),
            new WindowRule(5, 8),
            new WindowRule(6, 8),
            new WindowRule(5, 10),
            new WindowRule(6, 10),
            new WindowRule(7, 10)
    );

    private static final List<IntervalSpec> INTERVALS = List.of(
            new IntervalSpec("Daily", "1d", Aggregation.DAILY, 250, 10, 3.0,
                    List.of(0.5, 1.0, 1.5, 2.0, 3.0, 5.0)),
            new IntervalSpec("Weekly", "1wk", Aggregation.WEEKLY, 80, 8, 8.0,
                    List.of(1.5, 3.0, 5.0, 8.0, 12.0, 16.0)),
            new IntervalSpec("Monthly", "1mo", Aggregation.MONTHLY, 36, 6, 12.0,
                    List.of(1.5, 3.0, 5.0, 8.0, 12.0, 16.0, 20.0))
    );

    private final TechnicalIndicatorEnrichmentService enrichmentService =
            new TechnicalIndicatorEnrichmentService();
    private final CandlePatternDetectionService detectionService =
            new CandlePatternDetectionService();
    private final CandlestickAdaptiveTrendService adaptiveTrendService =
            new CandlestickAdaptiveTrendService();

    @Test
    void runsOneCandleComparisonAndTrendGrid() throws Exception {
        Path manifestPath = Path.of(System.getProperty(
                "backtest.candlestick.trend-grid.manifest-file", DEFAULT_MANIFEST.toString()));
        Path dataPath = Path.of(System.getProperty(
                "backtest.candlestick.trend-grid.data-file", DEFAULT_DATA.toString()));
        Path outputPath = Path.of(System.getProperty(
                "backtest.candlestick.trend-grid.output-file", OUTPUT.toString()));
        List<UniverseEntry> fullUniverse = loadManifest(manifestPath);
        int maximumSymbols = Integer.parseInt(System.getProperty(
                "backtest.candlestick.trend-grid.max-symbols",
                Integer.toString(fullUniverse.size())));
        List<UniverseEntry> universe = fullUniverse.subList(
                0, Math.min(Math.max(1, maximumSymbols), fullUniverse.size()));
        Map<String, List<Candle>> dailyBySymbol = loadDailyCandles(dataPath, universe);

        Map<String, IntervalResults> results = new LinkedHashMap<>();
        for (IntervalSpec interval : INTERVALS) {
            results.put(interval.apiInterval(), new IntervalResults(
                    interval, configurations(interval), adaptiveConfigurations()));
        }

        int processed = 0;
        for (UniverseEntry entry : universe) {
            List<Candle> daily = dailyBySymbol.get(entry.symbol());
            for (IntervalSpec interval : INTERVALS) {
                evaluateSymbol(entry.symbol(), aggregate(daily, interval.aggregation()),
                        results.get(interval.apiInterval()));
            }
            processed++;
            if (processed % 25 == 0 || processed == universe.size()) {
                System.out.printf(Locale.ROOT,
                        "Trend-grid benchmark processed %,d/%,d symbols.%n",
                        processed, universe.size());
            }
        }

        String report = buildReport(universe, results);
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, report, StandardCharsets.UTF_8);
        if (universe.size() == EXPECTED_SYMBOLS && outputPath.normalize().equals(OUTPUT.normalize())) {
            publishInExistingReport(report);
        }
        System.out.println(report);
        System.out.println("Trend-grid report: " + outputPath.toAbsolutePath());
    }

    private void publishInExistingReport(String generatedReport) throws IOException {
        String existing = Files.readString(EXISTING_REPORT, StandardCharsets.UTF_8);
        int generatedStart = existing.indexOf(EXISTING_REPORT_START);
        if (generatedStart >= 0) {
            int generatedEnd = existing.indexOf(EXISTING_REPORT_END, generatedStart);
            if (generatedEnd < 0) {
                throw new IllegalStateException("The existing candlestick report has an incomplete generated-results section.");
            }
            existing = existing.substring(0, generatedStart)
                    + existing.substring(generatedEnd + EXISTING_REPORT_END.length());
        }
        String merged = existing.stripTrailing()
                + "\n\n" + EXISTING_REPORT_START + "\n\n"
                + generatedReport.strip() + "\n\n"
                + EXISTING_REPORT_END + "\n";
        Files.writeString(EXISTING_REPORT, merged, StandardCharsets.UTF_8);
    }

    private void evaluateSymbol(String symbol,
                                List<Candle> candles,
                                IntervalResults results) {
        IntervalSpec interval = results.interval;
        if (candles.size() < interval.minimumHistory() + interval.horizon() + 1) {
            return;
        }
        List<EnrichedCandle> enriched = enrichmentService.enrich(candles, candles.size());
        assertEquals(candles.size(), enriched.size(), "Enrichment size drift for " + symbol);
        results.symbols++;
        results.candles += candles.size();

        int lastSignalIndex = candles.size() - interval.horizon() - 2;
        TrendConfig factory = results.factoryConfig();
        for (int signalIndex = interval.minimumHistory() - 1;
             signalIndex <= lastSignalIndex;
             signalIndex++) {
            List<GeometricPatternCandidate> geometry =
                    detectionService.geometricCandidatesAt(enriched, signalIndex);
            if (geometry.isEmpty()) {
                continue;
            }
            results.geometricCandidates += geometry.size();

            for (TrendConfig config : results.configurations) {
                PriorTrendAssessment[] assessments = new PriorTrendAssessment[4];
                for (GeometricPatternCandidate candidate : geometry) {
                    int patternCandles = candidate.patternCandleCount();
                    PriorTrendAssessment assessment = assessments[patternCandles];
                    if (assessment == null) {
                        assessment = detectionService.assessPreparedPriorTrend(
                                enriched,
                                signalIndex - patternCandles + 1,
                                config.rules());
                        assessments[patternCandles] = assessment;
                    }
                    if (!candidate.accepts(assessment)) {
                        continue;
                    }
                    EvaluatedTrade newTrade = evaluateNewLifecycle(
                            candidate, candles, signalIndex, interval);
                    Segment segment = segment(candles.get(
                            newTrade == null ? signalIndex : newTrade.entryIndex()).getTimestamp());
                    ConfigStats configStats = results.grid.get(config).get(segment);
                    configStats.candidates++;
                    if (newTrade == null) {
                        configStats.rejected++;
                    } else {
                        configStats.add(symbol, candidate, newTrade, interval.outcomeMove());
                    }

                    if (config.equals(factory)) {
                        addFactoryComparison(symbol, candidate, candles, signalIndex, results);
                    }
                }
            }
            evaluateAdaptiveModels(symbol, candles, enriched, signalIndex, geometry, results);
        }
    }

    private void evaluateAdaptiveModels(String symbol,
                                        List<Candle> candles,
                                        List<EnrichedCandle> enriched,
                                        int signalIndex,
                                        List<GeometricPatternCandidate> geometry,
                                        IntervalResults results) {
        Map<GeometricPatternCandidate, EvaluatedTrade> trades = new HashMap<>();
        for (GeometricPatternCandidate candidate : geometry) {
            trades.put(candidate, evaluateNewLifecycle(candidate, candles, signalIndex, results.interval));
        }
        for (int patternCandles = 1; patternCandles <= 3; patternCandles++) {
            int patternLength = patternCandles;
            List<GeometricPatternCandidate> matching = geometry.stream()
                    .filter(candidate -> candidate.patternCandleCount() == patternLength)
                    .toList();
            if (matching.isEmpty()) continue;
            int patternStartIndex = signalIndex - patternLength + 1;
            Map<SwingParameters, StructureEvidence> structures = new HashMap<>();
            Map<Integer, RegressionEvidence> measurements = new HashMap<>();
            Map<RegressionParameters, RegressionEvidence> regressions = new HashMap<>();
            for (AdaptiveConfig config : results.adaptiveConfigurations) {
                SwingParameters swing = config.parameters().structure();
                RegressionParameters regressionParameters = config.parameters().regression();
                StructureEvidence structure = swing == null
                        ? StructureEvidence.unavailable("structure is not used")
                        : structures.computeIfAbsent(swing, ignored -> adaptiveTrendService.assessStructure(
                        enriched, patternStartIndex, swing));
                RegressionEvidence regression = regressionParameters == null
                        ? RegressionEvidence.unavailable("regression is not used")
                        : regressions.computeIfAbsent(regressionParameters, ignored -> {
                    RegressionEvidence measurement = measurements.computeIfAbsent(
                            regressionParameters.windowBars(),
                            window -> adaptiveTrendService.measureRegression(
                                    enriched, patternStartIndex, window));
                    return adaptiveTrendService.classifyRegression(measurement, regressionParameters);
                });
                TrendAssessment assessment = adaptiveTrendService.combine(
                        config.parameters().policy(), structure, regression);
                PriorTrendAssessment priorTrend = assessment.asPriorTrendAssessment();
                for (GeometricPatternCandidate candidate : matching) {
                    if (!candidate.accepts(priorTrend)) continue;
                    EvaluatedTrade trade = trades.get(candidate);
                    Segment segment = segment(candles.get(
                            trade == null ? signalIndex : trade.entryIndex()).getTimestamp());
                    ConfigStats configStats = results.adaptiveGrid.get(config).get(segment);
                    configStats.candidates++;
                    if (trade == null) {
                        configStats.rejected++;
                    } else {
                        configStats.add(symbol, candidate, trade, results.interval.outcomeMove());
                    }
                }
            }
        }
    }

    private void addFactoryComparison(String symbol,
                                      GeometricPatternCandidate candidate,
                                      List<Candle> candles,
                                      int signalIndex,
                                      IntervalResults results) {
        IntervalSpec interval = results.interval;
        EvaluatedTrade legacy = evaluateTrade(
                candidate.tradeSignal(), candles, signalIndex, interval.horizon());
        results.legacyAll.add(symbol, candidate, legacy, interval.outcomeMove());
        results.legacyByPattern.get(candidate.pattern())
                .add(symbol, candidate, legacy, interval.outcomeMove());

        boolean oneCandle = candidate.patternCandleCount() == 1;
        if (oneCandle) {
            results.legacyOneCandle.add(symbol, candidate, legacy, interval.outcomeMove());
            results.oneCandleCandidates++;
        }
        EvaluatedTrade detected = evaluateNewLifecycle(candidate, candles, signalIndex, interval);
        if (detected == null) {
            if (oneCandle) {
                results.oneCandleRejected++;
            }
            return;
        }
        results.newAll.add(symbol, candidate, detected, interval.outcomeMove());
        results.newByPattern.get(candidate.pattern())
                .add(symbol, candidate, detected, interval.outcomeMove());
        if (oneCandle) {
            results.oneCandleAccepted++;
            results.newOneCandle.add(symbol, candidate, detected, interval.outcomeMove());
            results.acceptedLegacyTiming.add(symbol, candidate, legacy, interval.outcomeMove());
        }
    }

    private EvaluatedTrade evaluateNewLifecycle(GeometricPatternCandidate candidate,
                                                List<Candle> candles,
                                                int signalIndex,
                                                IntervalSpec interval) {
        if (candidate.patternCandleCount() != 1) {
            return evaluateTrade(candidate.tradeSignal(), candles, signalIndex, interval.horizon());
        }
        LifecycleResolution gate = CandlestickSignalLifecyclePolicy.resolveCandidateGate(
                candidate.pattern(),
                candidate.tradeSignal(),
                candles.get(signalIndex).getClosePrice(),
                List.of(candles.get(signalIndex + 1)));
        if (gate == null || gate.status() == SignalLifecycleStatus.REJECTED) {
            return null;
        }
        return evaluateTrade(
                candidate.tradeSignal(), candles, signalIndex + 1, interval.horizon());
    }

    private EvaluatedTrade evaluateTrade(TradeSignal direction,
                                         List<Candle> candles,
                                         int entryIndex,
                                         int horizon) {
        int exitIndex = entryIndex + horizon;
        double entry = candles.get(entryIndex).getClosePrice();
        double exit = candles.get(exitIndex).getClosePrice();
        double directionalReturn = direction == TradeSignal.BUY
                ? percent(entry, exit)
                : -percent(entry, exit);
        double best = Double.NEGATIVE_INFINITY;
        double worst = Double.POSITIVE_INFINITY;
        for (int index = entryIndex + 1; index <= exitIndex; index++) {
            Candle candle = candles.get(index);
            double favorable = direction == TradeSignal.BUY
                    ? percent(entry, candle.getHighPrice())
                    : -percent(entry, candle.getLowPrice());
            double adverse = direction == TradeSignal.BUY
                    ? percent(entry, candle.getLowPrice())
                    : -percent(entry, candle.getHighPrice());
            best = Math.max(best, favorable);
            worst = Math.min(worst, adverse);
        }
        return new EvaluatedTrade(entryIndex, directionalReturn, best, worst);
    }

    private List<TrendConfig> configurations(IntervalSpec interval) {
        Set<TrendConfig> values = new LinkedHashSet<>();
        for (WindowRule window : WINDOWS) {
            for (double move : interval.trendMoves()) {
                values.add(new TrendConfig(
                        window.minimumDirectionalConfirmations(),
                        window.lookbackCandles(),
                        move));
            }
        }
        values.add(new TrendConfig(3, 5, 1.5));
        return List.copyOf(values);
    }

    private List<AdaptiveConfig> adaptiveConfigurations() {
        List<SwingParameters> swings = new ArrayList<>();
        for (int width : List.of(1, 2)) {
            for (int lookback : List.of(15, 30)) {
                for (double prominence : List.of(0.0, 0.25, 0.5)) {
                    swings.add(new SwingParameters(width, width, lookback, prominence));
                }
            }
        }
        List<RegressionParameters> regressions = new ArrayList<>();
        for (int window : List.of(8, 15, 25)) {
            for (double slope : List.of(0.0, 0.15, 0.30)) {
                for (double rSquared : List.of(0.0, 0.20)) {
                    regressions.add(new RegressionParameters(window, slope, rSquared));
                }
            }
        }
        List<RegressionParameters> combinedRegressions = regressions.stream()
                .filter(parameters -> parameters.minimumAbsoluteNormalizedSlope() > 0.0)
                .filter(parameters -> parameters.minimumRSquared() == 0.20)
                .toList();
        List<AdaptiveConfig> configs = new ArrayList<>();
        for (SwingParameters swing : swings) {
            configs.add(AdaptiveConfig.of(TrendPolicy.STRUCTURE_ONLY, swing, null));
        }
        for (RegressionParameters regression : regressions) {
            configs.add(AdaptiveConfig.of(TrendPolicy.REGRESSION_ONLY, null, regression));
        }
        for (SwingParameters swing : swings) {
            for (RegressionParameters regression : combinedRegressions) {
                configs.add(AdaptiveConfig.of(TrendPolicy.STRICT_AGREEMENT, swing, regression));
                configs.add(AdaptiveConfig.of(
                        TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO, swing, regression));
            }
        }
        assertEquals(174, configs.size(), "Adaptive research-grid size drifted.");
        return List.copyOf(configs);
    }

    private String buildReport(List<UniverseEntry> universe,
                               Map<String, IntervalResults> byInterval) {
        StringBuilder report = new StringBuilder();
        report.append("# Expanded one-candle lifecycle and trend-grid benchmark\n\n");
        report.append(String.format(Locale.ROOT,
                "Date run: 2026-08-11. Frozen power universe: **%,d tickers** and **%,d adjusted daily candles**. ",
                universe.size(), universe.stream().mapToInt(UniverseEntry::dailyCandles).sum()));
        report.append("All outcomes use completed adjusted candles. Precision is success / (success + failure); inconclusive outcomes are excluded. Average return includes every retained signal.\n\n");
        report.append("Chronological selection segments are 2003-2010 training, 2011-2014 validation, and 2015-2018 holdout. The trend-grid rank uses training and validation only; holdout is displayed after ranking.\n\n");

        report.append("## Production decision recorded after the benchmark\n\n");
        report.append("The tables below contain the evidence; these settings record the production decision made after reviewing it.\n\n");
        report.append("| Interval | Production trend rule | Decision |\n");
        report.append("|---|---|---|\n");
        report.append("| Daily | 1.5% - 3/5 | Kept because the selected 2.0% - 3/5 rule was effectively identical in holdout. |\n");
        report.append("| Weekly | 16.0% - 4/6 | Adopted after selection on training/validation and improvement over the former 1.5% - 3/5 rule in holdout. |\n");
        report.append("| Monthly | 1.5% - 3/5 | Kept because the selected 20.0% - 4/5 rule failed in holdout. |\n\n");

        report.append("## Coverage\n\n");
        report.append("| Interval | Symbols | Candles | Geometry candidates | Configurations | Outcome |\n");
        report.append("|---|---:|---:|---:|---:|---|\n");
        for (IntervalResults result : byInterval.values()) {
            report.append(String.format(Locale.ROOT,
                    "| %s | %,d | %,d | %,d | %,d | %d candles / %.1f%% |%n",
                    result.interval.label(), result.symbols, result.candles,
                    result.geometricCandidates, result.configurations.size(),
                    result.interval.horizon(), result.interval.outcomeMove()));
        }

        report.append("\n## Controlled legacy-versus-new one-candle comparison\n\n");
        report.append("The legacy row includes geometric one-candle candidates immediately and measures from the candidate close. The accepted-subset row shows those same candidates with legacy timing. The new row requires the immediately following green/higher or red/lower close and measures from that detection close. Multi-candle patterns are unchanged.\n\n");
        report.append("| Interval | Cohort | Signals | Actionable | Precision | Avg return | Ticker-balanced precision | Ticker-balanced avg | Avg MFE | Avg MAE | Coverage |\n");
        report.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (IntervalResults result : byInterval.values()) {
            appendStats(report, result.interval.label(), "Legacy one-candle candidates", result.legacyOneCandle);
            appendStats(report, result.interval.label(), "Gate-accepted subset, legacy timing", result.acceptedLegacyTiming);
            appendStats(report, result.interval.label(), "New detected one-candle signals", result.newOneCandle);
            appendStats(report, result.interval.label(), "Legacy all patterns", result.legacyAll);
            appendStats(report, result.interval.label(), "New all patterns", result.newAll);
            report.append(String.format(Locale.ROOT,
                    "\n%s gate: %,d candidates; %,d accepted (%.2f%%); %,d rejected.\n\n",
                    result.interval.label(), result.oneCandleCandidates,
                    result.oneCandleAccepted,
                    percentage(result.oneCandleAccepted, result.oneCandleCandidates),
                    result.oneCandleRejected));
        }

        report.append("## One-candle results by pattern\n\n");
        report.append("| Interval | Pattern | Legacy N | Legacy precision | Legacy avg | New N | New precision | New avg | Accepted |\n");
        report.append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (IntervalResults result : byInterval.values()) {
            for (CandlePattern pattern : oneCandlePatterns()) {
                Stats legacy = result.legacyByPattern.get(pattern);
                Stats current = result.newByPattern.get(pattern);
                report.append(String.format(Locale.ROOT,
                        "| %s | %s | %,d | %s | %s | %,d | %s | %s | %.2f%% |%n",
                        result.interval.label(), pattern,
                        legacy.signals, formatPercent(legacy.precision()), formatSigned(legacy.averageReturn()),
                        current.signals, formatPercent(current.precision()), formatSigned(current.averageReturn()),
                        percentage(current.signals, legacy.signals)));
            }
        }

        report.append("\n## Complete trend-rule grid results\n\n");
        report.append("Every tested combination is included below. Ranking score uses the weaker signal-weighted or ticker-balanced result in each training/validation segment, then applies instability penalties. Signal-count floors are 500 actionable outcomes in both training and validation. Rank is fixed from training and validation only; holdout never affects rank.\n\n");
        report.append("`Candidates` are patterns that passed the configured prior-trend rule before the one-candle gate. `Gate rejected` counts potential one-candle patterns rejected by the immediately following candle. `N` is the retained signal count. `S`, `F`, and `I` mean success, failure, and inconclusive. Precision is `S / (S + F)`. Average return, MFE, and MAE include all retained signals. Ticker-balanced columns give every represented ticker equal weight. BUY and SELL columns expose the complete direction split.\n\n");
        for (IntervalResults result : byInterval.values()) {
            List<RankedConfig> ranking = rank(result);
            report.append("### ").append(result.interval.label()).append("\n\n");
            RankedConfig selected = ranking.getFirst();
            RankedConfig lifecycleBaseline = ranking.stream()
                    .filter(item -> item.config().isFactory())
                    .findFirst()
                    .orElseThrow();
            TrendConfig productionConfig = productionFactory(result.interval);
            RankedConfig production = ranking.stream()
                    .filter(item -> item.config().equals(productionConfig))
                    .findFirst()
                    .orElseThrow();
            report.append(String.format(Locale.ROOT,
                    "Selected without holdout: **%s**. Current production rule: **%s**. Common lifecycle-comparison baseline: **%s**. Holdout selected = %s precision / %s average return; current production rule = %s / %s.\n\n",
                    selected.config().label(), production.config().label(), lifecycleBaseline.config().label(),
                    formatPercent(selected.holdout().precision()), formatSigned(selected.holdout().averageReturn()),
                    formatPercent(production.holdout().precision()), formatSigned(production.holdout().averageReturn())));
            for (Segment segment : Segment.values()) {
                appendCompleteSegmentTable(report, result, ranking, segment);
            }
        }
        appendAdaptiveTrendResults(report, byInterval);
        return report.toString();
    }

    private void appendAdaptiveTrendResults(StringBuilder report,
                                            Map<String, IntervalResults> byInterval) {
        report.append("## Adaptive confirmed-structure and robust-regression experiment\n\n");
        report.append("This experiment compares confirmed ATR-adaptive swing structure, Theil-Sen log-price regression normalized by ATR, strict agreement, and structure with a strong regression veto. All inputs end before the pattern begins. A pivot is unavailable until every configured right-side candle has completed, so no pivot repaints and no future candle enters detection. HMM is intentionally excluded from this stage because it requires a separately fitted, filtered-probability walk-forward design.\n\n");
        report.append("There are 174 configurations per interval: 12 structure-only, 18 regression-only, 72 strict-agreement, and 72 regression-veto configurations. `Robust LCB` is the weakest of the 95% Wilson precision lower bounds and ticker-balanced precision across training and validation. `Retention` is the weaker training/validation signal count relative to the current production rule. `Pareto` means no other adaptive configuration has both equal-or-better robust precision and equal-or-better retention. Holdout never affects rank or Pareto membership.\n\n");
        report.append("**Production outcome:** no adaptive configuration replaces the current defaults in this run. Daily sacrifices too much coverage for an immaterial raw-precision change, weekly loses substantial precision, and monthly's encouraging holdout improvement is not reproduced in either selection segment. The adaptive implementation remains isolated and fully tested so it can be extended without changing live detection.\n\n");
        for (IntervalResults result : byInterval.values()) {
            List<RankedAdaptiveConfig> ranking = rankAdaptive(result);
            RankedAdaptiveConfig selected = ranking.getFirst();
            TrendConfig baselineConfig = productionFactory(result.interval);
            Stats baselineTrain = result.grid.get(baselineConfig).get(Segment.TRAIN).stats;
            Stats baselineValidation = result.grid.get(baselineConfig).get(Segment.VALIDATION).stats;
            Stats baselineHoldout = result.grid.get(baselineConfig).get(Segment.HOLDOUT).stats;
            report.append("### Adaptive ").append(result.interval.label()).append("\n\n");
            report.append(String.format(Locale.ROOT,
                    "Highest robust selection-precision configuration: **%s**. It retained %.2f%% of the production rule's weaker training/validation coverage. Holdout = %,d signals, %s precision, %s average return, and %s ticker-balanced precision. Current production holdout = %,d signals, %s precision, %s average return, and %s ticker-balanced precision.\n\n",
                    selected.config().label(), selected.retentionPercent(),
                    selected.holdout().signals, formatPercent(selected.holdout().precision()),
                    formatSigned(selected.holdout().averageReturn()),
                    formatPercent(selected.holdout().tickerBalancedPrecision()),
                    baselineHoldout.signals, formatPercent(baselineHoldout.precision()),
                    formatSigned(baselineHoldout.averageReturn()),
                    formatPercent(baselineHoldout.tickerBalancedPrecision())));
            report.append("Selection-period comparison:\n\n");
            report.append("| Model | Training N | Training precision | Validation N | Validation precision | Holdout N | Holdout precision | Holdout ticker precision | Holdout avg |\n");
            report.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            appendAdaptiveDecisionRow(report, "Current production", baselineTrain,
                    baselineValidation, baselineHoldout);
            appendAdaptiveDecisionRow(report, selected.config().label(), selected.train(),
                    selected.validation(), selected.holdout());
            report.append("\n").append(adaptiveDecision(result.interval.label())).append("\n\n");
            for (Segment segment : Segment.values()) {
                appendAdaptiveSegmentTable(report, result, ranking, segment);
            }
        }
    }

    private void appendAdaptiveDecisionRow(StringBuilder report, String model,
                                           Stats train, Stats validation, Stats holdout) {
        report.append(String.format(Locale.ROOT,
                "| %s | %,d | %s | %,d | %s | %,d | %s | %s | %s |%n",
                model, train.signals, formatPercent(train.precision()),
                validation.signals, formatPercent(validation.precision()),
                holdout.signals, formatPercent(holdout.precision()),
                formatPercent(holdout.tickerBalancedPrecision()),
                formatSigned(holdout.averageReturn())));
    }

    private String adaptiveDecision(String intervalLabel) {
        return switch (intervalLabel) {
            case "Daily" -> "**Decision: keep the current daily rule.** The adaptive leader retains only about one eighth of the signals and its holdout ticker-balanced precision is lower, so the 0.23-point raw holdout precision increase is not a useful precision-volume trade.";
            case "Weekly" -> "**Decision: keep the current weekly rule.** The adaptive leader produces more signals, but holdout precision, ticker-balanced precision, and average return all deteriorate materially.";
            case "Monthly" -> "**Decision: keep the current monthly rule for now.** The regression leader nearly doubles holdout volume and improves holdout precision, but it is less precise than production in both training and validation and slightly worsens holdout average return. That temporal instability requires a new walk-forward experiment before rollout.";
            default -> throw new IllegalArgumentException("Unsupported interval " + intervalLabel);
        };
    }

    private void appendAdaptiveSegmentTable(StringBuilder report,
                                            IntervalResults result,
                                            List<RankedAdaptiveConfig> ranking,
                                            Segment segment) {
        report.append("#### Adaptive ").append(segmentLabel(segment)).append(" - all ")
                .append(ranking.size()).append(" configurations\n\n");
        report.append("| Rank | Pareto | Model | Robust LCB | Retention | Candidates | Gate rejected | N | Tickers | S | F | I | Precision | Avg | MFE | MAE | Ticker precision | Ticker avg | BUY N | BUY S | BUY F | BUY precision | BUY avg | SELL N | SELL S | SELL F | SELL precision | SELL avg |\n");
        report.append("|---:|:---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (int index = 0; index < ranking.size(); index++) {
            RankedAdaptiveConfig ranked = ranking.get(index);
            ConfigStats config = result.adaptiveGrid.get(ranked.config()).get(segment);
            Stats stats = config.stats;
            LightStats buy = config.buyStats;
            LightStats sell = config.sellStats;
            report.append(String.format(Locale.ROOT,
                    "| %d | %s | %s | %.2f%% | %.2f%% | %,d | %,d | %,d | %,d | %,d | %,d | %,d | %s | %s | %s | %s | %s | %s | %,d | %,d | %,d | %s | %s | %,d | %,d | %,d | %s | %s |%n",
                    index + 1, ranked.pareto() ? "yes" : "", ranked.config().label(),
                    ranked.robustLowerBound(), ranked.retentionPercent(),
                    config.candidates, config.rejected, stats.signals, stats.symbols.size(),
                    stats.successes, stats.failures, stats.inconclusive,
                    formatPercent(stats.precision()), formatSigned(stats.averageReturn()),
                    formatSigned(stats.averageMfe()), formatSigned(stats.averageMae()),
                    formatPercent(stats.tickerBalancedPrecision()), formatSigned(stats.tickerBalancedReturn()),
                    buy.signals, buy.successes, buy.failures,
                    formatPercent(buy.precision()), formatSigned(buy.averageReturn()),
                    sell.signals, sell.successes, sell.failures,
                    formatPercent(sell.precision()), formatSigned(sell.averageReturn())));
        }
        report.append("\n");
    }

    private List<RankedAdaptiveConfig> rankAdaptive(IntervalResults result) {
        TrendConfig production = productionFactory(result.interval);
        Stats baselineTrain = result.grid.get(production).get(Segment.TRAIN).stats;
        Stats baselineValidation = result.grid.get(production).get(Segment.VALIDATION).stats;
        List<RankedAdaptiveConfig> unmarked = new ArrayList<>();
        for (AdaptiveConfig config : result.adaptiveConfigurations) {
            Stats train = result.adaptiveGrid.get(config).get(Segment.TRAIN).stats;
            Stats validation = result.adaptiveGrid.get(config).get(Segment.VALIDATION).stats;
            Stats holdout = result.adaptiveGrid.get(config).get(Segment.HOLDOUT).stats;
            double robustLowerBound = Math.min(
                    Math.min(wilsonLowerBound(train.successes, train.failures),
                            wilsonLowerBound(validation.successes, validation.failures)),
                    Math.min(train.tickerBalancedPrecision(), validation.tickerBalancedPrecision()));
            double retention = Math.min(
                    percentage(train.signals, baselineTrain.signals),
                    percentage(validation.signals, baselineValidation.signals));
            // Undefined precision means that the configuration did not produce an
            // actionable sample in at least one selection segment. It must rank
            // below supported configurations instead of relying on NaN ordering.
            if (!Double.isFinite(robustLowerBound)) robustLowerBound = -1.0;
            if (!Double.isFinite(retention)) retention = 0.0;
            unmarked.add(new RankedAdaptiveConfig(
                    config, train, validation, holdout, robustLowerBound, retention, false));
        }
        List<RankedAdaptiveConfig> marked = unmarked.stream().map(item -> {
            boolean dominated = unmarked.stream().anyMatch(other -> other != item
                    && other.robustLowerBound() >= item.robustLowerBound()
                    && other.retentionPercent() >= item.retentionPercent()
                    && (other.robustLowerBound() > item.robustLowerBound()
                    || other.retentionPercent() > item.retentionPercent()));
            return new RankedAdaptiveConfig(
                    item.config(), item.train(), item.validation(), item.holdout(),
                    item.robustLowerBound(), item.retentionPercent(), !dominated);
        }).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        marked.sort(Comparator.comparingDouble(RankedAdaptiveConfig::robustLowerBound).reversed()
                .thenComparing(Comparator.comparingDouble(
                        RankedAdaptiveConfig::retentionPercent).reversed())
                .thenComparing(item -> item.config().label()));
        return List.copyOf(marked);
    }

    private double wilsonLowerBound(long successes, long failures) {
        long total = successes + failures;
        if (total == 0) return Double.NaN;
        double z = 1.959963984540054;
        double proportion = (double) successes / total;
        double denominator = 1.0 + z * z / total;
        double center = proportion + z * z / (2.0 * total);
        double margin = z * Math.sqrt(
                proportion * (1.0 - proportion) / total + z * z / (4.0 * total * total));
        return (center - margin) / denominator * 100.0;
    }

    private void appendCompleteSegmentTable(StringBuilder report,
                                            IntervalResults result,
                                            List<RankedConfig> ranking,
                                            Segment segment) {
        report.append("#### ").append(segmentLabel(segment)).append(" - all ")
                .append(ranking.size()).append(" combinations\n\n");
        report.append("| Rank | Trend rule | Score | Candidates | Gate rejected | N | Tickers | S | F | I | Precision | Avg | MFE | MAE | Ticker precision | Ticker avg | BUY N | BUY S | BUY F | BUY precision | BUY avg | SELL N | SELL S | SELL F | SELL precision | SELL avg |\n");
        report.append("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (int index = 0; index < ranking.size(); index++) {
            RankedConfig ranked = ranking.get(index);
            ConfigStats config = result.grid.get(ranked.config()).get(segment);
            Stats stats = config.stats;
            LightStats buy = config.buyStats;
            LightStats sell = config.sellStats;
            report.append(String.format(Locale.ROOT,
                    "| %d | %s | %.3f | %,d | %,d | %,d | %,d | %,d | %,d | %,d | %s | %s | %s | %s | %s | %s | %,d | %,d | %,d | %s | %s | %,d | %,d | %,d | %s | %s |%n",
                    index + 1, ranked.config().label(), ranked.score(),
                    config.candidates, config.rejected, stats.signals, stats.symbols.size(),
                    stats.successes, stats.failures, stats.inconclusive,
                    formatPercent(stats.precision()), formatSigned(stats.averageReturn()),
                    formatSigned(stats.averageMfe()), formatSigned(stats.averageMae()),
                    formatPercent(stats.tickerBalancedPrecision()), formatSigned(stats.tickerBalancedReturn()),
                    buy.signals, buy.successes, buy.failures,
                    formatPercent(buy.precision()), formatSigned(buy.averageReturn()),
                    sell.signals, sell.successes, sell.failures,
                    formatPercent(sell.precision()), formatSigned(sell.averageReturn())));
        }
        report.append("\n");
    }

    private String segmentLabel(Segment segment) {
        return switch (segment) {
            case TRAIN -> "Training (2003-2010)";
            case VALIDATION -> "Validation (2011-2014)";
            case HOLDOUT -> "Holdout (2015-2018)";
        };
    }

    private TrendConfig productionFactory(IntervalSpec interval) {
        return switch (interval.aggregation()) {
            case DAILY, MONTHLY -> new TrendConfig(3, 5, 1.5);
            case WEEKLY -> new TrendConfig(4, 6, 16.0);
        };
    }

    private List<RankedConfig> rank(IntervalResults result) {
        List<RankedConfig> ranking = new ArrayList<>();
        for (TrendConfig config : result.configurations) {
            Stats train = result.grid.get(config).get(Segment.TRAIN).stats;
            Stats validation = result.grid.get(config).get(Segment.VALIDATION).stats;
            Stats holdout = result.grid.get(config).get(Segment.HOLDOUT).stats;
            double trainPrecision = Math.min(train.precision(), train.tickerBalancedPrecision());
            double validationPrecision = Math.min(validation.precision(), validation.tickerBalancedPrecision());
            double trainReturn = Math.min(train.averageReturn(), train.tickerBalancedReturn());
            double validationReturn = Math.min(validation.averageReturn(), validation.tickerBalancedReturn());
            double precisionFloor = Math.min(trainPrecision, validationPrecision);
            double returnFloor = Math.min(trainReturn, validationReturn);
            double precisionInstability = Math.abs(trainPrecision - validationPrecision);
            double returnInstability = Math.abs(trainReturn - validationReturn);
            double samplePenalty = train.actionable() < 500 || validation.actionable() < 500 ? 100.0 : 0.0;
            double score = precisionFloor - 50.0
                    + returnFloor / result.interval.outcomeMove() * 25.0
                    - precisionInstability * 0.25
                    - returnInstability / result.interval.outcomeMove() * 5.0
                    - samplePenalty;
            ranking.add(new RankedConfig(config, train, validation, holdout, score));
        }
        ranking.sort(Comparator.comparingDouble(RankedConfig::score).reversed()
                .thenComparing(item -> item.config().label()));
        return ranking;
    }

    private void appendStats(StringBuilder output, String interval, String cohort, Stats stats) {
        output.append(String.format(Locale.ROOT,
                "| %s | %s | %,d | %,d | %s | %s | %s | %s | %s | %s | %,d |%n",
                interval, cohort, stats.signals, stats.actionable(),
                formatPercent(stats.precision()), formatSigned(stats.averageReturn()),
                formatPercent(stats.tickerBalancedPrecision()), formatSigned(stats.tickerBalancedReturn()),
                formatSigned(stats.averageMfe()), formatSigned(stats.averageMae()),
                stats.symbols.size()));
    }

    private List<UniverseEntry> loadManifest(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "Missing power manifest: " + path);
        List<UniverseEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            assertEquals("symbol\tname\tsector\tcap_tier\tcohort\trole\tfirst_date\tlast_date\tdaily_candles\tmarket_cap_usd", header);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split("\t", -1);
                entries.add(new UniverseEntry(
                        values[0], LocalDate.parse(values[6]), LocalDate.parse(values[7]),
                        Integer.parseInt(values[8])));
            }
        }
        assertEquals(EXPECTED_SYMBOLS, entries.size());
        assertEquals(EXPECTED_DAILY_CANDLES,
                entries.stream().mapToInt(UniverseEntry::dailyCandles).sum());
        assertEquals(entries.size(), entries.stream().map(UniverseEntry::symbol).distinct().count());
        return List.copyOf(entries);
    }

    private Map<String, List<Candle>> loadDailyCandles(Path path,
                                                       List<UniverseEntry> universe) throws IOException {
        assertTrue(Files.isRegularFile(path), "Missing prepared power candles: " + path);
        Map<String, UniverseEntry> expected = new HashMap<>();
        Map<String, List<Candle>> candles = new LinkedHashMap<>();
        for (UniverseEntry entry : universe) {
            expected.put(entry.symbol(), entry);
            candles.put(entry.symbol(), new ArrayList<>(entry.dailyCandles()));
        }
        try (InputStream file = Files.newInputStream(path);
             InputStream data = path.toString().endsWith(".gz") ? new GZIPInputStream(file) : file;
             BufferedReader reader = new BufferedReader(new InputStreamReader(data, StandardCharsets.UTF_8))) {
            assertEquals("ticker,date,open,high,low,close,volume", reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                String[] value = line.split(",", -1);
                UniverseEntry entry = expected.get(value[0]);
                if (entry == null) continue;
                LocalDate date = LocalDate.parse(value[1]);
                candles.get(entry.symbol()).add(new Candle(
                        entry.symbol(), "1d", date.atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                        Double.parseDouble(value[2]), Double.parseDouble(value[3]),
                        Double.parseDouble(value[4]), Double.parseDouble(value[5]),
                        Long.parseLong(value[6])));
            }
        }
        for (UniverseEntry entry : universe) {
            List<Candle> values = candles.get(entry.symbol());
            values.sort(Comparator.comparing(Candle::getTimestamp));
            assertEquals(entry.dailyCandles(), values.size(), "Candle-count drift for " + entry.symbol());
            assertEquals(entry.firstDate(), utcDate(values.getFirst().getTimestamp()));
            assertEquals(entry.lastDate(), utcDate(values.getLast().getTimestamp()));
        }
        return candles;
    }

    private List<Candle> aggregate(List<Candle> daily, Aggregation aggregation) {
        if (aggregation == Aggregation.DAILY) return daily;
        Map<Object, MutableBar> values = new LinkedHashMap<>();
        for (Candle candle : daily) {
            LocalDate date = utcDate(candle.getTimestamp());
            Object key = aggregation == Aggregation.WEEKLY
                    ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    : YearMonth.from(date);
            values.computeIfAbsent(key, ignored -> new MutableBar(
                    candle.getSymbol(), aggregation.apiInterval(), candle)).add(candle);
        }
        return values.values().stream().map(MutableBar::toCandle).toList();
    }

    private Segment segment(long timestamp) {
        int year = utcDate(timestamp).getYear();
        if (year <= 2010) return Segment.TRAIN;
        if (year <= 2014) return Segment.VALIDATION;
        return Segment.HOLDOUT;
    }

    private List<CandlePattern> oneCandlePatterns() {
        return List.of(CandlePattern.HAMMER, CandlePattern.INVERTED_HAMMER,
                CandlePattern.HANGING_MAN, CandlePattern.SHOOTING_STAR);
    }

    private double percent(double start, double end) {
        return start == 0.0 ? 0.0 : (end - start) / start * 100.0;
    }

    private double percentage(long numerator, long denominator) {
        return denominator == 0 ? Double.NaN : numerator * 100.0 / denominator;
    }

    private String formatPercent(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f%%", value) : "n/a";
    }

    private String formatSigned(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%+.2f%%", value) : "n/a";
    }

    private LocalDate utcDate(long timestamp) {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private final class IntervalResults {
        private final IntervalSpec interval;
        private final List<TrendConfig> configurations;
        private final List<AdaptiveConfig> adaptiveConfigurations;
        private final Map<TrendConfig, EnumMap<Segment, ConfigStats>> grid = new LinkedHashMap<>();
        private final Map<AdaptiveConfig, EnumMap<Segment, ConfigStats>> adaptiveGrid = new LinkedHashMap<>();
        private final Stats legacyAll = new Stats();
        private final Stats newAll = new Stats();
        private final Stats legacyOneCandle = new Stats();
        private final Stats acceptedLegacyTiming = new Stats();
        private final Stats newOneCandle = new Stats();
        private final Map<CandlePattern, Stats> legacyByPattern = new EnumMap<>(CandlePattern.class);
        private final Map<CandlePattern, Stats> newByPattern = new EnumMap<>(CandlePattern.class);
        private int symbols;
        private long candles;
        private long geometricCandidates;
        private long oneCandleCandidates;
        private long oneCandleAccepted;
        private long oneCandleRejected;

        private IntervalResults(IntervalSpec interval,
                                List<TrendConfig> configurations,
                                List<AdaptiveConfig> adaptiveConfigurations) {
            this.interval = interval;
            this.configurations = configurations;
            this.adaptiveConfigurations = adaptiveConfigurations;
            for (TrendConfig config : configurations) {
                EnumMap<Segment, ConfigStats> segments = new EnumMap<>(Segment.class);
                for (Segment segment : Segment.values()) segments.put(segment, new ConfigStats());
                grid.put(config, segments);
            }
            for (AdaptiveConfig config : adaptiveConfigurations) {
                EnumMap<Segment, ConfigStats> segments = new EnumMap<>(Segment.class);
                for (Segment segment : Segment.values()) segments.put(segment, new ConfigStats());
                adaptiveGrid.put(config, segments);
            }
            for (CandlePattern pattern : CandlePattern.values()) {
                legacyByPattern.put(pattern, new Stats());
                newByPattern.put(pattern, new Stats());
            }
        }

        private TrendConfig factoryConfig() {
            return configurations.stream().filter(TrendConfig::isFactory).findFirst().orElseThrow();
        }
    }

    private static final class ConfigStats {
        private final Stats stats = new Stats();
        private final LightStats buyStats = new LightStats();
        private final LightStats sellStats = new LightStats();
        private long candidates;
        private long rejected;

        private void add(String symbol,
                         GeometricPatternCandidate candidate,
                         EvaluatedTrade trade,
                         double outcomeMove) {
            stats.add(symbol, candidate, trade, outcomeMove);
            (candidate.tradeSignal() == TradeSignal.BUY ? buyStats : sellStats)
                    .add(trade, outcomeMove);
        }
    }

    private static final class LightStats {
        private long signals;
        private long successes;
        private long failures;
        private double returnSum;

        private void add(EvaluatedTrade trade, double outcomeMove) {
            signals++;
            if (trade.directionalReturn() >= outcomeMove) successes++;
            else if (trade.directionalReturn() <= -outcomeMove) failures++;
            returnSum += trade.directionalReturn();
        }

        private long actionable() { return successes + failures; }
        private double precision() {
            return actionable() == 0 ? Double.NaN : successes * 100.0 / actionable();
        }
        private double averageReturn() { return signals == 0 ? Double.NaN : returnSum / signals; }
    }

    private static final class Stats {
        private long signals;
        private long buy;
        private long sell;
        private long successes;
        private long failures;
        private long inconclusive;
        private double returnSum;
        private double mfeSum;
        private double maeSum;
        private final Set<String> symbols = new HashSet<>();
        private final Map<String, SymbolStats> bySymbol = new HashMap<>();

        private void add(String symbol,
                         GeometricPatternCandidate candidate,
                         EvaluatedTrade trade,
                         double outcomeMove) {
            signals++;
            if (candidate.tradeSignal() == TradeSignal.BUY) buy++; else sell++;
            int outcome;
            if (trade.directionalReturn() >= outcomeMove) {
                successes++;
                outcome = 1;
            } else if (trade.directionalReturn() <= -outcomeMove) {
                failures++;
                outcome = -1;
            } else {
                inconclusive++;
                outcome = 0;
            }
            returnSum += trade.directionalReturn();
            mfeSum += trade.maximumFavorableExcursion();
            maeSum += trade.maximumAdverseExcursion();
            symbols.add(symbol);
            bySymbol.computeIfAbsent(symbol, ignored -> new SymbolStats())
                    .add(outcome, trade.directionalReturn());
        }

        private long actionable() { return successes + failures; }
        private double precision() {
            return actionable() == 0 ? Double.NaN : successes * 100.0 / actionable();
        }
        private double averageReturn() { return signals == 0 ? Double.NaN : returnSum / signals; }
        private double averageMfe() { return signals == 0 ? Double.NaN : mfeSum / signals; }
        private double averageMae() { return signals == 0 ? Double.NaN : maeSum / signals; }
        private double tickerBalancedPrecision() {
            return bySymbol.values().stream()
                    .filter(value -> value.actionable() > 0)
                    .mapToDouble(SymbolStats::precision)
                    .average()
                    .orElse(Double.NaN);
        }
        private double tickerBalancedReturn() {
            return bySymbol.values().stream()
                    .mapToDouble(SymbolStats::averageReturn)
                    .average()
                    .orElse(Double.NaN);
        }
    }

    private static final class SymbolStats {
        private long signals;
        private long successes;
        private long failures;
        private double returnSum;

        private void add(int outcome, double directionalReturn) {
            signals++;
            if (outcome > 0) successes++;
            else if (outcome < 0) failures++;
            returnSum += directionalReturn;
        }

        private long actionable() { return successes + failures; }
        private double precision() { return successes * 100.0 / actionable(); }
        private double averageReturn() { return returnSum / signals; }
    }

    private record UniverseEntry(String symbol, LocalDate firstDate, LocalDate lastDate, int dailyCandles) { }
    private record WindowRule(int minimumDirectionalConfirmations, int lookbackCandles) { }
    private record IntervalSpec(String label, String apiInterval, Aggregation aggregation,
                                int minimumHistory, int horizon, double outcomeMove,
                                List<Double> trendMoves) { }
    private record TrendConfig(int requiredConfirmations, int lookback, double move) {
        private TrendDetectionRules rules() {
            return new TrendDetectionRules(requiredConfirmations, lookback, move);
        }
        private boolean isFactory() { return requiredConfirmations == 3 && lookback == 5 && move == 1.5; }
        private String label() {
            return String.format(Locale.ROOT, "%.1f%% - %d/%d%s",
                    move, requiredConfirmations, lookback,
                    isFactory() ? " (common lifecycle baseline)" : "");
        }
    }
    private record AdaptiveConfig(String label,
                                  CandlestickAdaptiveTrendService.TrendModelParameters parameters) {
        private static AdaptiveConfig of(TrendPolicy policy,
                                         SwingParameters swing,
                                         RegressionParameters regression) {
            String structure = swing == null ? ""
                    : String.format(Locale.ROOT, "L%d-R%d-B%d-P%.2f",
                    swing.leftBars(), swing.rightConfirmationBars(),
                    swing.maximumLookbackBars(), swing.minimumProminenceAtr());
            String robustRegression = regression == null ? ""
                    : String.format(Locale.ROOT, "W%d-S%.2f-R%.2f",
                    regression.windowBars(), regression.minimumAbsoluteNormalizedSlope(),
                    regression.minimumRSquared());
            String label = switch (policy) {
                case STRUCTURE_ONLY -> "Structure " + structure;
                case REGRESSION_ONLY -> "Regression " + robustRegression;
                case STRICT_AGREEMENT -> "Strict " + structure + " + " + robustRegression;
                case STRUCTURE_WITH_REGRESSION_VETO -> "Veto " + structure + " + " + robustRegression;
            };
            return new AdaptiveConfig(label,
                    new CandlestickAdaptiveTrendService.TrendModelParameters(
                            policy, swing, regression));
        }
    }
    private record EvaluatedTrade(int entryIndex, double directionalReturn,
                                  double maximumFavorableExcursion,
                                  double maximumAdverseExcursion) { }
    private record RankedConfig(TrendConfig config, Stats train, Stats validation,
                                Stats holdout, double score) { }
    private record RankedAdaptiveConfig(AdaptiveConfig config,
                                        Stats train,
                                        Stats validation,
                                        Stats holdout,
                                        double robustLowerBound,
                                        double retentionPercent,
                                        boolean pareto) { }

    private enum Segment { TRAIN, VALIDATION, HOLDOUT }
    private enum Aggregation {
        DAILY("1d"), WEEKLY("1wk"), MONTHLY("1mo");
        private final String apiInterval;
        Aggregation(String apiInterval) { this.apiInterval = apiInterval; }
        private String apiInterval() { return apiInterval; }
    }

    private static final class MutableBar {
        private final String symbol;
        private final String interval;
        private long timestamp;
        private final double open;
        private double high;
        private double low;
        private double close;
        private long volume;

        private MutableBar(String symbol, String interval, Candle first) {
            this.symbol = symbol;
            this.interval = interval;
            this.timestamp = first.getTimestamp();
            this.open = first.getOpenPrice();
            this.high = first.getHighPrice();
            this.low = first.getLowPrice();
            this.close = first.getClosePrice();
            this.volume = first.getVolume() == null ? 0L : first.getVolume();
        }

        private void add(Candle candle) {
            if (candle.getTimestamp() == timestamp) return;
            timestamp = candle.getTimestamp();
            high = Math.max(high, candle.getHighPrice());
            low = Math.min(low, candle.getLowPrice());
            close = candle.getClosePrice();
            long next = candle.getVolume() == null ? 0L : candle.getVolume();
            volume = Long.MAX_VALUE - volume < next ? Long.MAX_VALUE : volume + next;
        }

        private Candle toCandle() {
            return new Candle(symbol, interval, timestamp, open, high, low, close, volume);
        }
    }
}
