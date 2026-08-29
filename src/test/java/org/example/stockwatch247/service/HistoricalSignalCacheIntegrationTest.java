package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@Transactional
class HistoricalSignalCacheIntegrationTest {
    @Autowired
    private HistoricalSignalCacheService cacheService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void reusesPayloadAndInvalidatesItAfterTheCandleSetChanges() {
        String symbol = ("CACHE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                .toUpperCase(Locale.ROOT);
        AtomicInteger calculations = new AtomicInteger();

        CachedResult first = cached(symbol, calculations);
        CachedResult hit = cached(symbol, calculations);

        assertThat(first).isEqualTo(new CachedResult("run-1", List.of(1)));
        assertThat(hit).isEqualTo(first);
        assertThat(calculations).hasValue(1);

        jdbcTemplate.update(
                """
                insert into candles
                    (symbol, time_interval, timestamp, open_price, high_price, low_price,
                     close_price, volume, updated_at)
                values (?, '1d', 1777000000, 10, 11, 9, 10.5, 1000, current_timestamp)
                """,
                symbol);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from candles where symbol = ? and time_interval = '1d'",
                Long.class,
                symbol)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select candle_count from historical_signal_cache where symbol = ?",
                Long.class,
                symbol)).isZero();

        CachedResult invalidated = cached(symbol, calculations);

        assertThat(invalidated).isEqualTo(new CachedResult("run-2", List.of(2)));
        assertThat(calculations).hasValue(2);
    }

    private CachedResult cached(String symbol, AtomicInteger calculations) {
        return cacheService.getOrCompute(
                HistoricalSignalCacheService.Family.HARMONIC_FORMATION,
                symbol,
                "1d",
                "TEST_V1",
                new Settings(0.08),
                CachedResult.class,
                () -> {
                    int run = calculations.incrementAndGet();
                    return new CachedResult("run-" + run, List.of(run));
                });
    }

    private record Settings(double tolerance) { }
    private record CachedResult(String name, List<Integer> values) { }
}
