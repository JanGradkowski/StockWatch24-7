package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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

    @Test
    void createsIntervalRiskRewardTargetsFromTheExactStructuralStop() {
        List<Candle> bullishEngulfing = List.of(
                candle(100L, 104.0, 106.0, 98.0, 99.0),
                candle(200L, 98.0, 108.0, 96.0, 105.0));

        CandlestickSignalLifecyclePolicy.TradePlan daily =
                CandlestickSignalLifecyclePolicy.tradePlan(
                        CandlePattern.BULLISH_ENGULFING, TradeSignal.BUY,
                        105.0, bullishEngulfing, TimeInterval.DAILY);
        CandlestickSignalLifecyclePolicy.TradePlan weekly =
                CandlestickSignalLifecyclePolicy.tradePlan(
                        CandlePattern.BULLISH_ENGULFING, TradeSignal.BUY,
                        105.0, bullishEngulfing, TimeInterval.WEEKLY);
        CandlestickSignalLifecyclePolicy.TradePlan monthly =
                CandlestickSignalLifecyclePolicy.tradePlan(
                        CandlePattern.BULLISH_ENGULFING, TradeSignal.BUY,
                        105.0, bullishEngulfing, TimeInterval.MONTHLY);

        assertThat(daily.stopLossPrice()).isEqualTo(96.0);
        assertThat(daily.profitTargetPrice()).isEqualTo(123.0);
        assertThat(daily.rewardRiskRatio()).isEqualTo(2.0);
        assertThat(weekly.profitTargetPrice()).isEqualTo(132.0);
        assertThat(weekly.rewardRiskRatio()).isEqualTo(3.0);
        assertThat(monthly.profitTargetPrice()).isEqualTo(141.0);
        assertThat(monthly.rewardRiskRatio()).isEqualTo(4.0);
        assertThat(monthly.timeStopCandles()).isEqualTo(8);
    }

    @Test
    void appliesCustomStructuralBufferOrFixedEntryStopAndCustomRewardRatio() {
        List<Candle> formation = List.of(
                candle(100L, 104.0, 106.0, 98.0, 99.0),
                candle(200L, 98.0, 108.0, 96.0, 105.0));

        var buffered = CandlestickSignalLifecyclePolicy.tradePlan(
                CandlePattern.BULLISH_ENGULFING, TradeSignal.BUY, 105.0, formation,
                TimeInterval.DAILY,
                CandlestickPatternPreferencesService.StopLossMode.STRUCTURAL_BUFFER,
                1.0, 2.5);
        var fixed = CandlestickSignalLifecyclePolicy.tradePlan(
                CandlePattern.BULLISH_ENGULFING, TradeSignal.BUY, 105.0, formation,
                TimeInterval.DAILY,
                CandlestickPatternPreferencesService.StopLossMode.FIXED_ENTRY_PERCENT,
                4.0, 1.5);

        assertThat(buffered.stopLossPrice()).isEqualTo(94.95);
        assertThat(buffered.profitTargetPrice()).isEqualTo(130.125);
        assertThat(buffered.rewardRiskRatio()).isEqualTo(2.5);
        assertThat(fixed.stopLossPrice()).isEqualTo(100.8);
        assertThat(fixed.profitTargetPrice()).isCloseTo(111.3, within(0.0000001));
        assertThat(fixed.rewardRiskRatio()).isEqualTo(1.5);
    }

    @Test
    void usesTheSpecifiedPatternCandleForPiercingAndDarkCloudStops() {
        List<Candle> bullish = List.of(
                candle(100L, 110.0, 112.0, 90.0, 94.0),
                candle(200L, 92.0, 105.0, 91.0, 103.0));
        List<Candle> bearish = List.of(
                candle(100L, 90.0, 115.0, 89.0, 112.0),
                candle(200L, 114.0, 114.0, 100.0, 102.0));

        assertThat(CandlestickSignalLifecyclePolicy.structuralStopPrice(
                CandlePattern.PIERCING_LINE, bullish)).isEqualTo(91.0);
        assertThat(CandlestickSignalLifecyclePolicy.structuralStopPrice(
                CandlePattern.DARK_CLOUD_COVER, bearish)).isEqualTo(114.0);
    }

    @Test
    void mapsEverySupportedPatternToItsExactStructuralFormationBoundary() {
        List<Candle> formation = List.of(
                candle(100L, 100.0, 112.0, 88.0, 104.0),
                candle(200L, 104.0, 110.0, 90.0, 98.0),
                candle(300L, 98.0, 108.0, 92.0, 106.0));
        List<Candle> signalCandle = List.of(formation.getLast());

        assertThat(CandlestickSignalLifecyclePolicy.structuralStopPrice(
                CandlePattern.HAMMER, signalCandle)).isEqualTo(92.0);
        assertThat(CandlestickSignalLifecyclePolicy.structuralStopPrice(
                CandlePattern.INVERTED_HAMMER, signalCandle)).isEqualTo(92.0);
        assertThat(CandlestickSignalLifecyclePolicy.structuralStopPrice(
                CandlePattern.SHOOTING_STAR, signalCandle)).isEqualTo(108.0);
        assertThat(CandlestickSignalLifecyclePolicy.structuralStopPrice(
                CandlePattern.HANGING_MAN, signalCandle)).isEqualTo(108.0);

        for (CandlePattern bullish : List.of(
                CandlePattern.BULLISH_ENGULFING,
                CandlePattern.BULLISH_HARAMI,
                CandlePattern.MORNING_STAR,
                CandlePattern.THREE_WHITE_SOLDIERS)) {
            assertThat(CandlestickSignalLifecyclePolicy.structuralStopPrice(bullish, formation))
                    .as(bullish.name())
                    .isEqualTo(88.0);
        }
        for (CandlePattern bearish : List.of(
                CandlePattern.BEARISH_ENGULFING,
                CandlePattern.BEARISH_HARAMI,
                CandlePattern.EVENING_STAR,
                CandlePattern.THREE_BLACK_CROWS)) {
            assertThat(CandlestickSignalLifecyclePolicy.structuralStopPrice(bearish, formation))
                    .as(bearish.name())
                    .isEqualTo(112.0);
        }
    }

    @Test
    void aCloseExactlyOnTargetOrStopResolvesTheTrade() {
        assertThat(CandlestickSignalLifecyclePolicy.resolve(
                TradeSignal.BUY, 110.0, 90.0, List.of(candle(1L, 110.0)), 8).status())
                .isEqualTo(SignalLifecycleStatus.CONFIRMED);
        assertThat(CandlestickSignalLifecyclePolicy.resolve(
                TradeSignal.SELL, 90.0, 110.0, List.of(candle(1L, 110.0)), 8).status())
                .isEqualTo(SignalLifecycleStatus.INVALIDATED);
    }

    @Test
    void wickTouchesDoNotExitAndTheEighthCloseExpiresTheTrade() {
        List<Candle> candles = java.util.stream.LongStream.rangeClosed(1, 8)
                .mapToObj(index -> candle(index, 100.0, 112.0, 88.0, 100.0))
                .toList();

        CandlestickSignalLifecyclePolicy.LifecycleResolution resolution =
                CandlestickSignalLifecyclePolicy.resolve(
                        TradeSignal.BUY, 110.0, 90.0, candles, 8);

        assertThat(resolution.status()).isEqualTo(SignalLifecycleStatus.EXPIRED);
        assertThat(resolution.candleOffset()).isEqualTo(8);
        assertThat(resolution.resolutionCandle().getClosePrice()).isEqualTo(100.0);
    }

    private Candle candle(long timestamp, double close) {
        return new Candle("AAPL", "1d", timestamp, close, close + 1.0, close - 1.0, close, 1_000L);
    }

    private Candle candle(long timestamp, double open, double close) {
        return new Candle("AAPL", "1d", timestamp, open,
                Math.max(open, close) + 1.0, Math.min(open, close) - 1.0, close, 1_000L);
    }

    private Candle candle(long timestamp, double open, double high, double low, double close) {
        return new Candle("AAPL", "1d", timestamp, open, high, low, close, 1_000L);
    }
}
