package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestOutcome;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestReport;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestSettings;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestTrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@EnabledIfSystemProperty(named = "backtest.elliott.compare.enabled", matches = "true")
class ElliottWaveImplementationComparisonBacktestTest {
    private static final int SIGNAL_CANDLES = 80;
    private static final List<String> REPRESENTATIVE_SYMBOLS = List.of(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "AMD", "ORCL",
            "CRM", "JPM", "BAC", "GS", "V", "MA", "XOM", "CVX", "COP", "JNJ",
            "UNH", "PFE", "LLY", "PG", "KO", "COST", "WMT", "HD", "CAT", "BA"
    );
    private static final List<IntervalRun> RUNS = List.of(
            new IntervalRun("1wk", "Weekly 4 candles / 4%", new BacktestSettings(80, SIGNAL_CANDLES, 4, 4.0)),
            new IntervalRun("1wk", "Weekly 8 candles / 8%", new BacktestSettings(80, SIGNAL_CANDLES, 8, 8.0)),
            new IntervalRun("1wk", "Weekly 12 candles / 12%", new BacktestSettings(80, SIGNAL_CANDLES, 12, 12.0)),
            new IntervalRun("1mo", "Monthly 3 candles / 6%", new BacktestSettings(36, SIGNAL_CANDLES, 3, 6.0)),
            new IntervalRun("1mo", "Monthly 6 candles / 12%", new BacktestSettings(36, SIGNAL_CANDLES, 6, 12.0)),
            new IntervalRun("1mo", "Monthly 9 candles / 18%", new BacktestSettings(36, SIGNAL_CANDLES, 9, 18.0))
    );
    private static final List<ConfidenceFilter> CONFIDENCE_FILTERS = List.of(
            new ConfidenceFilter("0-100", 0, 100),
            new ConfidenceFilter("75-100", 75, 100),
            new ConfidenceFilter("80-100", 80, 100),
            new ConfidenceFilter("85-100", 85, 100),
            new ConfidenceFilter("90-100", 90, 100)
    );

    @Autowired
    private CandleRepository candleRepository;

    @Test
    void printsLegacyVersusCurrentElliottBacktestReport() {
        HistoricalSignalBacktestService legacyService = backtestService(new ElliottWaveDetectionService(0));
        HistoricalSignalBacktestService currentService = backtestService(new ElliottWaveDetectionService());

        System.out.println();
        System.out.println("=== Elliott Wave Implementation Comparison Backtest ===");
        System.out.println("Legacy = breakout/rebound candle only. Current = breakout/rebound plus one fresh follow-through candle.");
        System.out.println("Outcome: direction must move by minMove after forwardCandles; precision excludes inconclusive.");
        System.out.println();

        List<Result> legacyResults = runSuite("Legacy Elliott", legacyService);
        List<Result> currentResults = runSuite("Current Elliott", currentService);

        printBestResults("Legacy Elliott", legacyResults);
        printBestResults("Current Elliott", currentResults);
        printComparison(legacyResults, currentResults);
        System.out.println("=======================================================");
        System.out.println();
    }

    private HistoricalSignalBacktestService backtestService(ElliottWaveDetectionService elliottWaveDetectionService) {
        return new HistoricalSignalBacktestService(
                new TechnicalIndicatorEnrichmentService(),
                new CandlePatternDetectionService(),
                elliottWaveDetectionService
        );
    }

    private List<Result> runSuite(String label, HistoricalSignalBacktestService service) {
        List<Result> results = new ArrayList<>();
        for (IntervalRun run : RUNS) {
            List<BacktestTrade> allTrades = new ArrayList<>();
            int sufficientSymbols = 0;
            int totalCandles = 0;
            int analyzedCandles = 0;
            for (String symbol : REPRESENTATIVE_SYMBOLS) {
                List<Candle> candles = candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, run.interval());
                if (candles.size() < run.settings().minimumHistoricalCandles() + run.settings().forwardCandles()) {
                    continue;
                }
                sufficientSymbols++;
                BacktestReport report = service.backtest(candles, run.settings(), true);
                totalCandles += report.totalCandles();
                analyzedCandles += report.analyzedCandles();
                allTrades.addAll(report.trades());
            }
            System.out.printf(
                    "%s | %s interval=%s sufficientSymbols=%d/%d totalCandles=%d analyzed=%d rawSignals=%d%n",
                    label,
                    run.label(),
                    run.interval(),
                    sufficientSymbols,
                    REPRESENTATIVE_SYMBOLS.size(),
                    totalCandles,
                    analyzedCandles,
                    allTrades.size()
            );

            for (ConfidenceFilter filter : CONFIDENCE_FILTERS) {
                List<BacktestTrade> filtered = allTrades.stream()
                        .filter(filter::matches)
                        .toList();
                results.add(Result.from(run, filter, sufficientSymbols, totalCandles, analyzedCandles, filtered));
            }
        }
        return results;
    }

    private void printBestResults(String label, List<Result> results) {
        System.out.println();
        System.out.println("Best higher interval results with at least 20 signals: " + label);
        results.stream()
                .filter(result -> result.totalSignals() >= 20)
                .sorted(Comparator
                        .comparingDouble(Result::precisionPercent)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(Result::totalSignals).reversed()))
                .limit(10)
                .forEach(this::printResult);
    }

    private void printComparison(List<Result> legacyResults, List<Result> currentResults) {
        System.out.println();
        System.out.println("Current versus legacy by matching run and confidence filter:");
        for (Result legacy : legacyResults) {
            currentResults.stream()
                    .filter(current -> current.run().equals(legacy.run()) && current.filter().equals(legacy.filter()))
                    .findFirst()
                    .ifPresent(current -> System.out.printf(
                            "%-24s interval=%3s conf=%6s legacySignals=%4d legacyPrecision=%6.2f%% legacyAvgReturn=%7.2f%% currentSignals=%4d currentPrecision=%6.2f%% currentAvgReturn=%7.2f%% precisionChange=%7.2fpp avgReturnChange=%7.2fpp%n",
                            legacy.run().label(),
                            legacy.run().interval(),
                            legacy.filter().label(),
                            legacy.totalSignals(),
                            legacy.precisionPercent(),
                            legacy.averageDirectionalReturnPercent(),
                            current.totalSignals(),
                            current.precisionPercent(),
                            current.averageDirectionalReturnPercent(),
                            current.precisionPercent() - legacy.precisionPercent(),
                            current.averageDirectionalReturnPercent() - legacy.averageDirectionalReturnPercent()
                    ));
        }
    }

    private void printResult(Result result) {
        BacktestSettings settings = result.run().settings();
        System.out.printf(
                "%-24s interval=%3s horizon=%2d minMove=%4.1f%% conf=%6s symbols=%2d signals=%4d success=%4d failed=%4d inconclusive=%4d successRate=%6.2f%% precision=%6.2f%% avgReturn=%7.2f%%%n",
                result.run().label(),
                result.run().interval(),
                settings.forwardCandles(),
                settings.minimumMovePercent(),
                result.filter().label(),
                result.sufficientSymbols(),
                result.totalSignals(),
                result.successfulSignals(),
                result.failedSignals(),
                result.inconclusiveSignals(),
                result.successRatePercent(),
                result.precisionPercent(),
                result.averageDirectionalReturnPercent()
        );
    }

    private record IntervalRun(String interval, String label, BacktestSettings settings) {
    }

    private record ConfidenceFilter(String label, int minInclusive, int maxInclusive) {
        private boolean matches(BacktestTrade trade) {
            return trade.confidenceScore() >= minInclusive && trade.confidenceScore() <= maxInclusive;
        }
    }

    private record Result(
            IntervalRun run,
            ConfidenceFilter filter,
            int sufficientSymbols,
            int totalCandles,
            int analyzedCandles,
            int totalSignals,
            int successfulSignals,
            int failedSignals,
            int inconclusiveSignals,
            double successRatePercent,
            double precisionPercent,
            double averageDirectionalReturnPercent
    ) {
        private static Result from(IntervalRun run,
                                   ConfidenceFilter filter,
                                   int sufficientSymbols,
                                   int totalCandles,
                                   int analyzedCandles,
                                   List<BacktestTrade> trades) {
            int successful = countOutcome(trades, BacktestOutcome.SUCCESS);
            int failed = countOutcome(trades, BacktestOutcome.FAILURE);
            int inconclusive = countOutcome(trades, BacktestOutcome.INCONCLUSIVE);
            double averageReturn = trades.stream()
                    .mapToDouble(BacktestTrade::directionalReturnPercent)
                    .average()
                    .orElse(0.0);

            return new Result(
                    run,
                    filter,
                    sufficientSymbols,
                    totalCandles,
                    analyzedCandles,
                    trades.size(),
                    successful,
                    failed,
                    inconclusive,
                    percentage(successful, trades.size()),
                    percentage(successful, successful + failed),
                    averageReturn
            );
        }

        private static int countOutcome(List<BacktestTrade> trades, BacktestOutcome outcome) {
            return (int) trades.stream()
                    .filter(trade -> trade.outcome() == outcome)
                    .count();
        }

        private static double percentage(int numerator, int denominator) {
            if (denominator == 0) {
                return 0.0;
            }
            return (numerator * 100.0) / denominator;
        }
    }
}
