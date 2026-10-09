package org.example.stockwatch247.service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Small synchronized LRU/TTL cache with a strict maximum entry count. */
final class BoundedTtlCache<K, V> {
    private final int maximumSize;
    private final long ttlSeconds;
    private final Object[] computations = java.util.stream.IntStream.range(0, 32)
            .mapToObj(index -> new Object()).toArray();
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(128, 0.75f, true);

    BoundedTtlCache(int maximumSize, long ttlSeconds) {
        this.maximumSize = Math.max(1, maximumSize);
        this.ttlSeconds = Math.max(1L, ttlSeconds);
    }

    synchronized V get(K key, long nowEpochSeconds) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (nowEpochSeconds - entry.createdAtEpochSeconds() >= ttlSeconds) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    synchronized void put(K key, V value, long nowEpochSeconds) {
        removeExpired(nowEpochSeconds);
        entries.put(key, new Entry<>(nowEpochSeconds, value));
        while (entries.size() > maximumSize) {
            Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    synchronized int size() {
        return entries.size();
    }

    V getOrCompute(K key, Supplier<V> supplier) {
        V cached = get(key, java.time.Instant.now().getEpochSecond());
        if (cached != null) return cached;
        synchronized (computations[Math.floorMod(key.hashCode(), computations.length)]) {
            cached = get(key, java.time.Instant.now().getEpochSecond());
            if (cached != null) return cached;
            V value = supplier.get();
            put(key, value, java.time.Instant.now().getEpochSecond());
            return value;
        }
    }

    synchronized void removeIf(Predicate<K> predicate) {
        if (predicate != null) {
            entries.keySet().removeIf(predicate);
        }
    }

    private void removeExpired(long nowEpochSeconds) {
        entries.entrySet().removeIf(entry ->
                nowEpochSeconds - entry.getValue().createdAtEpochSeconds() >= ttlSeconds);
    }

    private record Entry<V>(long createdAtEpochSeconds, V value) {
    }
}
