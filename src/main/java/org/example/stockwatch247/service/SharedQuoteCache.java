package org.example.stockwatch247.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SharedQuoteCache {
    private static final TypeReference<Map<String, Object>> QUOTE_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SharedQuoteCache(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<Map<String, Object>> getFresh(String symbol, long ttlSeconds) {
        List<String> values = jdbcTemplate.query(
                """
                select quote_json from live_quote_cache
                where symbol = ?
                  and cached_at > current_timestamp - (? * interval '1 second')
                """,
                (rs, rowNum) -> rs.getString(1),
                symbol, Math.max(0L, ttlSeconds));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(values.getFirst(), QUOTE_TYPE));
        } catch (JsonProcessingException e) {
            jdbcTemplate.update("delete from live_quote_cache where symbol = ?", symbol);
            return Optional.empty();
        }
    }

    public void put(String symbol, Map<String, Object> quote) {
        try {
            jdbcTemplate.update(
                    """
                    insert into live_quote_cache (symbol, quote_json, cached_at)
                    values (?, ?, current_timestamp)
                    on conflict (symbol) do update
                    set quote_json = excluded.quote_json, cached_at = excluded.cached_at
                    """,
                    symbol, objectMapper.writeValueAsString(quote));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the shared quote cache entry", e);
        }
    }

    @Scheduled(cron = "${twelve-data.quote-cache-cleanup-cron:0 0 3 * * *}")
    public void removeStaleEntries() {
        jdbcTemplate.update(
                "delete from live_quote_cache where cached_at < current_timestamp - interval '7 days'");
    }
}
