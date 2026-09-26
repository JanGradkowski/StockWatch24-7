package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.EnrichedCandle;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Never join price action across malformed or duplicate observations. */
public final class PatternCandleIntegrity {
    private PatternCandleIntegrity() { }

    static List<Candle> raw(List<Candle> input) {
        if (!sameSeries(input)) return List.of();
        return latestSegment(input, Candle::getTimestamp, CandlestickFormationIntegrity::valid);
    }

    static List<EnrichedCandle> enriched(List<EnrichedCandle> input) {
        return latestSegment(input, EnrichedCandle::timestamp, PatternCandleIntegrity::valid);
    }

    static List<List<Candle>> rawSegments(List<Candle> input) {
        if (!sameSeries(input)) return List.of();
        return segments(input, Candle::getTimestamp, CandlestickFormationIntegrity::valid);
    }

    private static boolean sameSeries(List<Candle> input) {
        return input == null || input.stream().filter(Objects::nonNull)
                .map(c -> c.getSymbol() + ":" + c.getTimeInterval()).distinct().limit(2).count() <= 1;
    }

    static List<List<EnrichedCandle>> enrichedSegments(List<EnrichedCandle> input) {
        return segments(input, EnrichedCandle::timestamp, PatternCandleIntegrity::valid);
    }

    /** Calendar completeness cannot be inferred from elapsed time across exchange holidays. */
    public static Coverage inspect(List<Candle> input, Collection<Long> expectedPeriodStarts) {
        List<String> issues = new ArrayList<>();
        if (input == null || input.isEmpty()) return new Coverage(false, List.of("No candles supplied."));
        Set<Long> seen = new HashSet<>();
        Set<String> identities = new HashSet<>();
        for (Candle candle : input) {
            if (!CandlestickFormationIntegrity.valid(candle)) issues.add("Malformed or missing OHLC bar.");
            if (candle == null || candle.getTimestamp() == null) continue;
            if (!seen.add(candle.getTimestamp())) issues.add("Duplicate timestamp: " + candle.getTimestamp());
            identities.add(candle.getSymbol() + ":" + candle.getTimeInterval());
        }
        if (identities.size() > 1) issues.add("Mixed symbols or intervals.");
        if (expectedPeriodStarts != null) for (Long timestamp : expectedPeriodStarts) {
            if (!seen.contains(timestamp)) issues.add("Missing exchange period: " + timestamp);
        }
        return new Coverage(expectedPeriodStarts != null && issues.isEmpty(), issues.stream().distinct().toList());
    }

    public record Coverage(boolean calendarContinuityVerified, List<String> issues) { }

    private static <T> List<List<T>> segments(List<T> input, Function<T, Long> timestamp, Predicate<T> valid) {
        if (input == null || input.isEmpty()
                || input.stream().anyMatch(c -> c == null || timestamp.apply(c) == null)) return List.of();
        List<T> ordered = input.stream().sorted(Comparator.comparing(timestamp)).toList();
        Set<Long> duplicates = new HashSet<>(), seen = new HashSet<>();
        for (T c : ordered) if (!seen.add(timestamp.apply(c))) duplicates.add(timestamp.apply(c));
        List<List<T>> result = new ArrayList<>();
        int start = 0;
        for (int i=0;i<ordered.size();i++) {
            T c=ordered.get(i);
            if (!valid.test(c) || duplicates.contains(timestamp.apply(c))) {
                if (i>start) result.add(ordered.subList(start,i));
                start=i+1;
            }
        }
        if (start<ordered.size()) result.add(ordered.subList(start,ordered.size()));
        return List.copyOf(result);
    }

    static boolean valid(EnrichedCandle c) {
        return c != null && c.timestamp() != null
                && Double.isFinite(c.open()) && Double.isFinite(c.high())
                && Double.isFinite(c.low()) && Double.isFinite(c.close())
                && c.low() > 0 && c.high() >= Math.max(c.open(), c.close())
                && c.low() <= Math.min(c.open(), c.close());
    }

    private static <T> List<T> latestSegment(List<T> input, Function<T, Long> timestamp,
                                            Predicate<T> valid) {
        if (input == null || input.isEmpty()) return List.of();
        // A bar without an identity cannot be placed safely on either side of a gap.
        if (input.stream().anyMatch(c -> c == null || timestamp.apply(c) == null)) return List.of();
        List<T> ordered = input.stream().sorted(Comparator.comparing(timestamp)).toList();
        Set<Long> duplicates = new HashSet<>(), seen = new HashSet<>();
        for (T c : ordered) if (!seen.add(timestamp.apply(c))) duplicates.add(timestamp.apply(c));
        int start = 0;
        for (int i = 0; i < ordered.size(); i++) {
            T c = ordered.get(i);
            if (!valid.test(c) || duplicates.contains(timestamp.apply(c))) start = i + 1;
        }
        return ordered.subList(start, ordered.size());
    }
}
