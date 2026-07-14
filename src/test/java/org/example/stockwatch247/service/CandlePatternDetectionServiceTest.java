package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandlePatternDetectionServiceTest {
    private final CandlePatternDetectionService detectionService = new CandlePatternDetectionService();

    @Test
    void detectsBullishEngulfingWithHighConfidenceAfterPatternCalibration() {
        List<EnrichedCandle> candles = List.of(
                candle(1L, 106.0, 107.0, 101.0, 102.0, 1_000, 1_000, 48, 105, 92, 112, 8),
                candle(2L, 103.0, 104.0, 98.0, 99.0, 1_000, 1_000, 44, 104, 92, 112, 8),
                candle(3L, 100.0, 101.0, 95.0, 96.0, 1_000, 1_000, 39, 103, 92, 112, 8),
                candle(4L, 98.0, 102.0, 93.0, 94.0, 1_000, 1_000, 39, 101, 92, 112, 8),
                candle(5L, 93.0, 100.0, 92.0, 99.0, 1_000, 1_000, 42, 100, 92.5, 112, 8)
        );

        List<DetectedSignal> signals = detectionService.detect(candles);

        assertThat(signals).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.BULLISH_ENGULFING);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(signal.strength()).isEqualTo(SignalStength.HIGH_CONFIDENCE);
            assertThat(signal.confidenceScore()).isBetween(85, 100);
            assertThat(signal.candleTimestamp()).isEqualTo(5L);
        });
    }

    @Test
    void treatsHighCalibratedBullishEngulfingAsHighConfidence() {
        List<EnrichedCandle> candles = List.of(
                candle(1L, 106.0, 107.0, 101.0, 102.0, 1_000, 1_000, 48, 105, 92, 112, 8),
                candle(2L, 103.0, 104.0, 98.0, 99.0, 1_000, 1_000, 44, 104, 92, 112, 8),
                candle(3L, 100.0, 101.0, 95.0, 96.0, 1_000, 1_000, 25, 103, 92, 112, 8),
                candle(4L, 98.0, 98.0, 93.0, 94.0, 1_000, 1_000, 25, 101, 92, 112, 8),
                candle(5L, 93.0, 100.0, 92.0, 99.0, 2_000, 1_000, 30, 100, 92.5, 112, 8)
        );

        List<DetectedSignal> signals = detectionService.detect(candles);

        assertThat(signals).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.BULLISH_ENGULFING);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(signal.strength()).isEqualTo(SignalStength.HIGH_CONFIDENCE);
            assertThat(signal.confidenceScore()).isGreaterThanOrEqualTo(95);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("calibration raised confidence"));
        });
    }

    @Test
    void downweightsBearishEngulfingUsingPatternCalibration() {
        List<EnrichedCandle> candles = List.of(
                candle(1L, 94.0, 99.0, 93.0, 98.0, 1_000, 1_000, 52, 95, 88, 108, 8),
                candle(2L, 98.0, 103.0, 97.0, 102.0, 1_000, 1_000, 56, 96, 88, 108, 8),
                candle(3L, 102.0, 107.0, 101.0, 106.0, 1_000, 1_000, 61, 97, 88, 108, 8),
                candle(4L, 104.0, 107.0, 103.0, 106.0, 1_000, 1_000, 61, 99, 88, 108, 8),
                candle(5L, 107.0, 108.0, 100.0, 103.0, 1_000, 1_000, 58, 100, 88, 107.5, 8)
        );

        List<DetectedSignal> signals = detectionService.detect(candles);

        assertThat(signals).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.BEARISH_ENGULFING);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.SELL);
            assertThat(signal.strength()).isEqualTo(SignalStength.LOW_CONFIDENCE);
            assertThat(signal.confidenceScore()).isLessThan(75);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("calibration lowered confidence"));
            assertThat(signal.candleTimestamp()).isEqualTo(5L);
        });
    }

    @Test
    void returnsNoSignalsWhenThereAreNotEnoughCandles() {
        EnrichedCandle candle = candle(1L, 100.0, 101.0, 99.0, 100.5, 1_000, 1_000, 50, 100, 95, 105, 2);

        assertThat(detectionService.detect(List.of(candle))).isEmpty();
    }

    @Test
    void detectsThreeWhiteSoldiersAfterRecentDowntrendWithCalibratedMomentumConfirmation() {
        List<EnrichedCandle> candles = List.of(
                candle(1L, 102.0, 103.0, 97.0, 98.0, 1_000, 1_000, 44, 105, 90, 110, 8),
                candle(2L, 98.0, 99.0, 93.0, 94.0, 1_000, 1_000, 40, 103, 90, 110, 8),
                candle(3L, 90.0, 97.0, 89.0, 96.0, 1_000, 1_000, 52, 101, 90, 110, 8),
                candle(4L, 94.0, 102.0, 93.0, 101.0, 1_000, 1_000, 56, 100, 90, 110, 8),
                candle(5L, 99.0, 108.0, 98.0, 107.0, 1_000, 1_000, 60, 100, 90, 110, 8)
        );

        List<DetectedSignal> signals = detectionService.detect(candles);

        assertThat(signals).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.THREE_WHITE_SOLDIERS);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(signal.confidenceScore()).isBetween(75, 94);
            assertThat(signal.candleTimestamp()).isEqualTo(5L);
        });
    }

    @Test
    void emitsBullishEngulfingWithLowConfidenceWhenOnlyGeometryMatches() {
        List<EnrichedCandle> candles = List.of(
                candle(1L, 100.0, 105.0, 88.0, 90.0, 1_000, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN),
                candle(2L, 89.0, 103.0, 87.0, 101.0, 1_200, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        );

        List<DetectedSignal> signals = detectionService.detect(candles);

        assertThat(signals).anySatisfy(signal -> {
            assertThat(signal.pattern()).isEqualTo(CandlePattern.BULLISH_ENGULFING);
            assertThat(signal.tradeSignal()).isEqualTo(TradeSignal.BUY);
            assertThat(signal.strength()).isEqualTo(SignalStength.LOW_CONFIDENCE);
            assertThat(signal.confidenceScore()).isLessThan(75);
            assertThat(signal.reasons()).anyMatch(reason -> reason.contains("below the calibrated signal range"));
        });
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
