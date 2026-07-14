package org.example.stockwatch247.service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small synchronized LRU/TTL cache with a strict maximum entry count. */
final class BoundedTtlCache<K, V> {
    private final int maximumSize;
    private final long ttlSeconds;
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

    private void removeExpired(long nowEpochSeconds) {
        entries.entrySet().removeIf(entry ->
                nowEpochSeconds - entry.getValue().createdAtEpochSeconds() >= ttlSeconds);
    }

    private record Entry<V>(long createdAtEpochSeconds, V value) {
    }
}
