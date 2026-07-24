package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestOutcome;
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
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@EnabledIfSystemProperty(named = "backtest.candlestick.scoring.enabled", matches = "true")
class HistoricalCandlestickScoringValidationTest {
    private static final String INTERVAL = "1d";
    private static final long VALIDATION_START = LocalDate.of(2025, 1, 1)
            .atStartOfDay()
            .toEpochSecond(ZoneOffset.UTC);
    private static final BacktestSettings SETTINGS = new BacktestSettings(250, 100, 10, 3.0);
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
    void printsTemporalValidationForStockOnlyCandlestickScore() {
        List<LabeledTrade> allTrades = new ArrayList<>();
        int sufficientSymbols = 0;
        int totalCandles = 0;
        long firstTimestamp = Long.MAX_VALUE;
        long lastTimestamp = Long.MIN_VALUE;
        for (String symbol : SYMBOLS) {
            List<Candle> candles = candleRepository
                    .findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, INTERVAL);
            if (candles.size() < SETTINGS.minimumHistoricalCandles() + SETTINGS.forwardCandles()) {
                continue;
            }
            sufficientSymbols++;
            totalCandles += candles.size();
            firstTimestamp = Math.min(firstTimestamp, candles.getFirst().getTimestamp());
            lastTimestamp = Math.max(lastTimestamp, candles.getLast().getTimestamp());
            backtestService.backtest(candles, SETTINGS).trades()
                    .forEach(trade -> allTrades.add(new LabeledTrade(symbol, trade)));
        }

        assertThat(sufficientSymbols).isEqualTo(SYMBOLS.size());
        assertThat(allTrades).isNotEmpty();

        List<LabeledTrade> development = allTrades.stream()
                .filter(trade -> trade.trade().signalTimestamp() < VALIDATION_START)
                .toList();
        List<LabeledTrade> validation = allTrades.stream()
                .filter(trade -> trade.trade().signalTimestamp() >= VALIDATION_START)
                .toList();

        System.out.println();
        System.out.println("=== Candlestick Stock-Only Score Temporal Validation ===");
        System.out.printf("Settings: signalCandles=%d, horizon=%d, move=%.1f%%, symbols=%d/%d%n",
                SETTINGS.signalCandles(),
                SETTINGS.forwardCandles(),
                SETTINGS.minimumMovePercent(),
                sufficientSymbols,
                SYMBOLS.size());
        System.out.printf(
                "Data: candles=%d span=%s through %s interval=%s%n",
                totalCandles,
                utcDate(firstTimestamp),
                utcDate(lastTimestamp),
                INTERVAL
        );
        printSegment("Development through 2024-12-31", development);
        printSegment("Validation from 2025-01-01", validation);
        System.out.println("=====================================================");
        System.out.println();
    }

    private void printSegment(String label, List<LabeledTrade> trades) {
        System.out.println();
        System.out.println(label + ":");
        printRow("All scores", trades, ignored -> true);
        printRow("Score 0-59", trades, trade -> trade.trade().confidenceScore() < 60);
        printRow("Score 60-69", trades, trade -> trade.trade().confidenceScore() >= 60
                && trade.trade().confidenceScore() < 70);
        printRow("Score 70-100", trades, trade -> trade.trade().confidenceScore() >= 70);
        printRow("Score 75-100", trades, trade -> trade.trade().confidenceScore() >= 75);
        printRow("Score 70-100 BUY", trades, trade -> trade.trade().confidenceScore() >= 70
                && trade.trade().tradeSignal() == TradeSignal.BUY);
        printRow("Score 70-100 SELL", trades, trade -> trade.trade().confidenceScore() >= 70
                && trade.trade().tradeSignal() == TradeSignal.SELL);
        printRow("Score 75-100 BUY", trades, trade -> trade.trade().confidenceScore() >= 75
                && trade.trade().tradeSignal() == TradeSignal.BUY);
        printRow("Score 75-100 SELL", trades, trade -> trade.trade().confidenceScore() >= 75
                && trade.trade().tradeSignal() == TradeSignal.SELL);
        printRow("Higher TF aligned", trades,
                trade -> componentPoints(trade.trade(), "Higher-timeframe alignment") > 0);
        printRow("Price location", trades,
                trade -> componentPoints(trade.trade(), "Price location") > 0);
        printRow("Momentum/volatility", trades,
                trade -> componentPoints(trade.trade(), "Volatility and momentum") > 0);
        Map<String, List<LabeledTrade>> highByPattern = trades.stream()
                .filter(trade -> trade.trade().confidenceScore() >= 70)
                .collect(Collectors.groupingBy(trade -> trade.trade().pattern().name()));
        highByPattern.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, List<LabeledTrade>> entry) -> entry.getValue().size())
                        .reversed())
                .forEach(entry -> printRow("70+ " + entry.getKey(), entry.getValue(), ignored -> true));
    }

    private void printRow(String label,
                          List<LabeledTrade> trades,
                          Predicate<LabeledTrade> predicate) {
        List<LabeledTrade> selected = trades.stream().filter(predicate).toList();
        int successes = count(selected, BacktestOutcome.SUCCESS);
        int failures = count(selected, BacktestOutcome.FAILURE);
        int inconclusive = count(selected, BacktestOutcome.INCONCLUSIVE);
        double averageReturn = selected.stream()
                .mapToDouble(trade -> trade.trade().directionalReturnPercent())
                .average()
                .orElse(0.0);
        System.out.printf("  %-22s signals=%4d success=%3d failed=%3d inconclusive=%3d precision=%6.2f%% avgReturn=%+7.2f%%%n",
                label,
                selected.size(),
                successes,
                failures,
                inconclusive,
                percentage(successes, successes + failures),
                averageReturn);
    }

    private double componentPoints(BacktestTrade trade, String category) {
        String prefix = category + " +";
        return trade.reasons().stream()
                .filter(reason -> reason.startsWith(prefix))
                .findFirst()
                .map(reason -> {
                    int slash = reason.indexOf('/', prefix.length());
                    if (slash < 0) {
                        return 0.0;
                    }
                    return Double.parseDouble(reason.substring(prefix.length(), slash));
                })
                .orElse(0.0);
    }

    private int count(List<LabeledTrade> trades, BacktestOutcome outcome) {
        return (int) trades.stream().filter(trade -> trade.trade().outcome() == outcome).count();
    }

    private double percentage(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }

    private LocalDate utcDate(long timestamp) {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private record LabeledTrade(String symbol, BacktestTrade trade) {
    }
}
