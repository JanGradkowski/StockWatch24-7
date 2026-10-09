package org.example.stockwatch247.service;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.example.stockwatch247.model.EnrichedCandle;

final class CausalCandlePrefixes {
    private final List<EnrichedCandle> candles;
    private final ValidatedCandleWindow[] segments;
    private final int[] starts;

    CausalCandlePrefixes(List<EnrichedCandle> input) {
        candles = input == null || input.stream().anyMatch(candle -> candle == null || candle.timestamp() == null)
                ? List.of() : input.stream().sorted(Comparator.comparing(EnrichedCandle::timestamp)).toList();
        segments = new ValidatedCandleWindow[candles.size()];
        starts = new int[candles.size()];
        Set<Long> seen = new HashSet<>();
        Set<Long> duplicates = new HashSet<>();
        for (EnrichedCandle candle : candles) if (!seen.add(candle.timestamp())) duplicates.add(candle.timestamp());
        int start = 0;
        for (int index = 0; index <= candles.size(); index++) {
            if (index < candles.size() && PatternCandleIntegrity.valid(candles.get(index))
                    && !duplicates.contains(candles.get(index).timestamp())) continue;
            if (start < index) {
                ValidatedCandleWindow window = ValidatedCandleWindow.latest(candles.subList(start, index));
                for (int member = start; member < index; member++) {
                    segments[member] = window;
                    starts[member] = start;
                }
            }
            start = index + 1;
        }
    }

    List<EnrichedCandle> at(long timestamp) {
        int low = 0;
        int high = candles.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (candles.get(middle).timestamp() <= timestamp) low = middle + 1;
            else high = middle;
        }
        int last = low - 1;
        if (last < 0 || segments[last] == null) return List.of();
        return segments[last].subList(0, last - starts[last] + 1);
    }
}
