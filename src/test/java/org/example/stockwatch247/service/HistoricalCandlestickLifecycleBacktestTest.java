package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.CandlestickSignalLifecyclePolicy.LifecycleResolution;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestSettings;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestTrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in research harness for the exact three-candle lifecycle used in
 * production. It intentionally makes no profitability assertion: the printed
 * returns are evidence to review, not a build threshold to optimize against.
 */
@SpringBootTest(properties = "alerts.schedule.enabled=false")
@EnabledIfSystemProperty(named = "backtest.candlestick.lifecycle.enabled", matches = "true")
class HistoricalCandlestickLifecycleBacktestTest {
    private static final String INTERVAL = "1d";
    private static final int MINIMUM_HISTORY = 250;
    private static final int SIGNAL_CANDLES = 100;
    private static final int LIFECYCLE_WINDOW = 3;
    private static final double ILLUSTRATIVE_ROUND_TRIP_COST_PERCENT = 0.20;
    private static final long VALIDATION_START = LocalDate.of(2025, 1, 1)
            .atStartOfDay()
            .toEpochSecond(ZoneOffset.UTC);
    private static final BacktestSettings DETECTION_SETTINGS =
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, LIFECYCLE_WINDOW, 3.0);
    private static final List<String> SYMBOLS = List.of(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "AMD", "ORCL",
            "CRM", "JPM", "BAC", "GS", "V", "MA", "XOM", "CVX", "COP", "JNJ",
            "UNH", "PFE", "LLY", "PG", "KO", "COST", "WMT", "HD", "CAT", "BA"
    );

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private HistoricalSignalBacktestService backtestService;

    @Test
    void printsExecutableTimingBacktestForProductionLifecycleRules() {
        List<LifecycleSignal> lifecycleSignals = new ArrayList<>();
        int sufficientSymbols = 0;
        int totalCandles = 0;
        long firstTimestamp = Long.MAX_VALUE;
        long lastTimestamp = Long.MIN_VALUE;

        for (String symbol : SYMBOLS) {
            List<Candle> candles = chronologicalCandles(symbol);
            if (candles.size() < MINIMUM_HISTORY + LIFECYCLE_WINDOW) {
                continue;
            }

            sufficientSymbols++;
            totalCandles += candles.size();
            firstTimestamp = Math.min(firstTimestamp, candles.getFirst().getTimestamp());
            lastTimestamp = Math.max(lastTimestamp, candles.getLast().getTimestamp());

            Map<Long, Integer> candleIndexByTimestamp = indexByTimestamp(candles);
            backtestService.backtest(candles, DETECTION_SETTINGS).trades()
                    .forEach(trade -> lifecycleSignals.add(resolveLifecycle(
                            symbol,
                            trade,
                            candles,
                            candleIndexByTimestamp
                    )));
        }

        assertThat(sufficientSymbols).isEqualTo(SYMBOLS.size());
        assertThat(lifecycleSignals).isNotEmpty();
        assertThat(lifecycleSignals)
                .allSatisfy(signal -> {
                    assertThat(signal.status()).isNotEqualTo(SignalLifecycleStatus.DETECTED);
                    assertThat(signal.resolutionOffset()).isBetween(1, LIFECYCLE_WINDOW);
                    if (signal.status() == SignalLifecycleStatus.EXPIRED) {
                        assertThat(signal.resolutionOffset()).isEqualTo(LIFECYCLE_WINDOW);
                    }
                });
        assertThat(count(lifecycleSignals, ignored -> true))
                .isEqualTo(count(lifecycleSignals, signal -> signal.status() == SignalLifecycleStatus.CONFIRMED)
                        + count(lifecycleSignals, signal -> signal.status() == SignalLifecycleStatus.INVALIDATED)
                        + count(lifecycleSignals, signal -> signal.status() == SignalLifecycleStatus.EXPIRED));

        printReport(
                lifecycleSignals,
                sufficientSymbols,
                totalCandles,
                firstTimestamp,
                lastTimestamp
        );
    }

    private LifecycleSignal resolveLifecycle(String symbol,
                                             BacktestTrade trade,
                                             List<Candle> candles,
                                             Map<Long, Integer> candleIndexByTimestamp) {
        Integer signalIndex = candleIndexByTimestamp.get(trade.signalTimestamp());
        assertThat(signalIndex)
                .as("%s signal timestamp %s exists in source candles", symbol, trade.signalTimestamp())
                .isNotNull();

        int patternCandleCount = CandlestickSignalLifecyclePolicy.patternCandleCount(trade.pattern());
        assertThat(patternCandleCount)
                .as("%s has a supported lifecycle pattern", trade.pattern())
                .isPositive();
        int patternStartIndex = signalIndex - patternCandleCount + 1;
        assertThat(patternStartIndex).isNotNegative();

        List<Candle> patternCandles = candles.subList(patternStartIndex, signalIndex + 1);
        double patternHigh = patternCandles.stream()
                .mapToDouble(Candle::getHighPrice)
                .max()
                .orElseThrow();
        double patternLow = patternCandles.stream()
                .mapToDouble(Candle::getLowPrice)
                .min()
                .orElseThrow();
        assertThat(patternHigh).isGreaterThan(patternLow);

        double confirmationTrigger = trade.tradeSignal() == TradeSignal.BUY ? patternHigh : patternLow;
        double invalidationPrice = trade.tradeSignal() == TradeSignal.BUY ? patternLow : patternHigh;
        List<Candle> subsequentCandles = candles.subList(
                signalIndex + 1,
                signalIndex + LIFECYCLE_WINDOW + 1
        );
        LifecycleResolution resolution = CandlestickSignalLifecyclePolicy.resolve(
                trade.tradeSignal(),
                confirmationTrigger,
                invalidationPrice,
                subsequentCandles,
                LIFECYCLE_WINDOW
        );
        assertThat(resolution).isNotNull();

        return new LifecycleSignal(
                symbol,
                candles,
                trade,
                signalIndex,
                patternHigh,
                patternLow,
                resolution.status(),
                resolution.candleOffset(),
                resolution.resolutionCandle().getTimestamp()
        );
    }

    private void printReport(List<LifecycleSignal> signals,
                             int sufficientSymbols,
                             int totalCandles,
                             long firstTimestamp,
                             long lastTimestamp) {
        System.out.println();
        System.out.println("=== Candlestick Three-Candle Lifecycle Historical Backtest ===");
        System.out.printf(
                "Data: symbols=%d/%d candles=%d span=%s through %s interval=%s%n",
                sufficientSymbols,
                SYMBOLS.size(),
                totalCandles,
                utcDate(firstTimestamp),
                utcDate(lastTimestamp),
                INTERVAL
        );
        System.out.printf(
                "Detection: minimumHistory=%d signalCandles=%d lifecycleWindow=%d; only signals with all lifecycle candles are included.%n",
                MINIMUM_HISTORY,
                SIGNAL_CANDLES,
                LIFECYCLE_WINDOW
        );
        System.out.printf(
                "Execution model: signal is known at a close; hypothetical entry is the next candle open; exit is the selected horizon close.%n"
        );
        System.out.printf(
                "Illustrative net return subtracts %.2f%% round trip; no stop, sizing, capital, overlap, tax, or borrow model.%n",
                ILLUSTRATIVE_ROUND_TRIP_COST_PERCENT
        );

        System.out.println();
        System.out.println("Lifecycle outcomes:");
        System.out.printf(
                "  Detected             signals=%4d buy=%4d sell=%4d%n",
                signals.size(),
                count(signals, signal -> signal.trade().tradeSignal() == TradeSignal.BUY),
                count(signals, signal -> signal.trade().tradeSignal() == TradeSignal.SELL)
        );
        printLifecycleRow("CONFIRMED", signals, SignalLifecycleStatus.CONFIRMED);
        printLifecycleRow("INVALIDATED", signals, SignalLifecycleStatus.INVALIDATED);
        printLifecycleRow("EXPIRED", signals, SignalLifecycleStatus.EXPIRED);

        System.out.println();
        System.out.println("Resolution delay:");
        for (int offset = 1; offset <= LIFECYCLE_WINDOW; offset++) {
            int fixedOffset = offset;
            System.out.printf(
                    "  candle %d             total=%4d confirmed=%4d invalidated=%4d expired=%4d%n",
                    offset,
                    count(signals, signal -> signal.resolutionOffset() == fixedOffset),
                    count(signals, signal -> signal.resolutionOffset() == fixedOffset
                            && signal.status() == SignalLifecycleStatus.CONFIRMED),
                    count(signals, signal -> signal.resolutionOffset() == fixedOffset
                            && signal.status() == SignalLifecycleStatus.INVALIDATED),
                    count(signals, signal -> signal.resolutionOffset() == fixedOffset
                            && signal.status() == SignalLifecycleStatus.EXPIRED)
            );
        }
        System.out.printf(
                "  mean terminal delay    %.2f candles%n",
                signals.stream().mapToInt(LifecycleSignal::resolutionOffset).average().orElse(0.0)
        );

        List<LifecycleSignal> confirmed = select(
                signals,
                signal -> signal.status() == SignalLifecycleStatus.CONFIRMED
        );
        List<LifecycleSignal> invalidated = select(
                signals,
                signal -> signal.status() == SignalLifecycleStatus.INVALIDATED
        );
        List<LifecycleSignal> expired = select(
                signals,
                signal -> signal.status() == SignalLifecycleStatus.EXPIRED
        );

        List<Measurement> confirmedAfterResolution10 = measure(
                confirmed,
                EntryTiming.AFTER_RESOLUTION,
                10
        );
        List<LifecycleSignal> pairedConfirmed10 = confirmedAfterResolution10.stream()
                .map(Measurement::signal)
                .toList();

        System.out.println();
        System.out.println("Primary 10-session / +/-3.0% outcome:");
        printPerformanceHeader();
        printPerformanceRow(
                "All detected / detection entry",
                measure(signals, EntryTiming.AFTER_DETECTION, 10),
                3.0
        );
        printPerformanceRow(
                "Confirmed / detection entry",
                measure(pairedConfirmed10, EntryTiming.AFTER_DETECTION, 10),
                3.0
        );
        printPerformanceRow(
                "Confirmed / post-confirm entry",
                confirmedAfterResolution10,
                3.0
        );
        printPerformanceRow(
                "Invalidated / detection entry",
                measure(invalidated, EntryTiming.AFTER_DETECTION, 10),
                3.0
        );
        printPerformanceRow(
                "Expired / detection entry",
                measure(expired, EntryTiming.AFTER_DETECTION, 10),
                3.0
        );
        printPerformanceRow(
                "Confirmed BUY / post-confirm",
                measure(
                        select(confirmed, signal -> signal.trade().tradeSignal() == TradeSignal.BUY),
                        EntryTiming.AFTER_RESOLUTION,
                        10
                ),
                3.0
        );
        printPerformanceRow(
                "Confirmed SELL / post-confirm",
                measure(
                        select(confirmed, signal -> signal.trade().tradeSignal() == TradeSignal.SELL),
                        EntryTiming.AFTER_RESOLUTION,
                        10
                ),
                3.0
        );

        System.out.println();
        System.out.println("Horizon sensitivity:");
        printPerformanceHeader();
        printPerformanceRow(
                "All detected / 5 sessions",
                measure(signals, EntryTiming.AFTER_DETECTION, 5),
                2.0
        );
        printPerformanceRow(
                "Confirmed / post-confirm / 5",
                measure(confirmed, EntryTiming.AFTER_RESOLUTION, 5),
                2.0
        );
        printPerformanceRow(
                "All detected / 20 sessions",
                measure(signals, EntryTiming.AFTER_DETECTION, 20),
                5.0
        );
        printPerformanceRow(
                "Confirmed / post-confirm / 20",
                measure(confirmed, EntryTiming.AFTER_RESOLUTION, 20),
                5.0
        );

        System.out.println();
        System.out.println("Temporal stability at 10 sessions / +/-3.0%:");
        printPerformanceHeader();
        printPerformanceRow(
                "Development all detected",
                measure(
                        select(signals, signal -> signal.trade().signalTimestamp() < VALIDATION_START),
                        EntryTiming.AFTER_DETECTION,
                        10
                ),
                3.0
        );
        printPerformanceRow(
                "Development confirmed post",
                measure(
                        select(confirmed, signal -> signal.trade().signalTimestamp() < VALIDATION_START),
                        EntryTiming.AFTER_RESOLUTION,
                        10
                ),
                3.0
        );
        printPerformanceRow(
                "Development confirmed BUY",
                measure(
                        select(confirmed, signal -> signal.trade().signalTimestamp() < VALIDATION_START
                                && signal.trade().tradeSignal() == TradeSignal.BUY),
                        EntryTiming.AFTER_RESOLUTION,
                        10
                ),
                3.0
        );
        printPerformanceRow(
                "Development confirmed SELL",
                measure(
                        select(confirmed, signal -> signal.trade().signalTimestamp() < VALIDATION_START
                                && signal.trade().tradeSignal() == TradeSignal.SELL),
                        EntryTiming.AFTER_RESOLUTION,
                        10
                ),
                3.0
        );
        printPerformanceRow(
                "Validation all detected",
                measure(
                        select(signals, signal -> signal.trade().signalTimestamp() >= VALIDATION_START),
                        EntryTiming.AFTER_DETECTION,
                        10
                ),
                3.0
        );
        printPerformanceRow(
                "Validation confirmed BUY",
                measure(
                        select(confirmed, signal -> signal.trade().signalTimestamp() >= VALIDATION_START
                                && signal.trade().tradeSignal() == TradeSignal.BUY),
                        EntryTiming.AFTER_RESOLUTION,
                        10
                ),
                3.0
        );
        printPerformanceRow(
                "Validation confirmed SELL",
                measure(
                        select(confirmed, signal -> signal.trade().signalTimestamp() >= VALIDATION_START
                                && signal.trade().tradeSignal() == TradeSignal.SELL),
                        EntryTiming.AFTER_RESOLUTION,
                        10
                ),
                3.0
        );
        printPerformanceRow(
                "Validation confirmed post",
                measure(
                        select(confirmed, signal -> signal.trade().signalTimestamp() >= VALIDATION_START),
                        EntryTiming.AFTER_RESOLUTION,
                        10
                ),
                3.0
        );

        System.out.println();
        System.out.println("V3 score bands among confirmed, post-confirmation entries (10 sessions / +/-3.0%):");
        printPerformanceHeader();
        printPerformanceRow(
                "Confirmed score 0-59",
                measure(
                        select(confirmed, signal -> signal.trade().confidenceScore() < 60),
                        EntryTiming.AFTER_RESOLUTION,
                        10
                ),
                3.0
        );
        printPerformanceRow(
                "Confirmed score 60-69",
                measure(
                        select(confirmed, signal -> signal.trade().confidenceScore() >= 60
                                && signal.trade().confidenceScore() < 70),
                        EntryTiming.AFTER_RESOLUTION,
                        10
                ),
                3.0
        );
        printPerformanceRow(
                "Confirmed score 70-100",
                measure(
                        select(confirmed, signal -> signal.trade().confidenceScore() >= 70),
                        EntryTiming.AFTER_RESOLUTION,
                        10
                ),
                3.0
        );
        System.out.println("================================================================");
        System.out.println();
    }

    private void printLifecycleRow(String label,
                                   List<LifecycleSignal> signals,
                                   SignalLifecycleStatus status) {
        int statusSignals = count(signals, signal -> signal.status() == status);
        System.out.printf(
                "  %-20s signals=%4d rate=%6.2f%% buy=%4d sell=%4d%n",
                label,
                statusSignals,
                percentage(statusSignals, signals.size()),
                count(signals, signal -> signal.status() == status
                        && signal.trade().tradeSignal() == TradeSignal.BUY),
                count(signals, signal -> signal.status() == status
                        && signal.trade().tradeSignal() == TradeSignal.SELL)
        );
    }

    private void printPerformanceHeader() {
        System.out.println(
                "  Cohort                              n  succ  fail   inc   prec   gross+  net+   avgGross  median   avgNet   avgMFE   avgMAE"
        );
    }

    private void printPerformanceRow(String label,
                                     List<Measurement> measurements,
                                     double minimumMovePercent) {
        Metrics metrics = Metrics.from(
                measurements,
                minimumMovePercent,
                ILLUSTRATIVE_ROUND_TRIP_COST_PERCENT
        );
        System.out.printf(
                "  %-33s %4d %5d %5d %5d %6.2f%% %6.2f%% %6.2f%% %+8.2f%% %+7.2f%% %+7.2f%% %+7.2f%% %+7.2f%%%n",
                label,
                metrics.signals(),
                metrics.successes(),
                metrics.failures(),
                metrics.inconclusive(),
                metrics.precisionPercent(),
                metrics.grossPositivePercent(),
                metrics.netPositivePercent(),
                metrics.averageGrossReturnPercent(),
                metrics.medianGrossReturnPercent(),
                metrics.averageNetReturnPercent(),
                metrics.averageFavorableMovePercent(),
                metrics.averageAdverseMovePercent()
        );
    }

    private List<Measurement> measure(List<LifecycleSignal> signals,
                                      EntryTiming timing,
                                      int horizonCandles) {
        List<Measurement> measurements = new ArrayList<>();
        for (LifecycleSignal signal : signals) {
            int entryIndex = timing == EntryTiming.AFTER_DETECTION
                    ? signal.signalIndex() + 1
                    : signal.signalIndex() + signal.resolutionOffset() + 1;
            int exitIndex = entryIndex + horizonCandles - 1;
            if (entryIndex >= signal.candles().size() || exitIndex >= signal.candles().size()) {
                continue;
            }

            Candle entryCandle = signal.candles().get(entryIndex);
            Candle exitCandle = signal.candles().get(exitIndex);
            if (timing == EntryTiming.AFTER_RESOLUTION) {
                assertThat(entryCandle.getTimestamp()).isGreaterThan(signal.resolutionTimestamp());
            } else {
                assertThat(entryCandle.getTimestamp()).isGreaterThan(signal.trade().signalTimestamp());
            }

            double entryPrice = entryCandle.getOpenPrice();
            assertThat(entryPrice).isPositive();
            double grossReturn = directionalReturnPercent(
                    signal.trade().tradeSignal(),
                    entryPrice,
                    exitCandle.getClosePrice()
            );
            List<Candle> holdingCandles = signal.candles().subList(entryIndex, exitIndex + 1);
            double highestHigh = holdingCandles.stream()
                    .mapToDouble(Candle::getHighPrice)
                    .max()
                    .orElseThrow();
            double lowestLow = holdingCandles.stream()
                    .mapToDouble(Candle::getLowPrice)
                    .min()
                    .orElseThrow();
            double favorableMove = signal.trade().tradeSignal() == TradeSignal.BUY
                    ? percentMove(entryPrice, highestHigh)
                    : -percentMove(entryPrice, lowestLow);
            double adverseMove = signal.trade().tradeSignal() == TradeSignal.BUY
                    ? percentMove(entryPrice, lowestLow)
                    : -percentMove(entryPrice, highestHigh);

            measurements.add(new Measurement(
                    signal,
                    horizonCandles,
                    entryCandle.getTimestamp(),
                    entryPrice,
                    exitCandle.getTimestamp(),
                    exitCandle.getClosePrice(),
                    grossReturn,
                    favorableMove,
                    adverseMove
            ));
        }
        return List.copyOf(measurements);
    }

    private double directionalReturnPercent(TradeSignal tradeSignal,
                                            double entryPrice,
                                            double exitPrice) {
        double rawReturn = percentMove(entryPrice, exitPrice);
        return tradeSignal == TradeSignal.BUY ? rawReturn : -rawReturn;
    }

    private double percentMove(double entryPrice, double targetPrice) {
        return ((targetPrice - entryPrice) / entryPrice) * 100.0;
    }

    private List<Candle> chronologicalCandles(String symbol) {
        return candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, INTERVAL).stream()
                .filter(this::hasCompletePriceData)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
    }

    private boolean hasCompletePriceData(Candle candle) {
        return candle != null
                && candle.getTimestamp() != null
                && candle.getOpenPrice() != null
                && candle.getHighPrice() != null
                && candle.getLowPrice() != null
                && candle.getClosePrice() != null;
    }

    private Map<Long, Integer> indexByTimestamp(List<Candle> candles) {
        Map<Long, Integer> indexes = new HashMap<>();
        for (int index = 0; index < candles.size(); index++) {
            indexes.put(candles.get(index).getTimestamp(), index);
        }
        return Map.copyOf(indexes);
    }

    private List<LifecycleSignal> select(List<LifecycleSignal> signals,
                                         Predicate<LifecycleSignal> predicate) {
        return signals.stream().filter(predicate).toList();
    }

    private int count(List<LifecycleSignal> signals,
                      Predicate<LifecycleSignal> predicate) {
        return (int) signals.stream().filter(predicate).count();
    }

    private double percentage(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }

    private LocalDate utcDate(long timestamp) {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private enum EntryTiming {
        AFTER_DETECTION,
        AFTER_RESOLUTION
    }

    private record LifecycleSignal(
            String symbol,
            List<Candle> candles,
            BacktestTrade trade,
            int signalIndex,
            double patternHigh,
            double patternLow,
            SignalLifecycleStatus status,
            int resolutionOffset,
            long resolutionTimestamp
    ) {
    }

    private record Measurement(
            LifecycleSignal signal,
            int horizonCandles,
            long entryTimestamp,
            double entryPrice,
            long exitTimestamp,
            double exitPrice,
            double grossReturnPercent,
            double favorableMovePercent,
            double adverseMovePercent
    ) {
    }

    private record Metrics(
            int signals,
            int successes,
            int failures,
            int inconclusive,
            double precisionPercent,
            double grossPositivePercent,
            double netPositivePercent,
            double averageGrossReturnPercent,
            double medianGrossReturnPercent,
            double averageNetReturnPercent,
            double averageFavorableMovePercent,
            double averageAdverseMovePercent
    ) {
        private static Metrics from(List<Measurement> measurements,
                                    double minimumMovePercent,
                                    double roundTripCostPercent) {
            int successes = (int) measurements.stream()
                    .filter(measurement -> measurement.grossReturnPercent() >= minimumMovePercent)
                    .count();
            int failures = (int) measurements.stream()
                    .filter(measurement -> measurement.grossReturnPercent() <= -minimumMovePercent)
                    .count();
            int inconclusive = measurements.size() - successes - failures;
            double averageGross = measurements.stream()
                    .mapToDouble(Measurement::grossReturnPercent)
                    .average()
                    .orElse(0.0);
            double medianGross = median(
                    measurements.stream()
                            .mapToDouble(Measurement::grossReturnPercent)
                            .sorted()
                            .toArray()
            );

            return new Metrics(
                    measurements.size(),
                    successes,
                    failures,
                    inconclusive,
                    percentage(successes, successes + failures),
                    percentage(
                            (int) measurements.stream()
                                    .filter(measurement -> measurement.grossReturnPercent() > 0.0)
                                    .count(),
                            measurements.size()
                    ),
                    percentage(
                            (int) measurements.stream()
                                    .filter(measurement ->
                                            measurement.grossReturnPercent() > roundTripCostPercent)
                                    .count(),
                            measurements.size()
                    ),
                    averageGross,
                    medianGross,
                    averageGross - roundTripCostPercent,
                    measurements.stream()
                            .mapToDouble(Measurement::favorableMovePercent)
                            .average()
                            .orElse(0.0),
                    measurements.stream()
                            .mapToDouble(Measurement::adverseMovePercent)
                            .average()
                            .orElse(0.0)
            );
        }

        private static double percentage(int numerator, int denominator) {
            return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
        }

        private static double median(double[] sortedValues) {
            if (sortedValues.length == 0) {
                return 0.0;
            }
            int middle = sortedValues.length / 2;
            return sortedValues.length % 2 == 0
                    ? (sortedValues[middle - 1] + sortedValues[middle]) / 2.0
                    : sortedValues[middle];
        }
    }
}
