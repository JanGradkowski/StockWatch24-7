package org.example.stockwatch247.service;

import java.util.ArrayList;
import java.util.List;
import org.example.stockwatch247.model.EnrichedCandle;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CausalCandlePrefixesTest {
    @Test void prefixesMatchIndependentValidationAcrossMalformedBarsAndDuplicateTimestamps() {
        List<EnrichedCandle> candles = new ArrayList<>();
        for (int index = 1; index < 100; index++) {
            double close = index == 40 ? Double.NaN : 10 + index % 7;
            var candle = new EnrichedCandle((long) index, close, close + 1, close - 1, close,
                    100, 100, 50, close, close, close, 0, 0, 0, 0, close, close - 1, close + 1, 1, close);
            candles.add(candle);
            if (index == 70) candles.add(candle);
        }
        var prefixes = new CausalCandlePrefixes(candles.reversed());
        for (long timestamp = 0; timestamp <= 101; timestamp++) {
            long cutoff = timestamp;
            var expected = ValidatedCandleWindow.latest(candles.stream().filter(candle -> candle.timestamp() <= cutoff).toList());
            assertThat(prefixes.at(timestamp)).as("timestamp %s", timestamp).isEqualTo(expected);
        }
    }
}
