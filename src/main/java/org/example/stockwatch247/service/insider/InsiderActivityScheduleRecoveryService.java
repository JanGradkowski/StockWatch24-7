package org.example.stockwatch247.service.insider;

import org.example.stockwatch247.service.insider.InsiderActivityCheckJobStore.EnqueueResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

@Component
public class InsiderActivityScheduleRecoveryService {
    private final InsiderActivityCheckJobStore jobStore;
    private final ZoneId scheduleZone;
    private final CronExpression schedule;
    private final Duration initialLookback;
    private final Clock clock;

    @Autowired
    public InsiderActivityScheduleRecoveryService(
            InsiderActivityCheckJobStore jobStore,
            @Value("${insider-activity.schedule-zone:${alerts.schedule.zone:Europe/Brussels}}")
            String scheduleZone,
            @Value("${insider-activity.daily-cron:0 15 0 * * *}") String dailyCron,
            @Value("${insider-activity.initial-lookback-days:30}") int initialLookbackDays) {
        this(
                jobStore,
                scheduleZone,
                dailyCron,
                initialLookbackDays,
                Clock.systemUTC());
    }

    InsiderActivityScheduleRecoveryService(
            InsiderActivityCheckJobStore jobStore,
            String scheduleZone,
            String dailyCron,
            int initialLookbackDays,
            Clock clock) {
        this.jobStore = jobStore;
        this.scheduleZone = ZoneId.of(scheduleZone);
        this.schedule = CronExpression.parse(dailyCron);
        this.initialLookback = Duration.ofDays(Math.max(1, initialLookbackDays));
        this.clock = clock;
    }

    public RecoveryResult enqueueLatestDueRun() {
        Instant now = clock.instant();
        Optional<Instant> latestRecordedRun = jobStore.findLatestScheduledRun();
        DueRun dueRun = latestRecordedRun
                .map(recorded -> findLatestMissedRun(recorded, now))
                .orElseGet(() -> findLatestInitialRun(now));
        if (dueRun.scheduledFor() == null) {
            return RecoveryResult.NONE;
        }
        EnqueueResult enqueueResult = jobStore.enqueueScheduledRun(dueRun.scheduledFor());
        return new RecoveryResult(
                dueRun.missedRuns(),
                enqueueResult.insertedRuns(),
                enqueueResult.queuedJobs(),
                dueRun.scheduledFor());
    }

    private DueRun findLatestMissedRun(Instant latestRecordedRun, Instant now) {
        ZonedDateTime cursor = latestRecordedRun.atZone(scheduleZone);
        Instant latestDue = null;
        int missedRuns = 0;
        ZonedDateTime next = schedule.next(cursor);
        while (next != null && !next.toInstant().isAfter(now)) {
            latestDue = next.toInstant();
            missedRuns++;
            next = schedule.next(next);
        }
        return new DueRun(latestDue, missedRuns);
    }

    private DueRun findLatestInitialRun(Instant now) {
        ZonedDateTime cursor = now.minus(initialLookback).atZone(scheduleZone);
        Instant latestDue = null;
        ZonedDateTime next = schedule.next(cursor);
        while (next != null && !next.toInstant().isAfter(now)) {
            latestDue = next.toInstant();
            next = schedule.next(next);
        }
        return new DueRun(latestDue, latestDue == null ? 0 : 1);
    }

    private record DueRun(Instant scheduledFor, int missedRuns) {
    }

    public record RecoveryResult(
            int dueRuns,
            int insertedRuns,
            int queuedJobs,
            Instant scheduledFor) {
        private static final RecoveryResult NONE = new RecoveryResult(0, 0, 0, null);
    }
}
