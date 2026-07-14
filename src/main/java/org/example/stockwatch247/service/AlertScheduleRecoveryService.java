package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AlertScheduleRecoveryService {
    private static final List<TimeInterval> SCHEDULED_INTERVALS = List.of(
            TimeInterval.DAILY,
            TimeInterval.WEEKLY,
            TimeInterval.MONTHLY
    );

    private final AlertCheckJobStore jobStore;
    private final ZoneId scheduleZone;
    private final Map<TimeInterval, CronExpression> schedules;
    private final int catchUpBatchSize;
    private final Duration initialLookback;
    private final Clock clock;

    @Autowired
    public AlertScheduleRecoveryService(
            AlertCheckJobStore jobStore,
            @Value("${alerts.schedule.zone:Europe/Brussels}") String scheduleZone,
            @Value("${alerts.schedule.daily-cron:0 0 0 * * TUE-SAT}") String dailyCron,
            @Value("${alerts.schedule.weekly-cron:0 0 0 * * SAT}") String weeklyCron,
            @Value("${alerts.schedule.monthly-cron:0 0 0 1 * *}") String monthlyCron,
            @Value("${alerts.schedule.catch-up-batch-size:100}") int catchUpBatchSize,
            @Value("${alerts.schedule.initial-lookback-days:370}") int initialLookbackDays) {
        this(jobStore, scheduleZone, dailyCron, weeklyCron, monthlyCron,
                catchUpBatchSize, initialLookbackDays, Clock.systemUTC());
    }

    AlertScheduleRecoveryService(AlertCheckJobStore jobStore,
                                 String scheduleZone,
                                 String dailyCron,
                                 String weeklyCron,
                                 String monthlyCron,
                                 int catchUpBatchSize,
                                 int initialLookbackDays,
                                 Clock clock) {
        this.jobStore = jobStore;
        this.scheduleZone = ZoneId.of(scheduleZone);
        this.schedules = Map.of(
                TimeInterval.DAILY, CronExpression.parse(dailyCron),
                TimeInterval.WEEKLY, CronExpression.parse(weeklyCron),
                TimeInterval.MONTHLY, CronExpression.parse(monthlyCron)
        );
        this.catchUpBatchSize = Math.max(1, catchUpBatchSize);
        this.initialLookback = Duration.ofDays(Math.max(1, initialLookbackDays));
        this.clock = clock;
    }

    public RecoveryResult enqueueAllDueRuns() {
        RecoveryResult total = RecoveryResult.NONE;
        for (TimeInterval interval : SCHEDULED_INTERVALS) {
            total = total.plus(enqueueDueRuns(interval));
        }
        return total;
    }

    public RecoveryResult enqueueDueRuns(TimeInterval interval) {
        Instant now = clock.instant();
        List<Instant> dueRuns = findDueRuns(interval, now);
        int queuedJobs = 0;
        for (Instant scheduledFor : dueRuns) {
            queuedJobs += jobStore.enqueueScheduledRun(interval, scheduledFor);
        }
        return new RecoveryResult(dueRuns.size(), queuedJobs);
    }

    private List<Instant> findDueRuns(TimeInterval interval, Instant now) {
        CronExpression schedule = schedules.get(interval);
        if (schedule == null) {
            throw new IllegalArgumentException("No alert schedule is configured for " + interval);
        }

        Optional<Instant> latestRecordedRun = jobStore.findLatestScheduledRun(interval);
        if (latestRecordedRun.isEmpty()) {
            return findLatestInitialRun(schedule, now).map(List::of).orElseGet(List::of);
        }

        List<Instant> dueRuns = new ArrayList<>();
        ZonedDateTime cursor = latestRecordedRun.orElseThrow().atZone(scheduleZone);
        while (dueRuns.size() < catchUpBatchSize) {
            ZonedDateTime next = schedule.next(cursor);
            if (next == null || next.toInstant().isAfter(now)) {
                break;
            }
            dueRuns.add(next.toInstant());
            cursor = next;
        }
        return List.copyOf(dueRuns);
    }

    private Optional<Instant> findLatestInitialRun(CronExpression schedule, Instant now) {
        ZonedDateTime cursor = now.minus(initialLookback).atZone(scheduleZone);
        Instant latest = null;
        ZonedDateTime next = schedule.next(cursor);
        while (next != null && !next.toInstant().isAfter(now)) {
            latest = next.toInstant();
            next = schedule.next(next);
        }
        return Optional.ofNullable(latest);
    }

    public record RecoveryResult(int scheduledRuns, int queuedJobs) {
        private static final RecoveryResult NONE = new RecoveryResult(0, 0);

        private RecoveryResult plus(RecoveryResult other) {
            return new RecoveryResult(scheduledRuns + other.scheduledRuns, queuedJobs + other.queuedJobs);
        }
    }
}
