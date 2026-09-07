package org.example.stockwatch247.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Locale;

@Service
public class CandleRevisionService {
    private final JdbcTemplate jdbc;
    public CandleRevisionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public long generation(String symbol, String interval) {
        return jdbc.queryForObject("""
            select coalesce((select generation from candle_revisions where symbol = ? and time_interval = ?), 0)
            """, Long.class, symbol.toUpperCase(Locale.ROOT), interval);
    }
}
