package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The experimental score model rewards a reversal for turning, not for already trending its way. */
class CandlestickTurnConfirmationScoreTest {

    @Test
    void aReversalThatTurnsOnEveryIndicatorEarnsTheFullTurnConfirmationSection() {
        List<EnrichedCandle> candles = downtrendEndingInABullishEngulfing();

        var signal = new CandlePatternDetectionService().withTurnConfirmation(true)
                .detectAlertSignalsFactory(candles, TimeInterval.DAILY).stream()
                .filter(detected -> detected.pattern() == CandlePattern.BULLISH_ENGULFING)
                .findFirst().orElseThrow();

        assertThat(signal.reasons()).anySatisfy(reason -> assertThat(reason)
                .startsWith("Turn confirmation +20/20")
                .contains("MACD histogram turned toward zero", "slope turned in the signal direction",
                        "close crossed back over", "bullish RSI divergence"));
        assertThat(signal.reasons()).noneMatch(reason -> reason.startsWith("Trend indicators"));
    }

    @Test
    void theDefaultModelIsUnchangedAndRecordsItsOwnVersion() {
        var detector = new CandlePatternDetectionService();
        var signal = detector.detectAlertSignalsFactory(downtrendEndingInABullishEngulfing(), TimeInterval.DAILY).stream()
                .filter(detected -> detected.pattern() == CandlePattern.BULLISH_ENGULFING)
                .findFirst().orElseThrow();

        assertThat(signal.reasons()).anyMatch(reason -> reason.startsWith("Trend indicators"));
        assertThat(detector.scoreVersion()).isEqualTo(CandlePatternDetectionService.SETUP_SCORE_VERSION);
        assertThat(detector.withTurnConfirmation(true).scoreVersion())
                .isEqualTo(CandlePatternDetectionService.TURN_CONFIRMATION_SCORE_VERSION);
    }

    /** Thirty falling bars, then a bearish bar fully engulfed by a bullish one, with turning indicators. */
    private static List<EnrichedCandle> downtrendEndingInABullishEngulfing() {
        List<EnrichedCandle> candles = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            double open = 220 - 2 * index;
            candles.add(bar(index + 1, open, open + .5, open - 1.5, open - 1, Double.NaN, Double.NaN, Double.NaN));
        }
        // The last swing low before the pattern, with a deeply oversold RSI.
        EnrichedCandle swingLow = candles.get(28);
        candles.set(28, bar(swingLow.timestamp(), swingLow.open(), swingLow.high(), swingLow.low(), swingLow.close(),
                25.0, Double.NaN, Double.NaN));
        EnrichedCandle beforeFormation = candles.get(29);
        candles.set(29, bar(beforeFormation.timestamp(), beforeFormation.open(), beforeFormation.high(),
                beforeFormation.low(), beforeFormation.close(), Double.NaN, 170.0, Double.NaN));
        candles.add(bar(31, 161, 166, 156, 160, Double.NaN, 164.0, -0.8));
        // A lower low than the swing low, but RSI 35 against 25: bullish divergence.
        candles.add(bar(32, 159.9, 162, 155.0, 161.1, 35.0, 160.5, -0.5));
        return candles;
    }

    private static EnrichedCandle bar(long timestamp, double open, double high, double low, double close,
                                      double rsi, double fastEma, double macdHistogram) {
        return new EnrichedCandle(timestamp, open, high, low, close, 1000, Double.NaN, rsi, fastEma,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, macdHistogram, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }
}
