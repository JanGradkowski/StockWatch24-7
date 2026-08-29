package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Large, frozen-universe Elliott score validation.
 *
 * <p>The detector is evaluated exactly as the alert jobs use it: completed
 * weekly/monthly candles, 100-candle detection windows, the production alert
 * gate, and only actionable V/ABC endings. The adjusted OHLC source and
 * pre-outcome universe are shared with the expanded candlestick study.</p>
 */
@EnabledIfSystemProperty(named = "backtest.elliott.expanded.enabled", matches = "true")
class ExpandedElliottScoringValidationTest {
    private static final Path DEFAULT_MANIFEST = Path.of(
            "target/expanded-backtest-data/expanded-candlestick-power-universe.tsv");
    private static final Path DEFAULT_DATA = Path.of(
            "target/expanded-backtest-data/expanded-power-candles.csv.gz");
    private static final Path OUTPUT_DIRECTORY = Path.of("target/expanded-backtest-data");
    private static final String POWER_MANIFEST_SHA256 =
            "8364BE9B75B3D91A3377FB128195849CAD080189FE928243AC92F6CE573F160F";
    private static final int EXPECTED_SYMBOLS = 2_193;
    private static final int EXPECTED_DAILY_CANDLES = 7_802_979;
    private static final int SIGNAL_WINDOW = 100;
    private static final ElliottWaveDetectionService.ScoringModel BENCHMARK_SCORE_MODEL =
            ElliottWaveDetectionService.ScoringModel.valueOf(System.getProperty(
                    "backtest.elliott.score-model", "V2").trim().toUpperCase(Locale.ROOT));
    private static final boolean ALLOW_WAVE_ONE_LONGEST = Boolean.parseBoolean(System.getProperty(
            "backtest.elliott.allow-wave-one-longest", "true"));
    private static final boolean REQUIRE_WAVE_ONE_SHORTEST = Boolean.parseBoolean(System.getProperty(
            "backtest.elliott.require-wave-one-shortest", "false"));
    private static final String DETECTION_VARIANT = REQUIRE_WAVE_ONE_SHORTEST
            ? "wave-one-shortest"
            : (ALLOW_WAVE_ONE_LONGEST ? "factory" : "wave-one-not-longest");
    private static final List<IntervalRun> RUNS = List.of(
            new IntervalRun("1wk", "Weekly", TimeInterval.WEEKLY, Aggregation.WEEKLY,
                    List.of(new OutcomeWindow(4, 4.0), new OutcomeWindow(8, 8.0),
                            new OutcomeWindow(12, 12.0))),
            new IntervalRun("1mo", "Monthly", TimeInterval.MONTHLY, Aggregation.MONTHLY,
                    List.of(new OutcomeWindow(3, 6.0), new OutcomeWindow(6, 12.0),
                            new OutcomeWindow(9, 18.0)))
    );
    private static final Set<Integer> SCORE_THRESHOLDS = Set.of(75, 80, 85, 90);

    private final TechnicalIndicatorEnrichmentService enrichmentService =
            new TechnicalIndicatorEnrichmentService();
    private final ElliottWaveDetectionService detectionService =
            new ElliottWaveDetectionService(1, BENCHMARK_SCORE_MODEL).configured(
                    ElliottWaveDetectionService.DetectionRules.factory()
                            .withAllowWaveOneLongest(ALLOW_WAVE_ONE_LONGEST)
                            .withRequireWaveOneShortest(REQUIRE_WAVE_ONE_SHORTEST));

    @Test
    void runsFrozenExpandedElliottValidation() throws Exception {
        Path manifestPath = Path.of(System.getProperty(
                "backtest.elliott.expanded.manifest-file", DEFAULT_MANIFEST.toString()));
        Path dataPath = Path.of(System.getProperty(
                "backtest.elliott.expanded.data-file", DEFAULT_DATA.toString()));
        List<UniverseEntry> universe = loadAndValidateManifest(manifestPath);
        Map<String, List<Candle>> dailyBySymbol = loadDailyCandles(dataPath, universe);

        String scoreVersion = "ELLIOTT_" + BENCHMARK_SCORE_MODEL.name();
        String fileLabel = scoreVersion.toLowerCase(Locale.ROOT).replace('_', '-')
                + ("factory".equals(DETECTION_VARIANT) ? "" : "-" + DETECTION_VARIANT);
        ConcurrentLinkedQueue<LabeledSignal> allSignals = new ConcurrentLinkedQueue<>();
        List<Coverage> coverage = new ArrayList<>();

        System.out.printf(Locale.ROOT,
                "Expanded Elliott %s [%s]: symbols=%,d adjustedDailyCandles=%,d%n",
                scoreVersion, DETECTION_VARIANT, universe.size(), EXPECTED_DAILY_CANDLES);
        for (IntervalRun run : RUNS) {
            AtomicInteger completed = new AtomicInteger();
            List<SymbolResult> symbolResults = universe.parallelStream()
                    .map(entry -> analyzeSymbol(entry, dailyBySymbol.get(entry.symbol()), run))
                    .peek(ignored -> {
                        int count = completed.incrementAndGet();
                        if (count % 100 == 0 || count == universe.size()) {
                            System.out.printf(Locale.ROOT,
                                    "  %s: processed %,d/%,d symbols%n",
                                    run.label(), count, universe.size());
                        }
                    })
                    .toList();
            symbolResults.forEach(result -> allSignals.addAll(result.signals()));
            coverage.add(new Coverage(
                    run,
                    symbolResults.stream().mapToInt(SymbolResult::candles).sum(),
                    symbolResults.stream().mapToInt(SymbolResult::evaluatedWindows).sum(),
                    symbolResults.stream().mapToInt(result -> result.signals().size()).sum()
            ));
        }

        List<LabeledSignal> orderedSignals = allSignals.stream()
                .sorted(Comparator.comparing(LabeledSignal::interval)
                        .thenComparing(LabeledSignal::symbol)
                        .thenComparingLong(LabeledSignal::signalTimestamp)
                        .thenComparing(signal -> signal.pattern().name()))
                .toList();
        assertFalse(orderedSignals.isEmpty(), "The expanded Elliott run produced no signals.");
        if ("ELLIOTT_V2".equals(scoreVersion) && "factory".equals(DETECTION_VARIANT)) {
            assertV1DetectionParity(orderedSignals);
        }

        Files.createDirectories(OUTPUT_DIRECTORY);
        Path tradesPath = OUTPUT_DIRECTORY.resolve("expanded-elliott-signals-" + fileLabel + ".csv");
        Path summaryPath = OUTPUT_DIRECTORY.resolve("expanded-elliott-summary-" + fileLabel + ".md");
        writeSignals(tradesPath, orderedSignals);
        String report = buildReport(scoreVersion, universe, coverage, orderedSignals);
        Files.writeString(summaryPath, report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("Signal output: " + tradesPath);
        System.out.println("Summary output: " + summaryPath);
    }

    @Test
    @EnabledIfSystemProperty(named = "backtest.elliott.developing.enabled", matches = "true")
    void runsDevelopingMultiTimeframeValidation() throws Exception {
        Path manifestPath = Path.of(System.getProperty(
                "backtest.elliott.expanded.manifest-file", DEFAULT_MANIFEST.toString()));
        Path dataPath = Path.of(System.getProperty(
                "backtest.elliott.expanded.data-file", DEFAULT_DATA.toString()));
        List<UniverseEntry> completeUniverse = loadAndValidateManifest(manifestPath);
        int maximumSymbols = Math.min(completeUniverse.size(), Integer.getInteger(
                "backtest.elliott.developing.max-symbols", completeUniverse.size()));
        int developingWindow = Math.max(100, Integer.getInteger(
                "backtest.elliott.developing.window", 200));
        List<UniverseEntry> universe = completeUniverse.subList(0, maximumSymbols);
        Map<String, List<Candle>> dailyBySymbol = loadDailyCandles(dataPath, completeUniverse);
        ConcurrentLinkedQueue<DevelopingLabeledSignal> allNotifications = new ConcurrentLinkedQueue<>();
        List<DevelopingCoverage> coverage = new ArrayList<>();

        System.out.printf(Locale.ROOT,
                "Developing Elliott multi-timeframe: symbols=%,d/%d weeklyWindow=%d monthlyWindow=100%n",
                universe.size(), completeUniverse.size(), developingWindow);
        for (IntervalRun run : RUNS) {
            int runWindow = run.aggregation() == Aggregation.MONTHLY ? 100 : developingWindow;
            AtomicInteger completed = new AtomicInteger();
            List<DevelopingSymbolResult> results = universe.parallelStream()
                    .map(entry -> analyzeDevelopingSymbol(
                            entry, dailyBySymbol.get(entry.symbol()), run, runWindow))
                    .peek(ignored -> {
                        int count = completed.incrementAndGet();
                        if (count % 25 == 0 || count == universe.size()) {
                            System.out.printf(Locale.ROOT,
                                    "  %s: processed %,d/%,d symbols%n",
                                    run.label(), count, universe.size());
                        }
                    })
                    .toList();
            results.forEach(result -> allNotifications.addAll(result.notifications()));
            coverage.add(new DevelopingCoverage(
                    run, runWindow,
                    results.stream().mapToInt(DevelopingSymbolResult::evaluatedWindows).sum(),
                    results.stream().mapToInt(DevelopingSymbolResult::cycles).sum(),
                    results.stream().mapToInt(result -> result.notifications().size()).sum()));
        }

        List<DevelopingLabeledSignal> ordered = allNotifications.stream()
                .sorted(Comparator.comparing(DevelopingLabeledSignal::interval)
                        .thenComparing(DevelopingLabeledSignal::symbol)
                        .thenComparingLong(DevelopingLabeledSignal::signalTimestamp)
                        .thenComparing(signal -> signal.stage().progressionOrder()))
                .toList();
        Files.createDirectories(OUTPUT_DIRECTORY);
        String suffix = maximumSymbols == completeUniverse.size()
                ? "full" : "first-" + maximumSymbols;
        Path signalsPath = OUTPUT_DIRECTORY.resolve(
                "expanded-elliott-developing-signals-" + suffix + ".csv");
        Path summaryPath = OUTPUT_DIRECTORY.resolve(
                "expanded-elliott-developing-summary-" + suffix + ".md");
        writeDevelopingSignals(signalsPath, ordered);
        String report = buildDevelopingReport(
                completeUniverse.size(), universe.size(), coverage, ordered);
        Files.writeString(summaryPath, report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("Developing signal output: " + signalsPath);
        System.out.println("Developing summary output: " + summaryPath);
    }

    private DevelopingSymbolResult analyzeDevelopingSymbol(
            UniverseEntry entry,
            List<Candle> daily,
            IntervalRun run,
            int signalWindow) {
        List<Candle> parent = aggregate(daily, run.aggregation());
        List<Candle> child = run.aggregation() == Aggregation.WEEKLY
                ? daily : aggregate(daily, Aggregation.WEEKLY);
        TimeInterval childInterval = run.aggregation() == Aggregation.WEEKLY
                ? TimeInterval.DAILY : TimeInterval.WEEKLY;
        int maximumHorizon = run.outcomes().stream()
                .mapToInt(OutcomeWindow::candles).max().orElseThrow();
        if (parent.size() < signalWindow + maximumHorizon) {
            return new DevelopingSymbolResult(0, 0, List.of());
        }
        List<EnrichedCandle> enrichedParent = enrichmentService.enrichForElliott(
                parent, parent.size(), run.intervalType());
        List<EnrichedCandle> enrichedChild = enrichmentService.enrichForElliott(
                child, child.size(), childInterval);
        Map<String, Integer> maximumStageByCycle = new LinkedHashMap<>();
        ElliottWaveDetectionService.DevelopingScanContext scanContext =
                new ElliottWaveDetectionService.DevelopingScanContext();
        List<DevelopingLabeledSignal> notifications = new ArrayList<>();
        int lastIndex = parent.size() - maximumHorizon - 1;
        for (int index = signalWindow - 1; index <= lastIndex; index++) {
            Candle currentParent = parent.get(index);
            Candle previousParent = parent.get(index - 1);
            boolean confirmsLow = currentParent.getClosePrice() > previousParent.getHighPrice();
            boolean confirmsHigh = currentParent.getClosePrice() < previousParent.getLowPrice();
            if (!confirmsLow && !confirmsHigh) continue;
            List<EnrichedCandle> window = enrichedParent.subList(
                    index - signalWindow + 1, index + 1);
            long nextParentTimestamp = parent.get(index + 1).getTimestamp();
            List<EnrichedCandle> childAsOf = enrichedChild.subList(
                    0, lowerBoundByTimestamp(enrichedChild, nextParentTimestamp));
            long currentTimestamp = parent.get(index).getTimestamp();
            for (ElliottWaveDetectionService.DevelopingImpulse candidate
                    : detectionService.findDevelopingImpulses(window, childAsOf, scanContext)) {
                if (candidate.confirmationTimestamp() != currentTimestamp) continue;
                String cycleKey = run.interval() + '|' + entry.symbol() + '|'
                        + candidate.developmentKey();
                int progression = candidate.stage().progressionOrder();
                int prior = maximumStageByCycle.getOrDefault(cycleKey, 0);
                if (prior == 0 && candidate.stage() != ElliottSignalStage.WAVE_II_END) continue;
                if (progression <= prior) continue;
                maximumStageByCycle.put(cycleKey, progression);
                int signalIndex = index;
                List<Outcome> outcomes = run.outcomes().stream()
                        .map(outcome -> evaluateDevelopingOutcome(
                                parent, signalIndex, candidate.expectedMove(),
                                candidate.confirmationClose(), outcome))
                        .toList();
                notifications.add(new DevelopingLabeledSignal(
                        run.interval(), entry.symbol(), entry.cohort(), candidate.developmentKey(),
                        candidate.stage(), candidate.expectedMove(), candidate.confidenceScore(),
                        candidate.confirmationTimestamp(), candidate.confirmationClose(), outcomes));
            }
        }
        return new DevelopingSymbolResult(
                Math.max(0, lastIndex - signalWindow + 2),
                maximumStageByCycle.size(), List.copyOf(notifications));
    }

    private int lowerBoundByTimestamp(List<EnrichedCandle> candles, long timestamp) {
        int low = 0;
        int high = candles.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (candles.get(middle).timestamp() < timestamp) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private Outcome evaluateDevelopingOutcome(
            List<Candle> candles,
            int signalIndex,
            TradeSignal direction,
            double entry,
            OutcomeWindow window) {
        int exitIndex = signalIndex + window.candles();
        List<Candle> future = candles.subList(signalIndex + 1, exitIndex + 1);
        double exit = candles.get(exitIndex).getClosePrice();
        double closeReturn = directionalMove(direction, entry, exit);
        double highest = future.stream().mapToDouble(Candle::getHighPrice).max().orElse(exit);
        double lowest = future.stream().mapToDouble(Candle::getLowPrice).min().orElse(exit);
        double best = direction == TradeSignal.BUY
                ? percentMove(entry, highest) : -percentMove(entry, lowest);
        double worst = direction == TradeSignal.BUY
                ? percentMove(entry, lowest) : -percentMove(entry, highest);
        OutcomeClass outcomeClass = closeReturn >= window.minimumMovePercent()
                ? OutcomeClass.SUCCESS
                : closeReturn <= -window.minimumMovePercent()
                ? OutcomeClass.FAILURE : OutcomeClass.INCONCLUSIVE;
        return new Outcome(window, candles.get(exitIndex).getTimestamp(), closeReturn,
                best, worst, outcomeClass);
    }

    private void assertV1DetectionParity(List<LabeledSignal> v2Signals) throws IOException {
        Path baseline = OUTPUT_DIRECTORY.resolve("expanded-elliott-signals-elliott-v1.csv");
        assertTrue(Files.isRegularFile(baseline),
                "Run and preserve the ELLIOTT_V1 expanded benchmark before V2.");
        Set<String> v1Keys;
        try (java.util.stream.Stream<String> lines = Files.lines(baseline, StandardCharsets.UTF_8)) {
            v1Keys = lines.skip(1)
                    .map(line -> line.split(",", -1))
                    .map(values -> signalKey(values[0], values[1], values[3], values[4], values[6]))
                    .collect(Collectors.toSet());
        }
        Set<String> v2Keys = v2Signals.stream()
                .map(signal -> signalKey(signal.interval(), signal.symbol(), signal.pattern().name(),
                        signal.direction().name(), Long.toString(signal.signalTimestamp())))
                .collect(Collectors.toSet());
        assertEquals(v1Keys, v2Keys,
                "V2 must score the exact V1-qualified signal set without changing detection or timing.");
        System.out.printf(Locale.ROOT,
                "Detection parity: %,d/%,d V1 signal identities preserved (100.00%%).%n",
                v2Keys.size(), v1Keys.size());
    }

    private String signalKey(String interval, String symbol, String pattern,
                             String direction, String timestamp) {
        return String.join("|", interval, symbol, pattern, direction, timestamp);
    }

    private SymbolResult analyzeSymbol(UniverseEntry entry, List<Candle> daily, IntervalRun run) {
        List<Candle> candles = aggregate(daily, run.aggregation());
        int maximumHorizon = run.outcomes().stream().mapToInt(OutcomeWindow::candles).max().orElseThrow();
        if (candles.size() < SIGNAL_WINDOW + maximumHorizon) {
            return new SymbolResult(candles.size(), 0, List.of());
        }
        List<EnrichedCandle> enriched = enrichmentService.enrichForElliott(
                candles, candles.size(), run.intervalType());
        List<LabeledSignal> signals = new ArrayList<>();
        int lastIndex = candles.size() - maximumHorizon - 1;
        for (int index = SIGNAL_WINDOW - 1; index <= lastIndex; index++) {
            int signalIndex = index;
            List<EnrichedCandle> window = enriched.subList(index - SIGNAL_WINDOW + 1, index + 1);
            long currentTimestamp = candles.get(index).getTimestamp();
            detectionService.detectAlertSignals(window).stream()
                    .filter(this::isActionableTurningPoint)
                    .filter(signal -> signal.candleTimestamp() == currentTimestamp)
                    .map(signal -> evaluate(entry, run, candles, signalIndex, signal))
                    .forEach(signals::add);
        }
        return new SymbolResult(candles.size(), Math.max(0, lastIndex - SIGNAL_WINDOW + 2),
                List.copyOf(signals));
    }

    private LabeledSignal evaluate(UniverseEntry entry,
                                   IntervalRun run,
                                   List<Candle> candles,
                                   int signalIndex,
                                   DetectedSignal signal) {
        List<Outcome> outcomes = run.outcomes().stream()
                .map(window -> evaluateOutcome(candles, signalIndex, signal, window))
                .toList();
        return new LabeledSignal(
                run.interval(), entry.symbol(), entry.cohort(), signal.pattern(), signal.tradeSignal(),
                signal.confidenceScore(), signal.candleTimestamp(), signal.closePrice(), outcomes);
    }

    private Outcome evaluateOutcome(List<Candle> candles,
                                    int signalIndex,
                                    DetectedSignal signal,
                                    OutcomeWindow window) {
        int exitIndex = signalIndex + window.candles();
        List<Candle> future = candles.subList(signalIndex + 1, exitIndex + 1);
        double entry = signal.closePrice();
        double exit = candles.get(exitIndex).getClosePrice();
        double closeReturn = directionalMove(signal.tradeSignal(), entry, exit);
        double highest = future.stream().mapToDouble(Candle::getHighPrice).max().orElse(exit);
        double lowest = future.stream().mapToDouble(Candle::getLowPrice).min().orElse(exit);
        double best = signal.tradeSignal() == TradeSignal.BUY
                ? percentMove(entry, highest) : -percentMove(entry, lowest);
        double worst = signal.tradeSignal() == TradeSignal.BUY
                ? percentMove(entry, lowest) : -percentMove(entry, highest);
        OutcomeClass outcomeClass = closeReturn >= window.minimumMovePercent()
                ? OutcomeClass.SUCCESS
                : closeReturn <= -window.minimumMovePercent()
                ? OutcomeClass.FAILURE : OutcomeClass.INCONCLUSIVE;
        return new Outcome(window, candles.get(exitIndex).getTimestamp(), closeReturn, best, worst, outcomeClass);
    }

    private boolean isActionableTurningPoint(DetectedSignal signal) {
        String pattern = signal.pattern().name();
        return pattern.endsWith("WAVE_V_END") || pattern.endsWith("CORRECTION");
    }

    private double directionalMove(TradeSignal direction, double entry, double exit) {
        double move = percentMove(entry, exit);
        return direction == TradeSignal.BUY ? move : -move;
    }

    private double percentMove(double entry, double target) {
        return entry == 0.0 ? 0.0 : ((target - entry) / entry) * 100.0;
    }

    private String buildReport(String scoreVersion,
                               List<UniverseEntry> universe,
                               List<Coverage> coverage,
                               List<LabeledSignal> signals) {
        StringBuilder report = new StringBuilder();
        report.append("# Expanded Elliott scoring validation\n\n");
        report.append(String.format(Locale.ROOT,
                "Score model: `%s`; detection variant: `%s`; frozen symbols: %,d; adjusted daily source candles: %,d. "
                        + "Detection uses completed candles and rolling 100-candle windows.\n\n",
                scoreVersion, DETECTION_VARIANT, universe.size(), EXPECTED_DAILY_CANDLES));
        report.append("Precision is success / (success + failure), excluding inconclusive outcomes. ");
        report.append("Return, best move, and worst move are direction-adjusted: positive is favorable for both buys and sells.\n\n");
        report.append("| Interval | Aggregated candles | Evaluated windows | Alert-qualified signals |\n");
        report.append("|---|---:|---:|---:|\n");
        coverage.forEach(item -> report.append(String.format(Locale.ROOT,
                "| %s | %,d | %,d | %,d |%n", item.run().label(), item.candles(),
                item.evaluatedWindows(), item.signals())));

        report.append("\n## Accuracy and potential returns\n\n");
        report.append("| Interval | Horizon / move | Score | Stage | Signals | Success | Failure | Inconclusive | Precision | Avg close return | Avg best move | Avg worst move |\n");
        report.append("|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (IntervalRun run : RUNS) {
            for (OutcomeWindow outcomeWindow : run.outcomes()) {
                for (int threshold : SCORE_THRESHOLDS.stream().sorted().toList()) {
                    appendStatistics(report, signals, run, outcomeWindow, threshold, "ALL", signal -> true);
                    appendStatistics(report, signals, run, outcomeWindow, threshold, "V END",
                            signal -> signal.pattern().name().endsWith("WAVE_V_END"));
                    appendStatistics(report, signals, run, outcomeWindow, threshold, "ABC END",
                            signal -> signal.pattern().name().endsWith("CORRECTION"));
                }
            }
        }
        return report.toString();
    }

    private void appendStatistics(StringBuilder report,
                                  List<LabeledSignal> signals,
                                  IntervalRun run,
                                  OutcomeWindow window,
                                  int threshold,
                                  String stage,
                                  java.util.function.Predicate<LabeledSignal> stageFilter) {
        List<Outcome> outcomes = signals.stream()
                .filter(signal -> signal.interval().equals(run.interval()))
                .filter(signal -> signal.confidenceScore() >= threshold)
                .filter(stageFilter)
                .map(signal -> signal.outcomes().stream()
                        .filter(outcome -> outcome.window().equals(window)).findFirst().orElseThrow())
                .toList();
        long success = outcomes.stream().filter(outcome -> outcome.outcomeClass() == OutcomeClass.SUCCESS).count();
        long failure = outcomes.stream().filter(outcome -> outcome.outcomeClass() == OutcomeClass.FAILURE).count();
        long inconclusive = outcomes.size() - success - failure;
        report.append(String.format(Locale.ROOT,
                "| %s | %d / %.1f%% | %d+ | %s | %,d | %,d | %,d | %,d | %.2f%% | %+.2f%% | %+.2f%% | %+.2f%% |%n",
                run.label(), window.candles(), window.minimumMovePercent(), threshold, stage,
                outcomes.size(), success, failure, inconclusive, percentage(success, success + failure),
                average(outcomes, Outcome::closeReturnPercent),
                average(outcomes, Outcome::bestMovePercent),
                average(outcomes, Outcome::worstMovePercent)));
    }

    private double average(List<Outcome> outcomes, java.util.function.ToDoubleFunction<Outcome> extractor) {
        return outcomes.stream().mapToDouble(extractor).average().orElse(0.0);
    }

    private double percentage(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }

    private void writeSignals(Path output, List<LabeledSignal> signals) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("interval,symbol,cohort,pattern,direction,score,signal_timestamp,entry_price,horizon,minimum_move_percent,exit_timestamp,directional_close_return_percent,best_directional_move_percent,worst_directional_move_percent,outcome\n");
            for (LabeledSignal signal : signals) {
                for (Outcome outcome : signal.outcomes()) {
                    writer.write(String.format(Locale.ROOT,
                            "%s,%s,%s,%s,%s,%d,%d,%.8f,%d,%.2f,%d,%.8f,%.8f,%.8f,%s%n",
                            signal.interval(), signal.symbol(), signal.cohort(), signal.pattern(),
                            signal.direction(), signal.confidenceScore(), signal.signalTimestamp(),
                            signal.entryPrice(), outcome.window().candles(),
                            outcome.window().minimumMovePercent(), outcome.exitTimestamp(),
                            outcome.closeReturnPercent(), outcome.bestMovePercent(),
                            outcome.worstMovePercent(), outcome.outcomeClass()));
                }
            }
        }
    }

    private String buildDevelopingReport(
            int frozenSymbols,
            int testedSymbols,
            List<DevelopingCoverage> coverage,
            List<DevelopingLabeledSignal> notifications) {
        StringBuilder report = new StringBuilder();
        report.append("# Developing Elliott multi-timeframe validation\n\n");
        report.append(String.format(Locale.ROOT,
                "Frozen universe: %,d symbols; tested: %,d. "
                        + "Weekly parent legs use daily child waves; monthly parent legs use weekly child waves. "
                        + "One development key represents one app signal row; each higher stage represents an update/notification.\n\n",
                frozenSymbols, testedSymbols));
        report.append("Precision is success / (success + failure), excluding inconclusive outcomes. "
                + "Returns are direction-adjusted for the move forecast immediately after each stage.\n\n");
        report.append("| Interval | Parent window | Evaluated windows | Unique signal rows/cycles | Stage notifications |\n");
        report.append("|---|---:|---:|---:|---:|\n");
        coverage.forEach(item -> report.append(String.format(Locale.ROOT,
                "| %s | %d | %,d | %,d | %,d |%n", item.run().label(), item.signalWindow(),
                item.evaluatedWindows(), item.cycles(), item.notifications())));
        report.append("\n## Stage counts\n\n");
        report.append("| Interval | II | III | IV | V |\n");
        report.append("|---|---:|---:|---:|---:|\n");
        for (IntervalRun run : RUNS) {
            report.append(String.format(Locale.ROOT, "| %s | %,d | %,d | %,d | %,d |%n",
                    run.label(),
                    countDeveloping(notifications, run, ElliottSignalStage.WAVE_II_END),
                    countDeveloping(notifications, run, ElliottSignalStage.WAVE_III_END),
                    countDeveloping(notifications, run, ElliottSignalStage.WAVE_IV_END),
                    countDeveloping(notifications, run, ElliottSignalStage.WAVE_V_END)));
        }
        report.append("\n## Returns and precision\n\n");
        report.append("| Interval | Horizon / move | Confidence | Stage | Notifications | Success | Failure | Inconclusive | Precision | Avg close return | Avg best move | Avg worst move |\n");
        report.append("|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (IntervalRun run : RUNS) {
            for (OutcomeWindow outcome : run.outcomes()) {
                for (int threshold : List.of(60, 70, 75, 80)) {
                    for (ElliottSignalStage stage : List.of(
                            ElliottSignalStage.WAVE_II_END,
                            ElliottSignalStage.WAVE_III_END,
                            ElliottSignalStage.WAVE_IV_END,
                            ElliottSignalStage.WAVE_V_END)) {
                        appendDevelopingStatistics(
                                report, notifications, run, outcome, threshold, stage);
                    }
                }
            }
        }
        return report.toString();
    }

    private long countDeveloping(
            List<DevelopingLabeledSignal> notifications,
            IntervalRun run,
            ElliottSignalStage stage) {
        return notifications.stream()
                .filter(signal -> signal.interval().equals(run.interval()))
                .filter(signal -> signal.stage() == stage)
                .count();
    }

    private void appendDevelopingStatistics(
            StringBuilder report,
            List<DevelopingLabeledSignal> notifications,
            IntervalRun run,
            OutcomeWindow window,
            int threshold,
            ElliottSignalStage stage) {
        List<Outcome> outcomes = notifications.stream()
                .filter(signal -> signal.interval().equals(run.interval()))
                .filter(signal -> signal.stage() == stage)
                .filter(signal -> signal.confidenceScore() >= threshold)
                .map(signal -> signal.outcomes().stream()
                        .filter(outcome -> outcome.window().equals(window))
                        .findFirst().orElseThrow())
                .toList();
        long success = outcomes.stream()
                .filter(outcome -> outcome.outcomeClass() == OutcomeClass.SUCCESS).count();
        long failure = outcomes.stream()
                .filter(outcome -> outcome.outcomeClass() == OutcomeClass.FAILURE).count();
        long inconclusive = outcomes.size() - success - failure;
        report.append(String.format(Locale.ROOT,
                "| %s | %d / %.1f%% | %d+ | %s | %,d | %,d | %,d | %,d | %.2f%% | %+.2f%% | %+.2f%% | %+.2f%% |%n",
                run.label(), window.candles(), window.minimumMovePercent(), threshold,
                stage.name().replace("WAVE_", "").replace("_END", ""), outcomes.size(),
                success, failure, inconclusive, percentage(success, success + failure),
                average(outcomes, Outcome::closeReturnPercent),
                average(outcomes, Outcome::bestMovePercent),
                average(outcomes, Outcome::worstMovePercent)));
    }

    private void writeDevelopingSignals(
            Path output,
            List<DevelopingLabeledSignal> notifications) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("interval,symbol,cohort,development_key,stage,direction,score,signal_timestamp,entry_price,horizon,minimum_move_percent,exit_timestamp,directional_close_return_percent,best_directional_move_percent,worst_directional_move_percent,outcome\n");
            for (DevelopingLabeledSignal signal : notifications) {
                for (Outcome outcome : signal.outcomes()) {
                    writer.write(String.format(Locale.ROOT,
                            "%s,%s,%s,%s,%s,%s,%d,%d,%.8f,%d,%.2f,%d,%.8f,%.8f,%.8f,%s%n",
                            signal.interval(), signal.symbol(), signal.cohort(),
                            signal.developmentKey(), signal.stage(), signal.direction(),
                            signal.confidenceScore(), signal.signalTimestamp(), signal.entryPrice(),
                            outcome.window().candles(), outcome.window().minimumMovePercent(),
                            outcome.exitTimestamp(), outcome.closeReturnPercent(),
                            outcome.bestMovePercent(), outcome.worstMovePercent(),
                            outcome.outcomeClass()));
                }
            }
        }
    }

    private List<UniverseEntry> loadAndValidateManifest(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "Frozen Elliott universe is missing: " + path);
        assertEquals(POWER_MANIFEST_SHA256, sha256(Files.readAllBytes(path)),
                "The frozen power universe changed.");
        List<UniverseEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            assertEquals("symbol\tname\tsector\tcap_tier\tcohort\trole\tfirst_date\tlast_date\tdaily_candles\tmarket_cap_usd", header);
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split("\\t", -1);
                entries.add(new UniverseEntry(values[0], values[4], LocalDate.parse(values[6]),
                        LocalDate.parse(values[7]), Integer.parseInt(values[8])));
            }
        }
        assertEquals(EXPECTED_SYMBOLS, entries.size());
        assertEquals(EXPECTED_SYMBOLS, entries.stream().map(UniverseEntry::symbol).distinct().count());
        assertEquals(EXPECTED_DAILY_CANDLES, entries.stream().mapToInt(UniverseEntry::dailyCandles).sum());
        return List.copyOf(entries);
    }

    private Map<String, List<Candle>> loadDailyCandles(Path dataPath,
                                                        List<UniverseEntry> universe) throws IOException {
        assertTrue(Files.isRegularFile(dataPath), "Prepared adjusted candles are missing: " + dataPath);
        Map<String, UniverseEntry> entries = universe.stream()
                .collect(Collectors.toMap(UniverseEntry::symbol, Function.identity()));
        Map<String, List<Candle>> candles = new LinkedHashMap<>();
        universe.forEach(entry -> candles.put(entry.symbol(), new ArrayList<>()));
        try (InputStream raw = Files.newInputStream(dataPath);
             InputStream input = dataPath.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            assertEquals("ticker,date,open,high,low,close,volume", reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",", -1);
                UniverseEntry entry = entries.get(values[0]);
                if (entry == null) {
                    continue;
                }
                LocalDate date = LocalDate.parse(values[1]);
                candles.get(entry.symbol()).add(new Candle(entry.symbol(), "1d",
                        date.atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                        Double.parseDouble(values[2]), Double.parseDouble(values[3]),
                        Double.parseDouble(values[4]), Double.parseDouble(values[5]),
                        Long.parseLong(values[6])));
            }
        }
        for (UniverseEntry entry : universe) {
            List<Candle> symbolCandles = candles.get(entry.symbol());
            symbolCandles.sort(Comparator.comparing(Candle::getTimestamp));
            assertEquals(entry.dailyCandles(), symbolCandles.size(), "Candle count drift: " + entry.symbol());
            assertEquals(entry.firstDate(), utcDate(symbolCandles.getFirst().getTimestamp()));
            assertEquals(entry.lastDate(), utcDate(symbolCandles.getLast().getTimestamp()));
        }
        return candles;
    }

    private List<Candle> aggregate(List<Candle> daily, Aggregation aggregation) {
        Map<Object, MutableBar> bars = new LinkedHashMap<>();
        for (Candle candle : daily) {
            LocalDate date = utcDate(candle.getTimestamp());
            Object key = aggregation == Aggregation.WEEKLY
                    ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    : YearMonth.from(date);
            bars.computeIfAbsent(key, ignored -> new MutableBar(
                    candle.getSymbol(), aggregation.interval(), candle)).add(candle);
        }
        return bars.values().stream().map(MutableBar::toCandle).toList();
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static LocalDate utcDate(long timestamp) {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private enum Aggregation {
        WEEKLY("1wk"), MONTHLY("1mo");

        private final String interval;

        Aggregation(String interval) {
            this.interval = interval;
        }

        private String interval() {
            return interval;
        }
    }

    private enum OutcomeClass { SUCCESS, FAILURE, INCONCLUSIVE }

    private record IntervalRun(String interval, String label, TimeInterval intervalType,
                               Aggregation aggregation, List<OutcomeWindow> outcomes) { }

    private record OutcomeWindow(int candles, double minimumMovePercent) { }

    private record Coverage(IntervalRun run, int candles, int evaluatedWindows, int signals) { }

    private record UniverseEntry(String symbol, String cohort, LocalDate firstDate,
                                 LocalDate lastDate, int dailyCandles) { }

    private record SymbolResult(int candles, int evaluatedWindows, List<LabeledSignal> signals) { }

    private record DevelopingCoverage(IntervalRun run, int signalWindow, int evaluatedWindows,
                                      int cycles, int notifications) { }

    private record DevelopingSymbolResult(int evaluatedWindows, int cycles,
                                          List<DevelopingLabeledSignal> notifications) { }

    private record DevelopingLabeledSignal(
            String interval, String symbol, String cohort, String developmentKey,
            ElliottSignalStage stage, TradeSignal direction, int confidenceScore,
            long signalTimestamp, double entryPrice, List<Outcome> outcomes) { }

    private record LabeledSignal(String interval, String symbol, String cohort,
                                 CandlePattern pattern, TradeSignal direction, int confidenceScore,
                                 long signalTimestamp, double entryPrice, List<Outcome> outcomes) { }

    private record Outcome(OutcomeWindow window, long exitTimestamp, double closeReturnPercent,
                           double bestMovePercent, double worstMovePercent,
                           OutcomeClass outcomeClass) { }

    private static final class MutableBar {
        private final String symbol;
        private final String interval;
        private final long timestamp;
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
        }

        private void add(Candle candle) {
            high = Math.max(high, candle.getHighPrice());
            low = Math.min(low, candle.getLowPrice());
            close = candle.getClosePrice();
            volume += candle.getVolume();
        }

        private Candle toCandle() {
            return new Candle(symbol, interval, timestamp, open, high, low, close, volume);
        }
    }
}
