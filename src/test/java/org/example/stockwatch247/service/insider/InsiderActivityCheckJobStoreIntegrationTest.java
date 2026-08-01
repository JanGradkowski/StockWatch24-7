package org.example.stockwatch247.service.insider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "alerts.schedule.enabled=false")
@Transactional
class InsiderActivityCheckJobStoreIntegrationTest {
    @Autowired
    private InsiderActivityCheckJobStore jobStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void scheduleCheckpointAndPerStockJobInsertionAreDurableAndIdempotent() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String ticker = "I" + suffix;
        Long userId = jdbcTemplate.queryForObject(
                """
                insert into users (email, password_hash, first_name, last_name, is_verified)
                values (?, 'test-hash', 'Insider', 'Schedule', true)
                returning id
                """,
                Long.class,
                "insider-schedule-" + suffix.toLowerCase() + "@example.com");
        Long assetId = jdbcTemplate.queryForObject(
                """
                insert into stock_assets (
                    ticker_symbol, company_name, exchange, currency, instrument_type
                )
                values (?, 'Insider Schedule Test', 'TEST', 'USD', 'EQUITY')
                returning id
                """,
                Long.class,
                ticker);
        jdbcTemplate.update(
                """
                insert into insider_trade_subscriptions (
                    user_id, stock_asset_id, active, activated_at
                )
                values (?, ?, true, current_timestamp)
                """,
                userId,
                assetId);
        Instant scheduledFor = Instant.now()
                .plusSeconds(86_400)
                .truncatedTo(ChronoUnit.MICROS);

        var first = jobStore.enqueueScheduledRun(scheduledFor);
        var duplicate = jobStore.enqueueScheduledRun(scheduledFor);

        assertThat(first.insertedRuns()).isEqualTo(1);
        assertThat(first.queuedJobs()).isGreaterThanOrEqualTo(1);
        assertThat(duplicate).isEqualTo(new InsiderActivityCheckJobStore.EnqueueResult(0, 0));
        assertThat(jobStore.findLatestScheduledRun()).contains(scheduledFor);
        Integer queuedJobs = jdbcTemplate.queryForObject(
                """
                select count(*)
                from insider_activity_check_jobs
                where stock_asset_id = ?
                  and scheduled_for = ?
                """,
                Integer.class,
                assetId,
                scheduledFor.atOffset(ZoneOffset.UTC));
        assertThat(queuedJobs).isEqualTo(1);
    }
}
