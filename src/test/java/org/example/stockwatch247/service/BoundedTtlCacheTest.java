package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedTtlCacheTest {

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
