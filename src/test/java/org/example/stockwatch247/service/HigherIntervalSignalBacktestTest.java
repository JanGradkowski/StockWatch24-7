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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@EnabledIfSystemProperty(named = "backtest.higher.enabled", matches = "true")
class HigherIntervalSignalBacktestTest {
    private static final int SIGNAL_CANDLES = 80;
    private static final boolean SYNC_MISSING_CANDLES = Boolean.getBoolean("backtest.higher.sync-missing");
    private static final long SYNC_DELAY_MS = Long.getLong("backtest.higher.sync-delay-ms", 8_500L);
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
            new ConfidenceFilter("60-100", 60, 100),
            new ConfidenceFilter("65-100", 65, 100),
            new ConfidenceFilter("70-100", 70, 100),
            new ConfidenceFilter("75-100", 75, 100),
            new ConfidenceFilter("80-100", 80, 100),
            new ConfidenceFilter("85-100", 85, 100),
            new ConfidenceFilter("90-100", 90, 100)
    );

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private HistoricalSignalBacktestService backtestService;

    @Autowired
    private MarketDataService marketDataService;

    @Test
    void printsHigherIntervalBacktestReport() {
        System.out.println();
        System.out.println("=== Higher Interval Signal Backtest ===");
        System.out.println("Outcome: direction must move by minMove after forwardCandles; precision excludes inconclusive.");
        System.out.println();

        syncMissingCandlesIfRequested();

        List<HigherIntervalResult> candlestickOnly = runSuite("Candlestick only", false);
        List<HigherIntervalResult> candlestickAndElliott = runSuite("Candlestick + Elliott", true);

        printSuiteResults("Candlestick only", candlestickOnly);
        printSuiteResults("Candlestick + Elliott", candlestickAndElliott);
        printBeforeAfterSummary(candlestickOnly, candlestickAndElliott);
        System.out.println("=======================================");
        System.out.println();
    }

    private void syncMissingCandlesIfRequested() {
        if (!SYNC_MISSING_CANDLES) {
            System.out.println("Higher interval candle sync disabled. Set -Dbacktest.higher.sync-missing=true to fetch missing data.");
            System.out.println();
            return;
        }

        System.out.printf(
                "Syncing missing higher interval candles before backtest. delayMs=%d%n",
                SYNC_DELAY_MS
        );
        Set<IntervalRequirement> requirements = intervalRequirements();
        int synced = 0;
        int skipped = 0;
        for (String symbol : REPRESENTATIVE_SYMBOLS) {
            for (IntervalRequirement requirement : requirements) {
                int existing = candleRepository
                        .findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, requirement.interval())
                        .size();
                if (existing >= requirement.minimumCandles()) {
                    skipped++;
                    continue;
                }

                System.out.printf(
                        "Syncing %s %s: existing=%d required=%d%n",
                        symbol,
                        requirement.interval(),
                        existing,
                        requirement.minimumCandles()
                );
                marketDataService.syncCandles(symbol, requirement.interval(), null, true);
                synced++;
                sleepBetweenProviderCalls();
            }
        }
        System.out.printf("Higher interval candle sync complete. synced=%d skipped=%d%n%n", synced, skipped);
    }

    private Set<IntervalRequirement> intervalRequirements() {
        java.util.Map<String, Integer> requirements = new java.util.LinkedHashMap<>();
        for (IntervalRun run : RUNS) {
            int required = run.settings().minimumHistoricalCandles() + run.settings().forwardCandles();
            requirements.merge(run.interval(), required, Math::max);
        }

        Set<IntervalRequirement> intervalRequirements = new LinkedHashSet<>();
        requirements.forEach((interval, minimumCandles) ->
                intervalRequirements.add(new IntervalRequirement(interval, minimumCandles)));
        return intervalRequirements;
    }

    private void sleepBetweenProviderCalls() {
        if (SYNC_DELAY_MS <= 0) {
            return;
        }
        try {
            Thread.sleep(SYNC_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while spacing provider API calls.", e);
        }
    }

    private List<HigherIntervalResult> runSuite(String suiteLabel, boolean includeElliottWaves) {
        List<HigherIntervalResult> results = new ArrayList<>();
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
                BacktestReport report = backtestService.backtest(
                        candles,
                        run.settings(),
                        includeElliottWaves
                );
                totalCandles += report.totalCandles();
                analyzedCandles += report.analyzedCandles();
                allTrades.addAll(report.trades());
            }

            System.out.printf(
                    "%s | %s interval=%s sufficientSymbols=%d/%d totalCandles=%d analyzed=%d rawSignals=%d%n",
                    suiteLabel,
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
                results.add(HigherIntervalResult.from(run, filter, sufficientSymbols, totalCandles, analyzedCandles, filtered));
            }
        }
        return results;
    }

    private void printSuiteResults(String suiteLabel, List<HigherIntervalResult> results) {
        System.out.println();
        System.out.println("All higher interval results: " + suiteLabel);
        results.stream()
                .sorted(Comparator
                        .comparing((HigherIntervalResult result) -> result.run().interval())
                        .thenComparing(result -> result.run().settings().forwardCandles())
                        .thenComparing(result -> result.filter().label()))
                .forEach(this::printResult);

        System.out.println();
        System.out.println("Best higher interval results with at least 20 signals: " + suiteLabel);
        results.stream()
                .filter(result -> result.totalSignals() >= 20)
                .sorted(Comparator
                        .comparingDouble(HigherIntervalResult::precisionPercent)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(HigherIntervalResult::totalSignals).reversed()))
                .limit(10)
                .forEach(this::printResult);
    }

    private void printBeforeAfterSummary(List<HigherIntervalResult> before, List<HigherIntervalResult> after) {
        System.out.println();
        System.out.println("Before/after summary by matching run and confidence filter:");
        for (HigherIntervalResult beforeResult : before) {
            after.stream()
                    .filter(afterResult -> afterResult.run().equals(beforeResult.run())
                            && afterResult.filter().equals(beforeResult.filter()))
                    .findFirst()
                    .ifPresent(afterResult -> System.out.printf(
                            "%-24s interval=%3s conf=%6s beforeSignals=%4d beforePrecision=%6.2f%% beforeAvgReturn=%7.2f%% afterSignals=%4d afterPrecision=%6.2f%% afterAvgReturn=%7.2f%% precisionChange=%7.2fpp avgReturnChange=%7.2fpp%n",
                            beforeResult.run().label(),
                            beforeResult.run().interval(),
                            beforeResult.filter().label(),
                            beforeResult.totalSignals(),
                            beforeResult.precisionPercent(),
                            beforeResult.averageDirectionalReturnPercent(),
                            afterResult.totalSignals(),
                            afterResult.precisionPercent(),
                            afterResult.averageDirectionalReturnPercent(),
                            afterResult.precisionPercent() - beforeResult.precisionPercent(),
                            afterResult.averageDirectionalReturnPercent() - beforeResult.averageDirectionalReturnPercent()
                    ));
        }
    }

    private void printResult(HigherIntervalResult result) {
        BacktestSettings settings = result.run().settings();
        System.out.printf(
                "%-24s interval=%3s horizon=%2d minMove=%4.1f%% conf=%6s symbols=%2d signals=%4d success=%3d failed=%3d inconclusive=%3d successRate=%6.2f%% precision=%6.2f%% avgReturn=%7.2f%%%n",
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

    private record IntervalRequirement(String interval, int minimumCandles) {
    }

    private record ConfidenceFilter(String label, int minInclusive, int maxInclusive) {
        private boolean matches(BacktestTrade trade) {
            return trade.confidenceScore() >= minInclusive && trade.confidenceScore() <= maxInclusive;
        }
    }

    private record HigherIntervalResult(
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
        private static HigherIntervalResult from(IntervalRun run,
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

            return new HigherIntervalResult(
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
