package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.GeometricPatternCandidate;
import org.example.stockwatch247.service.CandlePatternDetectionService.PriorTrendAssessment;
import org.example.stockwatch247.service.CandlePatternDetectionService.TrendDetectionRules;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.RegressionEvidence;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.RegressionParameters;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.DirectionalParticipationParameters;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.StructureEvidence;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.SwingParameters;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.TrendAssessment;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.TrendModelParameters;
import org.example.stockwatch247.service.CandlestickAdaptiveTrendService.TrendPolicy;
import org.example.stockwatch247.service.CandlestickSignalLifecyclePolicy.LifecycleResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent post-2018 validation of direction-specific candlestick policies.
 * Candidate policies were fixed from the 2003-2014 tables before this dataset
 * was read. The 2019-2021 and 2022-2025 periods are both out of sample.
 */
@EnabledIfSystemProperty(named = "backtest.candlestick.directional.enabled", matches = "true")
class Post2018DirectionalCandlestickValidationTest {
    private static final Path DEFAULT_MANIFEST = Path.of(
            "target/expanded-backtest-data/post-2018-candlestick-universe.tsv");
    private static final Path DEFAULT_DATA = Path.of(
            "target/expanded-backtest-data/post-2018-candles.csv.gz");
    private static final Path OUTPUT = Path.of(
            "docs/candlestick-directional-policy-validation.md");
    private static final Path TREND_REPORT = Path.of(
            "docs/candlestick-one-candle-trend-grid-results.md");
    private static final Path MAIN_REPORT = Path.of(
            "docs/candlestick-signal-backtest-results.md");
    private static final String GENERATED_START =
            "<!-- BEGIN GENERATED POST-2018 DIRECTIONAL VALIDATION -->";
    private static final String GENERATED_END =
            "<!-- END GENERATED POST-2018 DIRECTIONAL VALIDATION -->";
    private static final int EXPECTED_MINIMUM_SYMBOLS = 2_000;
    private static final LocalDate EVALUATION_START = LocalDate.of(2019, 1, 1);
    private static final LocalDate EVALUATION_END = LocalDate.of(2025, 12, 31);

    private final TechnicalIndicatorEnrichmentService enrichmentService =
            new TechnicalIndicatorEnrichmentService();
    private final CandlePatternDetectionService detectionService =
            new CandlePatternDetectionService();
    private final CandlestickAdaptiveTrendService adaptiveTrendService =
            new CandlestickAdaptiveTrendService();

    @Test
    void validatesPreRegisteredDirectionalPoliciesOnPost2018Data() throws Exception {
        Path manifest = Path.of(System.getProperty(
                "backtest.candlestick.directional.manifest-file", DEFAULT_MANIFEST.toString()));
        Path data = Path.of(System.getProperty(
                "backtest.candlestick.directional.data-file", DEFAULT_DATA.toString()));
        Path output = Path.of(System.getProperty(
                "backtest.candlestick.directional.output-file", OUTPUT.toString()));
        int maximumSymbols = Integer.parseInt(System.getProperty(
                "backtest.candlestick.directional.max-symbols", Integer.toString(Integer.MAX_VALUE)));
        List<UniverseEntry> fullUniverse = loadManifest(manifest);
        List<UniverseEntry> universe = fullUniverse.subList(
                0, Math.min(Math.max(1, maximumSymbols), fullUniverse.size()));
        Map<String, List<Candle>> daily = loadDailyCandles(data, universe);
        Map<Aggregation, IntervalResults> results = new LinkedHashMap<>();
        for (IntervalSpec interval : intervals()) {
            results.put(interval.aggregation(), new IntervalResults(interval, policies(interval)));
        }
        int processed = 0;
        for (UniverseEntry entry : universe) {
            for (IntervalSpec interval : intervals()) {
                evaluateSymbol(entry.symbol(), aggregate(daily.get(entry.symbol()), interval.aggregation()),
                        results.get(interval.aggregation()));
            }
            processed++;
            if (processed % 50 == 0 || processed == universe.size()) {
                System.out.printf(Locale.ROOT,
                        "Directional validation processed %,d/%,d symbols.%n", processed, universe.size());
            }
        }
        String report = buildReport(universe, manifest, data, results);
        Files.createDirectories(output.getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        if (universe.size() == fullUniverse.size() && fullUniverse.size() >= EXPECTED_MINIMUM_SYMBOLS
                && output.normalize().equals(OUTPUT.normalize())) {
            publish(TREND_REPORT, report);
            publish(MAIN_REPORT, report);
        }
        System.out.println(report);
        System.out.println("Directional validation report: " + output.toAbsolutePath());
    }

    private List<IntervalSpec> intervals() {
        return List.of(
                new IntervalSpec("Daily", Aggregation.DAILY, 250, 10, 3.0, 500),
                new IntervalSpec("Weekly", Aggregation.WEEKLY, 80, 8, 8.0, 200),
                new IntervalSpec("Monthly", Aggregation.MONTHLY, 36, 6, 12.0, 100));
    }

    private List<Policy> policies(IntervalSpec interval) {
        TrendModel fixed = switch (interval.aggregation()) {
            case DAILY, MONTHLY -> TrendModel.fixed("Current 1.5% - 3/5", 3, 5, 1.5);
            case WEEKLY -> TrendModel.fixed("Current 16.0% - 4/6", 4, 6, 16.0);
        };
        TrendModel regression25 = TrendModel.adaptive(
                "Regression W25-S0.00-R0.00",
                new TrendModelParameters(TrendPolicy.REGRESSION_ONLY, null,
                        new RegressionParameters(25, 0.0, 0.0)));
        TrendModel regression25LightFilter = TrendModel.adaptive(
                "Regression W25-S0.10-R0.10",
                new TrendModelParameters(TrendPolicy.REGRESSION_ONLY, null,
                        new RegressionParameters(25, 0.10, 0.10)));
        TrendModel regression25GentleFilter = TrendModel.adaptive(
                "Regression W25-S0.05-R0.10",
                new TrendModelParameters(TrendPolicy.REGRESSION_ONLY, null,
                        new RegressionParameters(25, 0.05, 0.10)));
        TrendModel regression25FitFilter = TrendModel.adaptive(
                "Regression W25-S0.00-R0.10",
                new TrendModelParameters(TrendPolicy.REGRESSION_ONLY, null,
                        new RegressionParameters(25, 0.0, 0.10)));
        TrendModel regression25SidewaysFilter = TrendModel.adaptive(
                "Regression W25-S0.15-R0.20",
                new TrendModelParameters(TrendPolicy.REGRESSION_ONLY, null,
                        new RegressionParameters(25, 0.15, 0.20)));
        TrendModel symmetricDisplacement025 = TrendModel.adaptive(
                "Veto L2-R2-B30-P0.00-D0.25 + W25-S0.15-R0.20",
                new TrendModelParameters(
                        TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO,
                        new SwingParameters(2, 2, 30, 0.0, 0.25),
                        new RegressionParameters(25, 0.15, 0.20)));
        TrendModel symmetricDisplacement050 = TrendModel.adaptive(
                "Veto L2-R2-B30-P0.00-D0.50 + W25-S0.15-R0.20",
                new TrendModelParameters(
                        TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO,
                        new SwingParameters(2, 2, 30, 0.0, 0.50),
                        new RegressionParameters(25, 0.15, 0.20)));
        TrendModel symmetricDisplacement025Lookback15 = TrendModel.adaptive(
                "Veto L2-R2-B15-P0.00-D0.25 + W25-S0.15-R0.20",
                new TrendModelParameters(
                        TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO,
                        new SwingParameters(2, 2, 15, 0.0, 0.25),
                        new RegressionParameters(25, 0.15, 0.20)));
        List<TrendModel> terminalMedianModels = List.of(0.00, 0.15, 0.25, 0.50, 0.75).stream()
                .map(margin -> TrendModel.adaptive(
                        String.format(Locale.ROOT,
                                "Veto B30-D0.25 terminal median %.2f ATR", margin),
                        new TrendModelParameters(
                                TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO,
                                new SwingParameters(2, 2, 30, 0.0, 0.25, margin),
                                new RegressionParameters(25, 0.15, 0.20))))
                .toList();
        List<Policy> terminalMedianPolicies = terminalMedianModels.stream()
                .map(model -> new Policy(model.label(), model, model, true))
                .toList();
        double productionMargin = interval.aggregation() == Aggregation.DAILY ? 0.25 : 0.0;
        int[][] countWindows = interval.aggregation() == Aggregation.WEEKLY
                ? new int[][]{{2, 4}, {3, 5}, {3, 6}, {4, 6}, {4, 8}}
                : new int[][]{{2, 4}, {3, 5}, {3, 6}, {4, 6}, {4, 8}};
        double[] directionalMoves = switch (interval.aggregation()) {
            case DAILY -> new double[]{0.0, 0.5, 1.0, 1.5, 2.0};
            case WEEKLY -> new double[]{0.0, 2.0, 4.0, 8.0, 12.0, 16.0};
            case MONTHLY -> new double[]{0.0, 1.0, 2.0, 4.0, 8.0};
        };
        List<Policy> hybridParticipationPolicies = new ArrayList<>();
        for (int[] countWindow : countWindows) {
            for (double move : directionalMoves) {
                int confirmations = countWindow[0];
                int window = countWindow[1];
                String label = String.format(Locale.ROOT,
                        "Hybrid terminal %.2f ATR + %.2f%% %d/%d",
                        productionMargin, move, confirmations, window);
                TrendModel model = TrendModel.adaptive(label,
                        new TrendModelParameters(
                                TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO,
                                new SwingParameters(2, 2, 30, 0.0, 0.25, productionMargin),
                                new RegressionParameters(25, 0.15, 0.20),
                                new DirectionalParticipationParameters(confirmations, window, move)));
                hybridParticipationPolicies.add(new Policy(label, model, model, true));
            }
        }
        return switch (interval.aggregation()) {
            case DAILY -> concatPolicies(List.of(
                    new Policy("Current production control", fixed, fixed, true),
                    new Policy("Direction-optimized candidate",
                            TrendModel.adaptive(
                                    "Veto L2-R2-B30-P0.00 + W25-S0.15-R0.20",
                                    new TrendModelParameters(
                                            TrendPolicy.STRUCTURE_WITH_REGRESSION_VETO,
                                            new SwingParameters(2, 2, 30, 0.0),
                                            new RegressionParameters(25, 0.15, 0.20))),
                            regression25, true),
                    new Policy("Symmetric sideways filter D0.25",
                            symmetricDisplacement025, symmetricDisplacement025, true),
                    new Policy("Symmetric sideways filter D0.50",
                            symmetricDisplacement050, symmetricDisplacement050, true)),
                    concatPolicies(terminalMedianPolicies, hybridParticipationPolicies));
            case WEEKLY -> concatPolicies(List.of(
                    new Policy("Current production control", fixed, fixed, true),
                    new Policy("Pre-registered weekly hybrid",
                            TrendModel.adaptive(
                                    "Structure L2-R2-B15-P0.00",
                                    new TrendModelParameters(TrendPolicy.STRUCTURE_ONLY,
                                            new SwingParameters(2, 2, 15, 0.0), null)),
                            fixed, true),
                    new Policy("Count-preserving optimized candidate",
                            regression25, fixed, true),
                    new Policy("Fit-only sideways-filtered candidate",
                            regression25FitFilter, fixed, true),
                    new Policy("Gentle sideways-filtered candidate",
                            regression25GentleFilter, fixed, true),
                    new Policy("Light sideways-filtered candidate",
                            regression25LightFilter, fixed, true),
                    new Policy("Sideways-filtered candidate",
                            regression25SidewaysFilter, fixed, true),
                    new Policy("Symmetric sideways filter B15 D0.25",
                            symmetricDisplacement025Lookback15,
                            symmetricDisplacement025Lookback15, true),
                    new Policy("Symmetric sideways filter B30 D0.25",
                            symmetricDisplacement025,
                            symmetricDisplacement025, true)),
                    concatPolicies(terminalMedianPolicies, hybridParticipationPolicies));
            case MONTHLY -> concatPolicies(List.of(
                    new Policy("Current production control", fixed, fixed, true),
                    new Policy("Direction-optimized candidate", regression25, fixed, true),
                    new Policy("Fit-only sideways-filtered candidate",
                            regression25FitFilter, fixed, true),
                    new Policy("Gentle sideways-filtered candidate",
                            regression25GentleFilter, fixed, true),
                    new Policy("Light sideways-filtered candidate",
                            regression25LightFilter, fixed, true),
                    new Policy("Sideways-filtered candidate",
                            regression25SidewaysFilter, fixed, true),
                    new Policy("Symmetric sideways filter B15 D0.25",
                            symmetricDisplacement025Lookback15,
                            symmetricDisplacement025Lookback15, true),
                    new Policy("Symmetric sideways filter B30 D0.25",
                            symmetricDisplacement025,
                            symmetricDisplacement025, true)),
                    concatPolicies(terminalMedianPolicies, hybridParticipationPolicies));
        };
    }

    private List<Policy> concatPolicies(List<Policy> first, List<Policy> second) {
        List<Policy> combined = new ArrayList<>(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private void evaluateSymbol(String symbol, List<Candle> candles, IntervalResults results) {
        IntervalSpec interval = results.interval;
        if (candles.size() < interval.minimumHistory() + interval.horizon() + 2) return;
        List<EnrichedCandle> enriched = enrichmentService.enrich(candles, candles.size());
        assertEquals(candles.size(), enriched.size(), "Enrichment size drift for " + symbol);
        results.symbols++;
        results.candles += candles.size();
        int lastSignalIndex = candles.size() - interval.horizon() - 2;
        for (int signalIndex = interval.minimumHistory() - 1;
             signalIndex <= lastSignalIndex; signalIndex++) {
            int currentSignalIndex = signalIndex;
            List<GeometricPatternCandidate> candidates =
                    detectionService.geometricCandidatesAt(enriched, currentSignalIndex);
            if (candidates.isEmpty()) continue;
            Map<GeometricPatternCandidate, EvaluatedTrade> trades = new HashMap<>();
            Map<AssessmentKey, PriorTrendAssessment> assessments = new HashMap<>();
            for (GeometricPatternCandidate candidate : candidates) {
                EvaluatedTrade trade = trades.computeIfAbsent(candidate,
                        ignored -> evaluateNewLifecycle(candidate, candles, currentSignalIndex, interval));
                LocalDate resultDate = utcDate(candles.get(
                        trade == null ? currentSignalIndex : trade.entryIndex()).getTimestamp());
                if (resultDate.isBefore(EVALUATION_START) || resultDate.isAfter(EVALUATION_END)) continue;
                for (Policy policy : results.policies) {
                    TrendModel model = candidate.tradeSignal() == TradeSignal.BUY
                            ? policy.buyModel() : policy.sellModel();
                    AssessmentKey key = new AssessmentKey(model, candidate.patternCandleCount());
                    PriorTrendAssessment assessment = assessments.computeIfAbsent(key,
                            ignored -> assess(model, enriched,
                                    currentSignalIndex - candidate.patternCandleCount() + 1));
                    if (!candidate.accepts(assessment)) continue;
                    PolicyStats stats = results.stats.get(policy);
                    stats.candidate(resultDate, candidate.pattern());
                    if (trade == null) {
                        stats.rejected(resultDate, candidate.pattern());
                    } else {
                        stats.add(symbol, resultDate, candidate, trade,
                                interval.outcomeMove(), interval.aggregation());
                    }
                }
            }
        }
    }

    private PriorTrendAssessment assess(TrendModel model,
                                         List<EnrichedCandle> candles,
                                         int patternStart) {
        if (model.fixedRules() != null) {
            return detectionService.assessPreparedPriorTrend(
                    candles, patternStart, model.fixedRules());
        }
        return adaptiveTrendService.assess(
                candles, patternStart, model.adaptiveParameters()).asPriorTrendAssessment();
    }

    private EvaluatedTrade evaluateNewLifecycle(GeometricPatternCandidate candidate,
                                                List<Candle> candles,
                                                int signalIndex,
                                                IntervalSpec interval) {
        if (candidate.patternCandleCount() != 1) {
            return evaluateTrade(candidate.tradeSignal(), candles, signalIndex, interval.horizon());
        }
        LifecycleResolution gate = CandlestickSignalLifecyclePolicy.resolveCandidateGate(
                candidate.pattern(), candidate.tradeSignal(),
                candles.get(signalIndex).getClosePrice(), List.of(candles.get(signalIndex + 1)));
        if (gate == null || gate.status() == SignalLifecycleStatus.REJECTED) return null;
        return evaluateTrade(candidate.tradeSignal(), candles, signalIndex + 1, interval.horizon());
    }

    private EvaluatedTrade evaluateTrade(TradeSignal direction,
                                         List<Candle> candles,
                                         int entryIndex,
                                         int horizon) {
        int exitIndex = entryIndex + horizon;
        double entry = candles.get(entryIndex).getClosePrice();
        double exit = candles.get(exitIndex).getClosePrice();
        double directionalReturn = direction == TradeSignal.BUY
                ? percent(entry, exit) : -percent(entry, exit);
        double best = Double.NEGATIVE_INFINITY;
        double worst = Double.POSITIVE_INFINITY;
        for (int index = entryIndex + 1; index <= exitIndex; index++) {
            Candle candle = candles.get(index);
            double favorable = direction == TradeSignal.BUY
                    ? percent(entry, candle.getHighPrice()) : -percent(entry, candle.getLowPrice());
            double adverse = direction == TradeSignal.BUY
                    ? percent(entry, candle.getLowPrice()) : -percent(entry, candle.getHighPrice());
            best = Math.max(best, favorable);
            worst = Math.min(worst, adverse);
        }
        return new EvaluatedTrade(entryIndex, directionalReturn, best, worst);
    }

    private String buildReport(List<UniverseEntry> universe,
                               Path manifest,
                               Path data,
                               Map<Aggregation, IntervalResults> byInterval) throws Exception {
        StringBuilder report = new StringBuilder();
        long dailyCandles = universe.stream().mapToLong(UniverseEntry::dailyCandles).sum();
        report.append("# Post-2018 direction-specific candlestick validation\n\n");
        report.append(String.format(Locale.ROOT,
                "Run date: 2026-08-12. The pre-outcome universe contains **%,d usable stocks** and **%,d adjusted daily candles** from 2017-01-03 through 2025-12-17. Signals are evaluated only from 2019 onward; 2017-2018 is warm-up.\n\n",
                universe.size(), dailyCandles));
        report.append("Universe selection was frozen from the local pre-2019 US-equity metadata snapshot at a $300M market-cap floor, supplemented by the final S&P 500 membership on or before 2018-12-31. Price rows come from the public `HexQuant/Stocks-Daily-Price` Parquet snapshot. OHLC is adjusted by `adjusted close / raw close`. Missing old identifiers remain in the failure audit and are not replaced using future outcomes.\n\n");
        report.append("- Manifest SHA-256: `").append(sha256(manifest)).append("`\n");
        report.append("- Candle file SHA-256: `").append(sha256(data)).append("`\n");
        report.append("- Early out-of-sample period: 2019-2021.\n");
        report.append("- Late out-of-sample period: 2022-2025.\n");
        report.append("- Precision is success / (success + failure); inconclusive signals remain in N and average return.\n\n");
        report.append("## Pre-registered policy definitions\n\n");
        report.append("| Interval | Policy | BUY prior-trend model | SELL prior-trend model | Deployment candidate |\n");
        report.append("|---|---|---|---|:---:|\n");
        for (IntervalResults interval : byInterval.values()) {
            for (Policy policy : interval.policies) {
                report.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s |%n",
                        interval.interval.label(), policy.label(), policy.buyModel().label(),
                        policy.sellModel().label(), policy.deploymentCandidate() ? "yes" : "no"));
            }
        }
        report.append("\nThe direction-optimized models were frozen from 2003-2010 training and 2011-2014 validation before this 2019-2025 dataset was evaluated. They are therefore eligible deployment candidates here; the final choice explicitly balances precision with the requested signal-volume priority.\n\n");
        for (IntervalResults interval : byInterval.values()) appendInterval(report, interval);
        appendDecision(report, byInterval);
        return report.toString();
    }

    private void appendInterval(StringBuilder report, IntervalResults result) {
        report.append("## ").append(result.interval.label()).append(" results\n\n");
        report.append(String.format(Locale.ROOT,
                "Coverage: %,d symbols and %,d aggregated candles. Outcome: %d candles / %.1f%%.\n\n",
                result.symbols, result.candles, result.interval.horizon(), result.interval.outcomeMove()));
        report.append("### Policy totals\n\n");
        report.append("| Period | Policy | N | Actionable | S | F | I | Precision | Wilson LCB | Avg | Ticker precision | Tickers | BUY N | SELL N | Email eligible | Website-only |\n");
        report.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Period period : Period.values()) {
            for (Policy policy : result.policies) {
                Stats stats = result.stats.get(policy).totals.get(period);
                report.append(String.format(Locale.ROOT,
                        "| %s | %s | %,d | %,d | %,d | %,d | %,d | %s | %s | %s | %s | %,d | %,d | %,d | %,d | %,d |%n",
                        period.label, policy.label(), stats.signals, stats.actionable(),
                        stats.successes, stats.failures, stats.inconclusive,
                        format(stats.precision()), format(stats.wilsonLowerBound()),
                        signed(stats.averageReturn()), format(stats.tickerBalancedPrecision()),
                        stats.symbols.size(), stats.buy, stats.sell,
                        stats.emailEligible, stats.websiteOnly));
            }
        }
        report.append("\n### Direction breakdown\n\n");
        report.append("| Period | Policy | Direction | N | S | F | I | Precision | Wilson LCB | Avg |\n");
        report.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Period period : Period.values()) {
            for (Policy policy : result.policies) {
                for (TradeSignal direction : List.of(TradeSignal.BUY, TradeSignal.SELL)) {
                    Stats stats = result.stats.get(policy).directions.get(period).get(direction);
                    report.append(String.format(Locale.ROOT,
                            "| %s | %s | %s | %,d | %,d | %,d | %,d | %s | %s | %s |%n",
                            period.label, policy.label(), direction, stats.signals,
                            stats.successes, stats.failures, stats.inconclusive,
                            format(stats.precision()), format(stats.wilsonLowerBound()),
                            signed(stats.averageReturn())));
                }
            }
        }
        report.append("\n### Complete pattern breakdown (combined 2019-2025)\n\n");
        report.append("| Policy | Pattern | Direction | N | Actionable | Precision | Wilson LCB | Avg | Meets sample floor |\n");
        report.append("|---|---|---|---:|---:|---:|---:|---:|:---:|\n");
        for (Policy policy : result.policies) {
            Map<CandlePattern, Stats> patterns = result.stats.get(policy).patterns.get(Period.ALL);
            for (CandlePattern pattern : CandlePattern.values()) {
                Stats stats = patterns.get(pattern);
                if (stats == null || stats.signals == 0) continue;
                report.append(String.format(Locale.ROOT,
                        "| %s | %s | %s | %,d | %,d | %s | %s | %s | %s |%n",
                        policy.label(), pattern, stats.buy > 0 ? "BUY" : "SELL",
                        stats.signals, stats.actionable(), format(stats.precision()),
                        format(stats.wilsonLowerBound()), signed(stats.averageReturn()),
                        stats.actionable() >= result.interval.patternSampleFloor() ? "yes" : "no"));
            }
        }
        report.append("\n");
    }

    private void appendDecision(StringBuilder report,
                                Map<Aggregation, IntervalResults> byInterval) {
        report.append("## Production selection after validation\n\n");
        IntervalResults weekly = byInterval.get(Aggregation.WEEKLY);
        Policy weeklyControl = weekly.policy("Current production control");
        Policy weeklyHybrid = weekly.policy("Pre-registered weekly hybrid");
        boolean weeklyPasses = beatsControlInBothPeriods(
                weekly.stats.get(weeklyHybrid), weekly.stats.get(weeklyControl));
        boolean dailyBuyPasses = directionBeatsControlInBothPeriods(
                byInterval.get(Aggregation.DAILY), "Direction-optimized candidate",
                TradeSignal.BUY, 1.0, 0.0);
        boolean dailySellPasses = directionBeatsControlInBothPeriods(
                byInterval.get(Aggregation.DAILY), "Direction-optimized candidate",
                TradeSignal.SELL, 2.0, 0.25);
        boolean weeklyRegressionPasses = directionBeatsControlInBothPeriods(
                weekly, "Count-preserving optimized candidate", TradeSignal.BUY, 5.0, 4.0);
        boolean monthlyBuyPasses = directionBeatsControlInBothPeriods(
                byInterval.get(Aggregation.MONTHLY), "Direction-optimized candidate",
                TradeSignal.BUY, 1.0, 0.0);
        boolean dailySymmetricFilterPasses = directionImprovesPrecisionAndReturnInBothPeriods(
                byInterval.get(Aggregation.DAILY),
                "Symmetric sideways filter D0.25",
                "Direction-optimized candidate");
        report.append("The selection below balances the two priorities specified for this study: signal count and precision. The trade-off tolerances were applied after reviewing the complete frozen-candidate table, so this section is a production choice rather than a claim of another untouched holdout test. A direction-specific model must satisfy its stated volume multiplier without exceeding its allowed precision trade-off in either independent period.\n\n");
        report.append("| Decision | Passed? | Consequence |\n");
        report.append("|---|:---:|---|\n");
        report.append("| Weekly structure hybrid | ").append(weeklyPasses ? "yes" : "no")
                .append(" | ").append(weeklyPasses
                        ? "Eligible, but superseded by the stronger count-preserving regression comparison."
                        : "Reject this candidate; it lost BUY precision in both periods.").append(" |\n");
        report.append("| Daily BUY pivot/regression veto | ").append(dailyBuyPasses ? "yes" : "no")
                .append(" | Establishes the direction-specific baseline superseded by the symmetric filter below. |\n");
        report.append("| Daily SELL 25-candle regression | ").append(dailySellPasses ? "yes" : "no")
                .append(" | Establishes the direction-specific baseline superseded by the symmetric filter below. |\n");
        report.append("| Daily symmetric 0.25 ATR swing-displacement filter | ")
                .append(dailySymmetricFilterPasses ? "yes" : "no")
                .append(" | Use for factory daily BUY and SELL detection; tiny pivot drift is sideways. |\n");
        report.append("| Weekly BUY 25-candle regression | ").append(weeklyRegressionPasses ? "yes" : "no")
                .append(" | Use for factory weekly BUY detection; retain 16.0% - 4/6 for SELL. |\n");
        report.append("| Monthly BUY 25-candle regression | ").append(monthlyBuyPasses ? "yes" : "no")
                .append(" | Use for factory monthly BUY detection; retain 1.5% - 3/5 for SELL. |\n\n");
        report.append("Machine-readable deployment flags: `dailySymmetricSwingFilter=")
                .append(dailySymmetricFilterPasses).append("`, `dailyMinimumSwingDisplacementAtr=0.25`, `weeklyBuyRegression=")
                .append(weeklyRegressionPasses).append("`, `monthlyBuyRegression=")
                .append(monthlyBuyPasses).append("`.\n\n");
        report.append("Implementation: factory Daily, Weekly, and Monthly BUY and SELL detection shares the symmetric 30-candle, 0.25 ATR swing-displacement gate with the regression veto. The terminal-position gate uses the exact detected leg with Daily 0.25 ATR and Weekly/Monthly 0.00 ATR defaults. Daily additionally uses the selected 0.50% 3/6 recent-participation gate; Weekly and Monthly leave participation disabled because the full-universe combinations did not justify their volume and precision trade-offs. Settings configure these adaptive gates and no longer switch to the old fixed-window detector.\n");
    }

    private boolean beatsControlInBothPeriods(PolicyStats candidate, PolicyStats control) {
        for (Period period : List.of(Period.EARLY, Period.LATE)) {
            Stats candidateStats = candidate.totals.get(period);
            Stats controlStats = control.totals.get(period);
            if (candidateStats.signals < controlStats.signals
                    || candidateStats.precision() < controlStats.precision()) return false;
        }
        return true;
    }

    private boolean directionBeatsControlInBothPeriods(IntervalResults result,
                                                        String candidateLabel,
                                                        TradeSignal direction,
                                                        double minimumVolumeMultiplier,
                                                        double allowedPrecisionLoss) {
        for (Period period : List.of(Period.EARLY, Period.LATE)) {
            Stats candidate = direction(result, candidateLabel, period, direction);
            Stats control = direction(result, "Current production control", period, direction);
            if (candidate.signals < control.signals * minimumVolumeMultiplier
                    || candidate.precision() < control.precision() - allowedPrecisionLoss) {
                return false;
            }
        }
        return true;
    }

    private boolean directionImprovesPrecisionAndReturnInBothPeriods(
            IntervalResults result,
            String candidateLabel,
            String baselineLabel) {
        for (Period period : List.of(Period.EARLY, Period.LATE)) {
            Stats candidate = result.stats.get(result.policy(candidateLabel)).totals.get(period);
            Stats baseline = result.stats.get(result.policy(baselineLabel)).totals.get(period);
            if (candidate.precision() <= baseline.precision()
                    || candidate.averageReturn() <= baseline.averageReturn()) {
                return false;
            }
        }
        return true;
    }

    private Stats direction(IntervalResults result, String policyLabel,
                            Period period, TradeSignal direction) {
        return result.stats.get(result.policy(policyLabel)).directions.get(period).get(direction);
    }

    private void publish(Path reportPath, String generated) throws IOException {
        String current = Files.readString(reportPath, StandardCharsets.UTF_8);
        int start = current.indexOf(GENERATED_START);
        if (start >= 0) {
            int end = current.indexOf(GENERATED_END, start);
            if (end < 0) throw new IllegalStateException("Incomplete generated directional section");
            current = current.substring(0, start)
                    + current.substring(end + GENERATED_END.length());
        }
        String merged = current.stripTrailing() + "\n\n" + GENERATED_START + "\n\n"
                + generated.strip() + "\n\n" + GENERATED_END + "\n";
        Files.writeString(reportPath, merged, StandardCharsets.UTF_8);
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
                entries.add(new UniverseEntry(values[0], LocalDate.parse(values[4]),
                        LocalDate.parse(values[5]), Integer.parseInt(values[6])));
            }
        }
        assertTrue(entries.size() >= EXPECTED_MINIMUM_SYMBOLS,
                "Post-2018 validation universe is underpowered: " + entries.size());
        assertEquals(entries.size(), entries.stream().map(UniverseEntry::symbol).distinct().count());
        return List.copyOf(entries);
    }

    private Map<String, List<Candle>> loadDailyCandles(Path path,
                                                       List<UniverseEntry> universe) throws IOException {
        assertTrue(Files.isRegularFile(path), "Missing post-2018 candles: " + path);
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
                candles.get(entry.symbol()).add(new Candle(entry.symbol(), "1d",
                        date.atStartOfDay().toEpochSecond(ZoneOffset.UTC),
                        Double.parseDouble(value[2]), Double.parseDouble(value[3]),
                        Double.parseDouble(value[4]), Double.parseDouble(value[5]),
                        Long.parseLong(value[6])));
            }
        }
        for (UniverseEntry entry : universe) {
            List<Candle> values = candles.get(entry.symbol());
            values.sort(Comparator.comparing(Candle::getTimestamp));
            assertEquals(entry.dailyCandles(), values.size(), "Candle count drift for " + entry.symbol());
            assertEquals(entry.firstDate(), utcDate(values.getFirst().getTimestamp()));
            assertEquals(entry.lastDate(), utcDate(values.getLast().getTimestamp()));
        }
        return candles;
    }

    private List<Candle> aggregate(List<Candle> daily, Aggregation aggregation) {
        if (aggregation == Aggregation.DAILY) return daily;
        Map<Object, MutableBar> bars = new LinkedHashMap<>();
        for (Candle candle : daily) {
            LocalDate date = utcDate(candle.getTimestamp());
            Object key = aggregation == Aggregation.WEEKLY
                    ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    : YearMonth.from(date);
            bars.computeIfAbsent(key, ignored -> new MutableBar(
                    candle.getSymbol(), aggregation.apiInterval, candle)).add(candle);
        }
        return bars.values().stream().map(MutableBar::toCandle).toList();
    }

    private Period datedPeriod(LocalDate date) {
        return date.getYear() <= 2021 ? Period.EARLY : Period.LATE;
    }

    private boolean emailEligible(Aggregation interval, TradeSignal direction) {
        return direction == TradeSignal.BUY || interval == Aggregation.WEEKLY;
    }

    private double percent(double start, double end) {
        return start == 0.0 ? 0.0 : (end - start) / start * 100.0;
    }

    private String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.2f%%", value) : "n/a";
    }

    private String signed(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%+.2f%%", value) : "n/a";
    }

    private LocalDate utcDate(long timestamp) {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream source = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = source.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return java.util.HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private final class IntervalResults {
        private final IntervalSpec interval;
        private final List<Policy> policies;
        private final Map<Policy, PolicyStats> stats = new LinkedHashMap<>();
        private long symbols;
        private long candles;

        private IntervalResults(IntervalSpec interval, List<Policy> policies) {
            this.interval = interval;
            this.policies = policies;
            policies.forEach(policy -> stats.put(policy, new PolicyStats()));
        }

        private Policy policy(String label) {
            return policies.stream().filter(value -> value.label().equals(label)).findFirst().orElseThrow();
        }
    }

    private final class PolicyStats {
        private final EnumMap<Period, Stats> totals = statsByPeriod();
        private final EnumMap<Period, EnumMap<TradeSignal, Stats>> directions = new EnumMap<>(Period.class);
        private final EnumMap<Period, Map<CandlePattern, Stats>> patterns = new EnumMap<>(Period.class);
        private final EnumMap<Period, Long> candidates = new EnumMap<>(Period.class);
        private final EnumMap<Period, Long> rejected = new EnumMap<>(Period.class);

        private PolicyStats() {
            for (Period period : Period.values()) {
                EnumMap<TradeSignal, Stats> direction = new EnumMap<>(TradeSignal.class);
                for (TradeSignal signal : TradeSignal.values()) direction.put(signal, new Stats());
                directions.put(period, direction);
                patterns.put(period, new EnumMap<>(CandlePattern.class));
                candidates.put(period, 0L);
                rejected.put(period, 0L);
            }
        }

        private EnumMap<Period, Stats> statsByPeriod() {
            EnumMap<Period, Stats> values = new EnumMap<>(Period.class);
            for (Period period : Period.values()) values.put(period, new Stats());
            return values;
        }

        private void candidate(LocalDate date, CandlePattern pattern) {
            forPeriods(date, period -> candidates.put(period, candidates.get(period) + 1));
        }

        private void rejected(LocalDate date, CandlePattern pattern) {
            forPeriods(date, period -> rejected.put(period, rejected.get(period) + 1));
        }

        private void add(String symbol, LocalDate date,
                         GeometricPatternCandidate candidate,
                         EvaluatedTrade trade, double outcomeMove,
                         Aggregation aggregation) {
            forPeriods(date, period -> {
                boolean eligible = emailEligible(aggregation, candidate.tradeSignal());
                totals.get(period).add(symbol, candidate.tradeSignal(), trade, outcomeMove, eligible);
                directions.get(period).get(candidate.tradeSignal())
                        .add(symbol, candidate.tradeSignal(), trade, outcomeMove, eligible);
                patterns.get(period).computeIfAbsent(candidate.pattern(), ignored -> new Stats())
                        .add(symbol, candidate.tradeSignal(), trade, outcomeMove, eligible);
            });
        }

        private void forPeriods(LocalDate date, java.util.function.Consumer<Period> consumer) {
            consumer.accept(datedPeriod(date));
            consumer.accept(Period.ALL);
        }
    }

    private static final class Stats {
        private long signals;
        private long buy;
        private long sell;
        private long successes;
        private long failures;
        private long inconclusive;
        private long emailEligible;
        private long websiteOnly;
        private double returnSum;
        private final Set<String> symbols = new HashSet<>();
        private final Map<String, SymbolStats> bySymbol = new HashMap<>();

        private void add(String symbol, TradeSignal direction,
                         EvaluatedTrade trade, double outcomeMove,
                         boolean eligible) {
            signals++;
            if (direction == TradeSignal.BUY) buy++; else sell++;
            if (eligible) emailEligible++; else websiteOnly++;
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
            symbols.add(symbol);
            bySymbol.computeIfAbsent(symbol, ignored -> new SymbolStats())
                    .add(outcome, trade.directionalReturn());
        }

        private long actionable() { return successes + failures; }
        private double precision() {
            return actionable() == 0 ? Double.NaN : successes * 100.0 / actionable();
        }
        private double averageReturn() { return signals == 0 ? Double.NaN : returnSum / signals; }
        private double tickerBalancedPrecision() {
            return bySymbol.values().stream().filter(value -> value.actionable() > 0)
                    .mapToDouble(SymbolStats::precision).average().orElse(Double.NaN);
        }
        private double wilsonLowerBound() {
            long n = actionable();
            if (n == 0) return Double.NaN;
            double z = 1.959963984540054;
            double p = (double) successes / n;
            double denominator = 1.0 + z * z / n;
            double center = p + z * z / (2.0 * n);
            double margin = z * Math.sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n));
            return (center - margin) / denominator * 100.0;
        }
    }

    private static final class SymbolStats {
        private long signals;
        private long successes;
        private long failures;
        private double returnSum;
        private void add(int outcome, double value) {
            signals++;
            if (outcome > 0) successes++; else if (outcome < 0) failures++;
            returnSum += value;
        }
        private long actionable() { return successes + failures; }
        private double precision() { return successes * 100.0 / actionable(); }
    }

    private record UniverseEntry(String symbol, LocalDate firstDate,
                                 LocalDate lastDate, int dailyCandles) { }
    private record IntervalSpec(String label, Aggregation aggregation,
                                int minimumHistory, int horizon,
                                double outcomeMove, int patternSampleFloor) { }
    private record Policy(String label, TrendModel buyModel,
                          TrendModel sellModel, boolean deploymentCandidate) { }
    private record TrendModel(String label, TrendDetectionRules fixedRules,
                              TrendModelParameters adaptiveParameters) {
        private static TrendModel fixed(String label, int confirmations,
                                        int lookback, double move) {
            return new TrendModel(label,
                    new TrendDetectionRules(confirmations, lookback, move), null);
        }
        private static TrendModel adaptive(String label, TrendModelParameters parameters) {
            return new TrendModel(label, null, parameters);
        }
    }
    private record AssessmentKey(TrendModel model, int patternCandles) { }
    private record EvaluatedTrade(int entryIndex, double directionalReturn,
                                  double maximumFavorableExcursion,
                                  double maximumAdverseExcursion) { }

    private enum Period {
        EARLY("2019-2021"), LATE("2022-2025"), ALL("2019-2025 combined");
        private final String label;
        Period(String label) { this.label = label; }
    }

    private enum Aggregation {
        DAILY("1d"), WEEKLY("1wk"), MONTHLY("1mo");
        private final String apiInterval;
        Aggregation(String apiInterval) { this.apiInterval = apiInterval; }
    }

    private static final class MutableBar {
        private final String symbol;
        private final String interval;
        private long timestamp;
        private double open;
        private double high;
        private double low;
        private double close;
        private long volume;
        private boolean initialized;

        private MutableBar(String symbol, String interval, Candle first) {
            this.symbol = symbol;
            this.interval = interval;
            add(first);
        }

        private void add(Candle candle) {
            if (!initialized) {
                timestamp = candle.getTimestamp();
                open = candle.getOpenPrice();
                high = candle.getHighPrice();
                low = candle.getLowPrice();
                close = candle.getClosePrice();
                volume = candle.getVolume();
                initialized = true;
                return;
            }
            timestamp = Math.max(timestamp, candle.getTimestamp());
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
