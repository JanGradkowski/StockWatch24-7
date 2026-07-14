package org.example.stockwatch247.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class PostgresMarketDataSyncCoordinator implements MarketDataSyncCoordinator {
    private final JdbcTemplate jdbcTemplate;

    public PostgresMarketDataSyncCoordinator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Claim tryClaim(String symbol, String interval, long cooldownSeconds, long leaseSeconds) {
        String owner = UUID.randomUUID().toString();
        List<String> acquired = jdbcTemplate.query(
                """
                insert into market_data_sync_state
                    (symbol, time_interval, last_success_at, lease_owner, lease_until)
                values (?, ?, null, ?, current_timestamp + (? * interval '1 second'))
                on conflict (symbol, time_interval) do update
                set lease_owner = excluded.lease_owner,
                    lease_until = excluded.lease_until
                where (market_data_sync_state.last_success_at is null
                       or market_data_sync_state.last_success_at <= current_timestamp - (? * interval '1 second'))
                  and (market_data_sync_state.lease_until is null
                       or market_data_sync_state.lease_until <= current_timestamp)
                returning lease_owner
                """,
                (rs, rowNum) -> rs.getString(1),
                symbol, interval, owner, Math.max(1L, leaseSeconds), Math.max(0L, cooldownSeconds));
        if (!acquired.isEmpty()) {
            return new Claim(symbol, interval, owner, ClaimStatus.ACQUIRED);
        }

        ClaimStatus status = jdbcTemplate.queryForObject(
                """
                select case
                    when last_success_at is not null
                         and last_success_at > current_timestamp - (? * interval '1 second')
                        then 'RECENT_SUCCESS'
                    else 'IN_PROGRESS'
                end
                from market_data_sync_state
                where symbol = ? and time_interval = ?
                """,
                (rs, rowNum) -> ClaimStatus.valueOf(rs.getString(1)),
                Math.max(0L, cooldownSeconds), symbol, interval);
        return new Claim(symbol, interval, null, status == null ? ClaimStatus.IN_PROGRESS : status);
    }

    @Override
    public void markSuccessful(Claim claim) {
        if (!claim.acquired()) {
            return;
        }
        jdbcTemplate.update(
                """
                update market_data_sync_state
                set last_success_at = current_timestamp, lease_owner = null, lease_until = null
                where symbol = ? and time_interval = ? and lease_owner = ?
                """,
                claim.symbol(), claim.interval(), claim.owner());
    }

    @Override
    public void release(Claim claim) {
        if (!claim.acquired()) {
            return;
        }
        jdbcTemplate.update(
                """
                update market_data_sync_state
                set lease_owner = null, lease_until = null
                where symbol = ? and time_interval = ? and lease_owner = ?
                """,
                claim.symbol(), claim.interval(), claim.owner());
    }

    @Scheduled(cron = "${market-data.coordination-cleanup-cron:0 45 2 * * *}")
    public void removeStaleState() {
        jdbcTemplate.update(
                """
                delete from market_data_sync_state
                where (last_success_at is null and (lease_until is null or lease_until < current_timestamp))
                   or (last_success_at < current_timestamp - interval '30 days'
                       and (lease_until is null or lease_until < current_timestamp))
                """);
    }
}
