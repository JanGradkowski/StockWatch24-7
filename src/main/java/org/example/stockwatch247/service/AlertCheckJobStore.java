package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Component
public class AlertCheckJobStore {
    private final JdbcTemplate jdbcTemplate;

    public AlertCheckJobStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int enqueue(String symbol, TimeInterval interval, Instant scheduledFor) {
        return jdbcTemplate.update(
                """
                insert into alert_check_jobs (symbol, interval, scheduled_for, status)
                values (?, ?, ?, 'PENDING')
                on conflict (symbol, interval, scheduled_for)
                do nothing
                """,
                symbol, interval.name(), toOffsetDateTime(scheduledFor));
    }

    public int enqueueScheduledRun(TimeInterval interval, Instant scheduledFor) {
        return jdbcTemplate.update(
                """
                with inserted_run as (
                    insert into alert_schedule_runs (interval, scheduled_for)
                    values (?, ?)
                    on conflict (interval, scheduled_for) do nothing
                    returning interval, scheduled_for
                )
                insert into alert_check_jobs (symbol, interval, scheduled_for, status)
                select distinct asset.ticker_symbol, rule.interval, inserted_run.scheduled_for, 'PENDING'
                from inserted_run
                join alert_rules rule on rule.interval = inserted_run.interval and rule.is_active = true
                join stock_assets asset on asset.id = rule.stock_asset_id
                on conflict (symbol, interval, scheduled_for) do nothing
                """,
                interval.name(), toOffsetDateTime(scheduledFor));
    }

    public Optional<Instant> findLatestScheduledRun(TimeInterval interval) {
        List<Instant> scheduledRuns = jdbcTemplate.query(
                """
                select scheduled_for
                from alert_schedule_runs
                where interval = ?
                order by scheduled_for desc
                limit 1
                """,
                (rs, rowNum) -> rs.getObject("scheduled_for", OffsetDateTime.class).toInstant(),
                interval.name());
        return scheduledRuns.stream().findFirst();
    }

    public Optional<AlertCheckJob> claimNext(Duration lease) {
        return claimNext(lease, null);
    }

    Optional<AlertCheckJob> claimNextForSymbol(Duration lease, String symbol) {
        return claimNext(lease, symbol);
    }

    private Optional<AlertCheckJob> claimNext(Duration lease, String symbol) {
        List<AlertCheckJob> jobs = jdbcTemplate.query(
                """
                with candidate as (
                    select candidate_job.id
                    from alert_check_jobs candidate_job
                    where (cast(? as varchar) is null or candidate_job.symbol = ?)
                      and ((candidate_job.status = 'PENDING' and candidate_job.available_at <= current_timestamp)
                           or (candidate_job.status = 'PROCESSING' and candidate_job.lease_until <= current_timestamp))
                      and not exists (
                          select 1
                          from alert_check_jobs earlier_job
                          where earlier_job.symbol = candidate_job.symbol
                            and earlier_job.interval = candidate_job.interval
                            and earlier_job.status in ('PENDING', 'PROCESSING')
                            and (earlier_job.scheduled_for < candidate_job.scheduled_for
                                 or (earlier_job.scheduled_for = candidate_job.scheduled_for
                                     and earlier_job.id < candidate_job.id))
                      )
                    order by candidate_job.scheduled_for, candidate_job.available_at, candidate_job.id
                    for update of candidate_job skip locked
                    limit 1
                )
                update alert_check_jobs job
                set status = 'PROCESSING',
                    lease_until = current_timestamp + (? * interval '1 second'),
                    attempts = attempts + 1,
                    updated_at = current_timestamp
                from candidate
                where job.id = candidate.id
                returning job.id, job.symbol, job.interval, job.scheduled_for, job.attempts
                """,
                (rs, rowNum) -> new AlertCheckJob(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        TimeInterval.valueOf(rs.getString("interval")),
                        rs.getObject("scheduled_for", OffsetDateTime.class).toInstant(),
                        rs.getInt("attempts")),
                symbol, symbol, Math.max(1L, lease.toSeconds()));
        return jobs.stream().findFirst();
    }

    public void complete(long jobId) {
        jdbcTemplate.update(
                """
                update alert_check_jobs
                set status = 'COMPLETED', lease_until = null, last_error = null,
                    updated_at = current_timestamp
                where id = ? and status = 'PROCESSING'
                """,
                jobId);
    }

    public void retryOrFail(AlertCheckJob job, String error, int maximumAttempts, Duration retryDelay) {
        String safeError = error == null ? "Unknown processing error" : error.substring(0, Math.min(1000, error.length()));
        if (job.attempts() >= Math.max(1, maximumAttempts)) {
            jdbcTemplate.update(
                    """
                    update alert_check_jobs
                    set status = 'FAILED', lease_until = null, last_error = ?, updated_at = current_timestamp
                    where id = ? and status = 'PROCESSING'
                    """,
                    safeError, job.id());
            return;
        }
        jdbcTemplate.update(
                """
                update alert_check_jobs
                set status = 'PENDING', lease_until = null, last_error = ?,
                    available_at = current_timestamp + (? * interval '1 second'),
                    updated_at = current_timestamp
                where id = ? and status = 'PROCESSING'
                """,
                safeError, Math.max(1L, retryDelay.toSeconds()), job.id());
    }

    public int pendingCount() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from alert_check_jobs where status in ('PENDING', 'PROCESSING')",
                Integer.class);
        return count == null ? 0 : count;
    }

    public void removeFinishedBefore(Duration age) {
        jdbcTemplate.update(
                """
                delete from alert_check_jobs
                where status in ('COMPLETED', 'FAILED')
                  and updated_at < current_timestamp - (? * interval '1 second')
                """,
                Math.max(1L, age.toSeconds()));
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record AlertCheckJob(long id,
                                String symbol,
                                TimeInterval interval,
                                Instant scheduledFor,
                                int attempts) {
    }
}
