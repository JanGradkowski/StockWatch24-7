package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandlestickSignalLifecyclePolicyTest {

    @Test
    void oneCandleCandidateBecomesDetectedOnlyOnImmediateFavorableBodyAndClose() {
        CandlestickSignalLifecyclePolicy.LifecycleResolution resolution =
                CandlestickSignalLifecyclePolicy.resolveCandidateGate(
                        CandlePattern.HANGING_MAN,
                        TradeSignal.SELL,
                        100.0,
                        List.of(candle(200L, 101.0, 99.0), candle(300L, 95.0, 94.0)));

        assertThat(resolution.status()).isEqualTo(SignalLifecycleStatus.DETECTED);
        assertThat(resolution.candleOffset()).isEqualTo(1);
        assertThat(resolution.resolutionCandle().getTimestamp()).isEqualTo(200L);
    }

    @Test
    void oneCandleCandidateIsRejectedImmediatelyEvenIfALaterCandleWouldPass() {
        CandlestickSignalLifecyclePolicy.LifecycleResolution resolution =
                CandlestickSignalLifecyclePolicy.resolveCandidateGate(
                        CandlePattern.HANGING_MAN,
                        TradeSignal.SELL,
                        100.0,
                        List.of(candle(200L, 100.0, 101.0), candle(300L, 95.0, 94.0)));

        assertThat(resolution.status()).isEqualTo(SignalLifecycleStatus.REJECTED);
        assertThat(resolution.candleOffset()).isEqualTo(1);
        assertThat(resolution.resolutionCandle().getTimestamp()).isEqualTo(200L);
    }

    @Test
    void multiCandlePatternStillUsesTheConfiguredResolutionWindow() {
        CandlestickSignalLifecyclePolicy.LifecycleResolution resolution =
                CandlestickSignalLifecyclePolicy.resolve(
                        CandlePattern.BEARISH_ENGULFING,
                        TradeSignal.SELL,
                        95.0,
                        105.0,
                        List.of(candle(200L, 100.0), candle(300L, 94.0)),
                        3);

        assertThat(resolution.status()).isEqualTo(SignalLifecycleStatus.CONFIRMED);
        assertThat(resolution.candleOffset()).isEqualTo(2);
    }

    private Candle candle(long timestamp, double close) {
        return new Candle("AAPL", "1d", timestamp, close, close + 1.0, close - 1.0, close, 1_000L);
    }

    private Candle candle(long timestamp, double open, double close) {
        return new Candle("AAPL", "1d", timestamp, open,
                Math.max(open, close) + 1.0, Math.min(open, close) - 1.0, close, 1_000L);
    }
}
