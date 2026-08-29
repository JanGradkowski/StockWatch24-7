package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.GeometricPatternCandidate;
import org.example.stockwatch247.service.CandlePatternDetectionService.PriorTrendAssessment;
import org.example.stockwatch247.service.CandlestickSignalLifecyclePolicy.LifecycleResolution;
import org.example.stockwatch247.service.CandlestickSignalLifecyclePolicy.TradePlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in, research-only ATR circuit-breaker and reward/risk grid.
 *
 * <p>The grid uses the production factory pattern geometry, direction-aware
 * factory trend policy, mandatory one-candle detection gate, close-based
 * target/stop resolution, and candle-8 time stop. It never changes production
 * settings. Configuration selection uses 2019-2021 and validation uses the
 * untouched 2022-2025 period.</p>
 */
@EnabledIfSystemProperty(named = "backtest.candlestick.atr-grid.enabled", matches = "true")
class CandlestickAtrRiskRewardGridResearchTest {
    private static final Path DEFAULT_MANIFEST = Path.of(
            "target/expanded-backtest-data/post-2018-candlestick-universe.tsv");
    private static final Path DEFAULT_DATA = Path.of(
            "target/expanded-backtest-data/post-2018-candles.csv.gz");
    private static final Path DEFAULT_REPORT = Path.of(
            "target/expanded-backtest-data/candlestick-atr-rr-grid-report.md");
    private static final Path DEFAULT_CSV = Path.of(
            "target/expanded-backtest-data/candlestick-atr-rr-grid-results.csv");
    private static final int EXPECTED_MINIMUM_SYMBOLS = 2_000;
    private static final LocalDate EVALUATION_START = LocalDate.of(2019, 1, 1);
    private static final LocalDate SELECTION_END = LocalDate.of(2021, 12, 31);
    private static final LocalDate VALIDATION_START = LocalDate.of(2022, 1, 1);
    private static final LocalDate EVALUATION_END = LocalDate.of(2025, 12, 31);
    private static final int TIME_STOP = CandlestickSignalLifecyclePolicy.TIME_STOP_CANDLES;
    private static final List<Integer> ATR_PERIODS = List.of(7, 14, 21, 28);
    private static final List<Double> ATR_MULTIPLIERS = List.of(1.0, 1.5, 2.0, 2.5);
    private static final List<Double> REWARD_RISK_RATIOS = List.of(1.0, 1.5, 2.0, 2.5, 3.0, 4.0);

    private final TechnicalIndicatorEnrichmentService enrichmentService =
            new TechnicalIndicatorEnrichmentService();
    private final CandlePatternDetectionService detectionService =
            new CandlePatternDetectionService();

    @Test
    void evaluatesAtrAndRewardRiskGridOnPost2018Universe() throws Exception {
        Path manifest = Path.of(System.getProperty(
                "backtest.candlestick.atr-grid.manifest-file", DEFAULT_MANIFEST.toString()));
        Path data = Path.of(System.getProperty(
                "backtest.candlestick.atr-grid.data-file", DEFAULT_DATA.toString()));
        Path reportPath = Path.of(System.getProperty(
                "backtest.candlestick.atr-grid.report-file", DEFAULT_REPORT.toString()));
        Path csvPath = Path.of(System.getProperty(
                "backtest.candlestick.atr-grid.csv-file", DEFAULT_CSV.toString()));
        int maximumSymbols = Integer.parseInt(System.getProperty(
                "backtest.candlestick.atr-grid.max-symbols", Integer.toString(Integer.MAX_VALUE)));

        List<UniverseEntry> fullUniverse = loadManifest(manifest);
        List<UniverseEntry> universe = fullUniverse.subList(
                0, Math.min(Math.max(1, maximumSymbols), fullUniverse.size()));
        Map<String, UniverseEntry> selected = new HashMap<>();
        universe.forEach(entry -> selected.put(entry.symbol(), entry));

        Map<TimeInterval, IntervalResults> results = new EnumMap<>(TimeInterval.class);
        intervalSpecs().forEach(spec -> results.put(spec.interval(), new IntervalResults(spec, grid(spec))));
        Progress progress = new Progress(universe.size());
        streamDailyCandles(data, selected, symbolCandles -> {
            UniverseEntry entry = selected.get(symbolCandles.symbol());
            assertEquals(entry.dailyCandles(), symbolCandles.candles().size(),
                    "Candle count drift for " + entry.symbol());
            for (IntervalSpec spec : intervalSpecs()) {
                evaluateSymbol(entry.symbol(), aggregate(symbolCandles.candles(), spec), results.get(spec.interval()));
            }
            progress.completed++;
            if (progress.completed % 25 == 0 || progress.completed == progress.total) {
                System.out.printf(Locale.ROOT, "ATR/R:R grid processed %,d/%,d symbols.%n",
                        progress.completed, progress.total);
            }
        });

        assertEquals(universe.size(), progress.completed, "Not every selected symbol was processed.");
        assertTrue(results.values().stream().allMatch(result -> result.detectedTrades > 0),
                "Every interval needs detected trades.");
        assertTrue(results.values().stream().allMatch(result ->
                        result.stats.values().stream().allMatch(periods -> periods.get(Period.ALL).trades > 0)),
                "Every grid configuration needs evaluated trades.");

        Files.createDirectories(reportPath.toAbsolutePath().getParent());
        Files.createDirectories(csvPath.toAbsolutePath().getParent());
        writeCsv(csvPath, results);
        String report = buildReport(universe, results, csvPath);
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("ATR/R:R grid report: " + reportPath.toAbsolutePath());
        System.out.println("ATR/R:R grid CSV: " + csvPath.toAbsolutePath());
    }

    private void evaluateSymbol(String symbol, List<Candle> candles, IntervalResults results) {
        IntervalSpec spec = results.spec;
        if (candles.size() < spec.minimumHistory() + TIME_STOP + 2) return;
        List<EnrichedCandle> enriched = enrichmentService.enrich(candles, candles.size(), spec.interval());
        if (enriched.size() != candles.size()) {
            throw new IllegalStateException("Enrichment size drift for " + symbol + " " + spec.apiInterval());
        }
        results.symbols++;
        results.candles += candles.size();

        int lastCandidateIndex = candles.size() - TIME_STOP - 2;
        for (int signalIndex = spec.minimumHistory() - 1;
             signalIndex <= lastCandidateIndex; signalIndex++) {
            List<GeometricPatternCandidate> candidates =
                    detectionService.geometricCandidatesAt(enriched, signalIndex);
            if (candidates.isEmpty()) continue;
            for (GeometricPatternCandidate candidate : candidates) {
                int patternStart = signalIndex - candidate.patternCandleCount() + 1;
                PriorTrendAssessment assessment = detectionService.assessPreparedFactoryPriorTrend(
                        enriched, patternStart, spec.interval(), candidate.tradeSignal());
                if (!candidate.accepts(assessment)) continue;
                evaluateCandidate(symbol, candles, signalIndex, candidate, results);
            }
        }
    }

    private void evaluateCandidate(String symbol,
                                   List<Candle> candles,
                                   int signalIndex,
                                   GeometricPatternCandidate candidate,
                                   IntervalResults results) {
        int entryIndex = signalIndex;
        if (CandlestickSignalLifecyclePolicy.requiresNextCandleConfirmation(candidate.pattern())) {
            LifecycleResolution gate = CandlestickSignalLifecyclePolicy.resolveCandidateGate(
                    candidate.pattern(), candidate.tradeSignal(),
                    candles.get(signalIndex).getClosePrice(), List.of(candles.get(signalIndex + 1)));
            if (gate == null || gate.status() == SignalLifecycleStatus.REJECTED) {
                results.rejectedCandidates++;
                return;
            }
            entryIndex++;
        }
        if (entryIndex + TIME_STOP >= candles.size()) return;
        LocalDate entryDate = utcDate(candles.get(entryIndex).getTimestamp());
        if (entryDate.isBefore(EVALUATION_START) || entryDate.isAfter(EVALUATION_END)) return;

        int formationStart = signalIndex - candidate.patternCandleCount() + 1;
        List<Candle> formation = candles.subList(formationStart, signalIndex + 1);
        double entry = candles.get(entryIndex).getClosePrice();
        double structuralStop = CandlestickSignalLifecyclePolicy.structuralStopPrice(
                candidate.pattern(), formation);
        double structuralRisk = candidate.tradeSignal() == TradeSignal.BUY
                ? entry - structuralStop : structuralStop - entry;
        if (!Double.isFinite(structuralRisk) || structuralRisk <= 0.0) {
            results.invalidStructuralStops++;
            return;
        }

        results.detectedTrades++;
        Map<Integer, Double> atrByPeriod = new HashMap<>();
        for (int atrPeriod : ATR_PERIODS) {
            atrByPeriod.put(atrPeriod,
                    CandlestickSignalLifecyclePolicy.averageTrueRange(candles, entryIndex, atrPeriod));
        }
        List<Candle> outcomeCandles = candles.subList(entryIndex + 1, entryIndex + TIME_STOP + 1);
        Period samplePeriod = entryDate.isBefore(VALIDATION_START) ? Period.SELECTION : Period.VALIDATION;

        for (GridConfiguration configuration : results.configurations) {
            double atr = configuration.enabled()
                    ? atrByPeriod.get(configuration.atrPeriod()) : Double.NaN;
            CandlestickPatternPreferencesService.CircuitBreakerSettings circuitBreaker =
                    new CandlestickPatternPreferencesService.CircuitBreakerSettings(
                            configuration.enabled(), configuration.enabled() ? configuration.atrPeriod() : 14,
                            configuration.enabled() ? configuration.atrMultiplier() : 1.5,
                            configuration.thresholdPercent());
            TradePlan plan;
            try {
                plan = CandlestickSignalLifecyclePolicy.tradePlan(
                        candidate.tradeSignal(), entry, structuralStop, results.spec.interval(),
                        configuration.rewardRisk(), atr, circuitBreaker);
            } catch (IllegalStateException invalidPlan) {
                addSkipped(results.stats.get(configuration), samplePeriod);
                continue;
            }
            LifecycleResolution resolution = CandlestickSignalLifecyclePolicy.resolve(
                    candidate.tradeSignal(), plan.profitTargetPrice(), plan.stopLossPrice(),
                    outcomeCandles, TIME_STOP);
            if (resolution == null) {
                throw new IllegalStateException("A complete eight-candle outcome did not resolve.");
            }
            double exit = resolution.resolutionCandle().getClosePrice();
            double directionalReturn = candidate.tradeSignal() == TradeSignal.BUY
                    ? percent(entry, exit) : -percent(entry, exit);
            addResult(results.stats.get(configuration), samplePeriod,
                    resolution.status(), directionalReturn, plan.atrCircuitBreakerApplied());
        }
    }

    private void addSkipped(Map<Period, Stats> periods, Period samplePeriod) {
        periods.get(samplePeriod).skippedInvalidTargets++;
        periods.get(Period.ALL).skippedInvalidTargets++;
    }

    private void addResult(Map<Period, Stats> periods,
                           Period samplePeriod,
                           SignalLifecycleStatus status,
                           double directionalReturn,
                           boolean circuitBreakerApplied) {
        periods.get(samplePeriod).add(status, directionalReturn, circuitBreakerApplied);
        periods.get(Period.ALL).add(status, directionalReturn, circuitBreakerApplied);
    }

    private List<GridConfiguration> grid(IntervalSpec spec) {
        List<GridConfiguration> configurations = new ArrayList<>();
        for (double rewardRisk : REWARD_RISK_RATIOS) {
            configurations.add(GridConfiguration.disabled(rewardRisk, spec.defaultThreshold()));
        }
        for (double rewardRisk : REWARD_RISK_RATIOS) {
            for (int period : ATR_PERIODS) {
                for (double multiplier : ATR_MULTIPLIERS) {
                    for (double threshold : spec.thresholdGrid()) {
                        configurations.add(new GridConfiguration(
                                true, period, multiplier, threshold, rewardRisk));
                    }
                }
            }
        }
        return List.copyOf(configurations);
    }

    private List<IntervalSpec> intervalSpecs() {
        return List.of(
                new IntervalSpec(TimeInterval.DAILY, "1d", "Daily", Aggregation.DAILY,
                        250, 2.0, 25.0, List.of(15.0, 25.0, 35.0)),
                new IntervalSpec(TimeInterval.WEEKLY, "1wk", "Weekly", Aggregation.WEEKLY,
                        80, 3.0, 50.0, List.of(25.0, 50.0, 75.0)),
                new IntervalSpec(TimeInterval.MONTHLY, "1mo", "Monthly", Aggregation.MONTHLY,
                        36, 3.0, 50.0, List.of(25.0, 50.0, 75.0))
        );
    }

    private String buildReport(List<UniverseEntry> universe,
                               Map<TimeInterval, IntervalResults> byInterval,
                               Path csvPath) {
        long dailyCandles = universe.stream().mapToLong(UniverseEntry::dailyCandles).sum();
        StringBuilder report = new StringBuilder();
        report.append("# Candlestick ATR circuit-breaker and R:R grid\n\n");
        report.append(String.format(Locale.ROOT,
                "Universe: **%,d stocks** and **%,d adjusted daily candles**. " +
                        "Pattern/trend detection uses only data available at each signal. " +
                        "2019-2021 selects configurations; 2022-2025 is untouched validation.\n\n",
                universe.size(), dailyCandles));
        report.append("Execution assumptions: entry at the production detection close; one-candle patterns " +
                "must pass their next-candle gate; target and stop are tested on completed closes; " +
                "candle 8 closes unresolved trades; returns use the actual resolving close; " +
                "no fees, spread, borrow cost, tax, or position overlap constraint.\n\n");
        report.append("Precision definitions: **target hit rate** is target exits / every trade; " +
                "**decisive precision** is target / (target + stop), excluding time stops; " +
                "**profitable exits** includes profitable candle-8 exits.\n\n");
        report.append("Grid: ATR periods 7/14/21/28; multipliers 1.0/1.5/2.0/2.5; " +
                "R:R 1.0/1.5/2.0/2.5/3.0/4.0; daily thresholds 15/25/35%; " +
                "weekly/monthly thresholds 25/50/75%; plus circuit-breaker-off controls.\n\n");

        for (IntervalResults results : byInterval.values()) {
            report.append("## ").append(results.spec.label()).append("\n\n");
            report.append(String.format(Locale.ROOT,
                    "Coverage: %,d symbols, %,d aggregated candles, %,d accepted trades; " +
                            "%,d one-candle candidates rejected at the detection gate and %,d invalid structural stops excluded.\n\n",
                    results.symbols, results.candles, results.detectedTrades,
                    results.rejectedCandidates, results.invalidStructuralStops));

            GridConfiguration factory = findFactory(results);
            GridConfiguration disabledControl = results.configurations.stream()
                    .filter(configuration -> !configuration.enabled()
                            && Double.compare(configuration.rewardRisk(), results.spec.factoryRewardRisk()) == 0)
                    .findFirst().orElseThrow();
            report.append("### Current defaults and structural-stop control\n\n");
            report.append("| Period | Configuration | N | Target hit | Decisive precision | Profitable exits | Avg return | Target / Stop / Time | CB applied |\n");
            report.append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
            appendConfigurationRows(report, results, factory, "Current default");
            appendConfigurationRows(report, results, disabledControl, "Circuit breaker off");

            List<GridConfiguration> selected = topSelectionConfigurations(results, 10);
            report.append("\n### Top configurations selected only on 2019-2021 average return\n\n");
            report.append("The validation columns were not used for ranking. This table is descriptive; " +
                    "multiple grid comparisons make small differences unreliable.\n\n");
            report.append("| Rank | Configuration | Selection N | Selection avg | Selection target | Validation N | Validation avg | Validation target | Validation decisive | Validation profitable |\n");
            report.append("|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            int rank = 1;
            for (GridConfiguration configuration : selected) {
                Stats selection = results.stats.get(configuration).get(Period.SELECTION);
                Stats validation = results.stats.get(configuration).get(Period.VALIDATION);
                report.append(String.format(Locale.ROOT,
                        "| %d | %s | %,d | %+.3f%% | %.2f%% | %,d | %+.3f%% | %.2f%% | %.2f%% | %.2f%% |%n",
                        rank++, configuration.label(), selection.trades, selection.averageReturn(),
                        selection.targetHitRate(), validation.trades, validation.averageReturn(),
                        validation.targetHitRate(), validation.decisivePrecision(),
                        validation.profitableExitRate()));
            }
            if (selected.isEmpty()) {
                report.append("| - | No selection-period trades were available | 0 | - | - | 0 | - | - | - | - |\n");
            } else {
                appendValidationDecision(report, results, factory, selected.getFirst());
            }
        }

        report.append("## Interpretation limits\n\n");
        report.append("Signals on the same stock can overlap and some candles can produce more than one named pattern, " +
                "so observations are not fully independent. Average return is per signal, not a compounded portfolio return. " +
                "Short returns omit borrow availability and borrow fees. The CSV contains every configuration and period: `")
                .append(csvPath.toString().replace('\\', '/')).append("`.\n");
        return report.toString();
    }

    private void appendConfigurationRows(StringBuilder report,
                                         IntervalResults results,
                                         GridConfiguration configuration,
                                         String label) {
        for (Period period : Period.values()) {
            Stats stats = results.stats.get(configuration).get(period);
            report.append(String.format(Locale.ROOT,
                    "| %s | %s: %s | %,d | %.2f%% | %.2f%% | %.2f%% | %+.3f%% | %,d / %,d / %,d | %.2f%% |%n",
                    period.label, label, configuration.label(), stats.trades,
                    stats.targetHitRate(), stats.decisivePrecision(), stats.profitableExitRate(),
                    stats.averageReturn(), stats.targets, stats.stops, stats.timeStops,
                    stats.circuitBreakerRate()));
        }
    }

    private void appendValidationDecision(StringBuilder report,
                                          IntervalResults results,
                                          GridConfiguration factory,
                                          GridConfiguration selected) {
        Stats factoryValidation = results.stats.get(factory).get(Period.VALIDATION);
        Stats selectedValidation = results.stats.get(selected).get(Period.VALIDATION);
        double returnDelta = selectedValidation.averageReturn() - factoryValidation.averageReturn();
        double targetDelta = selectedValidation.targetHitRate() - factoryValidation.targetHitRate();
        report.append(String.format(Locale.ROOT,
                "\nEarly-selected leader `%s` changed validation average return by **%+.3f percentage points** " +
                        "and target hit rate by **%+.2f points** versus the current defaults.\n\n",
                selected.label(), returnDelta, targetDelta));
    }

    private GridConfiguration findFactory(IntervalResults results) {
        return results.configurations.stream()
                .filter(GridConfiguration::enabled)
                .filter(configuration -> configuration.atrPeriod() == 14)
                .filter(configuration -> Double.compare(configuration.atrMultiplier(), 1.5) == 0)
                .filter(configuration -> Double.compare(
                        configuration.thresholdPercent(), results.spec.defaultThreshold()) == 0)
                .filter(configuration -> Double.compare(
                        configuration.rewardRisk(), results.spec.factoryRewardRisk()) == 0)
                .findFirst().orElseThrow();
    }

    private List<GridConfiguration> topSelectionConfigurations(IntervalResults results, int limit) {
        long availableSelectionTrades = results.stats.get(findFactory(results))
                .get(Period.SELECTION).trades;
        long minimumSample = Math.max(1, availableSelectionTrades / 20);
        return results.configurations.stream()
                .filter(GridConfiguration::enabled)
                .filter(configuration -> results.stats.get(configuration)
                        .get(Period.SELECTION).trades >= minimumSample)
                .sorted(Comparator
                        .comparingDouble((GridConfiguration configuration) -> results.stats
                                .get(configuration).get(Period.SELECTION).averageReturn()).reversed()
                        .thenComparing(Comparator.comparingDouble((GridConfiguration configuration) -> results.stats
                                .get(configuration).get(Period.SELECTION).targetHitRate()).reversed())
                        .thenComparing(GridConfiguration::label))
                .limit(limit)
                .toList();
    }

    private void writeCsv(Path path, Map<TimeInterval, IntervalResults> byInterval) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("interval,period,circuit_breaker,atr_period,atr_multiplier,threshold_percent,reward_risk,trades,targets,stops,time_stops,target_hit_rate,decisive_precision,profitable_exit_rate,average_return_percent,circuit_breaker_rate,skipped_invalid_targets\n");
            for (IntervalResults results : byInterval.values()) {
                for (GridConfiguration configuration : results.configurations) {
                    for (Period period : Period.values()) {
                        Stats stats = results.stats.get(configuration).get(period);
                        writer.write(String.format(Locale.ROOT,
                                "%s,%s,%s,%d,%.2f,%.2f,%.2f,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d%n",
                                results.spec.apiInterval(), period.csvLabel, configuration.enabled(),
                                configuration.atrPeriod(), configuration.atrMultiplier(),
                                configuration.thresholdPercent(), configuration.rewardRisk(),
                                stats.trades, stats.targets, stats.stops, stats.timeStops,
                                stats.targetHitRate(), stats.decisivePrecision(),
                                stats.profitableExitRate(), stats.averageReturn(),
                                stats.circuitBreakerRate(), stats.skippedInvalidTargets));
                    }
                }
            }
        }
    }

    private List<UniverseEntry> loadManifest(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "Missing post-2018 manifest: " + path);
        List<UniverseEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            assertEquals("symbol\tname\tselection_source\tmarket_cap_usd_pre_2019\tfirst_date\tlast_date\tdaily_candles\tprice_source", header);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split("\t", -1);
                entries.add(new UniverseEntry(values[0], Integer.parseInt(values[6])));
            }
        }
        assertTrue(entries.size() >= EXPECTED_MINIMUM_SYMBOLS,
                "Post-2018 validation universe is underpowered: " + entries.size());
        assertEquals(entries.size(), entries.stream().map(UniverseEntry::symbol).distinct().count());
        return List.copyOf(entries);
    }

    private void streamDailyCandles(Path path,
                                    Map<String, UniverseEntry> selected,
                                    Consumer<SymbolCandles> consumer) throws IOException {
        assertTrue(Files.isRegularFile(path), "Missing post-2018 candles: " + path);
        Set<String> processed = new HashSet<>();
        String currentSymbol = null;
        List<Candle> currentCandles = new ArrayList<>();
        try (InputStream file = Files.newInputStream(path);
             InputStream data = path.toString().endsWith(".gz") ? new GZIPInputStream(file) : file;
             BufferedReader reader = new BufferedReader(new InputStreamReader(data, StandardCharsets.UTF_8))) {
            assertEquals("ticker,date,open,high,low,close,volume", reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                String[] value = line.split(",", -1);
                String symbol = value[0];
                if (!selected.containsKey(symbol)) continue;
                if (currentSymbol != null && !currentSymbol.equals(symbol)) {
                    consumer.accept(new SymbolCandles(currentSymbol, List.copyOf(currentCandles)));
                    processed.add(currentSymbol);
                    currentCandles.clear();
                }
                currentSymbol = symbol;
                LocalDate date = LocalDate.parse(value[1]);
                currentCandles.add(new Candle(symbol, "1d",
                        date.atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                        Double.parseDouble(value[2]), Double.parseDouble(value[3]),
                        Double.parseDouble(value[4]), Double.parseDouble(value[5]),
                        Long.parseLong(value[6])));
            }
        }
        if (currentSymbol != null) {
            consumer.accept(new SymbolCandles(currentSymbol, List.copyOf(currentCandles)));
            processed.add(currentSymbol);
        }
        assertEquals(selected.keySet(), processed, "Candle file did not contain exactly the selected symbols.");
    }

    private List<Candle> aggregate(List<Candle> daily, IntervalSpec spec) {
        if (spec.aggregation() == Aggregation.DAILY) return daily;
        Map<Object, MutableBar> bars = new LinkedHashMap<>();
        WeekFields weeks = WeekFields.ISO;
        for (Candle candle : daily) {
            LocalDate date = utcDate(candle.getTimestamp());
            Object key = spec.aggregation() == Aggregation.WEEKLY
                    ? date.get(weeks.weekBasedYear()) + "-" + date.get(weeks.weekOfWeekBasedYear())
                    : YearMonth.from(date);
            bars.computeIfAbsent(key, ignored -> new MutableBar(candle)).add(candle);
        }
        return bars.values().stream()
                .map(bar -> bar.toCandle(spec.apiInterval()))
                .toList();
    }

    private static LocalDate utcDate(long timestamp) {
        return java.time.Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static double percent(double entry, double exit) {
        return entry == 0.0 ? 0.0 : (exit - entry) / entry * 100.0;
    }

    private record UniverseEntry(String symbol, int dailyCandles) { }
    private record SymbolCandles(String symbol, List<Candle> candles) { }

    private record IntervalSpec(TimeInterval interval,
                                String apiInterval,
                                String label,
                                Aggregation aggregation,
                                int minimumHistory,
                                double factoryRewardRisk,
                                double defaultThreshold,
                                List<Double> thresholdGrid) { }

    private record GridConfiguration(boolean enabled,
                                     int atrPeriod,
                                     double atrMultiplier,
                                     double thresholdPercent,
                                     double rewardRisk) {
        private static GridConfiguration disabled(double rewardRisk, double threshold) {
            return new GridConfiguration(false, 0, 0.0, threshold, rewardRisk);
        }

        private String label() {
            if (!enabled) return String.format(Locale.ROOT, "CB off, 1:%.1f", rewardRisk);
            return String.format(Locale.ROOT, "ATR(%d) × %.1f, cap %.0f%%, 1:%.1f",
                    atrPeriod, atrMultiplier, thresholdPercent, rewardRisk);
        }
    }

    private static final class IntervalResults {
        private final IntervalSpec spec;
        private final List<GridConfiguration> configurations;
        private final Map<GridConfiguration, Map<Period, Stats>> stats = new LinkedHashMap<>();
        private long symbols;
        private long candles;
        private long detectedTrades;
        private long rejectedCandidates;
        private long invalidStructuralStops;

        private IntervalResults(IntervalSpec spec, List<GridConfiguration> configurations) {
            this.spec = spec;
            this.configurations = configurations;
            for (GridConfiguration configuration : configurations) {
                Map<Period, Stats> periods = new EnumMap<>(Period.class);
                for (Period period : Period.values()) periods.put(period, new Stats());
                stats.put(configuration, periods);
            }
        }
    }

    private static final class Stats {
        private long trades;
        private long targets;
        private long stops;
        private long timeStops;
        private long profitableExits;
        private long circuitBreakerApplied;
        private long skippedInvalidTargets;
        private double returnSum;

        private void add(SignalLifecycleStatus status, double directionalReturn, boolean applied) {
            trades++;
            if (status == SignalLifecycleStatus.CONFIRMED) targets++;
            else if (status == SignalLifecycleStatus.INVALIDATED) stops++;
            else if (status == SignalLifecycleStatus.EXPIRED) timeStops++;
            else throw new IllegalStateException("Unexpected terminal status " + status);
            if (directionalReturn > 0.0) profitableExits++;
            if (applied) circuitBreakerApplied++;
            returnSum += directionalReturn;
        }

        private double targetHitRate() { return percentage(targets, trades); }
        private double decisivePrecision() { return percentage(targets, targets + stops); }
        private double profitableExitRate() { return percentage(profitableExits, trades); }
        private double averageReturn() { return trades == 0 ? 0.0 : returnSum / trades; }
        private double circuitBreakerRate() { return percentage(circuitBreakerApplied, trades); }
        private static double percentage(long numerator, long denominator) {
            return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
        }
    }

    private static final class MutableBar {
        private final String symbol;
        private long timestamp;
        private final double open;
        private double high;
        private double low;
        private double close;
        private long volume;

        private MutableBar(Candle candle) {
            symbol = candle.getSymbol();
            timestamp = candle.getTimestamp();
            open = candle.getOpenPrice();
            high = candle.getHighPrice();
            low = candle.getLowPrice();
            close = candle.getClosePrice();
            volume = candle.getVolume() == null ? 0L : candle.getVolume();
        }

        private void add(Candle candle) {
            timestamp = Math.max(timestamp, candle.getTimestamp());
            high = Math.max(high, candle.getHighPrice());
            low = Math.min(low, candle.getLowPrice());
            close = candle.getClosePrice();
            volume += candle.getVolume() == null ? 0L : candle.getVolume();
        }

        private Candle toCandle(String interval) {
            return new Candle(symbol, interval, timestamp, open, high, low, close, volume);
        }
    }

    private static final class Progress {
        private final int total;
        private int completed;
        private Progress(int total) { this.total = total; }
    }

    private enum Period {
        SELECTION("2019-2021 selection", "selection"),
        VALIDATION("2022-2025 validation", "validation"),
        ALL("2019-2025 combined", "all");

        private final String label;
        private final String csvLabel;
        Period(String label, String csvLabel) {
            this.label = label;
            this.csvLabel = csvLabel;
        }
    }

    private enum Aggregation {
        DAILY,
        WEEKLY,
        MONTHLY
    }
}
