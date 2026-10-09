package org.example.stockwatch247.service;

import org.example.stockwatch247.model.EnrichedCandle;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ValidatedCandleWindowTest {
    @Test void indexedConfirmationsMatchScansWithoutSeeingBeyondAPrefixOrRewind() {
        var candles = new ArrayList<EnrichedCandle>();
        var random = new java.util.Random(812);
        for (int index = 0; index < 90; index++) {
            double close = 20 + random.nextInt(20);
            candles.add(new EnrichedCandle((long) index, close, close + 1, close - 1, close,
                    100, 100, 50, close, close, close, 0, 0, 0, 0, close, close - 1, close + 1, 1, close));
        }
        var full = ValidatedCandleWindow.latest(candles);
        for (int start = 0; start < 20; start++) for (int end = start + 1; end <= candles.size(); end++) {
            var prefix = full.subList(start, end);
            for (int pivot = 0; pivot < prefix.size(); pivot++) for (boolean bullish : List.of(true, false)) {
                int expected = -1;
                for (int next = pivot + 1; next < prefix.size(); next++) {
                    if (bullish ? prefix.get(next).close() > prefix.get(next - 1).high()
                            : prefix.get(next).close() < prefix.get(next - 1).low()) { expected = next; break; }
                }
                assertThat(prefix.firstConfirmationAfter(pivot, bullish)).isEqualTo(expected);
            }
        }
    }
    private EnrichedCandle candle(long timestamp, double close) {
        return new EnrichedCandle(timestamp, 10, 15, 5, close, 100, 100, 50, 10, 10, 10,
                0, 0, 0, 0, 10, 5, 15, 1, 10);
    }

    @Test void rangesHaveTheSameEqualityAndHashAsOrdinaryListsIncludingCorrectedInputs() {
        var candles = new ArrayList<EnrichedCandle>();
        for (int i = 0; i < 60; i++) candles.add(candle(i, 10));
        var window = ValidatedCandleWindow.latest(candles);
        for (int start = 0; start <= candles.size(); start++) for (int end = start; end <= candles.size(); end++) {
            var range = window.subList(start, end);
            assertThat(range).isEqualTo(candles.subList(start, end));
            assertThat(range.hashCode()).isEqualTo(candles.subList(start, end).hashCode());
            assertThat(range).isEqualTo(ValidatedCandleWindow.latest(candles.subList(start, end)));
        }
        candles.set(30, candle(30, 11));
        assertThat(window).isNotEqualTo(ValidatedCandleWindow.latest(candles));
        assertThat(window.get(30).close()).isEqualTo(10);
        assertThat(window.lowerBound(30)).isEqualTo(30);
        assertThat(window.lowerBound(100)).isEqualTo(60);
    }

    @Test void validationStillPreservesOnlyTheLatestValidSegment() {
        var duplicate = candle(2, 10);
        assertThat(ValidatedCandleWindow.latest(List.of(candle(1, 10), duplicate, duplicate, candle(3, 10))))
                .containsExactly(candle(3, 10));
    }
}
