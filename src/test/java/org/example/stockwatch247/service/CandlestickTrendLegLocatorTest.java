package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandlestickTrendLegLocatorTest {

    @Test
    void uptrendHighlightBeginsAtSwingLowRatherThanRegressionWindowStart() {
        List<Candle> candles = candles(110, 106, 101, 96, 91, 94, 99, 105, 111, 116, 115);

        int startIndex = CandlestickTrendLegLocator.locateStartIndex(
                candles, 0, 10, TradeSignal.SELL);

        assertThat(startIndex).isEqualTo(4);
    }

    @Test
    void downtrendHighlightBeginsAtSwingHighRatherThanRegressionWindowStart() {
        List<Candle> candles = candles(90, 93, 98, 104, 111, 108, 102, 97, 92, 88, 89);

        int startIndex = CandlestickTrendLegLocator.locateStartIndex(
                candles, 0, 10, TradeSignal.BUY);

        assertThat(startIndex).isEqualTo(4);
    }

    @Test
    void turningPointLeavesAtLeastThreeCandlesInVisibleLeg() {
        List<Candle> candles = candles(100, 102, 104, 106, 108, 90, 110);

        int startIndex = CandlestickTrendLegLocator.locateStartIndex(
                candles, 0, 6, TradeSignal.SELL);

        assertThat(startIndex).isEqualTo(0);
    }

    private List<Candle> candles(double... closes) {
        List<Candle> candles = new ArrayList<>();
        for (int index = 0; index < closes.length; index++) {
            double close = closes[index];
            candles.add(new Candle(
                    "TEST", "1d", 1_700_000_000L + index * 86_400L,
                    close, close + 1.0, close - 1.0, close, 1_000L
            ));
        }
        return candles;
    }
}
