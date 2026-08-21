package org.example.stockwatch247.service.technical;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.example.stockwatch247.service.ElliottWaveDetectionService;
import org.example.stockwatch247.service.TechnicalIndicatorEnrichmentService;
import org.example.stockwatch247.service.technical.Ta4jPatternPerformanceResearchService.CrossIntervalReport;
import org.example.stockwatch247.service.technical.Ta4jPatternPerformanceResearchService.EvaluatedTrade;
import org.example.stockwatch247.service.technical.Ta4jPatternPerformanceResearchService.HorizonReport;
import org.example.stockwatch247.service.technical.Ta4jPatternPerformanceResearchService.Settings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Ta4jPatternPerformanceResearchServiceTest {

    @Test
    void evaluatesStockWatchPatternsSeparatelyOnEveryNativeInterval() {
        TriggeringCandlestickDetector candlesticks = new TriggeringCandlestickDetector();
        TriggeringElliottDetector elliott = new TriggeringElliottDetector();
        Ta4jPatternPerformanceResearchService service = service(candlesticks, elliott);
        Map<TimeInterval, List<Candle>> intervals = new EnumMap<>(TimeInterval.class);
        intervals.put(TimeInterval.DAILY, candles("1d", 86_400L));
        intervals.put(TimeInterval.WEEKLY, candles("1wk", 604_800L));
        intervals.put(TimeInterval.MONTHLY, candles("1mo", 2_592_000L));

        CrossIntervalReport report = service.evaluate(intervals, settings(List.of(1, 2), 5.0, 2.0));

        assertThat(report.intervals()).containsOnlyKeys(
                TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY);
        report.intervals().values().forEach(interval -> {
            assertThat(interval.detectedSignals()).isEqualTo(2);
            assertThat(interval.horizons()).containsOnlyKeys(1, 2);
            HorizonReport oneBar = interval.horizons().get(1);
            assertThat(oneBar.overall().trades()).isEqualTo(2);
            assertThat(oneBar.byFamily()).containsOnlyKeys(
                    AlertPatternFamily.CANDLESTICK.name(), AlertPatternFamily.ELLIOTT_WAVE.name());
            assertThat(oneBar.byDirection()).containsOnlyKeys(TradeSignal.BUY.name(), TradeSignal.SELL.name());
            assertThat(oneBar.byPattern()).containsOnlyKeys(
                    CandlePattern.BULLISH_ENGULFING.name(), CandlePattern.ELLIOTT_BEARISH_IMPULSE.name());
            assertThat(oneBar.byScoreBand()).containsOnlyKeys("70-84", "85+");
        });
        assertThat(candlesticks.seenIntervals).containsExactlyInAnyOrder(
                TimeInterval.DAILY, TimeInterval.WEEKLY, TimeInterval.MONTHLY);
        assertThat(report.boundaryNotice()).contains("StockWatch detects").contains("TA4J only evaluates");
    }

    @Test
    void entersOnlyAtNextOpenAndChargesSlippageAndTransactionCosts() {
        Ta4jPatternPerformanceResearchService service = service(
                new TriggeringCandlestickDetector(), new NoElliottSignals());
        List<Candle> candles = candles("1d", 86_400L);

        HorizonReport horizon = service.evaluateInterval(
                        TimeInterval.DAILY,
                        candles,
                        new Settings(3, 6, List.of(1), 10.0, 5.0, true, false))
                .horizons().get(1);

        assertThat(horizon.trades()).hasSize(1);
        EvaluatedTrade trade = horizon.trades().getFirst();
        assertThat(trade.confirmationTimestamp()).isEqualTo(candles.get(2).getTimestamp());
        assertThat(trade.entryTimestamp()).isEqualTo(candles.get(3).getTimestamp());
        assertThat(trade.entryIndex()).isEqualTo(3);
        assertThat(trade.exitIndex()).isEqualTo(3);
        assertThat(trade.marketEntryPrice()).isEqualTo(candles.get(3).getOpenPrice());
        assertThat(trade.marketEntryPrice()).isNotEqualTo(candles.get(2).getClosePrice());
        assertThat(trade.executedEntryPrice()).isGreaterThan(trade.marketEntryPrice());
        assertThat(trade.executedExitPrice()).isLessThan(trade.marketExitPrice());
        assertThat(trade.netReturnPercent()).isLessThan(trade.grossDirectionalReturnPercent());
    }

    @Test
    void detectorNeverReceivesCandlesAfterTheEvaluatedConfirmationBar() {
        PrefixAuditingDetector detector = new PrefixAuditingDetector();
        List<Candle> candles = candles("1d", 86_400L);
        Ta4jPatternPerformanceResearchService service = service(detector, new NoElliottSignals());

        service.evaluateInterval(
                TimeInterval.DAILY,
                candles,
                new Settings(3, 3, List.of(1), 0.0, 0.0, true, false));

        assertThat(detector.latestTimestamps).containsExactly(
                candles.get(2).getTimestamp(),
                candles.get(3).getTimestamp(),
                candles.get(4).getTimestamp());
        assertThat(detector.largestPrefix).isEqualTo(3);
    }

    @Test
    void rejectsAReportedIntervalThatDoesNotMatchItsCandles() {
        Ta4jPatternPerformanceResearchService service = service(
                new TriggeringCandlestickDetector(), new NoElliottSignals());

        assertThatThrownBy(() -> service.evaluateInterval(
                TimeInterval.WEEKLY,
                candles("1d", 86_400L),
                new Settings(3, 6, List.of(1), 0.0, 0.0, true, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match requested interval");
    }

    @Test
    void ta4jDoesNotLeakIntoEitherPatternDetector() throws IOException {
        assertThat(Files.readString(Path.of(
                "src/main/java/org/example/stockwatch247/service/CandlePatternDetectionService.java")))
                .doesNotContain("org.ta4j");
        assertThat(Files.readString(Path.of(
                "src/main/java/org/example/stockwatch247/service/ElliottWaveDetectionService.java")))
                .doesNotContain("org.ta4j");
    }

    private Ta4jPatternPerformanceResearchService service(CandlePatternDetectionService candlesticks,
                                                           ElliottWaveDetectionService elliott) {
        return new Ta4jPatternPerformanceResearchService(
                new TechnicalIndicatorEnrichmentService(), candlesticks, elliott);
    }

    private Settings settings(List<Integer> horizons, double costs, double slippage) {
        return new Settings(3, 6, horizons, costs, slippage, true, true);
    }

    private List<Candle> candles(String interval, long spacingSeconds) {
        long start = 1_700_000_000L;
        return List.of(
                candle(interval, start, 100.0, 101.0),
                candle(interval, start + spacingSeconds, 101.0, 102.0),
                candle(interval, start + 2 * spacingSeconds, 102.0, 103.0),
                candle(interval, start + 3 * spacingSeconds, 105.0, 107.0),
                candle(interval, start + 4 * spacingSeconds, 107.0, 104.0),
                candle(interval, start + 5 * spacingSeconds, 104.0, 108.0)
        );
    }

    private Candle candle(String interval, long timestamp, double open, double close) {
        return new Candle("TEST", interval, timestamp, open,
                Math.max(open, close) + 1.0,
                Math.min(open, close) - 1.0,
                close, 10_000L);
    }

    private static final class TriggeringCandlestickDetector extends CandlePatternDetectionService {
        private final java.util.Set<TimeInterval> seenIntervals = new java.util.LinkedHashSet<>();

        @Override
        public List<DetectedSignal> detectFactory(List<EnrichedCandle> recentCandles, TimeInterval interval) {
            seenIntervals.add(interval);
            EnrichedCandle latest = recentCandles.getLast();
            if (Math.abs(latest.close() - 103.0) > 0.000001) return List.of();
            return List.of(new DetectedSignal(
                    CandlePattern.BULLISH_ENGULFING,
                    TradeSignal.BUY,
                    SignalStength.HIGH_CONFIDENCE,
                    88,
                    List.of("StockWatch candlestick evidence"),
                    latest.timestamp(),
                    latest.close()
            ));
        }
    }

    private static final class TriggeringElliottDetector extends ElliottWaveDetectionService {
        @Override
        public List<DetectedSignal> detect(List<EnrichedCandle> recentCandles) {
            EnrichedCandle latest = recentCandles.getLast();
            if (Math.abs(latest.close() - 103.0) > 0.000001) return List.of();
            return List.of(new DetectedSignal(
                    CandlePattern.ELLIOTT_BEARISH_IMPULSE,
                    TradeSignal.SELL,
                    SignalStength.HIGH_CONFIDENCE,
                    74,
                    List.of("StockWatch Elliott evidence"),
                    latest.timestamp(),
                    latest.close()
            ));
        }
    }

    private static final class NoElliottSignals extends ElliottWaveDetectionService {
        @Override
        public List<DetectedSignal> detect(List<EnrichedCandle> recentCandles) {
            return List.of();
        }
    }

    private static final class PrefixAuditingDetector extends CandlePatternDetectionService {
        private final java.util.List<Long> latestTimestamps = new java.util.ArrayList<>();
        private int largestPrefix;

        @Override
        public List<DetectedSignal> detectFactory(List<EnrichedCandle> recentCandles, TimeInterval interval) {
            latestTimestamps.add(recentCandles.getLast().timestamp());
            largestPrefix = Math.max(largestPrefix, recentCandles.size());
            return List.of();
        }
    }
}
