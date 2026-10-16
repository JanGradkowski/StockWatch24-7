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
    void aComputationThatAsksForItsOwnKeyDoesNotWaitOnItself() {
        BoundedTtlCache<String, String> cache = new BoundedTtlCache<>(2, 10);
        assertThat(cache.getOrCompute("outer", () -> "outer:" + cache.getOrCompute("outer", () -> "inner")))
                .isEqualTo("outer:inner");
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
