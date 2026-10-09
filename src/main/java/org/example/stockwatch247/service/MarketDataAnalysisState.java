package org.example.stockwatch247.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MarketDataAnalysisState {
    private final JdbcTemplate jdbc;
    public MarketDataAnalysisState(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean recentlyBootstrapped(String symbol, String interval, int required) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(select 1 from market_data_analysis_state where symbol=? and interval=?
                and requested >= ? and received > 0 and checked_at > current_timestamp - interval '1 day')
            """, Boolean.class, symbol, interval, required));
    }
    public void record(String symbol, String interval, int requested, int received) {
        jdbc.update("""
            insert into market_data_analysis_state(symbol, interval, requested, received) values(?,?,?,?)
            on conflict(symbol, interval) do update set requested=excluded.requested,
                received=excluded.received, checked_at=current_timestamp
            """, symbol, interval, requested, received);
    }
}
