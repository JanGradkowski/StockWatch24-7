package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestOutcome;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestSettings;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestTrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Research harness for the experimental turn-confirmation score model (decision D2): it ships only if it
 * separates good from bad outcomes at least as well as the current model out of sample.
 *
 * <p>Run with {@code -Dbacktest.candlestick.turn.enabled=true}. Candles are read, read-only, from the
 * application's real schema ({@code -Dbacktest.source.schema}, default {@code public}); the test itself
 * runs in an isolated schema, so nothing in the real schema is written or migrated.</p>
 */
@SpringBootTest(properties = "alerts.schedule.enabled=false")
@EnabledIfSystemProperty(named = "backtest.candlestick.turn.enabled", matches = "true")
class CandlestickTurnConfirmationValidationTest {
    private static final long VALIDATION_START = LocalDate.of(2025, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    private static final BacktestSettings SETTINGS = new BacktestSettings(250, 100, 10, 3.0);
    private static final List<String> SYMBOLS = List.of(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "AMD", "ORCL",
            "CRM", "JPM", "BAC", "GS", "V", "MA", "XOM", "CVX", "COP", "JNJ",
            "UNH", "PFE", "LLY", "PG", "KO", "COST", "WMT", "HD", "CAT", "BA");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void printsOutOfSampleSeparationForTheCurrentAndTurnConfirmationModels() {
        String schema = System.getProperty("backtest.source.schema", "public");
        if (!schema.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("Invalid schema name.");
        var enrichment = new TechnicalIndicatorEnrichmentService();
        var current = new HistoricalSignalBacktestService(enrichment, new CandlePatternDetectionService());
        var turn = new HistoricalSignalBacktestService(enrichment, new CandlePatternDetectionService().withTurnConfirmation(true));
        List<BacktestTrade> currentTrades = new ArrayList<>();
        List<BacktestTrade> turnTrades = new ArrayList<>();
        int symbolsWithData = 0;
        for (String symbol : SYMBOLS) {
            List<Candle> candles = jdbc.query("select timestamp, open_price, high_price, low_price, close_price, volume from "
                            + schema + ".candles where symbol = ? and time_interval = '1d' order by timestamp",
                    (row, number) -> new Candle(symbol, "1d", row.getLong(1), row.getDouble(2), row.getDouble(3),
                            row.getDouble(4), row.getDouble(5), row.getLong(6)), symbol);
            if (candles.size() < SETTINGS.minimumHistoricalCandles() + SETTINGS.forwardCandles()) continue;
            symbolsWithData++;
            currentTrades.addAll(current.backtest(candles, SETTINGS).trades());
            turnTrades.addAll(turn.backtest(candles, SETTINGS).trades());
        }
        System.out.println();
        System.out.println("=== Candlestick score model comparison (" + symbolsWithData + " symbols) ===");
        for (boolean validation : List.of(false, true)) {
            Predicate<BacktestTrade> segment = trade -> validation == (trade.signalTimestamp() >= VALIDATION_START);
            System.out.println(validation ? "Validation from 2025-01-01:" : "Development through 2024-12-31:");
            print("  current", currentTrades.stream().filter(segment).toList());
            print("  turn   ", turnTrades.stream().filter(segment).toList());
        }
        System.out.println("Ship turn confirmation only if its validation separation is no worse than the current model.");
    }

    private void print(String label, List<BacktestTrade> trades) {
        System.out.printf("%s trades=%d spearman(score, return)=%.3f | 70+: %s | <60: %s%n", label, trades.size(),
                spearman(trades), band(trades, trade -> trade.confidenceScore() >= 70),
                band(trades, trade -> trade.confidenceScore() < 60));
    }

    private String band(List<BacktestTrade> trades, Predicate<BacktestTrade> filter) {
        List<BacktestTrade> selected = trades.stream().filter(filter).toList();
        long successes = selected.stream().filter(trade -> trade.outcome() == BacktestOutcome.SUCCESS).count();
        double average = selected.stream().mapToDouble(BacktestTrade::directionalReturnPercent).average().orElse(Double.NaN);
        return String.format("n=%d success=%.1f%% avgReturn=%.2f%%", selected.size(),
                selected.isEmpty() ? Double.NaN : successes * 100.0 / selected.size(), average);
    }

    /** Rank correlation between score and directional return; higher means scores order outcomes better. */
    static double spearman(List<BacktestTrade> trades) {
        int n = trades.size();
        if (n < 3) return Double.NaN;
        double[] scoreRanks = ranks(trades.stream().mapToDouble(BacktestTrade::confidenceScore).toArray());
        double[] returnRanks = ranks(trades.stream().mapToDouble(BacktestTrade::directionalReturnPercent).toArray());
        double meanScore = java.util.Arrays.stream(scoreRanks).average().orElse(0);
        double meanReturn = java.util.Arrays.stream(returnRanks).average().orElse(0);
        double covariance = 0, scoreVariance = 0, returnVariance = 0;
        for (int index = 0; index < n; index++) {
            double a = scoreRanks[index] - meanScore;
            double b = returnRanks[index] - meanReturn;
            covariance += a * b;
            scoreVariance += a * a;
            returnVariance += b * b;
        }
        return scoreVariance == 0 || returnVariance == 0 ? 0 : covariance / Math.sqrt(scoreVariance * returnVariance);
    }

    private static double[] ranks(double[] values) {
        Integer[] order = new Integer[values.length];
        for (int index = 0; index < values.length; index++) order[index] = index;
        java.util.Arrays.sort(order, Comparator.comparingDouble(index -> values[index]));
        double[] ranks = new double[values.length];
        for (int start = 0; start < order.length; ) {
            int end = start;
            while (end + 1 < order.length && values[order[end + 1]] == values[order[start]]) end++;
            double rank = (start + end) / 2.0 + 1;
            for (int index = start; index <= end; index++) ranks[order[index]] = rank;
            start = end + 1;
        }
        return ranks;
    }
}
