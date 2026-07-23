package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CandlePatternDetectionServiceTest {
    private final CandlePatternDetectionService detectionService = new CandlePatternDetectionService();

    @Test
    void detectsBullishEngulfingAfterDeclineAndStoresFiveStockOnlyScoreComponents() {
        List<EnrichedCandle> candles = List.of(
                candle(1, 110, 111, 107, 108, 1_000, 1_000, 48, 109, 98, 120, 5),
                candle(2, 108, 109, 104, 105, 1_000, 1_000, 44, 107, 98, 120, 5),
                candle(3, 105, 106, 101, 102, 1_000, 1_000, 39, 105, 98, 120, 5),
                candle(4, 103, 104, 98, 99, 1_000, 1_000, 33, 103, 98, 120, 5),
                candle(5, 98, 105, 97, 104, 1_600, 1_000, 38, 102, 98, 120, 5)
        );

        DetectedSignal signal = signal(CandlePattern.BULLISH_ENGULFING, candles);

        assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(signal.setupScore()).isBetween(0, 100);
        assertThat(signal.reasons()).hasSize(5);
        assertThat(signal.reasons()).anyMatch(reason -> reason.startsWith("Pattern quality +"));
        assertThat(signal.reasons()).anyMatch(reason -> reason.startsWith("Higher-timeframe alignment +"));
        assertThat(signal.reasons()).anyMatch(reason -> reason.startsWith("Price location +"));
        assertThat(signal.reasons()).anyMatch(reason -> reason.startsWith("Volatility and momentum +"));
        assertThat(signal.reasons()).anyMatch(reason -> reason.startsWith("Volume +"));
        assertThat(signal.reasons()).noneMatch(reason -> reason.startsWith("Market"));
        assertThat(componentMaximum(signal, "Pattern quality")).isEqualTo(35.7);
        assertThat(componentMaximum(signal, "Higher-timeframe alignment")).isEqualTo(21.4);
        assertThat(componentMaximum(signal, "Price location")).isEqualTo(14.3);
        assertThat(componentMaximum(signal, "Volatility and momentum")).isEqualTo(21.4);
        assertThat(componentMaximum(signal, "Volume")).isEqualTo(7.2);
        assertThat(signal.reasons().stream()
                .mapToDouble(this::componentMaximum)
                .sum()).isCloseTo(100.0, within(0.001));
        assertThat(detectionService.detectAlertSignals(candles))
                .anyMatch(detected -> detected.pattern() == CandlePattern.BULLISH_ENGULFING);
    }

    @Test
    void weeklyPatternAddsFrozenHistoricalCalibrationWithoutChangingValidity() {
        long week = 7L * 86_400L;
        List<EnrichedCandle> candles = List.of(
                plain(week, 110, 111, 107, 108),
                plain(2 * week, 108, 109, 104, 105),
                plain(3 * week, 105, 106, 101, 102),
                plain(4 * week, 103, 104, 98, 99),
                plain(5 * week, 98, 105, 97, 104)
        );

        DetectedSignal signal = signal(CandlePattern.BULLISH_ENGULFING, candles);

        assertThat(signal.reasons()).hasSize(6);
        assertThat(signal.reasons()).anyMatch(reason ->
                reason.startsWith("Historical pattern calibration +11.4/14.3"));
        assertThat(componentMaximum(signal, "Pattern quality")).isEqualTo(21.4);
        assertThat(signal.reasons().stream()
                .mapToDouble(this::componentMaximum)
                .sum()).isCloseTo(100.0, within(0.001));
        assertThat(detectionService.detectAlertSignals(candles))
                .anyMatch(detected -> detected.pattern() == CandlePattern.BULLISH_ENGULFING);
    }

    @Test
    void atrCanChangeTheScoreButCannotSuppressValidPatternGeometry() {
        List<EnrichedCandle> ordinaryAtr = List.of(
                candle(1, 110, 111, 107, 108, 1_000, 1_000, 48, 109, 98, 120, 5),
                candle(2, 108, 109, 104, 105, 1_000, 1_000, 44, 107, 98, 120, 5),
                candle(3, 105, 106, 101, 102, 1_000, 1_000, 39, 105, 98, 120, 5),
                candle(4, 103, 104, 98, 99, 1_000, 1_000, 33, 103, 98, 120, 5),
                candle(5, 98, 105, 97, 104, 1_600, 1_000, 38, 102, 98, 120, 5)
        );
        List<EnrichedCandle> extremeAtr = ordinaryAtr.stream()
                .map(candle -> new EnrichedCandle(
                        candle.timestamp(),
                        candle.open(),
                        candle.high(),
                        candle.low(),
                        candle.close(),
                        candle.volume(),
                        candle.averageVolume20(),
                        candle.rsi14(),
                        candle.ema20(),
                        candle.sma200(),
                        candle.lowerBollinger(),
                        candle.upperBollinger(),
                        100.0
                ))
                .toList();

        DetectedSignal ordinary = signal(CandlePattern.BULLISH_ENGULFING, ordinaryAtr);
        DetectedSignal extreme = signal(CandlePattern.BULLISH_ENGULFING, extremeAtr);

        assertThat(detectionService.detectAlertSignals(extremeAtr))
                .anyMatch(signal -> signal.pattern() == CandlePattern.BULLISH_ENGULFING);
        assertThat(extreme.setupScore()).isLessThanOrEqualTo(ordinary.setupScore());
    }

    @Test
    void exposesTheSamePriorTrendAssessmentUsedByPatternDetection() {
        List<EnrichedCandle> candles = List.of(
                plain(1, 110, 111, 107, 108),
                plain(2, 108, 109, 104, 105),
                plain(3, 105, 106, 101, 102),
                plain(4, 103, 104, 98, 99),
                plain(5, 98, 105, 97, 104)
        );

        CandlePatternDetectionService.PriorTrendAssessment assessment =
                detectionService.assessPriorTrendForLatestPattern(candles, 2);

        assertThat(assessment.direction()).isEqualTo(CandlePatternDetectionService.TrendDirection.DOWN);
        assertThat(assessment.scorePoints()).isPositive();
        assertThat(assessment.description()).contains("established downtrend");
    }

    @Test
    void rejectsBullishEngulfingGeometryWithoutTheRequiredDecline() {
        List<EnrichedCandle> candles = List.of(
                plain(1, 100, 103, 99, 102),
                plain(2, 102, 105, 101, 104),
                plain(3, 104, 107, 103, 106),
                plain(4, 107, 108, 102, 103),
                plain(5, 102, 109, 101, 108)
        );

        assertThat(detectionService.detect(candles))
                .noneMatch(signal -> signal.pattern() == CandlePattern.BULLISH_ENGULFING);
        assertThat(detectionService.detectAlertSignals(candles))
                .noneMatch(signal -> signal.pattern() == CandlePattern.BULLISH_ENGULFING);
    }

    @Test
    void detectsBearishEngulfingOnlyAfterAdvance() {
        List<EnrichedCandle> candles = List.of(
                plain(1, 100, 103, 99, 102),
                plain(2, 102, 106, 101, 105),
                plain(3, 105, 109, 104, 108),
                plain(4, 107, 112, 106, 111),
                plain(5, 112, 113, 105, 106)
        );

        DetectedSignal signal = signal(CandlePattern.BEARISH_ENGULFING, candles);

        assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.SELL);
        assertThat(signal.reasons()).anyMatch(reason -> reason.contains("established uptrend"));
    }

    @Test
    void validPatternCanHaveWeakConfluenceButStillRemainActionable() {
        List<EnrichedCandle> candles = List.of(
                plain(1, 110, 111, 107, 108),
                plain(2, 108, 109, 104, 105),
                plain(3, 105, 106, 101, 102),
                plain(4, 103, 104, 98, 99),
                plain(5, 98, 105, 97, 104)
        );

        DetectedSignal signal = signal(CandlePattern.BULLISH_ENGULFING, candles);

        assertThat(signal.strength()).isEqualTo(SignalStength.LOW_CONFIDENCE);
        assertThat(signal.setupScore()).isLessThan(75);
        assertThat(detectionService.detectAlertSignals(candles))
                .anyMatch(detected -> detected.pattern() == CandlePattern.BULLISH_ENGULFING);
    }

    @Test
    void rejectsBearishHaramiShapeDuringDowntrend() {
        List<EnrichedCandle> candles = List.of(
                plain(1, 112, 113, 108, 109),
                plain(2, 109, 110, 105, 106),
                plain(3, 106, 107, 102, 103),
                plain(4, 102, 110, 101, 109),
                plain(5, 108, 109, 105, 106)
        );

        assertThat(detectionService.detect(candles))
                .noneMatch(signal -> signal.pattern() == CandlePattern.BEARISH_HARAMI);
    }

    @Test
    void detectsBearishHaramiOnlyAfterAdvance() {
        List<EnrichedCandle> candles = List.of(
                plain(1, 100, 104, 99, 103),
                plain(2, 103, 107, 102, 106),
                plain(3, 106, 110, 105, 109),
                plain(4, 108, 116, 107, 115),
                plain(5, 114, 115, 111, 112)
        );

        DetectedSignal signal = signal(CandlePattern.BEARISH_HARAMI, candles);

        assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.SELL);
        assertThat(signal.reasons()).anyMatch(reason -> reason.contains("established uptrend"));
    }

    @Test
    void detectsBullishHaramiOnlyAfterDecline() {
        List<EnrichedCandle> candles = List.of(
                plain(1, 112, 113, 108, 109),
                plain(2, 109, 110, 105, 106),
                plain(3, 106, 107, 102, 103),
                plain(4, 104, 105, 96, 97),
                plain(5, 98, 101, 97, 100)
        );

        DetectedSignal signal = signal(CandlePattern.BULLISH_HARAMI, candles);

        assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(signal.reasons()).anyMatch(reason -> reason.contains("established downtrend"));
    }

    @Test
    void classifiesLowerWickShapeByItsPriorTrend() {
        List<EnrichedCandle> decline = List.of(
                plain(1, 111, 112, 108, 109),
                plain(2, 109, 110, 106, 107),
                plain(3, 107, 108, 104, 105),
                plain(4, 104.5, 104.7, 101.5, 104.0)
        );
        List<EnrichedCandle> advance = List.of(
                plain(1, 99, 103, 98, 102),
                plain(2, 102, 106, 101, 105),
                plain(3, 105, 109, 104, 108),
                plain(4, 108.5, 108.7, 105.5, 108.0)
        );

        assertThat(signal(CandlePattern.HAMMER, decline).tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(detectionService.detect(decline))
                .noneMatch(signal -> signal.pattern() == CandlePattern.HANGING_MAN);
        assertThat(signal(CandlePattern.HANGING_MAN, advance).tradeSignal()).isEqualTo(TradeSignal.SELL);
        assertThat(detectionService.detect(advance))
                .noneMatch(signal -> signal.pattern() == CandlePattern.HAMMER);
    }

    @Test
    void classifiesUpperWickShapeByItsPriorTrend() {
        List<EnrichedCandle> decline = List.of(
                plain(1, 111, 112, 108, 109),
                plain(2, 109, 110, 106, 107),
                plain(3, 107, 108, 104, 105),
                plain(4, 104.0, 106.5, 103.3, 103.5)
        );
        List<EnrichedCandle> advance = List.of(
                plain(1, 99, 103, 98, 102),
                plain(2, 102, 106, 101, 105),
                plain(3, 105, 109, 104, 108),
                plain(4, 108.0, 110.5, 107.3, 107.5)
        );

        assertThat(signal(CandlePattern.INVERTED_HAMMER, decline).tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(detectionService.detect(decline))
                .noneMatch(signal -> signal.pattern() == CandlePattern.SHOOTING_STAR);
        assertThat(signal(CandlePattern.SHOOTING_STAR, advance).tradeSignal()).isEqualTo(TradeSignal.SELL);
        assertThat(detectionService.detect(advance))
                .noneMatch(signal -> signal.pattern() == CandlePattern.INVERTED_HAMMER);
    }

    @Test
    void detectsPiercingLineAndDarkCloudCoverWithRelativeLongBodies() {
        List<EnrichedCandle> piercing = List.of(
                plain(1, 112, 113, 108, 109),
                plain(2, 109, 110, 105, 106),
                plain(3, 106, 107, 102, 103),
                plain(4, 104, 105, 96, 97),
                plain(5, 96, 102, 95.5, 101)
        );
        List<EnrichedCandle> darkCloud = List.of(
                plain(1, 100, 104, 99, 103),
                plain(2, 103, 107, 102, 106),
                plain(3, 106, 110, 105, 109),
                plain(4, 108, 116, 107, 115),
                plain(5, 116, 116.5, 110, 110.5)
        );

        assertThat(signal(CandlePattern.PIERCING_LINE, piercing).tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(signal(CandlePattern.DARK_CLOUD_COVER, darkCloud).tradeSignal()).isEqualTo(TradeSignal.SELL);
    }

    @Test
    void detectsMorningAndEveningStarsOnlyAfterRequiredTrend() {
        List<EnrichedCandle> morning = List.of(
                plain(1, 112, 113, 108, 109),
                plain(2, 109, 110, 105, 106),
                plain(3, 106, 107, 102, 103),
                plain(4, 103, 104, 95, 96),
                plain(5, 95, 96.5, 94.5, 95.5),
                plain(6, 95.5, 103, 95, 102)
        );
        List<EnrichedCandle> evening = List.of(
                plain(1, 100, 104, 99, 103),
                plain(2, 103, 107, 102, 106),
                plain(3, 106, 110, 105, 109),
                plain(4, 108, 116, 107, 115),
                plain(5, 116, 117, 115, 115.5),
                plain(6, 115.5, 116, 108.5, 109)
        );

        assertThat(signal(CandlePattern.MORNING_STAR, morning).tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(signal(CandlePattern.EVENING_STAR, evening).tradeSignal()).isEqualTo(TradeSignal.SELL);
    }

    @Test
    void detectsThreeWhiteSoldiersAndThreeBlackCrowsAfterOppositeTrend() {
        List<EnrichedCandle> soldiers = List.of(
                plain(1, 112, 113, 108, 109),
                plain(2, 109, 110, 105, 106),
                plain(3, 106, 107, 102, 103),
                plain(4, 101, 106.5, 100.5, 106),
                plain(5, 104, 110.5, 103.5, 110),
                plain(6, 108, 114.5, 107.5, 114)
        );
        List<EnrichedCandle> crows = List.of(
                plain(1, 100, 104, 99, 103),
                plain(2, 103, 107, 102, 106),
                plain(3, 106, 110, 105, 109),
                plain(4, 111, 111.5, 105.5, 106),
                plain(5, 108, 108.5, 101.5, 102),
                plain(6, 104, 104.5, 97.5, 98)
        );

        assertThat(signal(CandlePattern.THREE_WHITE_SOLDIERS, soldiers).tradeSignal()).isEqualTo(TradeSignal.BUY);
        assertThat(signal(CandlePattern.THREE_BLACK_CROWS, crows).tradeSignal()).isEqualTo(TradeSignal.SELL);
    }

    @Test
    void detectsThreeWhiteSoldiersAfterCompactBasingPeriod() {
        List<EnrichedCandle> candles = List.of(
                plain(1, 100, 102, 99, 101),
                plain(2, 101, 102, 99, 100),
                plain(3, 100, 101.5, 99.5, 100.5),
                plain(4, 100, 105.5, 99.5, 105),
                plain(5, 103, 109.5, 102.5, 109),
                plain(6, 107, 113.5, 106.5, 113)
        );

        DetectedSignal signal = signal(CandlePattern.THREE_WHITE_SOLDIERS, candles);

        assertThat(signal.reasons()).anyMatch(reason -> reason.contains("compact basing range"));
    }

    @Test
    void dojiRemainsNeutralAndDoesNotRequireDirectionalContext() {
        List<EnrichedCandle> candles = List.of(
                plain(1, 100, 101, 99, 100.5),
                plain(2, 100, 102, 98, 100.05)
        );

        DetectedSignal signal = signal(CandlePattern.DOJI, candles);

        assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.HOLD);
        assertThat(detectionService.detectAlertSignals(candles))
                .noneMatch(detected -> detected.pattern() == CandlePattern.DOJI);
    }

    @Test
    void recognizesFourPriceDojiAsNeutralWhenAllPricesAreEqual() {
        List<EnrichedCandle> candles = List.of(
                plain(1, 99, 101, 98, 100),
                plain(2, 100, 100, 100, 100)
        );

        assertThat(signal(CandlePattern.DOJI, candles).tradeSignal()).isEqualTo(TradeSignal.HOLD);
    }

    @Test
    void rejectsShootingStarShapeWithoutMeaningfulAdvance() {
        List<EnrichedCandle> candles = List.of(
                candle(1, 246.68, 250.43, 233.59, 238.55, 1_000, 1_000, 48, 239.5, 220, 260, 15),
                candle(2, 245.02, 249.51, 236.00, 244.39, 1_000, 1_000, 51, 239.8, 220, 260, 15),
                candle(3, 240.08, 242.42, 225.55, 232.69, 1_000, 1_000, 43, 239.2, 220, 260, 15),
                candle(4, 234.22, 249.71, 233.80, 242.67, 1_000, 1_000, 50, 239.6, 220, 260, 15),
                candle(5, 243.80, 251.03, 238.25, 245.34, 1_000, 1_000, 53, 240.1, 220, 260, 15),
                candle(6, 244.68, 258.08, 243.59, 247.23, 1_000, 1_000, 55, 240.8, 220, 260, 15)
        );

        assertThat(detectionService.detect(candles))
                .noneMatch(signal -> signal.pattern() == CandlePattern.SHOOTING_STAR);
    }

    private DetectedSignal signal(CandlePattern pattern, List<EnrichedCandle> candles) {
        return detectionService.detect(candles).stream()
                .filter(signal -> signal.pattern() == pattern)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected pattern " + pattern + " but detected "
                        + detectionService.detect(candles).stream()
                        .map(DetectedSignal::pattern)
                        .toList()));
    }

    private double componentMaximum(DetectedSignal signal, String category) {
        return signal.reasons().stream()
                .filter(reason -> reason.startsWith(category + " +"))
                .findFirst()
                .map(this::componentMaximum)
                .orElseThrow();
    }

    private double componentMaximum(String renderedComponent) {
        int slash = renderedComponent.indexOf('/');
        int colon = renderedComponent.indexOf(':', slash);
        return Double.parseDouble(renderedComponent.substring(slash + 1, colon));
    }

    private EnrichedCandle plain(long timestamp,
                                 double open,
                                 double high,
                                 double low,
                                 double close) {
        return candle(timestamp, open, high, low, close, 1_000,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private EnrichedCandle candle(long timestamp,
                                  double open,
                                  double high,
                                  double low,
                                  double close,
                                  double volume,
                                  double averageVolume20,
                                  double rsi14,
                                  double ema20,
                                  double lowerBollinger,
                                  double upperBollinger,
                                  double atr14) {
        return new EnrichedCandle(
                timestamp,
                open,
                high,
                low,
                close,
                volume,
                averageVolume20,
                rsi14,
                ema20,
                Double.NaN,
                lowerBollinger,
                upperBollinger,
                atr14
        );
    }
}
