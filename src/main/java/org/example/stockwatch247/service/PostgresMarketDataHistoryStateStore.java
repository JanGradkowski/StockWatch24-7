package org.example.stockwatch247.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresMarketDataHistoryStateStore implements MarketDataHistoryStateStore {
    private final JdbcTemplate jdbcTemplate;

    public PostgresMarketDataHistoryStateStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean isEndReached(String symbol, String interval) {
        return jdbcTemplate.query(
                "select end_reached from market_data_history_state where symbol = ? and time_interval = ?",
                preparedStatement -> {
                    preparedStatement.setString(1, symbol);
                    preparedStatement.setString(2, interval);
                },
                resultSet -> resultSet.next() && resultSet.getBoolean(1));
    }

    @Override
    public void recordProgress(String symbol, String interval, long oldestTimestamp, boolean endReached) {
        jdbcTemplate.update("""
                insert into market_data_history_state
                    (symbol, time_interval, oldest_timestamp, end_reached, updated_at)
                values (?, ?, ?, ?, current_timestamp)
                on conflict (symbol, time_interval) do update set
                    oldest_timestamp = least(market_data_history_state.oldest_timestamp, excluded.oldest_timestamp),
                    end_reached = market_data_history_state.end_reached or excluded.end_reached,
                    updated_at = current_timestamp
                """, symbol, interval, oldestTimestamp, endReached);
    }
}
