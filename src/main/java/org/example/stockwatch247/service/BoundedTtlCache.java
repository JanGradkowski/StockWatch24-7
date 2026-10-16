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
    private final java.util.concurrent.ConcurrentHashMap<K, Computation<V>> inFlight = new java.util.concurrent.ConcurrentHashMap<>();

    private record Computation<V>(Thread owner, java.util.concurrent.CompletableFuture<V> result) { }
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

    /**
     * One computation per key: callers asking for the same key while it runs wait for its result, and
     * callers for other keys are never held up by it. If the running computation fails, a waiting
     * caller computes again itself rather than inheriting another caller's failure.
     */
    V getOrCompute(K key, Supplier<V> supplier) {
        while (true) {
            V cached = get(key, java.time.Instant.now().getEpochSecond());
            if (cached != null) return cached;
            Computation<V> mine = new Computation<>(Thread.currentThread(), new java.util.concurrent.CompletableFuture<>());
            Computation<V> running = inFlight.putIfAbsent(key, mine);
            if (running != null) {
                // A computation that asks for its own key again computes it directly instead of waiting on itself.
                if (running.owner() == Thread.currentThread()) return supplier.get();
                try {
                    return running.result().join();
                } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException failed) {
                    continue;
                }
            }
            try {
                cached = get(key, java.time.Instant.now().getEpochSecond());
                V value = cached != null ? cached : supplier.get();
                if (cached == null) put(key, value, java.time.Instant.now().getEpochSecond());
                mine.result().complete(value);
                return value;
            } catch (RuntimeException | Error failure) {
                // Removed before waiters wake, so the one that retries becomes the new computation.
                inFlight.remove(key, mine);
                mine.result().completeExceptionally(failure);
                throw failure;
            } finally {
                inFlight.remove(key, mine);
            }
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
