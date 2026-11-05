package org.example.stockwatch247.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wall time of one stock check, split by stage. Every moment belongs to exactly one stage, the innermost one
 * entered, so the stages add up to the check's total. Counters record how often something happened (scans
 * computed, checkpoint reads). Outside a check, and on threads other than the check's, every call does nothing.
 */
public final class CheckTimings {
    private static final ThreadLocal<CheckTimings> CURRENT = new ThreadLocal<>();
    private static final Stage NONE = () -> { };

    private final Map<String, Long> nanos = new LinkedHashMap<>();
    private final Map<String, Long> counts = new LinkedHashMap<>();
    private final List<String> open = new ArrayList<>();
    private final CheckTimings previous;
    private long since = System.nanoTime();

    /** Ends the stage it opened and resumes the one around it. */
    @FunctionalInterface
    public interface Stage extends AutoCloseable {
        @Override void close();
    }

    private CheckTimings(String first) {
        previous = CURRENT.get();
        open.add(first);
    }

    /** Starts timing a check on this thread; time not inside a named stage goes to {@code first}. */
    static CheckTimings start(String first) {
        CheckTimings timings = new CheckTimings(first);
        CURRENT.set(timings);
        return timings;
    }

    /** Stops timing on this thread (the totals stay readable); later calls do nothing. */
    void stop() {
        if (CURRENT.get() != this) return;
        charge();
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    public static Stage enter(String stage) {
        CheckTimings timings = CURRENT.get();
        if (timings == null) return NONE;
        timings.charge();
        timings.open.add(stage);
        int depth = timings.open.size();
        return () -> {
            timings.charge();
            // Closing out of order (an exception unwinding several stages) still returns to the stage around this one.
            while (timings.open.size() >= depth) timings.open.removeLast();
        };
    }

    public static void count(String counter) {
        CheckTimings timings = CURRENT.get();
        if (timings != null) timings.counts.merge(counter, 1L, Long::sum);
    }

    private void charge() {
        long now = System.nanoTime();
        if (!open.isEmpty()) nanos.merge(open.getLast(), now - since, Long::sum);
        since = now;
    }

    /** Milliseconds per stage, largest first. */
    Map<String, Long> millis() {
        Map<String, Long> result = new LinkedHashMap<>();
        nanos.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue() / 1_000_000));
        return result;
    }

    Map<String, Long> counts() {
        return Map.copyOf(counts);
    }

    /** "stage=ms ..." for every stage that took at least a millisecond, then the counters. */
    String summary() {
        StringBuilder text = new StringBuilder();
        millis().forEach((stage, millis) -> {
            if (millis > 0) text.append(text.isEmpty() ? "" : " ").append(stage).append('=').append(millis);
        });
        if (!counts.isEmpty()) text.append(" counts=").append(new java.util.TreeMap<>(counts));
        return text.toString();
    }
}
