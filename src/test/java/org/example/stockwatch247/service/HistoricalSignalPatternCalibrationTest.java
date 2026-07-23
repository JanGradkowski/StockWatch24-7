package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@EnabledIfSystemProperty(named = "backtest.pattern.enabled", matches = "true")
class HistoricalSignalPatternCalibrationTest {
    private static final String INTERVAL = "1d";
    private static final int MINIMUM_HISTORY = 250;
    private static final int SIGNAL_CANDLES = 100;
    private static final BacktestSettings BEST_DIRECTIONAL_SETTINGS =
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 10, 3.0);
    private static final BacktestSettings SHORTER_CONFIRMATION_SETTINGS =
            new BacktestSettings(MINIMUM_HISTORY, SIGNAL_CANDLES, 5, 2.0);
    private static final List<String> REPRESENTATIVE_SYMBOLS = List.of(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "AMD", "ORCL",
            "CRM", "JPM", "BAC", "GS", "V", "MA", "XOM", "CVX", "COP", "JNJ",
            "UNH", "PFE", "LLY", "PG", "KO", "COST", "WMT", "HD", "CAT", "BA"
    );

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private HistoricalSignalBacktestService backtestService;

    @Test
    void printsPerPatternCalibrationReport() {
        List<SymbolHistory> histories = REPRESENTATIVE_SYMBOLS.stream()
                .map(symbol -> new SymbolHistory(
                        symbol,
                        candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, INTERVAL)
                ))
                .filter(history -> history.candles().size() >= MINIMUM_HISTORY + 10)
                .toList();
        System.out.println();
        System.out.println("=== Per-Pattern Calibration Report ===");
        System.out.printf("Symbols with sufficient cached history: %d/%d%n", histories.size(), REPRESENTATIVE_SYMBOLS.size());
        printReport("10-candle / 3.0% move", histories, BEST_DIRECTIONAL_SETTINGS);
        printReport("5-candle / 2.0% move", histories, SHORTER_CONFIRMATION_SETTINGS);
        System.out.println("======================================");
        System.out.println();
    }

    private void printReport(String label,
                             List<SymbolHistory> histories,
                             BacktestSettings settings) {
        List<BacktestTrade> trades = new ArrayList<>();
        for (SymbolHistory history : histories) {
            BacktestReport report = backtestService.backtest(
                    history.candles(),
                    settings
            );
            trades.addAll(report.trades());
        }

        System.out.println();
        System.out.printf("%s: signals=%d, precision=%6.2f%%, avgReturn=%7.2f%%%n",
                label,
                trades.size(),
                precision(trades),
                averageReturn(trades));
        System.out.println("By pattern:");
        buildPatternStats(trades).values().stream()
                .sorted(Comparator
                        .comparingDouble(PatternStats::precision)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(PatternStats::totalSignals).reversed()))
                .forEach(this::printPatternStats);

        System.out.println("By pattern and direction:");
        buildPatternDirectionStats(trades).values().stream()
                .sorted(Comparator
                        .comparingDouble(PatternDirectionStats::precision)
                        .reversed()
                        .thenComparing(Comparator.comparingInt(PatternDirectionStats::totalSignals).reversed()))
                .forEach(this::printPatternDirectionStats);
    }

    private Map<CandlePattern, PatternStats> buildPatternStats(List<BacktestTrade> trades) {
        Map<CandlePattern, PatternStats> stats = new EnumMap<>(CandlePattern.class);
        for (BacktestTrade trade : trades) {
            stats.computeIfAbsent(trade.pattern(), PatternStats::new).add(trade);
        }
        return stats;
    }

    private Map<PatternDirection, PatternDirectionStats> buildPatternDirectionStats(List<BacktestTrade> trades) {
        Map<PatternDirection, PatternDirectionStats> stats = new java.util.HashMap<>();
        for (BacktestTrade trade : trades) {
            PatternDirection key = new PatternDirection(trade.pattern(), trade.tradeSignal());
            stats.computeIfAbsent(key, ignored -> new PatternDirectionStats(trade.pattern(), trade.tradeSignal()))
                    .add(trade);
        }
        return stats;
    }

    private void printPatternStats(PatternStats stats) {
        System.out.printf(
                "  %-22s signals=%4d success=%4d failed=%4d inconclusive=%4d precision=%6.2f%% avgScore=%6.2f avgReturn=%7.2f%%%n",
                stats.pattern(),
                stats.totalSignals(),
                stats.successfulSignals(),
                stats.failedSignals(),
                stats.inconclusiveSignals(),
                stats.precision(),
                stats.averageSetupScore(),
                stats.averageReturn()
        );
    }

    private void printPatternDirectionStats(PatternDirectionStats stats) {
        System.out.printf(
                "  %-22s %-4s signals=%4d success=%4d failed=%4d inconclusive=%4d precision=%6.2f%% avgScore=%6.2f avgReturn=%7.2f%%%n",
                stats.pattern(),
                stats.tradeSignal(),
                stats.totalSignals(),
                stats.successfulSignals(),
                stats.failedSignals(),
                stats.inconclusiveSignals(),
                stats.precision(),
                stats.averageSetupScore(),
                stats.averageReturn()
        );
    }

    private double precision(List<BacktestTrade> trades) {
        int success = countOutcome(trades, BacktestOutcome.SUCCESS);
        int failed = countOutcome(trades, BacktestOutcome.FAILURE);
        return percentage(success, success + failed);
    }

    private double averageReturn(List<BacktestTrade> trades) {
        return trades.stream()
                .mapToDouble(BacktestTrade::directionalReturnPercent)
                .average()
                .orElse(0.0);
    }

    private int countOutcome(List<BacktestTrade> trades, BacktestOutcome outcome) {
        return (int) trades.stream()
                .filter(trade -> trade.outcome() == outcome)
                .count();
    }

    private double percentage(int numerator, int denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return (numerator * 100.0) / denominator;
    }

    private record SymbolHistory(String symbol, List<Candle> candles) {
    }

    private record PatternDirection(CandlePattern pattern, TradeSignal tradeSignal) {
    }

    private static final class PatternStats {
        private final CandlePattern pattern;
        private int totalSignals;
        private int successfulSignals;
        private int failedSignals;
        private int inconclusiveSignals;
        private double setupScoreSum;
        private double returnSum;

        private PatternStats(CandlePattern pattern) {
            this.pattern = pattern;
        }

        private void add(BacktestTrade trade) {
            totalSignals++;
            setupScoreSum += trade.confidenceScore();
            returnSum += trade.directionalReturnPercent();
            if (trade.outcome() == BacktestOutcome.SUCCESS) {
                successfulSignals++;
            } else if (trade.outcome() == BacktestOutcome.FAILURE) {
                failedSignals++;
            } else {
                inconclusiveSignals++;
            }
        }

        private CandlePattern pattern() {
            return pattern;
        }

        private int totalSignals() {
            return totalSignals;
        }

        private int successfulSignals() {
            return successfulSignals;
        }

        private int failedSignals() {
            return failedSignals;
        }

        private int inconclusiveSignals() {
            return inconclusiveSignals;
        }

        private double precision() {
            return failedSignals + successfulSignals == 0
                    ? 0.0
                    : (successfulSignals * 100.0) / (successfulSignals + failedSignals);
        }

        private double averageSetupScore() {
            return totalSignals == 0 ? 0.0 : setupScoreSum / totalSignals;
        }

        private double averageReturn() {
            return totalSignals == 0 ? 0.0 : returnSum / totalSignals;
        }
    }

    private static final class PatternDirectionStats {
        private final CandlePattern pattern;
        private final TradeSignal tradeSignal;
        private final PatternStats stats;

        private PatternDirectionStats(CandlePattern pattern, TradeSignal tradeSignal) {
            this.pattern = pattern;
            this.tradeSignal = tradeSignal;
            this.stats = new PatternStats(pattern);
        }

        private void add(BacktestTrade trade) {
            stats.add(trade);
        }

        private CandlePattern pattern() {
            return pattern;
        }

        private TradeSignal tradeSignal() {
            return tradeSignal;
        }

        private int totalSignals() {
            return stats.totalSignals();
        }

        private int successfulSignals() {
            return stats.successfulSignals();
        }

        private int failedSignals() {
            return stats.failedSignals();
        }

        private int inconclusiveSignals() {
            return stats.inconclusiveSignals();
        }

        private double precision() {
            return stats.precision();
        }

        private double averageSetupScore() {
            return stats.averageSetupScore();
        }

        private double averageReturn() {
            return stats.averageReturn();
        }
    }
}
