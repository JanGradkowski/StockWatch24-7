package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps raw-data boundaries even when indicator enrichment drops malformed bars. */
final class CandlestickFormationIntegrity {
    private final Map<Long, Integer> indexes = new HashMap<>();
    private final Set<Long> invalid = new HashSet<>();
    private final boolean unknownTimestamp;
    private final Long latestTimestamp;

    CandlestickFormationIntegrity(List<Candle> raw) {
        unknownTimestamp = raw == null || raw.stream().anyMatch(c -> c == null || c.getTimestamp() == null);
        List<Candle> ordered = unknownTimestamp ? List.of() : raw.stream()
                .sorted(Comparator.comparing(Candle::getTimestamp)).toList();
        for (int index = 0; index < ordered.size(); index++) {
            Candle candle = ordered.get(index);
            if (indexes.put(candle.getTimestamp(), index) != null || !valid(candle)) {
                invalid.add(candle.getTimestamp());
            }
        }
        latestTimestamp = ordered.isEmpty() ? null : ordered.getLast().getTimestamp();
    }

    List<EnrichedCandle> latestContext(List<EnrichedCandle> enriched) {
        if (enriched == null || enriched.isEmpty() || latestTimestamp == null
                || !latestTimestamp.equals(enriched.getLast().timestamp())) return List.of();
        return context(enriched);
    }

    List<EnrichedCandle> context(List<EnrichedCandle> enriched) {
        if (unknownTimestamp || enriched == null || enriched.isEmpty()) return List.of();
        int start = 0;
        Integer previous = null;
        for (int index = 0; index < enriched.size(); index++) {
            EnrichedCandle candle = enriched.get(index);
            Long timestamp = candle == null ? null : candle.timestamp();
            Integer sourceIndex = indexes.get(timestamp);
            if (sourceIndex == null || invalid.contains(timestamp)) {
                start = index + 1;
                previous = null;
            } else {
                if (previous != null && sourceIndex != previous + 1) start = index;
                previous = sourceIndex;
            }
        }
        return enriched.subList(start, enriched.size());
    }

    boolean adjacent(long first, long second) {
        Integer left = indexes.get(first), right = indexes.get(second);
        return !unknownTimestamp && left != null && right != null && right == left + 1
                && !invalid.contains(first) && !invalid.contains(second);
    }

    static boolean valid(Candle candle) {
        return candle != null && candle.getTimestamp() != null
                && finitePositive(candle.getOpenPrice()) && finitePositive(candle.getHighPrice())
                && finitePositive(candle.getLowPrice()) && finitePositive(candle.getClosePrice())
                && candle.getHighPrice() >= Math.max(candle.getOpenPrice(), candle.getClosePrice())
                && candle.getLowPrice() <= Math.min(candle.getOpenPrice(), candle.getClosePrice());
    }

    private static boolean finitePositive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }
}
