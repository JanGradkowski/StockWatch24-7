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
@EnabledIfSystemProperty(named = "backtest.sweep.enabled", matches = "true")
class HistoricalSignalThresholdSweepTest {
    private static final String INTERVAL = "1d";
    private static final int MINIMUM_HISTORY = 250;
    private static final int SIGNAL_CANDLES = 5;
    private static final List<String> REPRESENTATIVE_SYMBOLS = List.of(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "AMD", "ORCL",
            "CRM", "JPM", "BAC", "GS", "V", "MA", "XOM", "CVX", "COP", "JNJ",
            "UNH", "PFE", "LLY", "PG", "KO", "COST", "WMT", "HD", "CAT", "BA"
    );
    private static final List<BacktestSettings> SETTINGS_GRID = List.of(
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 3, 1.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 3, 1.5),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 3, 2.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 5, 1.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 5, 1.5),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 5, 2.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 10, 1.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 10, 2.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 10, 3.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 20, 2.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 20, 3.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 20, 5.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 30, 3.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 30, 5.0),
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 30, 8.0)
    );
    private static final List<ConfidenceFilter> CONFIDENCE_FILTERS = List.of(
            new ConfidenceFilter("0-100", 0, 100),
            new ConfidenceFilter("0-64", 0, 64),
            new ConfidenceFilter("0-74", 0, 74),
            new ConfidenceFilter("30-64", 30, 64),
            new ConfidenceFilter("50-64", 50, 64),
            new ConfidenceFilter("65-100", 65, 100),
            new ConfidenceFilter("70-100", 70, 100),
            new ConfidenceFilter("75-100", 75, 100),
            new ConfidenceFilter("80-100", 80, 100),
            new ConfidenceFilter("85-100", 85, 100),
            new ConfidenceFilter("90-100", 90, 100),
            new ConfidenceFilter("95-100", 95, 100),
            new ConfidenceFilter("65-94", 65, 94),
            new ConfidenceFilter("75-94", 75, 94),
            new ConfidenceFilter("80-94", 80, 94),
            new ConfidenceFilter("85-94", 85, 94),
            new ConfidenceFilter("90-94", 90, 94)
    );

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private HistoricalSignalBacktestService backtestService;

    @Test
    void printsThresholdAndConfidenceSweep() {
        List<SymbolHistory> histories = REPRESENTATIVE_SYMBOLS.stream()
                .map(symbol -> new SymbolHistory(
                        symbol,
                        candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, INTERVAL)
                ))
                .filter(history -> history.candles().size() >= MINIMUM_HISTORY + 10)
                .toList();

        System.out.println();
        System.out.println("=== Signal Threshold Sweep ===");
        System.out.printf("Symbols with sufficient cached history: %d/%d%n", histories.size(), REPRESENTATIVE_SYMBOLS.size());
        System.out.println("Outcome: direction must move by minMove after forwardCandles; precision excludes inconclusive.");
        System.out.println();

        List<SweepResult> results = new ArrayList<>();
        for (BacktestSettings settings : SETTINGS_GRID) {
            List<BacktestTrade> allTrades = new ArrayList<>();
            int totalCandles = 0;
            int analyzedCandles = 0;
            for (SymbolHistory history : histories) {
                BacktestReport report = backtestService.backtest(history.candles(), settings);
                totalCandles += report.totalCandles();
                analyzedCandles += report.analyzedCandles();
                allTrades.addAll(report.trades());
            }

            for (ConfidenceFilter filter : CONFIDENCE_FILTERS) {
                List<BacktestTrade> filteredTrades = allTrades.stream()
                        .filter(filter::matches)
                        .toList();
                results.add(SweepResult.from(settings, filter, totalCandles, analyzedCandles, filteredTrades));
            }
        }

        printAllResults(results);
        printBestResults("Best results with at least 300 signals", results, 300);
        printBestResults("Best results with at least 500 signals", results, 500);
        System.out.println("==============================");
        System.out.println();
    }

    private void printAllResults(List<SweepResult> results) {
        System.out.println("All threshold results:");
        results.stream()
                .sorted(Comparator
                        .comparing((SweepResult result) -> result.settings().forwardCandles())
                        .thenComparing(result -> result.settings().minimumMovePercent())
                        .thenComparing(result -> result.filter().label()))
                .forEach(this::printResult);
        System.out.println();
    }

    private void printBestResults(String label, List<SweepResult> results, int minimumSignals) {
        System.out.println(label + ":");
        results.stream()
                .filter(result -> result.totalSignals() >= minimumSignals)
                .sorted(Comparator
                        .comparingDouble(SweepResult::precisionPercent)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(SweepResult::totalSignals).reversed()))
                .limit(10)
                .forEach(this::printResult);
        System.out.println();
    }

    private void printResult(SweepResult result) {
        BacktestSettings settings = result.settings();
        System.out.printf(
                "horizon=%2d minMove=%3.1f%% conf=%6s signals=%4d success=%4d failed=%4d inconclusive=%4d successRate=%6.2f%% precision=%6.2f%% avgReturn=%7.2f%%%n",
                settings.forwardCandles(),
                settings.minimumMovePercent(),
                result.filter().label(),
                result.totalSignals(),
                result.successfulSignals(),
                result.failedSignals(),
                result.inconclusiveSignals(),
                result.successRatePercent(),
                result.precisionPercent(),
                result.averageDirectionalReturnPercent()
        );
    }

    private record SymbolHistory(String symbol, List<Candle> candles) {
    }

    private record ConfidenceFilter(String label, int minInclusive, int maxInclusive) {
        private boolean matches(BacktestTrade trade) {
            return trade.confidenceScore() >= minInclusive && trade.confidenceScore() <= maxInclusive;
        }
    }

    private record SweepResult(
            BacktestSettings settings,
            ConfidenceFilter filter,
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
        private static SweepResult from(BacktestSettings settings,
                                        ConfidenceFilter filter,
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

            return new SweepResult(
                    settings,
                    filter,
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
