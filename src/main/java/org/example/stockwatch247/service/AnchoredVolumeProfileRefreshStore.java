package org.example.stockwatch247.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class AnchoredVolumeProfileRefreshStore {
    private final JdbcTemplate jdbcTemplate;

    public AnchoredVolumeProfileRefreshStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RefreshState> find(
            long userId,
            String symbol,
            String interval,
            long activeCandleTimestamp) {
        List<RefreshState> rows = jdbcTemplate.query(
                """
                select refresh_count,
                       last_refreshed_at,
                       provider_source,
                       snapshot_interval,
                       snapshot_candle_timestamp,
                       snapshot_open,
                       snapshot_high,
                       snapshot_low,
                       snapshot_close,
                       snapshot_volume
                from anchored_volume_profile_refresh_state
                where user_id = ?
                  and symbol = ?
                  and chart_interval = ?
                  and active_candle_timestamp = ?
                """,
                (resultSet, rowNumber) -> mapState(resultSet),
                userId,
                symbol,
                interval,
                activeCandleTimestamp);
        return rows.stream().findFirst();
    }

    public Optional<RefreshState> recordProviderRefresh(
            long userId,
            String symbol,
            String interval,
            long activeCandleTimestamp,
            int maximumRefreshes,
            String providerSource,
            CandleSnapshot snapshot) {
        List<RefreshState> rows = jdbcTemplate.query(
                """
                insert into anchored_volume_profile_refresh_state (
                    user_id,
                    symbol,
                    chart_interval,
                    active_candle_timestamp,
                    refresh_count,
                    last_refreshed_at,
                    provider_source,
                    snapshot_interval,
                    snapshot_candle_timestamp,
                    snapshot_open,
                    snapshot_high,
                    snapshot_low,
                    snapshot_close,
                    snapshot_volume
                )
                values (?, ?, ?, ?, 1, current_timestamp, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (user_id, symbol, chart_interval, active_candle_timestamp)
                do update set
                    refresh_count =
                        anchored_volume_profile_refresh_state.refresh_count + 1,
                    last_refreshed_at = current_timestamp,
                    provider_source = excluded.provider_source,
                    snapshot_interval = excluded.snapshot_interval,
                    snapshot_candle_timestamp = excluded.snapshot_candle_timestamp,
                    snapshot_open = excluded.snapshot_open,
                    snapshot_high = excluded.snapshot_high,
                    snapshot_low = excluded.snapshot_low,
                    snapshot_close = excluded.snapshot_close,
                    snapshot_volume = excluded.snapshot_volume
                where anchored_volume_profile_refresh_state.refresh_count < ?
                returning refresh_count,
                          last_refreshed_at,
                          provider_source,
                          snapshot_interval,
                          snapshot_candle_timestamp,
                          snapshot_open,
                          snapshot_high,
                          snapshot_low,
                          snapshot_close,
                          snapshot_volume
                """,
                (resultSet, rowNumber) -> mapState(resultSet),
                userId,
                symbol,
                interval,
                activeCandleTimestamp,
                providerSource,
                snapshot.interval(),
                snapshot.timestamp(),
                snapshot.open(),
                snapshot.high(),
                snapshot.low(),
                snapshot.close(),
                snapshot.volume(),
                Math.max(1, maximumRefreshes));
        return rows.stream().findFirst();
    }

    public void removeOlderThan(Instant cutoff) {
        jdbcTemplate.update(
                """
                delete from anchored_volume_profile_refresh_state
                where last_refreshed_at < ?
                """,
                Timestamp.from(cutoff));
    }

    private RefreshState mapState(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new RefreshState(
                resultSet.getInt("refresh_count"),
                resultSet.getObject("last_refreshed_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("provider_source"),
                new CandleSnapshot(
                        resultSet.getString("snapshot_interval"),
                        resultSet.getLong("snapshot_candle_timestamp"),
                        resultSet.getDouble("snapshot_open"),
                        resultSet.getDouble("snapshot_high"),
                        resultSet.getDouble("snapshot_low"),
                        resultSet.getDouble("snapshot_close"),
                        resultSet.getLong("snapshot_volume")));
    }

    public record CandleSnapshot(
            String interval,
            long timestamp,
            double open,
            double high,
            double low,
            double close,
            long volume) {
    }

    public record RefreshState(
            int refreshCount,
            Instant lastRefreshedAt,
            String providerSource,
            CandleSnapshot snapshot) {
    }
}
