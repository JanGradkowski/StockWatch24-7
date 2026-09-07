package org.example.stockwatch247.service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Immutable input/results reused within one symbol job only; never shared across jobs or users' requests. */
final class AnalysisComputationScope implements AutoCloseable {
    private static final ThreadLocal<Map<Object, Object>> VALUES = new ThreadLocal<>();
    private final Map<Object, Object> previous;
    private AnalysisComputationScope() { previous = VALUES.get(); VALUES.set(new HashMap<>()); }
    static AnalysisComputationScope open() { return new AnalysisComputationScope(); }
    @SuppressWarnings("unchecked")
    static <T> T memo(Object key, Supplier<T> calculation) {
        Map<Object, Object> values = VALUES.get();
        if (values == null) return calculation.get();
        if (values.containsKey(key)) return (T) values.get(key);
        T result = calculation.get(); // Nested computations may also populate this map.
        values.put(key, result);
        return result;
    }
    @Override public void close() { if (previous == null) VALUES.remove(); else VALUES.set(previous); }
}
