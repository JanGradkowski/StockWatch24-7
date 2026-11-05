package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedTtlCacheTest {

    @Test
    void failedComputationCanBeRetriedWithoutPoisoningTheCache() {
        BoundedTtlCache<String, String> cache = new BoundedTtlCache<>(2, 10);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cache.getOrCompute("one", () -> {
            throw new IllegalStateException("failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(cache.getOrCompute("one", () -> "recovered")).isEqualTo("recovered");
        assertThat(cache.getOrCompute("one", () -> "wrong")).isEqualTo("recovered");
    }

    @Test
    void aSlowComputationDoesNotBlockOtherKeysAndRunsOncePerKey() throws Exception {
        BoundedTtlCache<String, String> cache = new BoundedTtlCache<>(8, 60);
        var slowStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseSlow = new java.util.concurrent.CountDownLatch(1);
        var slowRuns = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var slow = pool.submit(() -> cache.getOrCompute("slow", () -> {
                slowRuns.incrementAndGet();
                slowStarted.countDown();
                try { releaseSlow.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "slow";
            }));
            slowStarted.await();
            var sameKey = pool.submit(() -> cache.getOrCompute("slow", () -> {
                slowRuns.incrementAndGet();
                return "duplicate";
            }));

            // Another key completes while the slow one is still running.
            assertThat(pool.submit(() -> cache.getOrCompute("fast", () -> "fast")).get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo("fast");

            releaseSlow.countDown();
            assertThat(slow.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("slow");
            assertThat(sameKey.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("slow");
            assertThat(slowRuns).hasValue(1);
        }
    }

    @Test
    void expiringEveryCacheEmptiesThemAll() {
        BoundedTtlCache<String, String> first = new BoundedTtlCache<>(4, 600);
        BoundedTtlCache<String, String> second = new BoundedTtlCache<>(4, 600);
        first.getOrCompute("one", () -> "1");
        second.getOrCompute("two", () -> "2");

        BoundedTtlCache.expireAll();

        assertThat(first.size()).isZero();
        assertThat(second.size()).isZero();
        assertThat(first.getOrCompute("one", () -> "computed again")).isEqualTo("computed again");
    }

    @Test
    void aResultTheCallerDeclinesToKeepIsReturnedButNotCached() {
        BoundedTtlCache<String, String> cache = new BoundedTtlCache<>(2, 10);
        assertThat(cache.getOrCompute("one", () -> "too large", value -> false)).isEqualTo("too large");
        assertThat(cache.size()).isZero();
        assertThat(cache.getOrCompute("one", () -> "kept", value -> true)).isEqualTo("kept");
        assertThat(cache.getOrCompute("one", () -> "recomputed")).isEqualTo("kept");
    }

    @Test
    void aComputationThatAsksForItsOwnKeyDoesNotWaitOnItself() {
        BoundedTtlCache<String, String> cache = new BoundedTtlCache<>(2, 10);
        assertThat(cache.getOrCompute("outer", () -> "outer:" + cache.getOrCompute("outer", () -> "inner")))
                .isEqualTo("outer:inner");
    }

    @Test
    void entriesAddedFromManyThreadsAtOnceStayWithinTheMaximum() throws Exception {
        BoundedTtlCache<String, Integer> cache = new BoundedTtlCache<>(16, 600);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var adding = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int thread = 0; thread < 8; thread++) {
                int first = thread * 1000;
                adding.add(pool.submit(() -> {
                    for (int value = first; value < first + 1000; value++) {
                        cache.put("key-" + value, value, 100);
                        assertThat(cache.get("key-" + (value - first) / 2, 100)).isIn(null, (value - first) / 2);
                    }
                }));
            }
            for (var thread : adding) thread.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(cache.size()).isEqualTo(16);
        cache.put("last", -1, 100);
        assertThat(cache.size()).isEqualTo(16);
        assertThat(cache.get("last", 100)).isEqualTo(-1);
    }

    @Test
    void enforcesStrictMaximumAndExpiresEntries() {
        BoundedTtlCache<String, String> cache = new BoundedTtlCache<>(2, 10);
        cache.put("one", "1", 100);
        cache.put("two", "2", 101);
        assertThat(cache.get("one", 102)).isEqualTo("1");

        cache.put("three", "3", 103);

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("two", 103)).isNull();
        assertThat(cache.get("one", 111)).isNull();
        assertThat(cache.get("three", 113)).isNull();
        assertThat(cache.size()).isZero();
    }
}
