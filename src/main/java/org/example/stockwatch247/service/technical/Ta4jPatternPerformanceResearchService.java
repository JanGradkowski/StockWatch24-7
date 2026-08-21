package org.example.stockwatch247.service.technical;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.TechnicalIndicatorEnrichmentService;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseTradingRecord;
import org.ta4j.core.Position;
import org.ta4j.core.Trade;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.analysis.cost.LinearTransactionCostModel;
import org.ta4j.core.analysis.cost.ZeroCostModel;
import org.ta4j.core.criteria.drawdown.MaximumDrawdownCriterion;
import org.ta4j.core.criteria.pnl.NetReturnCriterion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Offline evaluation of signals emitted by StockWatch detectors.
 *
 * <p>TA4J is deliberately limited to execution accounting and performance
 * criteria here. Candlestick and Elliott detection, setup scores, reasons,
 * alert eligibility, and every production decision remain application-owned.</p>
 */
public final class Ta4jPatternPerformanceResearchService {
    public static final String BOUNDARY_NOTICE =
            "Research only: StockWatch detects and scores every pattern; TA4J only evaluates execution and returns.";

    private final TechnicalIndicatorEnrichmentService enrichmentService;
    private final CandlePatternDetectionService candlestickDetector;
    private final ElliottWaveDetectionService elliottDetector;
    private final Ta4jBarSeriesFactory seriesFactory;

    public Ta4jPatternPerformanceResearchService() {
        this(new TechnicalIndicatorEnrichmentService(), new CandlePatternDetectionService(),
                new ElliottWaveDetectionService(), new Ta4jBarSeriesFactory());
    }

    public Ta4jPatternPerformanceResearchService(TechnicalIndicatorEnrichmentService enrichmentService,
                                                  CandlePatternDetectionService candlestickDetector,
                                                  ElliottWaveDetectionService elliottDetector) {
        this(enrichmentService, candlestickDetector, elliottDetector, new Ta4jBarSeriesFactory());
    }

    Ta4jPatternPerformanceResearchService(TechnicalIndicatorEnrichmentService enrichmentService,
                                           CandlePatternDetectionService candlestickDetector,
                                           ElliottWaveDetectionService elliottDetector,
                                           Ta4jBarSeriesFactory seriesFactory) {
        this.enrichmentService = enrichmentService;
        this.candlestickDetector = candlestickDetector;
        this.elliottDetector = elliottDetector;
        this.seriesFactory = seriesFactory;
    }

    public CrossIntervalReport evaluate(Map<TimeInterval, List<Candle>> candlesByInterval,
                                        Settings settings) {
        Settings effectiveSettings = settings == null ? Settings.defaults() : settings.validated();
        if (candlesByInterval == null || candlesByInterval.isEmpty()) {
            return new CrossIntervalReport(effectiveSettings, Map.of(), BOUNDARY_NOTICE);
        }

        Map<TimeInterval, IntervalReport> reports = new EnumMap<>(TimeInterval.class);
        candlesByInterval.forEach((interval, candles) -> {
            if (interval == null) {
                throw new IllegalArgumentException("An interval is required for every candle series.");
            }
            reports.put(interval, evaluateInterval(interval, candles, effectiveSettings));
        });
        return new CrossIntervalReport(effectiveSettings, reports, BOUNDARY_NOTICE);
    }

    public IntervalReport evaluateInterval(TimeInterval interval,
                                           List<Candle> historicalCandles,
                                           Settings settings) {
        if (interval == null) {
            throw new IllegalArgumentException("interval is required.");
        }
        Settings effectiveSettings = settings == null ? Settings.defaults() : settings.validated();
        List<Candle> candles = normalizeCandles(interval, historicalCandles);
        if (candles.size() < effectiveSettings.minimumHistoricalCandles() + 1) {
            return new IntervalReport(interval, candles.size(), 0, Map.of(), BOUNDARY_NOTICE);
        }

        BarSeries series = seriesFactory.create(candles);
        List<ObservedSignal> signals = detectCausally(interval, candles, effectiveSettings);
        Map<Integer, HorizonReport> horizonReports = new LinkedHashMap<>();
        effectiveSettings.holdingBars().forEach(holdingBars -> horizonReports.put(
                holdingBars,
                evaluateHorizon(series, candles, signals, holdingBars, effectiveSettings)
        ));
        return new IntervalReport(interval, candles.size(), signals.size(), horizonReports, BOUNDARY_NOTICE);
    }

    private List<ObservedSignal> detectCausally(TimeInterval interval,
                                                List<Candle> candles,
                                                Settings settings) {
        List<EnrichedCandle> candlestickHistory = settings.includeCandlesticks()
                ? enrichmentService.enrich(candles, candles.size()) : List.of();
        List<EnrichedCandle> elliottHistory = settings.includeElliottWaves()
                ? enrichmentService.enrichForElliott(candles, candles.size(), interval) : List.of();
        List<ObservedSignal> observations = new ArrayList<>();

        for (int signalIndex = settings.minimumHistoricalCandles() - 1;
             signalIndex < candles.size() - 1;
             signalIndex++) {
            int from = Math.max(0, signalIndex - settings.signalWindowCandles() + 1);
            long confirmationTimestamp = candles.get(signalIndex).getTimestamp();

            if (settings.includeCandlesticks()) {
                List<EnrichedCandle> prefix = candlestickHistory.subList(from, signalIndex + 1);
                addCurrentSignals(observations,
                        candlestickDetector.detectFactory(prefix, interval),
                        AlertPatternFamily.CANDLESTICK, interval, signalIndex, confirmationTimestamp);
            }
            if (settings.includeElliottWaves()) {
                List<EnrichedCandle> prefix = elliottHistory.subList(from, signalIndex + 1);
                addCurrentSignals(observations,
                        elliottDetector.detect(prefix),
                        AlertPatternFamily.ELLIOTT_WAVE, interval, signalIndex, confirmationTimestamp);
            }
        }
        return List.copyOf(observations);
    }

    private void addCurrentSignals(List<ObservedSignal> observations,
                                   List<DetectedSignal> detectedSignals,
                                   AlertPatternFamily family,
                                   TimeInterval interval,
                                   int confirmationIndex,
                                   long confirmationTimestamp) {
        if (detectedSignals == null) return;
        detectedSignals.stream()
                .filter(signal -> signal != null
                        && (signal.tradeSignal() == TradeSignal.BUY || signal.tradeSignal() == TradeSignal.SELL))
                .filter(signal -> signal.candleTimestamp() != null
                        && signal.candleTimestamp() == confirmationTimestamp)
                .forEach(signal -> observations.add(new ObservedSignal(
                        family,
                        signal.pattern(),
                        signal.tradeSignal(),
                        signal.setupScore(),
                        interval,
                        confirmationTimestamp,
                        confirmationIndex
                )));
    }

    private HorizonReport evaluateHorizon(BarSeries series,
                                           List<Candle> candles,
                                           List<ObservedSignal> signals,
                                           int holdingBars,
                                           Settings settings) {
        List<EvaluatedTrade> trades = signals.stream()
                .filter(signal -> signal.confirmationIndex() + holdingBars < candles.size())
                .map(signal -> evaluateTrade(series, candles, signal, holdingBars, settings))
                .toList();

        return new HorizonReport(
                holdingBars,
                summarize(series, "ALL", trades),
                grouped(series, trades, trade -> trade.family().name()),
                grouped(series, trades, trade -> trade.pattern().name()),
                grouped(series, trades, trade -> trade.direction().name()),
                grouped(series, trades, trade -> scoreBand(trade.setupScore())),
                trades
        );
    }

    private EvaluatedTrade evaluateTrade(BarSeries series,
                                         List<Candle> candles,
                                         ObservedSignal signal,
                                         int holdingBars,
                                         Settings settings) {
        int entryIndex = signal.confirmationIndex() + 1;
        int exitIndex = entryIndex + holdingBars - 1;
        double rawEntry = candles.get(entryIndex).getOpenPrice();
        double rawExit = candles.get(exitIndex).getClosePrice();
        double slippage = settings.slippageBps() / 10_000.0;
        boolean buy = signal.direction() == TradeSignal.BUY;
        double executedEntry = buy ? rawEntry * (1.0 + slippage) : rawEntry * (1.0 - slippage);
        double executedExit = buy ? rawExit * (1.0 - slippage) : rawExit * (1.0 + slippage);

        LinearTransactionCostModel transactionCosts =
                new LinearTransactionCostModel(settings.transactionCostBps() / 10_000.0);
        Position position = new Position(
                buy ? Trade.TradeType.BUY : Trade.TradeType.SELL,
                transactionCosts,
                new ZeroCostModel()
        );
        position.operate(entryIndex, series.numFactory().numOf(executedEntry), series.numFactory().one());
        position.operate(exitIndex, series.numFactory().numOf(executedExit), series.numFactory().one());
        double grossReturnPercent = directionalReturnPercent(signal.direction(), rawEntry, rawExit);
        double netReturnPercent = (new NetReturnCriterion().calculate(series, position).doubleValue() - 1.0) * 100.0;

        return new EvaluatedTrade(
                signal.family(), signal.pattern(), signal.direction(), signal.setupScore(), signal.interval(),
                signal.confirmationTimestamp(), candles.get(entryIndex).getTimestamp(),
                candles.get(exitIndex).getTimestamp(), entryIndex, exitIndex,
                rawEntry, rawExit, executedEntry, executedExit,
                grossReturnPercent, netReturnPercent, position
        );
    }

    private Map<String, PerformanceSlice> grouped(BarSeries series,
                                                   List<EvaluatedTrade> trades,
                                                   Function<EvaluatedTrade, String> classifier) {
        return trades.stream().collect(Collectors.groupingBy(
                        classifier,
                        LinkedHashMap::new,
                        Collectors.toList()))
                .entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> summarize(series, entry.getKey(), entry.getValue()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private PerformanceSlice summarize(BarSeries series,
                                       String key,
                                       List<EvaluatedTrade> trades) {
        if (trades.isEmpty()) {
            return new PerformanceSlice(key, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        List<Position> positions = trades.stream().map(EvaluatedTrade::ta4jPosition).toList();
        TradingRecord record = new BaseTradingRecord(positions);
        int winners = (int) trades.stream().filter(trade -> trade.netReturnPercent() > 0.0).count();
        int losers = (int) trades.stream().filter(trade -> trade.netReturnPercent() < 0.0).count();
        int breakeven = trades.size() - winners - losers;
        double average = trades.stream().mapToDouble(EvaluatedTrade::netReturnPercent).average().orElse(0.0);
        double median = median(trades.stream().mapToDouble(EvaluatedTrade::netReturnPercent).sorted().toArray());
        double compoundedNetReturn =
                (new NetReturnCriterion().calculate(series, record).doubleValue() - 1.0) * 100.0;
        double maximumDrawdown =
                new MaximumDrawdownCriterion().calculate(series, record).doubleValue() * 100.0;
        return new PerformanceSlice(key, trades.size(), winners, losers, breakeven,
                percentage(winners, trades.size()), average, median, compoundedNetReturn, maximumDrawdown);
    }

    private List<Candle> normalizeCandles(TimeInterval interval, List<Candle> historicalCandles) {
        List<Candle> candles = historicalCandles == null ? List.of() : historicalCandles.stream()
                .filter(this::hasCompletePriceData)
                .sorted(Comparator.comparing(Candle::getTimestamp))
                .toList();
        long distinctTimestamps = candles.stream().map(Candle::getTimestamp).distinct().count();
        if (distinctTimestamps != candles.size()) {
            throw new IllegalArgumentException("Candle timestamps must be unique within an interval.");
        }
        for (Candle candle : candles) {
            TimeInterval candleInterval = parseInterval(candle.getTimeInterval());
            if (candleInterval != interval) {
                throw new IllegalArgumentException("Candle interval " + candle.getTimeInterval()
                        + " does not match requested interval " + interval + ".");
            }
        }
        return candles;
    }

    private TimeInterval parseInterval(String interval) {
        if (interval == null) throw new IllegalArgumentException("Candle time interval is required.");
        return switch (interval.trim().toLowerCase()) {
            case "15m", "15min" -> TimeInterval.FIFTEEN_MINUTE;
            case "1h", "60m", "60min" -> TimeInterval.ONE_HOUR;
            case "4h", "240m", "240min" -> TimeInterval.FOUR_HOUR;
            case "1d", "daily" -> TimeInterval.DAILY;
            case "1wk", "1w", "weekly" -> TimeInterval.WEEKLY;
            case "1mo", "monthly" -> TimeInterval.MONTHLY;
            case "1y", "yearly" -> TimeInterval.YEARLY;
            default -> throw new IllegalArgumentException("Unsupported candle interval: " + interval);
        };
    }

    private boolean hasCompletePriceData(Candle candle) {
        return candle != null
                && candle.getTimestamp() != null
                && candle.getOpenPrice() != null && candle.getOpenPrice() > 0.0
                && candle.getHighPrice() != null && candle.getHighPrice() > 0.0
                && candle.getLowPrice() != null && candle.getLowPrice() > 0.0
                && candle.getClosePrice() != null && candle.getClosePrice() > 0.0;
    }

    private double directionalReturnPercent(TradeSignal direction, double entry, double exit) {
        double move = ((exit - entry) / entry) * 100.0;
        return direction == TradeSignal.BUY ? move : -move;
    }

    private String scoreBand(int score) {
        if (score < 50) return "0-49";
        if (score < 70) return "50-69";
        if (score < 85) return "70-84";
        return "85+";
    }

    private double percentage(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }

    private double median(double[] sortedValues) {
        if (sortedValues.length == 0) return 0.0;
        int middle = sortedValues.length / 2;
        return sortedValues.length % 2 == 0
                ? (sortedValues[middle - 1] + sortedValues[middle]) / 2.0
                : sortedValues[middle];
    }

    public record Settings(int minimumHistoricalCandles,
                           int signalWindowCandles,
                           List<Integer> holdingBars,
                           double transactionCostBps,
                           double slippageBps,
                           boolean includeCandlesticks,
                           boolean includeElliottWaves) {
        public Settings {
            holdingBars = holdingBars == null ? List.of() : List.copyOf(holdingBars);
        }

        public static Settings defaults() {
            return new Settings(60, 120, List.of(1, 3, 5, 10), 5.0, 2.0, true, true);
        }

        private Settings validated() {
            if (minimumHistoricalCandles < 2) {
                throw new IllegalArgumentException("minimumHistoricalCandles must be at least 2.");
            }
            if (signalWindowCandles < 3) {
                throw new IllegalArgumentException("signalWindowCandles must be at least 3.");
            }
            if (holdingBars.isEmpty() || holdingBars.stream().anyMatch(value -> value == null || value < 1)) {
                throw new IllegalArgumentException("At least one positive holding period is required.");
            }
            if (holdingBars.stream().distinct().count() != holdingBars.size()) {
                throw new IllegalArgumentException("Holding periods must be unique.");
            }
            if (transactionCostBps < 0.0 || slippageBps < 0.0) {
                throw new IllegalArgumentException("Costs and slippage cannot be negative.");
            }
            if (!includeCandlesticks && !includeElliottWaves) {
                throw new IllegalArgumentException("At least one StockWatch detector must be included.");
            }
            return this;
        }
    }

    public record CrossIntervalReport(Settings settings,
                                      Map<TimeInterval, IntervalReport> intervals,
                                      String boundaryNotice) {
        public CrossIntervalReport {
            intervals = intervals == null ? Map.of() : Map.copyOf(intervals);
        }
    }

    public record IntervalReport(TimeInterval interval,
                                 int totalCandles,
                                 int detectedSignals,
                                 Map<Integer, HorizonReport> horizons,
                                 String boundaryNotice) {
        public IntervalReport {
            horizons = horizons == null ? Map.of() : Map.copyOf(horizons);
        }
    }

    public record HorizonReport(int holdingBars,
                                PerformanceSlice overall,
                                Map<String, PerformanceSlice> byFamily,
                                Map<String, PerformanceSlice> byPattern,
                                Map<String, PerformanceSlice> byDirection,
                                Map<String, PerformanceSlice> byScoreBand,
                                List<EvaluatedTrade> trades) {
        public HorizonReport {
            byFamily = Map.copyOf(byFamily);
            byPattern = Map.copyOf(byPattern);
            byDirection = Map.copyOf(byDirection);
            byScoreBand = Map.copyOf(byScoreBand);
            trades = List.copyOf(trades);
        }
    }

    public record PerformanceSlice(String key,
                                   int trades,
                                   int winners,
                                   int losers,
                                   int breakeven,
                                   double winRatePercent,
                                   double averageNetReturnPercent,
                                   double medianNetReturnPercent,
                                   double compoundedNetReturnPercent,
                                   double maximumDrawdownPercent) {
    }

    public record EvaluatedTrade(AlertPatternFamily family,
                                 CandlePattern pattern,
                                 TradeSignal direction,
                                 int setupScore,
                                 TimeInterval interval,
                                 long confirmationTimestamp,
                                 long entryTimestamp,
                                 long exitTimestamp,
                                 int entryIndex,
                                 int exitIndex,
                                 double marketEntryPrice,
                                 double marketExitPrice,
                                 double executedEntryPrice,
                                 double executedExitPrice,
                                 double grossDirectionalReturnPercent,
                                 double netReturnPercent,
                                 Position ta4jPosition) {
    }

    private record ObservedSignal(AlertPatternFamily family,
                                  CandlePattern pattern,
                                  TradeSignal direction,
                                  int setupScore,
                                  TimeInterval interval,
                                  long confirmationTimestamp,
                                  int confirmationIndex) {
    }
}
