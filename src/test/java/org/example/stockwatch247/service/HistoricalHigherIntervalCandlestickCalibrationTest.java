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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@EnabledIfSystemProperty(named = "backtest.candlestick.higher.calibration.enabled", matches = "true")
class HistoricalHigherIntervalCandlestickCalibrationTest {
    private static final long VALIDATION_START = LocalDate.of(2020, 1, 1)
            .atStartOfDay()
            .toEpochSecond(ZoneOffset.UTC);
    private static final double PRIOR_ACTIONABLE_TRADES = 30.0;
    private static final double PRIOR_RETURN_TRADES = 30.0;
    private static final List<String> SYMBOLS = List.of(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AVGO", "AMD", "ORCL",
            "CRM", "JPM", "BAC", "GS", "V", "MA", "XOM", "CVX", "COP", "JNJ",
            "UNH", "PFE", "LLY", "PG", "KO", "COST", "WMT", "HD", "CAT", "BA"
    );
    private static final List<CalibrationRun> RUNS = List.of(
            new CalibrationRun("1wk", "Weekly 4 / 4%", new BacktestSettings(80, 100, 4, 4.0)),
            new CalibrationRun("1wk", "Weekly 8 / 8%", new BacktestSettings(80, 100, 8, 8.0)),
            new CalibrationRun("1wk", "Weekly 12 / 12%", new BacktestSettings(80, 100, 12, 12.0)),
            new CalibrationRun("1mo", "Monthly 3 / 6%", new BacktestSettings(36, 100, 3, 6.0)),
            new CalibrationRun("1mo", "Monthly 6 / 12%", new BacktestSettings(36, 100, 6, 12.0)),
            new CalibrationRun("1mo", "Monthly 9 / 18%", new BacktestSettings(36, 100, 9, 18.0))
    );

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private HistoricalSignalBacktestService backtestService;

    @Test
    void printsShrunkPerPatternDevelopmentAndValidationResults() {
        System.out.println();
        System.out.println("=== Candlestick V4 Higher-Interval Diagnostic ===");
        System.out.println("Development ends 2019-12-31; validation starts 2020-01-01.");
        System.out.println("Elliott Wave remains a separate detector and does not modify candlestick setup scores.");

        for (CalibrationRun run : RUNS) {
            List<LabeledTrade> trades = new ArrayList<>();
            int sufficientSymbols = 0;
            for (String symbol : SYMBOLS) {
                List<Candle> candles = candleRepository
                        .findBySymbolAndTimeIntervalOrderByTimestampAsc(symbol, run.interval());
                if (candles.size() < run.settings().minimumHistoricalCandles() + run.settings().forwardCandles()) {
                    continue;
                }
                sufficientSymbols++;
                BacktestReport v4 = backtestService.backtest(candles, run.settings(), false);
                v4.trades().forEach(trade -> trades.add(new LabeledTrade(symbol, trade)));
            }

            assertThat(sufficientSymbols).isEqualTo(SYMBOLS.size());
            assertThat(trades).isNotEmpty();
            printRun(run, trades, sufficientSymbols);
        }

        System.out.println("=========================================================");
        System.out.println();
    }

    private void printRun(CalibrationRun run, List<LabeledTrade> trades, int sufficientSymbols) {
        List<LabeledTrade> development = trades.stream()
                .filter(trade -> trade.trade().signalTimestamp() < VALIDATION_START)
                .toList();
        List<LabeledTrade> validation = trades.stream()
                .filter(trade -> trade.trade().signalTimestamp() >= VALIDATION_START)
                .toList();
        Map<TradeSignal, Stats> directionBaselines = statsByDirection(development);
        Map<PatternDirection, Stats> developmentByPattern = statsByPattern(development);
        Map<PatternDirection, Stats> validationByPattern = statsByPattern(validation);

        System.out.println();
        System.out.printf("%s interval=%s symbols=%d total=%d development=%d validation=%d%n",
                run.label(), run.interval(), sufficientSymbols, trades.size(), development.size(), validation.size());
        printSummary("Development", development);
        printSummary("Validation", validation);
        printScoreCohorts("Development", development);
        printScoreCohorts("Validation", validation);
        System.out.println("  Pattern                  Side  dev n  act  rawP  shrunkP   lift  devRet | val n  act   valP   valRet");

        developmentByPattern.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<PatternDirection, Stats> entry) -> entry.getKey().tradeSignal())
                        .thenComparing((Map.Entry<PatternDirection, Stats> entry) ->
                                shrunkPrecision(entry.getValue(), directionBaselines.get(entry.getKey().tradeSignal())))
                        .reversed())
                .forEach(entry -> printPatternRow(
                        entry.getKey(),
                        entry.getValue(),
                        validationByPattern.getOrDefault(entry.getKey(), new Stats()),
                        directionBaselines.get(entry.getKey().tradeSignal())
                ));
    }

    private void printSummary(String label, List<LabeledTrade> trades) {
        Stats stats = new Stats();
        trades.forEach(trade -> stats.add(trade.trade()));
        System.out.printf("  %-11s n=%4d actionable=%4d precision=%6.2f%% avgReturn=%+7.2f%%%n",
                label, stats.total(), stats.actionable(), stats.precision(), stats.averageReturn());
    }

    private void printScoreCohorts(String label, List<LabeledTrade> trades) {
        for (int threshold : List.of(60, 65, 70, 75)) {
            Stats stats = new Stats();
            trades.stream()
                    .map(LabeledTrade::trade)
                    .filter(trade -> trade.confidenceScore() >= threshold)
                    .forEach(stats::add);
            System.out.printf("    %-11s score>=%d n=%4d actionable=%3d precision=%6.2f%% avgReturn=%+7.2f%%%n",
                    label,
                    threshold,
                    stats.total(),
                    stats.actionable(),
                    stats.precision(),
                    stats.averageReturn());
        }
    }

    private void printPatternRow(PatternDirection key,
                                 Stats development,
                                 Stats validation,
                                 Stats directionBaseline) {
        double shrunkPrecision = shrunkPrecision(development, directionBaseline);
        double precisionLift = shrunkPrecision - directionBaseline.precision();
        double shrunkReturn = shrunkReturn(development, directionBaseline);
        System.out.printf(
                "  %-24s %-4s %6d %4d %5.1f%% %7.1f%% %+6.1f %7.2f%% | %5d %4d %6.1f%% %+8.2f%%%n",
                key.pattern(),
                key.tradeSignal(),
                development.total(),
                development.actionable(),
                development.precision(),
                shrunkPrecision,
                precisionLift,
                shrunkReturn,
                validation.total(),
                validation.actionable(),
                validation.precision(),
                validation.averageReturn()
        );
    }

    private Map<TradeSignal, Stats> statsByDirection(List<LabeledTrade> trades) {
        Map<TradeSignal, Stats> result = new HashMap<>();
        for (LabeledTrade labeledTrade : trades) {
            BacktestTrade trade = labeledTrade.trade();
            result.computeIfAbsent(trade.tradeSignal(), ignored -> new Stats()).add(trade);
        }
        return result;
    }

    private Map<PatternDirection, Stats> statsByPattern(List<LabeledTrade> trades) {
        Map<PatternDirection, Stats> result = new HashMap<>();
        for (LabeledTrade labeledTrade : trades) {
            BacktestTrade trade = labeledTrade.trade();
            PatternDirection key = new PatternDirection(trade.pattern(), trade.tradeSignal());
            result.computeIfAbsent(key, ignored -> new Stats()).add(trade);
        }
        return result;
    }

    private double shrunkPrecision(Stats pattern, Stats directionBaseline) {
        if (directionBaseline == null || directionBaseline.actionable() == 0) {
            return 50.0;
        }
        double priorSuccesses = PRIOR_ACTIONABLE_TRADES * directionBaseline.precision() / 100.0;
        return (pattern.successes() + priorSuccesses)
                * 100.0 / (pattern.actionable() + PRIOR_ACTIONABLE_TRADES);
    }

    private double shrunkReturn(Stats pattern, Stats directionBaseline) {
        if (directionBaseline == null || directionBaseline.total() == 0) {
            return pattern.averageReturn();
        }
        return (pattern.returnSum() + PRIOR_RETURN_TRADES * directionBaseline.averageReturn())
                / (pattern.total() + PRIOR_RETURN_TRADES);
    }

    private record CalibrationRun(String interval, String label, BacktestSettings settings) {
    }

    private record LabeledTrade(String symbol, BacktestTrade trade) {
    }

    private record PatternDirection(CandlePattern pattern, TradeSignal tradeSignal) {
    }

    private static final class Stats {
        private int total;
        private int successes;
        private int failures;
        private double returnSum;

        private void add(BacktestTrade trade) {
            total++;
            returnSum += trade.directionalReturnPercent();
            if (trade.outcome() == BacktestOutcome.SUCCESS) {
                successes++;
            } else if (trade.outcome() == BacktestOutcome.FAILURE) {
                failures++;
            }
        }

        private int total() {
            return total;
        }

        private int successes() {
            return successes;
        }

        private int actionable() {
            return successes + failures;
        }

        private double precision() {
            return actionable() == 0 ? 0.0 : successes * 100.0 / actionable();
        }

        private double averageReturn() {
            return total == 0 ? 0.0 : returnSum / total;
        }

        private double returnSum() {
            return returnSum;
        }
    }
}
