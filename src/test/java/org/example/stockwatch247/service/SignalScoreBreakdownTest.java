package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TradeSignal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignalScoreBreakdownTest {

    @Test
    void labelsEveryConfiguredIndicatorWithItsPeriods() {
        SignalScoreBreakdown.Section trend = SignalScoreBreakdown.parse(
                "Trend indicators +0/20: weekly profile: EMA(8)/EMA(21) order was not aligned "
                        + "with the signal; fast EMA slope was not aligned with the signal; "
                        + "slow EMA slope was not aligned with the signal; "
                        + "close versus SMA(40) was not aligned with the signal; "
                        + "MACD(8,21,5) line/signal was not aligned with the signal; "
                        + "MACD histogram change was not aligned with the signal",
                "Trend evidence",
                TradeSignal.SELL
        );
        SignalScoreBreakdown.Section momentum = SignalScoreBreakdown.parse(
                "Momentum +15/15: weekly profile: RSI(10) was 71.3 (+5/5 for directional "
                        + "reversal location); RSI change was aligned with the signal; "
                        + "CCI(14) was 145.0 (+4/4 for directional reversal location); "
                        + "CCI change was aligned with the signal",
                "Momentum",
                TradeSignal.SELL
        );

        assertThat(trend.details()).extracting(SignalScoreBreakdown.Detail::label)
                .containsExactly(
                        "Indicator profile",
                        "EMA(8) vs EMA(21)",
                        "EMA(8) slope",
                        "EMA(21) slope",
                        "SMA(40) position",
                        "MACD(8, 21, 5) line vs signal",
                        "MACD(8, 21, 5) histogram"
                );
        assertThat(momentum.details()).extracting(SignalScoreBreakdown.Detail::label)
                .containsExactly(
                        "Indicator profile",
                        "RSI(10) level",
                        "RSI(10) change",
                        "CCI(14) level",
                        "CCI(14) change"
                );
    }

    @Test
    void labelsVolatilityAndVolumeMetricsWithTheirLookbackSettings() {
        SignalScoreBreakdown.Section bollinger = SignalScoreBreakdown.parse(
                "Bollinger volatility/location +10/10: Bollinger(13,2.0): the pattern tested "
                        + "the upper band; Bollinger %B was 0.94 (+4/4); "
                        + "the close moved back inside the tested band; bandwidth was 20.80%",
                "Bollinger",
                TradeSignal.SELL
        );
        SignalScoreBreakdown.Section volume = SignalScoreBreakdown.parse(
                "Volume participation +1/10: weekly profile: volume was 0.81x its 13-bar average "
                        + "(+0/4); close versus rolling VWAP(13) was not aligned with the signal; "
                        + "rolling VWAP slope was not aligned with the signal; "
                        + "26-bar OHLCV volume-profile approximation: point-of-control side was "
                        + "aligned with the signal; close was 1.85 ATR-equivalents from the "
                        + "relevant 70% value-area boundary (+0/2)",
                "Volume",
                TradeSignal.SELL
        );

        assertThat(bollinger.details()).extracting(SignalScoreBreakdown.Detail::label)
                .containsExactly(
                        "Settings",
                        "Bollinger(13, 2.0) band test",
                        "Bollinger(13, 2.0) %B position",
                        "Bollinger(13, 2.0) re-entry",
                        "Bollinger(13, 2.0) band width"
                );
        assertThat(volume.details()).extracting(SignalScoreBreakdown.Detail::label)
                .containsExactly(
                        "Indicator profile",
                        "Relative volume (13-bar average)",
                        "VWAP(13) position",
                        "VWAP(13) slope",
                        "26-bar volume profile",
                        "26-bar value area"
                );
    }
}
