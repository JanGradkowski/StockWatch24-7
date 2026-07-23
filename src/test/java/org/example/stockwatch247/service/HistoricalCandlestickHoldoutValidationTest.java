package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestOutcome;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestReport;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestSettings;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestTrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@EnabledIfSystemProperty(named = "backtest.candlestick.holdout.enabled", matches = "true")
class HistoricalCandlestickHoldoutValidationTest {
    private static final String INTERVAL = "1d";
    private static final int MINIMUM_HISTORY = 250;
    private static final int SIGNAL_CANDLES = 100;
    private static final int FORWARD_CANDLES = 10;
    private static final double MINIMUM_MOVE_PERCENT = 3.0;
    private static final int MAX_CONTROL_DISTANCE_CANDLES = 63;
    private static final int BOOTSTRAP_SAMPLES = 10_000;
    private static final long VALIDATION_START = LocalDate.of(2025, 1, 1)
            .atStartOfDay(ZoneOffset.UTC)
            .toEpochSecond();
    private static final BacktestSettings SETTINGS = new BacktestSettings(
            MINIMUM_HISTORY,
            SIGNAL_CANDLES,
            FORWARD_CANDLES,
            MINIMUM_MOVE_PERCENT
    );
    private static final List<PatternSpec> PATTERNS = List.of(
            new PatternSpec(CandlePattern.BULLISH_ENGULFING, 2),
            new PatternSpec(CandlePattern.INVERTED_HAMMER, 1)
    );
    private static final List<String> REPRESENTATIVE_SYMBOLS = List.of(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "AMD", "ORCL",
            "CRM", "JPM", "BAC", "GS", "V", "MA", "XOM", "CVX", "COP", "JNJ",
            "UNH", "PFE", "LLY", "PG", "KO", "COST", "WMT", "HD", "CAT", "BA"
    );

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private TechnicalIndicatorEnrichmentService enrichmentService;

    @Autowired
    private CandlePatternDetectionService detectionService;

    @Autowired
    private HistoricalSignalBacktestService backtestService;

    @Test
    void comparesFrozenBullishCandidatesWithMatchedDowntrendControls() {
        List<SymbolHistory> histories = REPRESENTATIVE_SYMBOLS.stream()
                .map(this::loadHistory)
                .filter(history -> history.candles().size() >= MINIMUM_HISTORY + FORWARD_CANDLES)
                .toList();

        assertThat(histories).hasSize(REPRESENTATIVE_SYMBOLS.size());

        System.out.println();
        System.out.println("=== Frozen Candlestick Temporal Holdout Validation ===");
        System.out.printf(
                "Settings: signalCandles=%d, forwardCandles=%d, minMove=%.1f%%, validationStart=2025-01-01%n",
                SIGNAL_CANDLES,
                FORWARD_CANDLES,
                MINIMUM_MOVE_PERCENT
        );
        System.out.printf(
                "Controls: same symbol and prior downtrend, no bullish reversal signal, within %d trading candles; trend-score difference minimized before date distance.%n",
                MAX_CONTROL_DISTANCE_CANDLES
        );
        System.out.println("Bootstrap: 10,000 deterministic symbol-cluster resamples; intervals are exploratory 95% percentile intervals.");
        System.out.println();

        for (Segment segment : Segment.values()) {
            List<ValidationResult> segmentResults = new ArrayList<>();
            for (PatternSpec pattern : PATTERNS) {
                ValidationResult result = validate(histories, segment, pattern);
                segmentResults.add(result);
                printResult(result);
            }
            printResult(ValidationResult.combined(segment, segmentResults));
            System.out.println();
        }

        System.out.println("===================================================");
        System.out.println();
    }

    private SymbolHistory loadHistory(String symbol) {
        List<Candle> candles = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, INTERVAL)
                .stream()
                .filter(this::hasCompletePriceData)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        List<EnrichedCandle> enrichedCandles = enrichmentService.enrich(candles, candles.size());
        Map<Long, Integer> indexByTimestamp = new HashMap<>();
        for (int index = 0; index < candles.size(); index++) {
            indexByTimestamp.put(candles.get(index).getTimestamp(), index);
        }
        return new SymbolHistory(
                symbol,
                candles,
                enrichedCandles,
                indexByTimestamp,
                backtestService.backtest(candles, SETTINGS)
        );
    }

    private ValidationResult validate(List<SymbolHistory> histories,
                                      Segment segment,
                                      PatternSpec pattern) {
        List<MatchedPair> pairs = new ArrayList<>();
        int targetSignals = 0;

        for (SymbolHistory history : histories) {
            List<BacktestTrade> targets = history.report().trades().stream()
                    .filter(trade -> trade.pattern() == pattern.pattern())
                    .filter(trade -> trade.tradeSignal() == TradeSignal.BUY)
                    .filter(trade -> segment.contains(trade.signalTimestamp(), trade.exitTimestamp()))
                    .sorted(Comparator.comparing(BacktestTrade::signalTimestamp))
                    .toList();
            targetSignals += targets.size();
            pairs.addAll(matchControls(history, segment, pattern, targets));
        }

        return new ValidationResult(segment, pattern.pattern().name(), targetSignals, List.copyOf(pairs));
    }

    private List<MatchedPair> matchControls(SymbolHistory history,
                                            Segment segment,
                                            PatternSpec pattern,
                                            List<BacktestTrade> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }

        Set<Long> bullishSignalTimestamps = history.report().trades().stream()
                .filter(trade -> trade.tradeSignal() == TradeSignal.BUY)
                .map(BacktestTrade::signalTimestamp)
                .collect(java.util.stream.Collectors.toSet());
        List<ControlCandidate> controls = controlCandidates(
                history,
                segment,
                pattern,
                bullishSignalTimestamps
        );
        Set<Integer> usedControlIndices = new HashSet<>();
        List<MatchedPair> pairs = new ArrayList<>();

        for (BacktestTrade target : targets) {
            Integer signalIndex = history.indexByTimestamp().get(target.signalTimestamp());
            if (signalIndex == null) {
                continue;
            }
            int signalTrendScore = priorTrend(history.enrichedCandles(), signalIndex, pattern.candleCount())
                    .scorePoints();

            ControlCandidate control = controls.stream()
                    .filter(candidate -> !usedControlIndices.contains(candidate.index()))
                    .filter(candidate -> Math.abs(candidate.index() - signalIndex) <= MAX_CONTROL_DISTANCE_CANDLES)
                    .min(Comparator
                            .comparingInt((ControlCandidate candidate) ->
                                    Math.abs(candidate.trendScore() - signalTrendScore))
                            .thenComparingInt(candidate -> Math.abs(candidate.index() - signalIndex))
                            .thenComparingInt(ControlCandidate::index))
                    .orElse(null);
            if (control == null) {
                continue;
            }

            usedControlIndices.add(control.index());
            pairs.add(new MatchedPair(
                    history.symbol(),
                    pattern.pattern(),
                    target.signalTimestamp(),
                    control.timestamp(),
                    signalIndex,
                    control.index(),
                    signalTrendScore,
                    control.trendScore(),
                    target.directionalReturnPercent(),
                    control.directionalReturnPercent(),
                    target.outcome(),
                    control.outcome()
            ));
        }
        return List.copyOf(pairs);
    }

    private List<ControlCandidate> controlCandidates(SymbolHistory history,
                                                     Segment segment,
                                                     PatternSpec pattern,
                                                     Set<Long> bullishSignalTimestamps) {
        List<ControlCandidate> controls = new ArrayList<>();
        int lastSignalIndex = history.candles().size() - FORWARD_CANDLES - 1;
        for (int index = MINIMUM_HISTORY - 1; index <= lastSignalIndex; index++) {
            Candle candle = history.candles().get(index);
            Candle exitCandle = history.candles().get(index + FORWARD_CANDLES);
            if (!segment.contains(candle.getTimestamp(), exitCandle.getTimestamp())
                    || bullishSignalTimestamps.contains(candle.getTimestamp())) {
                continue;
            }

            CandlePatternDetectionService.PriorTrendAssessment trend =
                    priorTrend(history.enrichedCandles(), index, pattern.candleCount());
            if (trend.direction() != CandlePatternDetectionService.TrendDirection.DOWN) {
                continue;
            }

            double directionalReturn = percentMove(candle.getClosePrice(), exitCandle.getClosePrice());
            controls.add(new ControlCandidate(
                    index,
                    candle.getTimestamp(),
                    trend.scorePoints(),
                    directionalReturn,
                    outcome(directionalReturn)
            ));
        }
        return List.copyOf(controls);
    }

    private CandlePatternDetectionService.PriorTrendAssessment priorTrend(List<EnrichedCandle> candles,
                                                                           int signalIndex,
                                                                           int patternCandleCount) {
        int firstIndex = Math.max(0, signalIndex - SIGNAL_CANDLES + 1);
        return detectionService.assessPriorTrendForLatestPattern(
                candles.subList(firstIndex, signalIndex + 1),
                patternCandleCount
        );
    }

    private void printResult(ValidationResult result) {
        OutcomeStats signal = OutcomeStats.signalStats(result.pairs());
        OutcomeStats control = OutcomeStats.controlStats(result.pairs());
        BootstrapIntervals intervals = bootstrapIntervals(result.pairs(), result.seed());
        double averageTrendScoreGap = result.pairs().stream()
                .mapToInt(pair -> Math.abs(pair.signalTrendScore() - pair.controlTrendScore()))
                .average()
                .orElse(0.0);
        double averageDistance = result.pairs().stream()
                .mapToInt(pair -> Math.abs(pair.signalIndex() - pair.controlIndex()))
                .average()
                .orElse(0.0);

        System.out.printf(
                "%s %-21s signals=%3d matched=%3d | signal s/f/i=%3d/%3d/%3d precision=%6.2f%% avgReturn=%7.2f%% | control precision=%6.2f%% avgReturn=%7.2f%% | uplift precision=%+6.2fpp CI95=[%+6.2f,%+6.2f] return=%+7.2fpp CI95=[%+6.2f,%+6.2f] | match scoreGap=%.2f distance=%.1f%n",
                result.segment().label(),
                result.label(),
                result.targetSignals(),
                result.pairs().size(),
                signal.successful(),
                signal.failed(),
                signal.inconclusive(),
                signal.precisionPercent(),
                signal.averageReturnPercent(),
                control.precisionPercent(),
                control.averageReturnPercent(),
                signal.precisionPercent() - control.precisionPercent(),
                intervals.precisionUplift().lower(),
                intervals.precisionUplift().upper(),
                signal.averageReturnPercent() - control.averageReturnPercent(),
                intervals.returnUplift().lower(),
                intervals.returnUplift().upper(),
                averageTrendScoreGap,
                averageDistance
        );
    }

    private BootstrapIntervals bootstrapIntervals(List<MatchedPair> pairs, long seed) {
        if (pairs.size() < 2) {
            return BootstrapIntervals.empty();
        }

        Map<String, List<MatchedPair>> pairsBySymbol = new LinkedHashMap<>();
        for (MatchedPair pair : pairs) {
            pairsBySymbol.computeIfAbsent(pair.symbol(), ignored -> new ArrayList<>()).add(pair);
        }
        List<List<MatchedPair>> symbolClusters = List.copyOf(pairsBySymbol.values());
        Random random = new Random(seed);
        List<Double> returnUplifts = new ArrayList<>(BOOTSTRAP_SAMPLES);
        List<Double> precisionUplifts = new ArrayList<>(BOOTSTRAP_SAMPLES);

        for (int iteration = 0; iteration < BOOTSTRAP_SAMPLES; iteration++) {
            List<MatchedPair> sample = new ArrayList<>();
            for (int clusterIndex = 0; clusterIndex < symbolClusters.size(); clusterIndex++) {
                sample.addAll(symbolClusters.get(random.nextInt(symbolClusters.size())));
            }
            OutcomeStats signal = OutcomeStats.signalStats(sample);
            OutcomeStats control = OutcomeStats.controlStats(sample);
            returnUplifts.add(signal.averageReturnPercent() - control.averageReturnPercent());
            if (signal.actionable() > 0 && control.actionable() > 0) {
                precisionUplifts.add(signal.precisionPercent() - control.precisionPercent());
            }
        }

        return new BootstrapIntervals(
                percentileInterval(returnUplifts),
                percentileInterval(precisionUplifts)
        );
    }

    private PercentileInterval percentileInterval(List<Double> values) {
        if (values.isEmpty()) {
            return PercentileInterval.empty();
        }
        List<Double> sorted = values.stream().sorted().toList();
        int lowerIndex = (int) Math.floor((sorted.size() - 1) * 0.025);
        int upperIndex = (int) Math.ceil((sorted.size() - 1) * 0.975);
        return new PercentileInterval(sorted.get(lowerIndex), sorted.get(upperIndex));
    }

    private BacktestOutcome outcome(double directionalReturn) {
        if (directionalReturn >= MINIMUM_MOVE_PERCENT) {
            return BacktestOutcome.SUCCESS;
        }
        if (directionalReturn <= -MINIMUM_MOVE_PERCENT) {
            return BacktestOutcome.FAILURE;
        }
        return BacktestOutcome.INCONCLUSIVE;
    }

    private double percentMove(double entryClose, double exitClose) {
        return entryClose == 0.0 ? 0.0 : ((exitClose - entryClose) / entryClose) * 100.0;
    }

    private boolean hasCompletePriceData(Candle candle) {
        return candle != null
                && candle.getTimestamp() != null
                && candle.getOpenPrice() != null
                && candle.getHighPrice() != null
                && candle.getLowPrice() != null
                && candle.getClosePrice() != null;
    }

    private enum Segment {
        DEVELOPMENT("DEVELOPMENT") {
            @Override
            boolean contains(long signalTimestamp, long exitTimestamp) {
                return signalTimestamp < VALIDATION_START && exitTimestamp < VALIDATION_START;
            }
        },
        VALIDATION("VALIDATION ") {
            @Override
            boolean contains(long signalTimestamp, long exitTimestamp) {
                return signalTimestamp >= VALIDATION_START;
            }
        };

        private final String label;

        Segment(String label) {
            this.label = label;
        }

        private String label() {
            return label;
        }

        abstract boolean contains(long signalTimestamp, long exitTimestamp);
    }

    private record PatternSpec(CandlePattern pattern, int candleCount) {
    }

    private record SymbolHistory(
            String symbol,
            List<Candle> candles,
            List<EnrichedCandle> enrichedCandles,
            Map<Long, Integer> indexByTimestamp,
            BacktestReport report
    ) {
    }

    private record ControlCandidate(
            int index,
            long timestamp,
            int trendScore,
            double directionalReturnPercent,
            BacktestOutcome outcome
    ) {
    }

    private record MatchedPair(
            String symbol,
            CandlePattern pattern,
            long signalTimestamp,
            long controlTimestamp,
            int signalIndex,
            int controlIndex,
            int signalTrendScore,
            int controlTrendScore,
            double signalReturnPercent,
            double controlReturnPercent,
            BacktestOutcome signalOutcome,
            BacktestOutcome controlOutcome
    ) {
    }

    private record ValidationResult(
            Segment segment,
            String label,
            int targetSignals,
            List<MatchedPair> pairs
    ) {
        private static ValidationResult combined(Segment segment, List<ValidationResult> results) {
            List<MatchedPair> combinedPairs = results.stream()
                    .flatMap(result -> result.pairs().stream())
                    .toList();
            int combinedTargets = results.stream().mapToInt(ValidationResult::targetSignals).sum();
            return new ValidationResult(segment, "COMBINED", combinedTargets, combinedPairs);
        }

        private long seed() {
            return 31L * segment.ordinal() + label.hashCode();
        }
    }

    private record OutcomeStats(
            int total,
            int successful,
            int failed,
            int inconclusive,
            double precisionPercent,
            double averageReturnPercent
    ) {
        private static OutcomeStats signalStats(List<MatchedPair> pairs) {
            return from(
                    pairs.stream().map(MatchedPair::signalOutcome).toList(),
                    pairs.stream().mapToDouble(MatchedPair::signalReturnPercent).toArray()
            );
        }

        private static OutcomeStats controlStats(List<MatchedPair> pairs) {
            return from(
                    pairs.stream().map(MatchedPair::controlOutcome).toList(),
                    pairs.stream().mapToDouble(MatchedPair::controlReturnPercent).toArray()
            );
        }

        private static OutcomeStats from(List<BacktestOutcome> outcomes, double[] returns) {
            int successful = (int) outcomes.stream().filter(outcome -> outcome == BacktestOutcome.SUCCESS).count();
            int failed = (int) outcomes.stream().filter(outcome -> outcome == BacktestOutcome.FAILURE).count();
            int inconclusive = outcomes.size() - successful - failed;
            int actionable = successful + failed;
            double precision = actionable == 0 ? 0.0 : successful * 100.0 / actionable;
            double averageReturn = java.util.Arrays.stream(returns).average().orElse(0.0);
            return new OutcomeStats(
                    outcomes.size(),
                    successful,
                    failed,
                    inconclusive,
                    precision,
                    averageReturn
            );
        }

        private int actionable() {
            return successful + failed;
        }
    }

    private record BootstrapIntervals(
            PercentileInterval returnUplift,
            PercentileInterval precisionUplift
    ) {
        private static BootstrapIntervals empty() {
            return new BootstrapIntervals(PercentileInterval.empty(), PercentileInterval.empty());
        }
    }

    private record PercentileInterval(double lower, double upper) {
        private static PercentileInterval empty() {
            return new PercentileInterval(Double.NaN, Double.NaN);
        }
    }
}
