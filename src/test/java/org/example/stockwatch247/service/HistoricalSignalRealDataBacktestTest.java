package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestReport;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestSettings;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestOutcome;
import org.example.stockwatch247.service.HistoricalSignalBacktestService.BacktestTrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@EnabledIfSystemProperty(named = "backtest.real.enabled", matches = "true")
class HistoricalSignalRealDataBacktestTest {
    private static final String INTERVAL = "1d";
    private static final BacktestSettings SETTINGS = new BacktestSettings(250, 5, 5, 2.0);
    private static final List<String> REPRESENTATIVE_SYMBOLS = List.of(
            "AAPL",
            "MSFT",
            "NVDA",
            "AMZN",
            "GOOGL",
            "META",
            "TSLA",
            "AVGO",
            "AMD",
            "ORCL",
            "CRM",
            "JPM",
            "BAC",
            "GS",
            "V",
            "MA",
            "XOM",
            "CVX",
            "COP",
            "JNJ",
            "UNH",
            "PFE",
            "LLY",
            "PG",
            "KO",
            "COST",
            "WMT",
            "HD",
            "CAT",
            "BA"
    );

    @Autowired
    private MarketDataService marketDataService;

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private HistoricalSignalBacktestService backtestService;

    @Test
    void printsRepresentativeDailyBacktestReport() {
        List<BacktestReport> reports = new ArrayList<>();
        System.out.println();
        System.out.println("=== Representative Daily Signal Backtest ===");
        System.out.printf(
                "Settings: minimumHistory=%d, signalCandles=%d, forwardCandles=%d, minMove=%.2f%%%n",
                SETTINGS.minimumHistoricalCandles(),
                SETTINGS.signalCandles(),
                SETTINGS.forwardCandles(),
                SETTINGS.minimumMovePercent()
        );
        System.out.println("Outcome definition: BUY succeeds if close is >= minMove after the forward horizon; SELL succeeds if close is <= minMove lower.");
        System.out.println();

        int remoteSyncs = 0;
        for (int symbolIndex = 0; symbolIndex < REPRESENTATIVE_SYMBOLS.size(); symbolIndex++) {
            String symbol = REPRESENTATIVE_SYMBOLS.get(symbolIndex);
            if (shouldSync(symbol)) {
                if (remoteSyncs > 0 && remoteSyncs % 7 == 0) {
                    sleepForRateLimitWindow();
                }
                marketDataService.syncCandles(symbol, INTERVAL, null, true);
                remoteSyncs++;
            }
            List<Candle> candles = candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, INTERVAL);
            BacktestReport report = backtestService.backtest(candles, SETTINGS);
            reports.add(report);

            System.out.printf(
                    "%-5s candles=%4d analyzed=%4d signals=%3d buy=%3d sell=%3d success=%3d failed=%3d inconclusive=%3d successRate=%6.2f%% precision=%6.2f%% avgReturn=%7.2f%%%n",
                    symbol,
                    report.totalCandles(),
                    report.analyzedCandles(),
                    report.totalSignals(),
                    report.buySignals(),
                    report.sellSignals(),
                    report.successfulSignals(),
                    report.failedSignals(),
                    report.inconclusiveSignals(),
                    report.successRatePercent(),
                    report.precisionPercent(),
                    report.averageDirectionalReturnPercent()
            );

            List<BacktestTrade> highConfidenceTrades = highConfidenceTrades(report);
            System.out.printf(
                    "      highConfidence signals=%3d success=%3d failed=%3d inconclusive=%3d successRate=%6.2f%% precision=%6.2f%%%n",
                    highConfidenceTrades.size(),
                    countOutcome(highConfidenceTrades, BacktestOutcome.SUCCESS),
                    countOutcome(highConfidenceTrades, BacktestOutcome.FAILURE),
                    countOutcome(highConfidenceTrades, BacktestOutcome.INCONCLUSIVE),
                    percentage(countOutcome(highConfidenceTrades, BacktestOutcome.SUCCESS), highConfidenceTrades.size()),
                    percentage(
                            countOutcome(highConfidenceTrades, BacktestOutcome.SUCCESS),
                            countOutcome(highConfidenceTrades, BacktestOutcome.SUCCESS)
                                    + countOutcome(highConfidenceTrades, BacktestOutcome.FAILURE)
                    )
            );
        }

        int totalCandles = reports.stream().mapToInt(BacktestReport::totalCandles).sum();
        int analyzedCandles = reports.stream().mapToInt(BacktestReport::analyzedCandles).sum();
        int totalSignals = reports.stream().mapToInt(BacktestReport::totalSignals).sum();
        int buySignals = reports.stream().mapToInt(BacktestReport::buySignals).sum();
        int sellSignals = reports.stream().mapToInt(BacktestReport::sellSignals).sum();
        int successfulSignals = reports.stream().mapToInt(BacktestReport::successfulSignals).sum();
        int failedSignals = reports.stream().mapToInt(BacktestReport::failedSignals).sum();
        int inconclusiveSignals = reports.stream().mapToInt(BacktestReport::inconclusiveSignals).sum();
        double averageReturn = reports.stream()
                .flatMap(report -> report.trades().stream())
                .mapToDouble(HistoricalSignalBacktestService.BacktestTrade::directionalReturnPercent)
                .average()
                .orElse(0.0);
        List<BacktestTrade> highConfidenceTrades = reports.stream()
                .flatMap(report -> highConfidenceTrades(report).stream())
                .toList();
        List<BacktestTrade> allTrades = reports.stream()
                .flatMap(report -> report.trades().stream())
                .toList();

        System.out.println();
        System.out.printf(
                "TOTAL candles=%d analyzed=%d signals=%d buy=%d sell=%d success=%d failed=%d inconclusive=%d successRate=%6.2f%% precision=%6.2f%% avgReturn=%7.2f%%%n",
                totalCandles,
                analyzedCandles,
                totalSignals,
                buySignals,
                sellSignals,
                successfulSignals,
                failedSignals,
                inconclusiveSignals,
                percentage(successfulSignals, totalSignals),
                percentage(successfulSignals, successfulSignals + failedSignals),
                averageReturn
        );
        System.out.printf(
                "HIGH CONFIDENCE TOTAL signals=%d success=%d failed=%d inconclusive=%d successRate=%6.2f%% precision=%6.2f%%%n",
                highConfidenceTrades.size(),
                countOutcome(highConfidenceTrades, BacktestOutcome.SUCCESS),
                countOutcome(highConfidenceTrades, BacktestOutcome.FAILURE),
                countOutcome(highConfidenceTrades, BacktestOutcome.INCONCLUSIVE),
                percentage(countOutcome(highConfidenceTrades, BacktestOutcome.SUCCESS), highConfidenceTrades.size()),
                percentage(
                        countOutcome(highConfidenceTrades, BacktestOutcome.SUCCESS),
                        countOutcome(highConfidenceTrades, BacktestOutcome.SUCCESS)
                                + countOutcome(highConfidenceTrades, BacktestOutcome.FAILURE)
                )
        );
        printConfidenceBuckets(allTrades);
        System.out.println("============================================");
        System.out.println();
    }

    private List<BacktestTrade> highConfidenceTrades(BacktestReport report) {
        return report.trades().stream()
                .filter(trade -> trade.confidenceScore() >= 80)
                .toList();
    }

    private int countOutcome(List<BacktestTrade> trades, BacktestOutcome outcome) {
        return (int) trades.stream()
                .filter(trade -> trade.outcome() == outcome)
                .count();
    }

    private void printConfidenceBuckets(List<BacktestTrade> trades) {
        System.out.println();
        System.out.println("Confidence bucket breakdown:");
        printConfidenceBucket("65-69", trades, 65, 69);
        printConfidenceBucket("70-74", trades, 70, 74);
        printConfidenceBucket("75-79", trades, 75, 79);
        printConfidenceBucket("80-84", trades, 80, 84);
        printConfidenceBucket("85-89", trades, 85, 89);
        printConfidenceBucket("90-94", trades, 90, 94);
        printConfidenceBucket("95-100", trades, 95, 100);
    }

    private void printConfidenceBucket(String label, List<BacktestTrade> trades, int minInclusive, int maxInclusive) {
        List<BacktestTrade> bucket = trades.stream()
                .filter(trade -> trade.confidenceScore() >= minInclusive)
                .filter(trade -> trade.confidenceScore() <= maxInclusive)
                .toList();
        int success = countOutcome(bucket, BacktestOutcome.SUCCESS);
        int failed = countOutcome(bucket, BacktestOutcome.FAILURE);
        int inconclusive = countOutcome(bucket, BacktestOutcome.INCONCLUSIVE);
        System.out.printf(
                "  %-6s signals=%4d success=%4d failed=%4d inconclusive=%4d successRate=%6.2f%% precision=%6.2f%%%n",
                label,
                bucket.size(),
                success,
                failed,
                inconclusive,
                percentage(success, bucket.size()),
                percentage(success, success + failed)
        );
    }

    private double percentage(int numerator, int denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return (numerator * 100.0) / denominator;
    }

    private boolean shouldSync(String symbol) {
        return candleRepository.findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, INTERVAL).size()
                < SETTINGS.minimumHistoricalCandles() + SETTINGS.forwardCandles();
    }

    private void sleepForRateLimitWindow() {
        try {
            Thread.sleep(65_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Twelve Data rate limit window.", e);
        }
    }
}
