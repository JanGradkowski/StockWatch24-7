package org.example.stockwatch247.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Small LRU/TTL cache with a strict maximum entry count (exceeded only while entries are being added). Keys are
 * hashed and compared on the caller's own thread without a lock, so checks running side by side, and the threads of
 * one parallel count, never queue behind each other's lookups; only evicting is serialized.
 */
final class BoundedTtlCache<K, V> {
    private final int maximumSize;
    private final long ttlSeconds;
    private final ConcurrentHashMap<K, Computation<V>> inFlight = new ConcurrentHashMap<>();

    private record Computation<V>(Thread owner, java.util.concurrent.CompletableFuture<V> result) { }
    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    /** Orders the entries by last use, so the least recently used one is evicted first. */
    private final AtomicLong uses = new AtomicLong();

    /** Every cache, so a benchmark that replays days within minutes can let them expire as the days would. */
    private static final java.util.Set<BoundedTtlCache<?, ?>> ALL =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    BoundedTtlCache(int maximumSize, long ttlSeconds) {
        this.maximumSize = Math.max(1, maximumSize);
        this.ttlSeconds = Math.max(1L, ttlSeconds);
        ALL.add(this);
    }

    /** Empties every cache, as if its time had run out (computations still running are not affected). */
    static void expireAll() {
        java.util.List<BoundedTtlCache<?, ?>> caches;
        synchronized (ALL) {
            caches = java.util.List.copyOf(ALL);
        }
        caches.forEach(cache -> cache.removeIf(key -> true));
    }

    V get(K key, long nowEpochSeconds) {
        Entry<V> entry = key == null ? null : entries.get(key);
        if (entry == null) {
            return null;
        }
        if (nowEpochSeconds - entry.createdAtEpochSeconds >= ttlSeconds) {
            entries.remove(key, entry);
            return null;
        }
        entry.lastUsed = uses.incrementAndGet();
        return entry.value;
    }

    void put(K key, V value, long nowEpochSeconds) {
        if (key == null) return;
        entries.put(key, new Entry<>(nowEpochSeconds, value, uses.incrementAndGet()));
        if (entries.size() > maximumSize || nowEpochSeconds != lastSweep) evict(nowEpochSeconds);
    }

    /** The second expired entries were last swept out; lookups never return one, so once a second is enough. */
    private volatile long lastSweep = Long.MIN_VALUE;

    private synchronized void evict(long nowEpochSeconds) {
        if (nowEpochSeconds != lastSweep) {
            removeExpired(nowEpochSeconds);
            lastSweep = nowEpochSeconds;
        }
        while (entries.size() > maximumSize) {
            K eldest = null;
            long eldestUse = Long.MAX_VALUE;
            for (Map.Entry<K, Entry<V>> candidate : entries.entrySet()) {
                if (candidate.getValue().lastUsed < eldestUse) {
                    eldestUse = candidate.getValue().lastUsed;
                    eldest = candidate.getKey();
                }
            }
            entries.remove(eldest);
        }
    }

    int size() {
        return entries.size();
    }

    /**
     * One computation per key: callers asking for the same key while it runs wait for its result, and
     * callers for other keys are never held up by it. If the running computation fails, a waiting
     * caller computes again itself rather than inheriting another caller's failure.
     */
    V getOrCompute(K key, Supplier<V> supplier) {
        return getOrCompute(key, supplier, value -> true);
    }

    /** As above; a result {@code retain} rejects is handed to the callers waiting for it but not kept. */
    V getOrCompute(K key, Supplier<V> supplier, Predicate<V> retain) {
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
                if (cached == null && retain.test(value)) put(key, value, java.time.Instant.now().getEpochSecond());
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
        entries.values().removeIf(entry -> nowEpochSeconds - entry.createdAtEpochSeconds >= ttlSeconds);
    }

    private static final class Entry<V> {
        final long createdAtEpochSeconds;
        final V value;
        volatile long lastUsed;

        Entry(long createdAtEpochSeconds, V value, long lastUsed) {
            this.createdAtEpochSeconds = createdAtEpochSeconds;
            this.value = value;
            this.lastUsed = lastUsed;
        }
    }
}
