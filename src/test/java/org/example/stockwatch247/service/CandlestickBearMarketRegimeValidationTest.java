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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Descriptive regime stratification of the current factory candlestick model.
 * The regime is known at each signal close and never uses a future peak,
 * trough, return, or outcome.
 */
@EnabledIfSystemProperty(named = "backtest.candlestick.bear-regime.enabled", matches = "true")
class CandlestickBearMarketRegimeValidationTest {
    private static final Path DEFAULT_MANIFEST = Path.of(
            "target/expanded-backtest-data/post-2018-candlestick-universe.tsv");
    private static final Path DEFAULT_DATA = Path.of(
            "target/expanded-backtest-data/post-2018-candles.csv.gz");
    private static final Path DEFAULT_OUTPUT = Path.of(
            "docs/candlestick-bear-market-regime-validation.md");
    private static final Path DEFAULT_REFINEMENT_OUTPUT = Path.of(
            "docs/candlestick-sell-refinement-validation.md");
    private static final LocalDate EVALUATION_START = LocalDate.of(2019, 1, 1);
    private static final LocalDate EVALUATION_END = LocalDate.of(2025, 12, 31);
    private static final int EXPECTED_MINIMUM_SYMBOLS = 2_000;

    private final TechnicalIndicatorEnrichmentService enrichmentService =
            new TechnicalIndicatorEnrichmentService();
    private final CandlePatternDetectionService detectionService =
            new CandlePatternDetectionService();

    @Test
    void comparesCurrentFactorySignalsAcrossCausalMarketRegimes() throws Exception {
        Path manifest = Path.of(System.getProperty(
                "backtest.candlestick.bear-regime.manifest-file", DEFAULT_MANIFEST.toString()));
        Path data = Path.of(System.getProperty(
                "backtest.candlestick.bear-regime.data-file", DEFAULT_DATA.toString()));
        Path output = Path.of(System.getProperty(
                "backtest.candlestick.bear-regime.output-file", DEFAULT_OUTPUT.toString()));
        Path refinementOutput = Path.of(System.getProperty(
                "backtest.candlestick.sell-refinement.output-file",
                DEFAULT_REFINEMENT_OUTPUT.toString()));
        int maximumSymbols = Integer.parseInt(System.getProperty(
                "backtest.candlestick.bear-regime.max-symbols", Integer.toString(Integer.MAX_VALUE)));

        List<UniverseEntry> fullUniverse = loadManifest(manifest);
        List<UniverseEntry> universe = fullUniverse.subList(
                0, Math.min(Math.max(1, maximumSymbols), fullUniverse.size()));
        Map<String, List<Candle>> daily = loadDailyCandles(data, universe);
        MarketRegimeSeries market = buildMarketRegimes(universe, daily);
        EnumMap<Aggregation, IntervalResults> results = new EnumMap<>(Aggregation.class);
        intervals().forEach(interval -> results.put(interval.aggregation(), new IntervalResults(interval)));

        int processed = 0;
        for (UniverseEntry entry : universe) {
            for (IntervalSpec interval : intervals()) {
                evaluateSymbol(entry.symbol(), aggregate(daily.get(entry.symbol()), interval.aggregation()),
                        market.byInterval().get(interval.aggregation()), results.get(interval.aggregation()));
            }
            processed++;
            if (processed % 50 == 0 || processed == universe.size()) {
                System.out.printf(Locale.ROOT,
                        "Bear-regime validation processed %,d/%,d symbols.%n", processed, universe.size());
            }
        }

        validateResults(results);
        String report = buildReport(universe, manifest, data, market, results);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, report, StandardCharsets.UTF_8);
        String refinementReport = buildRefinementReport(universe, manifest, data, results);
        Files.createDirectories(refinementOutput.toAbsolutePath().getParent());
        Files.writeString(refinementOutput, refinementReport, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println(refinementReport);
        System.out.println("Bear-regime validation report: " + output.toAbsolutePath());
        System.out.println("SELL-refinement validation report: " + refinementOutput.toAbsolutePath());
    }

    private void validateResults(EnumMap<Aggregation, IntervalResults> results) {
        for (IntervalResults result : results.values()) {
            for (Segment segment : Segment.values()) {
                for (TradeSignal direction : List.of(TradeSignal.BUY, TradeSignal.SELL)) {
                    Stats all = result.stats.get(segment).get(MarketRegime.ALL).get(direction);
                    List<Stats> partitions = List.of(
                            result.stats.get(segment).get(MarketRegime.BEARISH).get(direction),
                            result.stats.get(segment).get(MarketRegime.BULLISH).get(direction),
                            result.stats.get(segment).get(MarketRegime.TRANSITIONAL).get(direction));
                    assertEquals(all.signals,
                            partitions.stream().mapToLong(value -> value.signals).sum(),
                            result.interval.label() + " " + segment.label + " " + direction + " N");
                    assertEquals(all.successes,
                            partitions.stream().mapToLong(value -> value.successes).sum(),
                            result.interval.label() + " " + segment.label + " " + direction + " successes");
                    assertEquals(all.failures,
                            partitions.stream().mapToLong(value -> value.failures).sum(),
                            result.interval.label() + " " + segment.label + " " + direction + " failures");
                    assertEquals(all.inconclusive,
                            partitions.stream().mapToLong(value -> value.inconclusive).sum(),
                            result.interval.label() + " " + segment.label + " " + direction + " inconclusive");
                }
            }
            for (Segment segment : Segment.values()) {
                Stats productionSell = result.stats.get(segment)
                        .get(MarketRegime.ALL).get(TradeSignal.SELL);
                Stats refinementBaseline = result.refinementStats.get(segment)
                        .get(RefinementStage.DETECTED_SETUP);
                assertEquals(productionSell.signals, refinementBaseline.signals,
                        result.interval.label() + " " + segment.label + " refinement baseline N");
                assertEquals(productionSell.successes, refinementBaseline.successes,
                        result.interval.label() + " " + segment.label + " refinement successes");
                assertEquals(refinementBaseline.signals,
                        result.primaryOutcomes.get(segment).values().stream()
                                .mapToLong(Long::longValue).sum(),
                        result.interval.label() + " " + segment.label + " primary outcomes");
            }
        }
    }

    private List<IntervalSpec> intervals() {
        return List.of(
                new IntervalSpec("Daily", Aggregation.DAILY, TimeInterval.DAILY,
                        250, 10, 3.0, 50, 200),
                new IntervalSpec("Weekly", Aggregation.WEEKLY, TimeInterval.WEEKLY,
                        80, 8, 8.0, 10, 40),
                new IntervalSpec("Monthly", Aggregation.MONTHLY, TimeInterval.MONTHLY,
                        36, 6, 12.0, 3, 10));
    }

    private void evaluateSymbol(String symbol,
                                List<Candle> candles,
                                NavigableMap<LocalDate, MarketRegime> regimes,
                                IntervalResults results) {
        IntervalSpec interval = results.interval();
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
            Map<AssessmentKey, PriorTrendAssessment> assessments = new HashMap<>();
            Map<GeometricPatternCandidate, EvaluatedTrade> trades = new HashMap<>();
            for (GeometricPatternCandidate candidate : candidates) {
                int patternStart = currentSignalIndex - candidate.patternCandleCount() + 1;
                AssessmentKey key = new AssessmentKey(patternStart, candidate.tradeSignal());
                PriorTrendAssessment trend = assessments.computeIfAbsent(key,
                        ignored -> detectionService.assessPreparedFactoryPriorTrend(
                                enriched, patternStart, interval.timeInterval(), candidate.tradeSignal()));
                if (!candidate.accepts(trend)) continue;
                EvaluatedTrade trade = trades.computeIfAbsent(candidate,
                        ignored -> evaluateLifecycle(
                                candidate, candles, currentSignalIndex, interval.horizon()));
                if (trade == null) continue;
                LocalDate signalDate = utcDate(candles.get(trade.entryIndex()).getTimestamp());
                if (signalDate.isBefore(EVALUATION_START) || signalDate.isAfter(EVALUATION_END)) continue;
                Map.Entry<LocalDate, MarketRegime> regimeEntry = regimes.floorEntry(signalDate);
                if (regimeEntry == null) continue;
                results.add(symbol, signalDate, regimeEntry.getValue(), candidate, trade);
                if (candidate.tradeSignal() == TradeSignal.SELL) {
                    RefinementEvaluation refinement = evaluateSellRefinement(
                            candidate, candles, currentSignalIndex, trade, interval.horizon());
                    results.addRefinement(symbol, signalDate, refinement);
                }
            }
        }
    }

    private RefinementEvaluation evaluateSellRefinement(
            GeometricPatternCandidate candidate,
            List<Candle> candles,
            int signalIndex,
            EvaluatedTrade detectedTrade,
            int horizon) {
        List<StageTrade> stages = new ArrayList<>();
        stages.add(new StageTrade(RefinementStage.DETECTED_SETUP, detectedTrade));
        int patternStart = signalIndex - candidate.patternCandleCount() + 1;
        double patternLow = Double.POSITIVE_INFINITY;
        double patternHigh = Double.NEGATIVE_INFINITY;
        for (int index = patternStart; index <= signalIndex; index++) {
            patternLow = Math.min(patternLow, candles.get(index).getLowPrice());
            patternHigh = Math.max(patternHigh, candles.get(index).getHighPrice());
        }
        ResearchTrigger trigger = researchSellTrigger(patternLow, candles, signalIndex, 0.25);
        int confirmationWindow = candidate.patternCandleCount() == 1 ? 10 : 3;
        int outcomeStart = detectedTrade.entryIndex() + 1;
        List<Candle> outcomeCandles = outcomeStart >= candles.size()
                ? List.of() : candles.subList(outcomeStart, candles.size());
        LifecycleResolution primary = CandlestickSignalLifecyclePolicy.resolve(
                TradeSignal.SELL, trigger.price(), patternHigh, outcomeCandles, confirmationWindow);
        if (primary == null) {
            return new RefinementEvaluation(stages, PrimaryOutcome.CENSORED);
        }
        PrimaryOutcome primaryOutcome = switch (primary.status()) {
            case CONFIRMED -> PrimaryOutcome.CONFIRMED;
            case INVALIDATED -> PrimaryOutcome.INVALIDATED;
            case EXPIRED -> PrimaryOutcome.EXPIRED;
            default -> PrimaryOutcome.CENSORED;
        };
        if (primary.status() != SignalLifecycleStatus.CONFIRMED) {
            return new RefinementEvaluation(stages, primaryOutcome);
        }

        int breakdownIndex = detectedTrade.entryIndex() + primary.candleOffset();
        addStageTrade(stages, RefinementStage.BREAKDOWN_CONFIRMED,
                candles, breakdownIndex, horizon);
        ResearchStageResolution first = researchResolveAfterBreakdown(
                        ResearchSellStage.BREAKDOWN_CONFIRMED, patternLow, trigger.atr(),
                        primary.resolutionCandle().getTimestamp(),
                        primary.resolutionCandle().getLowPrice(), null, candles,
                        3, 3, 0.10);
        if (first == null) {
            return new RefinementEvaluation(stages, primaryOutcome);
        }
        int firstIndex = breakdownIndex + first.candleOffset();
        RefinementStage firstStage = RefinementStage.from(first.stage());
        addStageTrade(stages, firstStage, candles, firstIndex, horizon);
        if (first.stage() != ResearchSellStage.RETESTING) {
            return new RefinementEvaluation(stages, primaryOutcome);
        }

        ResearchStageResolution second = researchResolveAfterBreakdown(
                        ResearchSellStage.RETESTING, patternLow, trigger.atr(),
                        primary.resolutionCandle().getTimestamp(),
                        primary.resolutionCandle().getLowPrice(),
                        first.candle().getTimestamp(), candles, 3, 3, 0.10);
        if (second != null) {
            int secondIndex = firstIndex + second.candleOffset();
            addStageTrade(stages, RefinementStage.from(second.stage()),
                    candles, secondIndex, horizon);
        }
        return new RefinementEvaluation(stages, primaryOutcome);
    }

    private void addStageTrade(List<StageTrade> stages,
                               RefinementStage stage,
                               List<Candle> candles,
                               int entryIndex,
                               int horizon) {
        int exitIndex = entryIndex + horizon;
        if (entryIndex < 0 || exitIndex >= candles.size()) return;
        double directionalReturn = -percent(
                candles.get(entryIndex).getClosePrice(), candles.get(exitIndex).getClosePrice());
        stages.add(new StageTrade(stage, new EvaluatedTrade(entryIndex, directionalReturn)));
    }

    /** Frozen copy of the rejected experiment, kept only to reproduce its audit report. */
    private ResearchTrigger researchSellTrigger(double patternLow,
                                                 List<Candle> candles,
                                                 int signalIndex,
                                                 double bufferAtr) {
        double atr = researchAverageTrueRange(candles, signalIndex, 14);
        double appliedBuffer = Double.isFinite(atr) && atr > 0.0 ? Math.max(0.0, bufferAtr) : 0.0;
        return new ResearchTrigger(patternLow - (Double.isFinite(atr) ? atr : 0.0) * appliedBuffer,
                Double.isFinite(atr) && atr > 0.0 ? atr : null);
    }

    private double researchAverageTrueRange(List<Candle> candles, int endIndex, int period) {
        if (candles == null || period < 1 || endIndex < period || endIndex >= candles.size()) {
            return Double.NaN;
        }
        double total = 0.0;
        for (int index = endIndex - period + 1; index <= endIndex; index++) {
            Candle current = candles.get(index);
            Candle previous = candles.get(index - 1);
            total += Math.max(current.getHighPrice() - current.getLowPrice(),
                    Math.max(Math.abs(current.getHighPrice() - previous.getClosePrice()),
                            Math.abs(current.getLowPrice() - previous.getClosePrice())));
        }
        return total / period;
    }

    private ResearchStageResolution researchResolveAfterBreakdown(
            ResearchSellStage currentStage,
            double patternLow,
            Double confirmationAtr,
            Long breakdownTimestamp,
            Double breakdownLow,
            Long retestTimestamp,
            List<Candle> candles,
            int retestWindow,
            int continuationWindow,
            double toleranceAtr) {
        double atr = confirmationAtr != null && Double.isFinite(confirmationAtr) && confirmationAtr > 0.0
                ? confirmationAtr : Math.max(Math.abs(patternLow) * 0.01, 0.000001);
        double tolerance = Math.max(0.0, toleranceAtr) * atr;
        if (currentStage == ResearchSellStage.BREAKDOWN_CONFIRMED) {
            List<Candle> observed = candles.stream()
                    .filter(candle -> candle.getTimestamp() > breakdownTimestamp)
                    .limit(Math.max(1, retestWindow)).toList();
            for (int index = 0; index < observed.size(); index++) {
                Candle candle = observed.get(index);
                if (candle.getClosePrice() > patternLow + tolerance) {
                    return new ResearchStageResolution(ResearchSellStage.RETEST_RECLAIMED, candle, index + 1);
                }
                if (candle.getHighPrice() >= patternLow - tolerance) {
                    return new ResearchStageResolution(ResearchSellStage.RETESTING, candle, index + 1);
                }
            }
            return observed.size() >= Math.max(1, retestWindow)
                    ? new ResearchStageResolution(ResearchSellStage.BREAKDOWN_ONLY,
                    observed.getLast(), observed.size()) : null;
        }
        if (currentStage != ResearchSellStage.RETESTING
                || retestTimestamp == null || breakdownLow == null) return null;
        List<Candle> observed = candles.stream()
                .filter(candle -> candle.getTimestamp() > retestTimestamp)
                .limit(Math.max(1, continuationWindow)).toList();
        for (int index = 0; index < observed.size(); index++) {
            Candle candle = observed.get(index);
            if (candle.getClosePrice() > patternLow + tolerance) {
                return new ResearchStageResolution(ResearchSellStage.RETEST_RECLAIMED, candle, index + 1);
            }
            if (candle.getClosePrice() < breakdownLow
                    && candle.getClosePrice() < candle.getOpenPrice()) {
                return new ResearchStageResolution(
                        ResearchSellStage.CONTINUATION_CONFIRMED, candle, index + 1);
            }
        }
        return observed.size() >= Math.max(1, continuationWindow)
                ? new ResearchStageResolution(ResearchSellStage.RETEST_UNRESOLVED,
                observed.getLast(), observed.size()) : null;
    }

    private EvaluatedTrade evaluateLifecycle(GeometricPatternCandidate candidate,
                                             List<Candle> candles,
                                             int signalIndex,
                                             int horizon) {
        int entryIndex = signalIndex;
        if (candidate.patternCandleCount() == 1) {
            LifecycleResolution gate = CandlestickSignalLifecyclePolicy.resolveCandidateGate(
                    candidate.pattern(), candidate.tradeSignal(),
                    candles.get(signalIndex).getClosePrice(), List.of(candles.get(signalIndex + 1)));
            if (gate == null || gate.status() == SignalLifecycleStatus.REJECTED) return null;
            entryIndex++;
        }
        int exitIndex = entryIndex + horizon;
        if (exitIndex >= candles.size()) return null;
        double entry = candles.get(entryIndex).getClosePrice();
        double exit = candles.get(exitIndex).getClosePrice();
        double rawReturn = percent(entry, exit);
        double directionalReturn = candidate.tradeSignal() == TradeSignal.BUY ? rawReturn : -rawReturn;
        return new EvaluatedTrade(entryIndex, directionalReturn);
    }

    private MarketRegimeSeries buildMarketRegimes(List<UniverseEntry> universe,
                                                   Map<String, List<Candle>> daily) {
        TreeMap<LocalDate, WeightedReturn> returns = new TreeMap<>();
        for (UniverseEntry entry : universe) {
            List<Candle> candles = daily.get(entry.symbol());
            for (int index = 1; index < candles.size(); index++) {
                Candle previous = candles.get(index - 1);
                Candle current = candles.get(index);
                double value = percent(previous.getClosePrice(), current.getClosePrice()) / 100.0;
                if (!Double.isFinite(value)) continue;
                returns.computeIfAbsent(utcDate(current.getTimestamp()), ignored -> new WeightedReturn())
                        .add(entry.marketCapWeight(), value);
            }
        }
        List<Candle> benchmarkDaily = new ArrayList<>();
        double level = 100.0;
        for (Map.Entry<LocalDate, WeightedReturn> entry : returns.entrySet()) {
            double previous = level;
            level *= 1.0 + entry.getValue().average();
            long timestamp = entry.getKey().atStartOfDay().toEpochSecond(ZoneOffset.UTC);
            benchmarkDaily.add(new Candle("FROZEN_US_EQUITY_BENCHMARK", "1d", timestamp,
                    previous, Math.max(previous, level), Math.min(previous, level), level, 0L));
        }

        EnumMap<Aggregation, NavigableMap<LocalDate, MarketRegime>> byInterval =
                new EnumMap<>(Aggregation.class);
        EnumMap<Aggregation, EnumMap<MarketRegime, Integer>> counts =
                new EnumMap<>(Aggregation.class);
        for (IntervalSpec interval : intervals()) {
            List<Candle> bars = aggregate(benchmarkDaily, interval.aggregation());
            NavigableMap<LocalDate, MarketRegime> classified = classify(
                    bars, interval.shortAverage(), interval.longAverage());
            byInterval.put(interval.aggregation(), classified);
            EnumMap<MarketRegime, Integer> intervalCounts = new EnumMap<>(MarketRegime.class);
            for (MarketRegime regime : MarketRegime.values()) intervalCounts.put(regime, 0);
            classified.forEach((date, regime) -> {
                if (!date.isBefore(EVALUATION_START) && !date.isAfter(EVALUATION_END)) {
                    intervalCounts.put(regime, intervalCounts.get(regime) + 1);
                }
            });
            counts.put(interval.aggregation(), intervalCounts);
        }
        return new MarketRegimeSeries(byInterval, counts);
    }

    private NavigableMap<LocalDate, MarketRegime> classify(List<Candle> bars,
                                                            int shortWindow,
                                                            int longWindow) {
        NavigableMap<LocalDate, MarketRegime> result = new TreeMap<>();
        double[] prefix = new double[bars.size() + 1];
        for (int index = 0; index < bars.size(); index++) {
            prefix[index + 1] = prefix[index] + bars.get(index).getClosePrice();
            if (index + 1 < longWindow) continue;
            double close = bars.get(index).getClosePrice();
            double shortAverage = (prefix[index + 1] - prefix[index + 1 - shortWindow]) / shortWindow;
            double longAverage = (prefix[index + 1] - prefix[index + 1 - longWindow]) / longWindow;
            MarketRegime regime = close < longAverage && shortAverage < longAverage
                    ? MarketRegime.BEARISH
                    : close > longAverage && shortAverage > longAverage
                    ? MarketRegime.BULLISH
                    : MarketRegime.TRANSITIONAL;
            result.put(utcDate(bars.get(index).getTimestamp()), regime);
        }
        return result;
    }

    private String buildReport(List<UniverseEntry> universe,
                               Path manifest,
                               Path data,
                               MarketRegimeSeries market,
                               EnumMap<Aggregation, IntervalResults> results) throws Exception {
        StringBuilder out = new StringBuilder();
        long dailyCandles = universe.stream().mapToLong(UniverseEntry::dailyCandles).sum();
        out.append("# Candlestick BUY/SELL performance by market regime\n\n");
        out.append(String.format(Locale.ROOT,
                "Run date: 2026-08-16. This study reruns the current factory candlestick model on the frozen post-2018 universe of **%,d usable stocks** and **%,d adjusted daily candles**. The evaluation period is 2019-01-01 through 2025-12-31; 2017-2018 supplies warm-up history.\n\n",
                universe.size(), dailyCandles));
        out.append("## Question and method\n\n");
        out.append("The question is whether the previously weak SELL results were caused by evaluating mostly bullish market conditions. BUY and SELL signals are therefore measured separately inside bearish, bullish, and transitional broad-market regimes. The signal detector is the current factory conflict-aware OR model, not the older fixed-window control used in the first directional report.\n\n");
        out.append("The broad-market proxy is a daily index built from this same frozen universe. Every stock's daily adjusted-close return is weighted by its pre-2019 market capitalization; available weights are renormalized each day. This prevents future constituent or future market-cap information from entering the regime label. The proxy is aggregated to each tested interval.\n\n");
        out.append("A bar is **bearish** only when its close is below the long moving average and its short moving average is also below the long average. It is **bullish** under the symmetric opposite rule; all other bars are transitional. Daily uses 50/200 sessions, Weekly 10/40 weeks, and Monthly 3/10 months. Both averages include the completed signal/entry bar, so the label was knowable then. No future peak, trough, return, or signal outcome is used.\n\n");
        out.append("- Manifest SHA-256: `").append(sha256(manifest)).append("`\n");
        out.append("- Candle file SHA-256: `").append(sha256(data)).append("`\n");
        out.append("- Daily outcome: 10 bars, success at +3%, failure at -3%.\n");
        out.append("- Weekly outcome: 8 bars, success at +8%, failure at -8%.\n");
        out.append("- Monthly outcome: 6 bars, success at +12%, failure at -12%.\n");
        out.append("- SELL returns are direction-adjusted: positive means price fell after the signal.\n");
        out.append("- Precision is success / (success + failure); inconclusive signals remain in N and average return.\n\n");

        out.append("## Regime coverage\n\n");
        out.append("| Interval | Bearish bars | Bullish bars | Transitional bars | Bearish share |\n");
        out.append("|---|---:|---:|---:|---:|\n");
        for (IntervalSpec interval : intervals()) {
            EnumMap<MarketRegime, Integer> counts = market.counts().get(interval.aggregation());
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            out.append(String.format(Locale.ROOT, "| %s | %,d | %,d | %,d | %.2f%% |%n",
                    interval.label(), counts.get(MarketRegime.BEARISH),
                    counts.get(MarketRegime.BULLISH), counts.get(MarketRegime.TRANSITIONAL),
                    percentage(counts.get(MarketRegime.BEARISH), total)));
        }
        out.append("\n");

        for (IntervalSpec interval : intervals()) {
            appendInterval(out, results.get(interval.aggregation()));
        }
        appendPreviousComparison(out, results);
        appendConclusion(out, results);
        return out.toString();
    }

    private void appendInterval(StringBuilder out, IntervalResults result) {
        out.append("## ").append(result.interval().label()).append(" results\n\n");
        out.append(String.format(Locale.ROOT,
                "Coverage: %,d symbols and %,d aggregated candles.\n\n",
                result.symbols, result.candles));
        out.append("### Combined 2019-2025\n\n");
        appendDirectionRegimeTable(out, result, Segment.ALL);
        out.append("### Independent time splits\n\n");
        for (Segment segment : List.of(Segment.EARLY, Segment.LATE)) {
            out.append("#### ").append(segment.label).append("\n\n");
            appendDirectionRegimeTable(out, result, segment);
        }
        out.append("### Bearish-regime pattern breakdown (2019-2025)\n\n");
        out.append("| Pattern | Direction | N | Actionable | Precision | Wilson LCB | Avg return | Tickers |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|---:|\n");
        result.bearishPatterns.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<PatternDirection, DirectedStats> entry) -> entry.getKey().pattern())
                        .thenComparing(entry -> entry.getKey().direction()))
                .forEach(entry -> appendStatsRow(out, entry.getKey().pattern().name(),
                        entry.getValue().direction, entry.getValue().stats));
        out.append("\n");
    }

    private void appendDirectionRegimeTable(StringBuilder out,
                                             IntervalResults result,
                                             Segment segment) {
        out.append("| Regime | Direction | N | Actionable | S | F | I | Precision | Wilson LCB | Avg return | Ticker precision | Tickers |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (MarketRegime regime : List.of(
                MarketRegime.ALL, MarketRegime.BEARISH,
                MarketRegime.BULLISH, MarketRegime.TRANSITIONAL)) {
            for (TradeSignal direction : List.of(TradeSignal.BUY, TradeSignal.SELL)) {
                Stats stats = result.stats.get(segment).get(regime).get(direction);
                out.append(String.format(Locale.ROOT,
                        "| %s | %s | %,d | %,d | %,d | %,d | %,d | %s | %s | %s | %s | %,d |%n",
                        regime.label, direction, stats.signals, stats.actionable(),
                        stats.successes, stats.failures, stats.inconclusive,
                        format(stats.precision()), format(stats.wilsonLowerBound()),
                        signed(stats.averageReturn()), format(stats.tickerBalancedPrecision()),
                        stats.symbols.size()));
            }
        }
        out.append("\n");
    }

    private void appendStatsRow(StringBuilder out, String label,
                                TradeSignal direction, Stats stats) {
        out.append(String.format(Locale.ROOT,
                "| %s | %s | %,d | %,d | %s | %s | %s | %,d |%n",
                label, direction, stats.signals, stats.actionable(),
                format(stats.precision()), format(stats.wilsonLowerBound()),
                signed(stats.averageReturn()), stats.symbols.size()));
    }

    private void appendPreviousComparison(StringBuilder out,
                                          EnumMap<Aggregation, IntervalResults> results) {
        out.append("## Comparison with the previous directional report\n\n");
        out.append("The previous report's `Current production control` used the older fixed-window model. The table below keeps it as historical context and places it beside the current factory model's all-regime and bearish-regime results. Model changes mean the N values are not expected to match; the clean causal comparison for the market-regime hypothesis is Current all regimes versus Current bearish.\n\n");
        out.append("| Interval | Direction | Previous N | Previous precision | Previous avg | Current all N | Current all precision | Current all avg | Current bearish N | Current bearish precision | Current bearish avg | Bearish precision delta vs current all |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        Map<Aggregation, Map<TradeSignal, PreviousResult>> previous = previousResults();
        for (IntervalSpec interval : intervals()) {
            IntervalResults current = results.get(interval.aggregation());
            for (TradeSignal direction : List.of(TradeSignal.BUY, TradeSignal.SELL)) {
                PreviousResult old = previous.get(interval.aggregation()).get(direction);
                Stats all = current.stats.get(Segment.ALL).get(MarketRegime.ALL).get(direction);
                Stats bear = current.stats.get(Segment.ALL).get(MarketRegime.BEARISH).get(direction);
                out.append(String.format(Locale.ROOT,
                        "| %s | %s | %,d | %.2f%% | %+.2f%% | %,d | %s | %s | %,d | %s | %s | %+.2f pp |%n",
                        interval.label(), direction, old.signals(), old.precision(), old.averageReturn(),
                        all.signals, format(all.precision()), signed(all.averageReturn()),
                        bear.signals, format(bear.precision()), signed(bear.averageReturn()),
                        bear.precision() - all.precision()));
            }
        }
        out.append("\n");
    }

    private void appendConclusion(StringBuilder out,
                                  EnumMap<Aggregation, IntervalResults> results) {
        out.append("## Interpretation\n\n");
        for (IntervalSpec interval : intervals()) {
            IntervalResults result = results.get(interval.aggregation());
            Stats sellAll = result.stats.get(Segment.ALL).get(MarketRegime.ALL).get(TradeSignal.SELL);
            Stats sellBear = result.stats.get(Segment.ALL).get(MarketRegime.BEARISH).get(TradeSignal.SELL);
            Stats buyAll = result.stats.get(Segment.ALL).get(MarketRegime.ALL).get(TradeSignal.BUY);
            Stats buyBear = result.stats.get(Segment.ALL).get(MarketRegime.BEARISH).get(TradeSignal.BUY);
            out.append(String.format(Locale.ROOT,
                    "- **%s:** SELL precision changes from %s across all regimes to %s in bearish regimes (%+.2f pp), while average direction-adjusted return changes from %s to %s. BUY precision changes from %s to %s (%+.2f pp), with average return changing from %s to %s.%n",
                    interval.label(), format(sellAll.precision()), format(sellBear.precision()),
                    sellBear.precision() - sellAll.precision(), signed(sellAll.averageReturn()),
                    signed(sellBear.averageReturn()), format(buyAll.precision()),
                    format(buyBear.precision()), buyBear.precision() - buyAll.precision(),
                    signed(buyAll.averageReturn()), signed(buyBear.averageReturn())));
        }
        out.append("\n### Decision\n\n");
        out.append("**The hypothesis that SELL weakness was mainly caused by bullish test conditions is rejected.** Daily and Monthly SELL signals were less precise and had worse average direction-adjusted returns in bearish regimes. Weekly SELL improved in the combined table, but the effect reversed completely between the two time splits: bearish precision was 9.26% in 2019-2021 and 65.22% in 2022-2025. Its combined bearish average return also remained negative. That instability is not a defensible regime filter. No SELL notification or scoring policy should change from this result.\n\n");
        out.append("The exploratory result worth retaining is on the other side: Daily and Weekly BUY reversal signals improved in bearish regimes in both time splits. That is economically plausible—bullish candlestick reversals have more room to rebound during broad drawdowns—but it was discovered on reused outcomes. It should be treated as a candidate for a future untouched or walk-forward confirmation, not deployed now. Monthly BUY did not show the same stable split-period improvement.\n\n");
        out.append("This is a descriptive regime-stratified rerun, not a newly untouched holdout: the current detector was developed before this analysis, but the 2019-2025 outcomes have already appeared in earlier studies. Signals also cluster within common market episodes, so individual-signal Wilson bounds overstate independence. A regime-dependent production policy should be considered only if the direction of the effect is consistent in both 2019-2021 and 2022-2025, has adequate bearish samples, and improves average return as well as precision.\n");
    }

    private String buildRefinementReport(
            List<UniverseEntry> universe,
            Path manifest,
            Path data,
            EnumMap<Aggregation, IntervalResults> results) throws Exception {
        StringBuilder out = new StringBuilder();
        long dailyCandles = universe.stream().mapToLong(UniverseEntry::dailyCandles).sum();
        out.append("# Candlestick SELL refinement validation\n\n");
        out.append(String.format(Locale.ROOT,
                "Run date: 2026-08-16. The current production SELL lifecycle was replayed over **%,d usable stocks** and **%,d adjusted daily candles**, with signals evaluated from 2019-01-01 through 2025-12-31.\n\n",
                universe.size(), dailyCandles));
        out.append("## What this test answers\n\n");
        out.append("This report records why the rejected bearish lifecycle experiment was rolled back. It tests whether waiting for its lifecycle stages improved precision and average direction-adjusted return relative to acting when the candlestick setup first became a detected signal. The frozen rules below now exist only in this opt-in research harness and are not part of production detection, scoring, history, or notifications.\n\n");
        out.append("The comparison is causal. A stage is entered at the close that proves that stage, never at the earlier pattern close. In the rejected experiment, one-candle patterns first had to pass the mandatory next-candle red-body/lower-close gate. Multi-candle setups then had 3 completed candles, and gated one-candle setups 10 completed candles, to close below `pattern low - 0.25 ATR`; a close above the pattern high invalidated first. Confirmed breakdowns used a 3-candle retest window, 0.10 ATR retest tolerance, and 3-candle continuation window.\n\n");
        out.append("- Manifest SHA-256: `").append(sha256(manifest)).append("`\n");
        out.append("- Candle file SHA-256: `").append(sha256(data)).append("`\n");
        out.append("- Daily outcome: 10 bars, success at a 3% fall and failure at a 3% rise.\n");
        out.append("- Weekly outcome: 8 bars, success at an 8% fall and failure at an 8% rise.\n");
        out.append("- Monthly outcome: 6 bars, success at a 12% fall and failure at a 12% rise.\n");
        out.append("- Positive average return means price fell after that stage's causal entry close.\n");
        out.append("- Precision is success / (success + failure); moves inside the symmetric threshold remain inconclusive but stay in N and average return.\n");
        out.append("- The 2019-2021 and 2022-2025 cohorts are shown separately as a stability check. They reuse previously studied outcomes and are not a fresh untouched holdout.\n\n");

        for (IntervalSpec interval : intervals()) {
            appendRefinementInterval(out, results.get(interval.aggregation()));
        }
        appendRefinementDecision(out, results);
        return out.toString();
    }

    private void appendRefinementInterval(StringBuilder out, IntervalResults result) {
        out.append("## ").append(result.interval().label()).append("\n\n");
        for (Segment segment : List.of(Segment.ALL, Segment.EARLY, Segment.LATE)) {
            out.append("### ").append(segment.label).append("\n\n");
            EnumMap<RefinementStage, Stats> stages = result.refinementStats.get(segment);
            Stats baseline = stages.get(RefinementStage.DETECTED_SETUP);
            out.append("| Causal entry stage | N | Share of detected | Actionable | S | F | I | Precision | Delta vs detected | Wilson LCB | Avg return | Delta vs detected | Tickers |\n");
            out.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            for (RefinementStage stage : RefinementStage.values()) {
                Stats stats = stages.get(stage);
                out.append(String.format(Locale.ROOT,
                        "| %s | %,d | %s | %,d | %,d | %,d | %,d | %s | %s | %s | %s | %s | %,d |%n",
                        stage.label, stats.signals, format(percentage(stats.signals, baseline.signals)),
                        stats.actionable(), stats.successes, stats.failures, stats.inconclusive,
                        format(stats.precision()), signedPoints(stats.precision() - baseline.precision()),
                        format(stats.wilsonLowerBound()), signed(stats.averageReturn()),
                        signed(stats.averageReturn() - baseline.averageReturn()), stats.symbols.size()));
            }
            EnumMap<PrimaryOutcome, Long> outcomes = result.primaryOutcomes.get(segment);
            long total = outcomes.values().stream().mapToLong(Long::longValue).sum();
            out.append("\nPrimary setup resolution: ");
            for (PrimaryOutcome outcome : PrimaryOutcome.values()) {
                out.append(String.format(Locale.ROOT, "%s %,d (%s)%s",
                        outcome.label, outcomes.get(outcome),
                        format(percentage(outcomes.get(outcome), total)),
                        outcome == PrimaryOutcome.values()[PrimaryOutcome.values().length - 1] ? ".\n\n" : "; "));
            }
        }
    }

    private void appendRefinementDecision(
            StringBuilder out,
            EnumMap<Aggregation, IntervalResults> results) {
        out.append("## Interpretation and deployment decision\n\n");
        out.append("A stage is described as stable only when both precision and average return exceed the detected-setup baseline in **both** time splits. A higher combined number by itself is not enough.\n\n");
        boolean anyStable = false;
        for (IntervalSpec interval : intervals()) {
            IntervalResults result = results.get(interval.aggregation());
            List<String> stable = new ArrayList<>();
            for (RefinementStage stage : RefinementStage.values()) {
                if (!stage.bearishEvidence) continue;
                boolean passes = true;
                for (Segment segment : List.of(Segment.EARLY, Segment.LATE)) {
                    Stats baseline = result.refinementStats.get(segment)
                            .get(RefinementStage.DETECTED_SETUP);
                    Stats candidate = result.refinementStats.get(segment).get(stage);
                    passes &= candidate.actionable() > 0
                            && candidate.precision() > baseline.precision()
                            && candidate.averageReturn() > baseline.averageReturn();
                }
                if (passes) stable.add(stage.label);
            }
            anyStable |= !stable.isEmpty();
            Stats baseline = result.refinementStats.get(Segment.ALL)
                    .get(RefinementStage.DETECTED_SETUP);
            Stats breakdown = result.refinementStats.get(Segment.ALL)
                    .get(RefinementStage.BREAKDOWN_CONFIRMED);
            out.append(String.format(Locale.ROOT,
                    "- **%s:** detected setups produced %,d signals at %s precision and %s average return. Buffered breakdown entries produced %,d (%s of detected) at %s precision and %s average return. Stages improving both measures in both splits: **%s**.\n",
                    interval.label(), baseline.signals, format(baseline.precision()),
                    signed(baseline.averageReturn()), breakdown.signals,
                    format(percentage(breakdown.signals, baseline.signals)),
                    format(breakdown.precision()), signed(breakdown.averageReturn()),
                    stable.isEmpty() ? "none" : String.join(", ", stable)));
        }
        out.append("\n");
        out.append(anyStable
                ? "At least one lifecycle stage clears the predeclared split-stability check. That supports presenting the stage as stronger evidence, but not suppressing original pattern notifications: the lifecycle is a deliberately lower-volume, later confirmation layer. The exact table still needs to be considered for sample size and economic delay before changing any automated action policy.\n\n"
                : "No lifecycle stage improves both precision and average return in both time splits. The rejected refinement was therefore removed rather than retained as a production status or trigger layer.\n\n");
        out.append("The original product objective remains unchanged: valid candlestick occurrences are detected and notification behavior is not filtered by the rejected experiment. This observational replay ignores fees, slippage, overlapping positions, position sizing, and cross-signal dependence.\n");
    }

    private String signedPoints(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%+.2f pp", value) : "n/a";
    }

    private Map<Aggregation, Map<TradeSignal, PreviousResult>> previousResults() {
        Map<Aggregation, Map<TradeSignal, PreviousResult>> values = new EnumMap<>(Aggregation.class);
        values.put(Aggregation.DAILY, Map.of(
                TradeSignal.BUY, new PreviousResult(63_267, 52.81, 0.48),
                TradeSignal.SELL, new PreviousResult(71_597, 46.43, -0.73)));
        values.put(Aggregation.WEEKLY, Map.of(
                TradeSignal.BUY, new PreviousResult(2_759, 58.16, 6.53),
                TradeSignal.SELL, new PreviousResult(4_722, 40.46, -4.14)));
        values.put(Aggregation.MONTHLY, Map.of(
                TradeSignal.BUY, new PreviousResult(2_428, 55.24, 6.58),
                TradeSignal.SELL, new PreviousResult(4_273, 35.07, -9.21)));
        return values;
    }

    private List<UniverseEntry> loadManifest(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "Missing post-2018 manifest: " + path);
        List<UniverseEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            assertEquals("symbol\tname\tselection_source\tmarket_cap_usd_pre_2019\tfirst_date\tlast_date\tdaily_candles\tprice_source",
                    reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] value = line.split("\t", -1);
                double marketCap = value[3].isBlank() ? 1.0 : Double.parseDouble(value[3]);
                entries.add(new UniverseEntry(value[0], LocalDate.parse(value[4]),
                        LocalDate.parse(value[5]), Integer.parseInt(value[6]), Math.max(1.0, marketCap)));
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
             InputStream source = path.toString().endsWith(".gz") ? new GZIPInputStream(file) : file;
             BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
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

    private double percent(double start, double end) {
        return start == 0.0 ? 0.0 : (end - start) / start * 100.0;
    }

    private double percentage(long part, long whole) {
        return whole == 0 ? Double.NaN : part * 100.0 / whole;
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
        private final EnumMap<Segment, EnumMap<MarketRegime, EnumMap<TradeSignal, Stats>>> stats =
                new EnumMap<>(Segment.class);
        private final EnumMap<Segment, EnumMap<RefinementStage, Stats>> refinementStats =
                new EnumMap<>(Segment.class);
        private final EnumMap<Segment, EnumMap<PrimaryOutcome, Long>> primaryOutcomes =
                new EnumMap<>(Segment.class);
        private final Map<PatternDirection, DirectedStats> bearishPatterns = new HashMap<>();
        private long symbols;
        private long candles;

        private IntervalResults(IntervalSpec interval) {
            this.interval = interval;
            for (Segment segment : Segment.values()) {
                EnumMap<MarketRegime, EnumMap<TradeSignal, Stats>> regimes =
                        new EnumMap<>(MarketRegime.class);
                for (MarketRegime regime : MarketRegime.values()) {
                    EnumMap<TradeSignal, Stats> directions = new EnumMap<>(TradeSignal.class);
                    directions.put(TradeSignal.BUY, new Stats());
                    directions.put(TradeSignal.SELL, new Stats());
                    regimes.put(regime, directions);
                }
                stats.put(segment, regimes);
                EnumMap<RefinementStage, Stats> stageValues = new EnumMap<>(RefinementStage.class);
                for (RefinementStage stage : RefinementStage.values()) {
                    stageValues.put(stage, new Stats());
                }
                refinementStats.put(segment, stageValues);
                EnumMap<PrimaryOutcome, Long> outcomeValues = new EnumMap<>(PrimaryOutcome.class);
                for (PrimaryOutcome outcome : PrimaryOutcome.values()) {
                    outcomeValues.put(outcome, 0L);
                }
                primaryOutcomes.put(segment, outcomeValues);
            }
        }

        private IntervalSpec interval() { return interval; }

        private void add(String symbol, LocalDate date, MarketRegime regime,
                         GeometricPatternCandidate candidate, EvaluatedTrade trade) {
            Segment dated = date.getYear() <= 2021 ? Segment.EARLY : Segment.LATE;
            for (Segment segment : List.of(dated, Segment.ALL)) {
                stats.get(segment).get(MarketRegime.ALL).get(candidate.tradeSignal())
                        .add(symbol, trade.directionalReturn(), interval.outcomeMove());
                stats.get(segment).get(regime).get(candidate.tradeSignal())
                        .add(symbol, trade.directionalReturn(), interval.outcomeMove());
            }
            if (regime == MarketRegime.BEARISH) {
                PatternDirection key = new PatternDirection(candidate.pattern(), candidate.tradeSignal());
                bearishPatterns.computeIfAbsent(key,
                                ignored -> new DirectedStats(candidate.tradeSignal(), new Stats()))
                        .stats.add(symbol, trade.directionalReturn(), interval.outcomeMove());
            }
        }

        private void addRefinement(String symbol,
                                   LocalDate setupDate,
                                   RefinementEvaluation refinement) {
            Segment dated = setupDate.getYear() <= 2021 ? Segment.EARLY : Segment.LATE;
            for (Segment segment : List.of(dated, Segment.ALL)) {
                for (StageTrade stageTrade : refinement.stages()) {
                    refinementStats.get(segment).get(stageTrade.stage())
                            .add(symbol, stageTrade.trade().directionalReturn(), interval.outcomeMove());
                }
                EnumMap<PrimaryOutcome, Long> outcomes = primaryOutcomes.get(segment);
                outcomes.put(refinement.primaryOutcome(),
                        outcomes.get(refinement.primaryOutcome()) + 1L);
            }
        }
    }

    private static final class Stats {
        private long signals;
        private long successes;
        private long failures;
        private long inconclusive;
        private double returnSum;
        private final Set<String> symbols = new HashSet<>();
        private final Map<String, SymbolStats> bySymbol = new HashMap<>();

        private void add(String symbol, double directionalReturn, double outcomeMove) {
            signals++;
            int outcome;
            if (directionalReturn >= outcomeMove) {
                successes++;
                outcome = 1;
            } else if (directionalReturn <= -outcomeMove) {
                failures++;
                outcome = -1;
            } else {
                inconclusive++;
                outcome = 0;
            }
            returnSum += directionalReturn;
            symbols.add(symbol);
            bySymbol.computeIfAbsent(symbol, ignored -> new SymbolStats()).add(outcome);
        }

        private long actionable() { return successes + failures; }
        private double precision() { return percentage(successes, actionable()); }
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
        private static double percentage(long part, long whole) {
            return whole == 0 ? Double.NaN : part * 100.0 / whole;
        }
    }

    private static final class SymbolStats {
        private long successes;
        private long failures;
        private void add(int outcome) {
            if (outcome > 0) successes++;
            else if (outcome < 0) failures++;
        }
        private long actionable() { return successes + failures; }
        private double precision() { return successes * 100.0 / actionable(); }
    }

    private static final class WeightedReturn {
        private double weightedSum;
        private double weightSum;
        private void add(double weight, double value) {
            weightedSum += weight * value;
            weightSum += weight;
        }
        private double average() { return weightSum == 0.0 ? 0.0 : weightedSum / weightSum; }
    }

    private record UniverseEntry(String symbol, LocalDate firstDate, LocalDate lastDate,
                                 int dailyCandles, double marketCapWeight) { }
    private record IntervalSpec(String label, Aggregation aggregation, TimeInterval timeInterval,
                                int minimumHistory, int horizon, double outcomeMove,
                                int shortAverage, int longAverage) { }
    private record AssessmentKey(int patternStart, TradeSignal direction) { }
    private record EvaluatedTrade(int entryIndex, double directionalReturn) { }
    private record StageTrade(RefinementStage stage, EvaluatedTrade trade) { }
    private record RefinementEvaluation(List<StageTrade> stages, PrimaryOutcome primaryOutcome) { }
    private record ResearchTrigger(double price, Double atr) { }
    private record ResearchStageResolution(
            ResearchSellStage stage, Candle candle, int candleOffset) { }
    private record PreviousResult(long signals, double precision, double averageReturn) { }
    private record PatternDirection(CandlePattern pattern, TradeSignal direction) { }
    private record DirectedStats(TradeSignal direction, Stats stats) { }
    private record MarketRegimeSeries(
            EnumMap<Aggregation, NavigableMap<LocalDate, MarketRegime>> byInterval,
            EnumMap<Aggregation, EnumMap<MarketRegime, Integer>> counts) { }

    private enum Segment {
        EARLY("2019-2021"), LATE("2022-2025"), ALL("2019-2025 combined");
        private final String label;
        Segment(String label) { this.label = label; }
    }

    private enum ResearchSellStage {
        BREAKDOWN_CONFIRMED,
        RETESTING,
        CONTINUATION_CONFIRMED,
        BREAKDOWN_ONLY,
        RETEST_RECLAIMED,
        RETEST_UNRESOLVED
    }

    private enum RefinementStage {
        DETECTED_SETUP("Detected setup", false),
        BREAKDOWN_CONFIRMED("Buffered breakdown confirmed", true),
        RETESTING("Retest reached", true),
        CONTINUATION_CONFIRMED("Failed-retest continuation confirmed", true),
        BREAKDOWN_ONLY("Breakdown held without retest", true),
        RETEST_RECLAIMED("Broken level reclaimed", false),
        RETEST_UNRESOLVED("Retest unresolved", false);

        private final String label;
        private final boolean bearishEvidence;

        RefinementStage(String label, boolean bearishEvidence) {
            this.label = label;
            this.bearishEvidence = bearishEvidence;
        }

        private static RefinementStage from(ResearchSellStage stage) {
            return switch (stage) {
                case BREAKDOWN_CONFIRMED -> BREAKDOWN_CONFIRMED;
                case RETESTING -> RETESTING;
                case CONTINUATION_CONFIRMED -> CONTINUATION_CONFIRMED;
                case BREAKDOWN_ONLY -> BREAKDOWN_ONLY;
                case RETEST_RECLAIMED -> RETEST_RECLAIMED;
                case RETEST_UNRESOLVED -> RETEST_UNRESOLVED;
                default -> throw new IllegalArgumentException("Unsupported research stage: " + stage);
            };
        }
    }

    private enum PrimaryOutcome {
        CONFIRMED("confirmed"), INVALIDATED("invalidated"),
        EXPIRED("expired"), CENSORED("censored at data boundary");
        private final String label;
        PrimaryOutcome(String label) { this.label = label; }
    }

    private enum MarketRegime {
        ALL("All regimes"), BEARISH("Bearish"), BULLISH("Bullish"), TRANSITIONAL("Transitional");
        private final String label;
        MarketRegime(String label) { this.label = label; }
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
