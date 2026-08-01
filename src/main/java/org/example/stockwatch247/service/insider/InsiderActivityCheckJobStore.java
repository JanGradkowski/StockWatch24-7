package org.example.stockwatch247.service.insider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Component
public class InsiderActivityCheckJobStore {
    private final JdbcTemplate jdbcTemplate;

    public InsiderActivityCheckJobStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public EnqueueResult enqueueScheduledRun(Instant scheduledFor) {
        List<EnqueueResult> results = jdbcTemplate.query(
                """
                with inserted_run as (
                    insert into insider_activity_schedule_runs (scheduled_for)
                    values (?)
                    on conflict (scheduled_for) do nothing
                    returning scheduled_for
                ),
                inserted_jobs as (
                    insert into insider_activity_check_jobs (
                        stock_asset_id, ticker_symbol, scheduled_for, status
                    )
                    select distinct asset.id, asset.ticker_symbol, inserted_run.scheduled_for, 'PENDING'
                    from inserted_run
                    join insider_trade_subscriptions subscription on subscription.active = true
                    join stock_assets asset on asset.id = subscription.stock_asset_id
                    on conflict (stock_asset_id, scheduled_for) do nothing
                    returning id
                )
                select
                    (select count(*) from inserted_run) as inserted_runs,
                    (select count(*) from inserted_jobs) as queued_jobs
                """,
                (resultSet, rowNumber) -> new EnqueueResult(
                        resultSet.getInt("inserted_runs"),
                        resultSet.getInt("queued_jobs")),
                toOffsetDateTime(scheduledFor));
        return results.stream().findFirst().orElse(EnqueueResult.NONE);
    }

    public Optional<Instant> findLatestScheduledRun() {
        List<Instant> scheduledRuns = jdbcTemplate.query(
                """
                select scheduled_for
                from insider_activity_schedule_runs
                order by scheduled_for desc
                limit 1
                """,
                (resultSet, rowNumber) ->
                        resultSet.getObject("scheduled_for", OffsetDateTime.class).toInstant());
        return scheduledRuns.stream().findFirst();
    }

    public Optional<InsiderActivityCheckJob> claimNext(Duration lease) {
        List<InsiderActivityCheckJob> jobs = jdbcTemplate.query(
                """
                with candidate as (
                    select candidate_job.id
                    from insider_activity_check_jobs candidate_job
                    where ((candidate_job.status = 'PENDING'
                                and candidate_job.available_at <= current_timestamp)
                           or (candidate_job.status = 'PROCESSING'
                                and candidate_job.lease_until <= current_timestamp))
                      and not exists (
                          select 1
                          from insider_activity_check_jobs earlier_job
                          where earlier_job.stock_asset_id = candidate_job.stock_asset_id
                            and earlier_job.status in ('PENDING', 'PROCESSING')
                            and (earlier_job.scheduled_for < candidate_job.scheduled_for
                                 or (earlier_job.scheduled_for = candidate_job.scheduled_for
                                     and earlier_job.id < candidate_job.id))
                      )
                    order by candidate_job.scheduled_for,
                             candidate_job.available_at,
                             candidate_job.id
                    for update of candidate_job skip locked
                    limit 1
                )
                update insider_activity_check_jobs job
                set status = 'PROCESSING',
                    lease_until = current_timestamp + (? * interval '1 second'),
                    attempts = attempts + 1,
                    updated_at = current_timestamp
                from candidate
                where job.id = candidate.id
                returning job.id,
                          job.stock_asset_id,
                          job.ticker_symbol,
                          job.scheduled_for,
                          job.attempts
                """,
                (resultSet, rowNumber) -> new InsiderActivityCheckJob(
                        resultSet.getLong("id"),
                        resultSet.getLong("stock_asset_id"),
                        resultSet.getString("ticker_symbol"),
                        resultSet.getObject("scheduled_for", OffsetDateTime.class).toInstant(),
                        resultSet.getInt("attempts")),
                Math.max(1L, lease.toSeconds()));
        return jobs.stream().findFirst();
    }

    public void complete(long jobId) {
        jdbcTemplate.update(
                """
                update insider_activity_check_jobs
                set status = 'COMPLETED',
                    lease_until = null,
                    last_error = null,
                    updated_at = current_timestamp
                where id = ?
                  and status = 'PROCESSING'
                """,
                jobId);
    }

    public void retryOrFail(
            InsiderActivityCheckJob job,
            String error,
            int maximumAttempts,
            Duration retryDelay) {
        String safeError = safeError(error);
        if (job.attempts() >= Math.max(1, maximumAttempts)) {
            jdbcTemplate.update(
                    """
                    update insider_activity_check_jobs
                    set status = 'FAILED',
                        lease_until = null,
                        last_error = ?,
                        updated_at = current_timestamp
                    where id = ?
                      and status = 'PROCESSING'
                    """,
                    safeError,
                    job.id());
            return;
        }
        jdbcTemplate.update(
                """
                update insider_activity_check_jobs
                set status = 'PENDING',
                    lease_until = null,
                    last_error = ?,
                    available_at = current_timestamp + (? * interval '1 second'),
                    updated_at = current_timestamp
                where id = ?
                  and status = 'PROCESSING'
                """,
                safeError,
                Math.max(1L, retryDelay.toSeconds()),
                job.id());
    }

    public int pendingCount() {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from insider_activity_check_jobs
                where status in ('PENDING', 'PROCESSING')
                """,
                Integer.class);
        return count == null ? 0 : count;
    }

    public void removeFinishedBefore(Duration age) {
        jdbcTemplate.update(
                """
                delete from insider_activity_check_jobs
                where status in ('COMPLETED', 'FAILED')
                  and updated_at < current_timestamp - (? * interval '1 second')
                """,
                Math.max(1L, age.toSeconds()));
    }

    private String safeError(String error) {
        if (error == null || error.isBlank()) {
            return "Unknown processing error";
        }
        return error.substring(0, Math.min(1000, error.length()));
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record EnqueueResult(int insertedRuns, int queuedJobs) {
        private static final EnqueueResult NONE = new EnqueueResult(0, 0);
    }

    public record InsiderActivityCheckJob(
            long id,
            long stockAssetId,
            String tickerSymbol,
            Instant scheduledFor,
            int attempts) {
    }
}
