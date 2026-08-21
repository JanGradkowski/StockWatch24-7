package org.example.stockwatch247.service.technical;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.service.CandlePatternDetectionService;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.TechnicalIndicatorEnrichmentService;
import org.example.stockwatch247.service.technical.Ta4jPatternPerformanceResearchService.EvaluatedTrade;
import org.example.stockwatch247.service.technical.Ta4jPatternPerformanceResearchService.IntervalReport;
import org.example.stockwatch247.service.technical.Ta4jPatternPerformanceResearchService.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opt-in research run over locally cached market data. It performs no remote
 * sync and makes no production configuration changes.
 *
 * Run with: mvnw.cmd -Dbacktest.ta4j.patterns.enabled=true
 * -Dtest=Ta4jPatternMultiIntervalRealDataBacktestTest test
 */
@SpringBootTest(properties = "alerts.schedule.enabled=false")
@EnabledIfSystemProperty(named = "backtest.ta4j.patterns.enabled", matches = "true")
class Ta4jPatternMultiIntervalRealDataBacktestTest {
    private static final List<String> SYMBOLS = List.of(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "JPM", "XOM", "JNJ"
    );
    private static final List<IntervalSpec> INTERVALS = List.of(
            new IntervalSpec(TimeInterval.DAILY, "1d", 250, 120, List.of(1, 3, 5, 10)),
            new IntervalSpec(TimeInterval.WEEKLY, "1wk", 80, 100, List.of(1, 2, 4, 8)),
            new IntervalSpec(TimeInterval.MONTHLY, "1mo", 36, 80, List.of(1, 2, 3, 6))
    );

    @Autowired
    private CandleRepository candleRepository;

    @Test
    void printsCostAdjustedCandlestickAndElliottResultsByNativeInterval() {
        Ta4jPatternPerformanceResearchService service = new Ta4jPatternPerformanceResearchService(
                new TechnicalIndicatorEnrichmentService(),
                new CandlePatternDetectionService(),
                new ElliottWaveDetectionService()
        );

        System.out.println();
        System.out.println("=== TA4J StockWatch Pattern Performance (native intervals) ===");
        System.out.println(Ta4jPatternPerformanceResearchService.BOUNDARY_NOTICE);
        System.out.println("Execution: next-bar open; exit: Nth bar close; transaction cost=5 bps/side; slippage=2 bps/side.");

        for (IntervalSpec spec : INTERVALS) {
            Map<Integer, List<EvaluatedTrade>> tradesByHorizon = new LinkedHashMap<>();
            spec.horizons().forEach(horizon -> tradesByHorizon.put(horizon, new ArrayList<>()));
            int symbolsWithHistory = 0;

            for (String symbol : SYMBOLS) {
                List<Candle> candles = candleRepository
                        .findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, spec.persistenceInterval());
                if (candles.size() < spec.minimumHistory() + 1) continue;
                symbolsWithHistory++;
                IntervalReport report = service.evaluateInterval(
                        spec.interval(),
                        candles,
                        new Settings(spec.minimumHistory(), spec.signalWindow(), spec.horizons(),
                                5.0, 2.0, true, true)
                );
                report.horizons().forEach((horizon, result) ->
                        tradesByHorizon.get(horizon).addAll(result.trades()));
            }

            System.out.printf("%n%s (%s): symbolsWithHistory=%d/%d%n",
                    spec.interval(), spec.persistenceInterval(), symbolsWithHistory, SYMBOLS.size());
            tradesByHorizon.forEach((horizon, trades) -> printSummary(horizon, trades));
        }
        System.out.println("=============================================================");
        System.out.println();
    }

    private void printSummary(int horizon, List<EvaluatedTrade> trades) {
        List<EvaluatedTrade> candlesticks = trades.stream()
                .filter(trade -> trade.family().name().equals("CANDLESTICK"))
                .toList();
        List<EvaluatedTrade> elliott = trades.stream()
                .filter(trade -> trade.family().name().equals("ELLIOTT_WAVE"))
                .toList();
        printSlice(horizon, "ALL", trades);
        printSlice(horizon, "CANDLE", candlesticks);
        printSlice(horizon, "ELLIOTT", elliott);
        printSlice(horizon, "SCORE>=70", trades.stream()
                .filter(trade -> trade.setupScore() >= 70).toList());
        printSlice(horizon, "SCORE>=85", trades.stream()
                .filter(trade -> trade.setupScore() >= 85).toList());
    }

    private void printSlice(int horizon, String label, List<EvaluatedTrade> trades) {
        int wins = (int) trades.stream().filter(trade -> trade.netReturnPercent() > 0.0).count();
        double average = trades.stream().mapToDouble(EvaluatedTrade::netReturnPercent).average().orElse(0.0);
        System.out.printf("  horizon=%2d %-9s trades=%4d wins=%4d winRate=%6.2f%% avgNet=%7.3f%%%n",
                horizon, label, trades.size(), wins,
                trades.isEmpty() ? 0.0 : wins * 100.0 / trades.size(), average);
    }

    private record IntervalSpec(TimeInterval interval,
                                String persistenceInterval,
                                int minimumHistory,
                                int signalWindow,
                                List<Integer> horizons) {
    }
}
