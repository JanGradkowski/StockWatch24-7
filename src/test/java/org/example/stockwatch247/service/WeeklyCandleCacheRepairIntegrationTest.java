package org.example.stockwatch247.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alerts.email.enabled=false"})
@Transactional
class WeeklyCandleCacheRepairIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    @Test
    void removesDailySnapshotsWithoutOverwritingWeeklyOhlcAndInvalidatesOnlyAffectedCaches() {
        String symbol = "WK" + UUID.randomUUID().toString().substring(0, 8);
        String empty = symbol + "E", untouched = symbol + "U";
        long monday = LocalDate.of(2026, 9, 7).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long friday = monday + 4 * 86400L;
        candle(symbol, "1wk", monday, 123);
        candle(symbol, "1wk", monday - 3 * 86400L, 80);
        candle(symbol, "1wk", monday + 16 * 3600L, 90);
        candle(symbol, "1wk", friday, 95);
        candle(symbol, "1d", friday, 99);
        candle(empty, "1wk", friday, 95);
        candle(untouched, "1wk", monday, 125);
        state(symbol, "1wk", monday - 3 * 86400L);
        state(symbol, "1d", friday);
        state(empty, "1wk", friday);
        state(untouched, "1wk", monday);
        long generation = generation(symbol);
        long untouchedGeneration = generation(untouched);

        var repair = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V70__remove_noncanonical_weekly_snapshots.sql"));
        repair.execute(dataSource);

        assertThat(jdbc.queryForList("select timestamp from candles where symbol=? and time_interval='1wk'", Long.class, symbol))
                .containsExactly(monday);
        assertThat(jdbc.queryForObject("select close_price from candles where symbol=? and time_interval='1wk'", Double.class, symbol))
                .isEqualTo(123);
        assertThat(jdbc.queryForObject("select close_price from candles where symbol=? and time_interval='1d'", Double.class, symbol))
                .isEqualTo(99);
        assertThat(jdbc.queryForObject("select count(*) from candles where symbol=?", Long.class, empty)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from market_data_sync_state where symbol in (?,?) and time_interval='1wk'", Long.class, symbol, empty))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from market_data_sync_state where symbol=? or (symbol=? and time_interval='1d')", Long.class, untouched, symbol))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select oldest_timestamp from market_data_history_state where symbol=? and time_interval='1wk'", Long.class, symbol))
                .isEqualTo(monday);
        assertThat(jdbc.queryForObject("select count(*) from market_data_history_state where symbol=?", Long.class, empty)).isZero();
        assertThat(generation(symbol)).isGreaterThan(generation);
        assertThat(generation(untouched)).isEqualTo(untouchedGeneration);

        long repairedGeneration = generation(symbol);
        repair.execute(dataSource);
        assertThat(generation(symbol)).isEqualTo(repairedGeneration);
    }

    private void candle(String symbol, String interval, long timestamp, double price) {
        jdbc.update("insert into candles(symbol,time_interval,timestamp,open_price,high_price,low_price,close_price,volume) values (?,?,?,?,?,?,?,1000)",
                symbol, interval, timestamp, price, price, price, price);
    }

    private void state(String symbol, String interval, long oldest) {
        jdbc.update("insert into market_data_sync_state(symbol,time_interval,last_success_at) values (?,?,current_timestamp)", symbol, interval);
        jdbc.update("insert into market_data_history_state(symbol,time_interval,oldest_timestamp,end_reached) values (?,?,?,true)", symbol, interval, oldest);
    }

    private long generation(String symbol) {
        return jdbc.queryForObject("select generation from candle_revisions where symbol=? and time_interval='1wk'", Long.class, symbol);
    }
}
