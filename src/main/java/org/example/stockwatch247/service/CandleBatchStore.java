package org.example.stockwatch247.service;

import org.example.stockwatch247.model.Candle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.List;

/** Performs cold candle bootstraps as bounded JDBC batches instead of identity-based JPA inserts. */
@Repository
public class CandleBatchStore {
    private static final int BATCH_SIZE = 100;
    private static final String UPSERT = """
            insert into candles
                (symbol, time_interval, timestamp, open_price, high_price, low_price, close_price, volume, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
            on conflict (symbol, time_interval, timestamp) do update set
                open_price = excluded.open_price,
                high_price = excluded.high_price,
                low_price = excluded.low_price,
                close_price = excluded.close_price,
                volume = excluded.volume,
                updated_at = current_timestamp
            """;

    private final JdbcTemplate jdbcTemplate;

    public CandleBatchStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void upsert(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) return;
        jdbcTemplate.batchUpdate(UPSERT, candles, BATCH_SIZE, this::bind);
    }

    private void bind(PreparedStatement statement, Candle candle) throws java.sql.SQLException {
        statement.setString(1, candle.getSymbol());
        statement.setString(2, candle.getTimeInterval());
        statement.setLong(3, candle.getTimestamp());
        statement.setDouble(4, candle.getOpenPrice());
        statement.setDouble(5, candle.getHighPrice());
        statement.setDouble(6, candle.getLowPrice());
        statement.setDouble(7, candle.getClosePrice());
        if (candle.getVolume() == null) statement.setNull(8, java.sql.Types.BIGINT);
        else statement.setLong(8, candle.getVolume());
    }
}
