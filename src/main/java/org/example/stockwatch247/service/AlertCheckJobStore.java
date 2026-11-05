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
                select ?, ?, ?, 'PENDING'
                where not exists (select 1 from alert_schedule_control where cancelled_through >= ?)
                on conflict (symbol, interval, scheduled_for)
                do nothing
                """,
                symbol, interval.name(), toOffsetDateTime(scheduledFor), toOffsetDateTime(scheduledFor));
    }

    public int enqueueScheduledRun(TimeInterval interval, Instant scheduledFor) {
        return jdbcTemplate.update(
                """
                with inserted_run as (
                    insert into alert_schedule_runs (interval, scheduled_for)
                    select ?, ?
                    where not exists (select 1 from alert_schedule_control where cancelled_through >= ?)
                    on conflict (interval, scheduled_for) do nothing
                    returning interval, scheduled_for
                )
                insert into alert_check_jobs (symbol, interval, scheduled_for, status)
                select distinct candidate.symbol, candidate.interval, inserted_run.scheduled_for, 'PENDING'
                from inserted_run
                join (
                    select asset.ticker_symbol as symbol, rule.interval
                    from alert_rules rule
                    join stock_assets asset on asset.id = rule.stock_asset_id
                    where rule.is_active = true

                    union

                    select asset.ticker_symbol as symbol, rule.interval
                    from alert_events event
                    join alert_rules rule on rule.id = event.alert_rule_id
                    join stock_assets asset on asset.id = rule.stock_asset_id
                    where event.lifecycle_status in ('POTENTIAL', 'DETECTED')
                      and event.confirmation_window_candles is not null
                      and event.pattern_high is not null
                      and event.pattern_low is not null

                    union

                    select asset.ticker_symbol as symbol, rule.interval
                    from alert_events event
                    join alert_rules rule on rule.id = event.alert_rule_id
                    join stock_assets asset on asset.id = rule.stock_asset_id
                    where (event.harmonic_stop_status = 'ACTIVE'
                             and event.trade_plan_version in ('HARMONIC_STOP_V1', 'HARMONIC_TRADE_V2', 'HARMONIC_TRADE_V3')
                          or event.harmonic_stop_status = 'PROJECTION_ONLY'
                             and event.trade_plan_version in ('HARMONIC_TRADE_V2', 'HARMONIC_TRADE_V3'))
                      and event.deleted_at is null

                    union

                    select asset.ticker_symbol as symbol, rule.interval
                    from elliott_stage_trade_plans plan
                    join alert_events event on event.id = plan.alert_event_id
                    join alert_rules rule on rule.id = event.alert_rule_id
                    join stock_assets asset on asset.id = rule.stock_asset_id
                    where (plan.status = 'ACTIVE'
                          or plan.status = 'PROJECTION_ONLY'
                             and plan.plan_version in ('ELLIOTT_FIB_RR_V3', 'ELLIOTT_FIB_RR_V4'))
                      and event.deleted_at is null

                    union

                    select asset.ticker_symbol as symbol, rule.interval
                    from elliott_projection_sets projection
                    join alert_events event on event.id = projection.alert_event_id
                    join alert_rules rule on rule.id = event.alert_rule_id
                    join stock_assets asset on asset.id = rule.stock_asset_id
                    where projection.status = 'ACTIVE'
                      and event.deleted_at is null

                    union

                    select asset.ticker_symbol as symbol, subscription.interval
                    from technical_outlook_subscriptions subscription
                    join stock_assets asset on asset.id = subscription.stock_asset_id
                    where subscription.is_active = true

                    union

                    select chart_alert.symbol, chart_alert.interval
                    from chart_alerts chart_alert
                    where chart_alert.active = true

                    union

                    select asset.ticker_symbol as symbol, custom_alert.interval
                    from custom_alert_effective_targets target
                    join custom_alerts custom_alert on custom_alert.id = target.alert_id
                    join stock_assets asset on asset.id = target.stock_asset_id
                    where custom_alert.active = true

                    union

                    -- Triggers keep measuring the price after them, even once their alert is paused.
                    select asset.ticker_symbol as symbol, custom_alert.interval
                    from custom_alert_events custom_event
                    join custom_alerts custom_alert on custom_alert.id = custom_event.alert_id
                    join stock_assets asset on asset.id = custom_event.stock_asset_id
                    where custom_event.outcome_status = 'PENDING' and custom_event.deleted_at is null
                ) candidate on candidate.interval = inserted_run.interval
                on conflict (symbol, interval, scheduled_for) do nothing
                """,
                interval.name(), toOffsetDateTime(scheduledFor), toOffsetDateTime(scheduledFor));
    }

    public Optional<Instant> findLatestScheduledRun(TimeInterval interval) {
        List<Instant> scheduledRuns = jdbcTemplate.query(
                """
                select max(scheduled_for) as scheduled_for from (
                    select scheduled_for from alert_schedule_runs where interval = ?
                    union all select cancelled_through from alert_schedule_control
                ) boundaries having max(scheduled_for) is not null
                """,
                (rs, rowNum) -> rs.getObject("scheduled_for", OffsetDateTime.class).toInstant(),
                interval.name());
        return scheduledRuns.stream().findFirst();
    }

    public Optional<AlertCheckJob> claimNext(Duration lease) {
        return claimNext(lease, 1);
    }

    /**
     * Claims the next check while fewer than {@code parallel} run (across every worker). Two checks of the same stock,
     * whatever their intervals, never run at once: they share daily and 60-minute candles and their refreshes.
     */
    public Optional<AlertCheckJob> claimNext(Duration lease, int parallel) {
        return claimNext(lease, null, parallel);
    }

    Optional<AlertCheckJob> claimNextForSymbol(Duration lease, String symbol) {
        return claimNext(lease, symbol, 1);
    }

    private Optional<AlertCheckJob> claimNext(Duration lease, String symbol, int parallel) {
        List<AlertCheckJob> jobs = jdbcTemplate.query(
                """
                with claim_gate as materialized (
                    select pg_try_advisory_xact_lock(7510247) as acquired
                ), candidate as (
                    select candidate_job.id
                    from alert_check_jobs candidate_job
                    where (select acquired from claim_gate)
                      and (cast(? as varchar) is null or candidate_job.symbol = ?)
                      and ((candidate_job.status = 'PENDING' and candidate_job.available_at <= current_timestamp)
                           or (candidate_job.status = 'PROCESSING' and candidate_job.lease_until <= current_timestamp))
                      -- At most so many technical checks run at once across every worker, and never two of one stock.
                      and (select count(*)
                           from alert_check_jobs running_job
                           where running_job.status = 'PROCESSING'
                             and running_job.lease_until > current_timestamp
                             and running_job.id <> candidate_job.id) < ?
                      and not exists (
                          select 1
                          from alert_check_jobs running_job
                          where running_job.status = 'PROCESSING'
                            and running_job.lease_until > current_timestamp
                            and running_job.id <> candidate_job.id
                            and running_job.symbol = candidate_job.symbol
                      )
                      -- The oldest check keeps priority, except while its series waits out a data
                      -- outage. Checks for the same series always run in schedule order. A check of
                      -- another series holds up no one while it runs on a live lease, nor while it only
                      -- waits for a check of its own stock to finish.
                      and not exists (
                          select 1
                          from alert_check_jobs earlier_job
                          where earlier_job.status in ('PENDING', 'PROCESSING')
                            and (earlier_job.scheduled_for, earlier_job.id)
                                < (candidate_job.scheduled_for, candidate_job.id)
                            and ((earlier_job.symbol = candidate_job.symbol
                                  and earlier_job.interval = candidate_job.interval)
                                 or ((earlier_job.status = 'PENDING' or earlier_job.lease_until <= current_timestamp)
                                     and not exists (
                                         select 1
                                         from alert_check_jobs busy_job
                                         where busy_job.symbol = earlier_job.symbol
                                           and busy_job.status = 'PROCESSING'
                                           and busy_job.lease_until > current_timestamp
                                           and busy_job.id <> earlier_job.id)
                                     and not earlier_job.yields_priority
                                     and not exists (
                                         select 1
                                         from alert_check_jobs outage_job
                                         where outage_job.symbol = earlier_job.symbol
                                           and outage_job.interval = earlier_job.interval
                                           and outage_job.status = 'PENDING'
                                           and outage_job.yields_priority
                                           and (outage_job.scheduled_for, outage_job.id)
                                               < (earlier_job.scheduled_for, earlier_job.id))))
                      )
                    order by candidate_job.scheduled_for, candidate_job.id
                    for update of candidate_job skip locked
                    limit 1
                )
                update alert_check_jobs job
                set status = 'PROCESSING',
                    lease_until = current_timestamp + (? * interval '1 second'),
                    attempts = attempts + 1,
                    yields_priority = false,
                    updated_at = current_timestamp
                from candidate
                where job.id = candidate.id
                  and ((job.status = 'PENDING' and job.available_at <= current_timestamp)
                       or (job.status = 'PROCESSING' and job.lease_until <= current_timestamp))
                returning job.id, job.symbol, job.interval, job.scheduled_for, job.attempts
                """,
                (rs, rowNum) -> new AlertCheckJob(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        TimeInterval.valueOf(rs.getString("interval")),
                        rs.getObject("scheduled_for", OffsetDateTime.class).toInstant(),
                        rs.getInt("attempts")),
                symbol, symbol, Math.max(1, parallel), Math.max(1L, lease.toSeconds()));
        return jobs.stream().findFirst();
    }

    private org.springframework.transaction.support.TransactionTemplate ownTransaction;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureTransactions(org.springframework.transaction.PlatformTransactionManager manager) {
        ownTransaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        ownTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Extends the lease in a transaction of its own and commits at once: a check that renews from inside a business
     * transaction (publishing a signal) does not hold the job row's lock until that commits, so the heartbeat
     * thread's renewal never waits on it.
     */
    public boolean renew(AlertCheckJob job, Duration lease) {
        if (ownTransaction == null) return extend(job, lease);
        return Boolean.TRUE.equals(ownTransaction.execute(status -> extend(job, lease)));
    }

    private boolean extend(AlertCheckJob job, Duration lease) {
        return jdbcTemplate.update("""
            update alert_check_jobs set lease_until = current_timestamp + (? * interval '1 second')
            where id = ? and status = 'PROCESSING' and attempts = ? and lease_until > current_timestamp
            """, Math.max(1, lease.toSeconds()), job.id(), job.attempts()) == 1;
    }

    public boolean complete(AlertCheckJob job) {
        return jdbcTemplate.update(
                """
                update alert_check_jobs
                set status = 'COMPLETED', lease_until = null, last_error = null,
                    updated_at = current_timestamp
                where id = ? and status = 'PROCESSING' and attempts = ? and lease_until > current_timestamp
                """,
                job.id(), job.attempts()) == 1;
    }

    public void retryOrFail(AlertCheckJob job, String error, int maximumAttempts, Duration retryDelay) {
        String safeError = error == null ? "Unknown processing error" : error.substring(0, Math.min(1000, error.length()));
        jdbcTemplate.update(
                """
                update alert_check_jobs
                set status = case when calculation_failures + 1 >= ? then 'FAILED' else 'PENDING' end,
                    calculation_failures = calculation_failures + 1,
                    lease_until = null, last_error = ?, updated_at = current_timestamp,
                    available_at = current_timestamp + (? * interval '1 second')
                where id = ? and status = 'PROCESSING' and attempts = ? and lease_until > current_timestamp
                """, Math.max(1, maximumAttempts), safeError, Math.max(1L, retryDelay.toSeconds()),
                job.id(), job.attempts());
    }

    /** A checkpointed continuation keeps its place at the head of the queue. */
    public void defer(AlertCheckJob job, String error, Duration retryDelay) {
        String safeError = error == null ? "Market data temporarily unavailable" : error.substring(0, Math.min(1000, error.length()));
        jdbcTemplate.update(
                """
                update alert_check_jobs
                set status = 'PENDING', lease_until = null, last_error = ?, yields_priority = false,
                    available_at = current_timestamp + (? * interval '1 second'),
                    updated_at = current_timestamp
                where id = ? and status = 'PROCESSING' and attempts = ? and lease_until > current_timestamp
                """,
                safeError, Math.max(1L, retryDelay.toSeconds()), job.id(), job.attempts());
    }

    /**
     * Provider and data outages wait without blocking other instruments. They do not consume the
     * calculation retry budget, but a series that never returns data is eventually failed.
     */
    public void backoff(AlertCheckJob job, String error, Duration retryDelay, int maximumDeferrals) {
        String safeError = error == null ? "Market data temporarily unavailable" : error.substring(0, Math.min(900, error.length()));
        jdbcTemplate.update(
                """
                update alert_check_jobs
                set status = case when deferrals + 1 >= ? then 'FAILED' else 'PENDING' end,
                    last_error = case when deferrals + 1 >= ?
                        then 'Market data stayed unavailable after ' || (deferrals + 1) || ' attempts: ' || ?
                        else ? end,
                    deferrals = deferrals + 1,
                    yields_priority = true,
                    lease_until = null,
                    available_at = current_timestamp + (? * interval '1 second'),
                    updated_at = current_timestamp
                where id = ? and status = 'PROCESSING' and attempts = ? and lease_until > current_timestamp
                """,
                Math.max(1, maximumDeferrals), Math.max(1, maximumDeferrals), safeError, safeError,
                Math.max(1L, retryDelay.toSeconds()), job.id(), job.attempts());
    }

    /** The next checks waiting to run, in the worker's order (for downloading their candles ahead of them). */
    public List<AlertCheckJob> upcoming(int limit) {
        return jdbcTemplate.query(
                """
                select id, symbol, interval, scheduled_for, attempts from alert_check_jobs
                where status = 'PENDING' and available_at <= current_timestamp
                order by scheduled_for, id
                limit ?
                """,
                (rs, rowNum) -> new AlertCheckJob(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        TimeInterval.valueOf(rs.getString("interval")),
                        rs.getObject("scheduled_for", OffsetDateTime.class).toInstant(),
                        rs.getInt("attempts")),
                Math.max(0, limit));
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
