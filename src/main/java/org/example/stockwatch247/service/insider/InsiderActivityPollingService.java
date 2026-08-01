package org.example.stockwatch247.service.insider;

import org.example.stockwatch247.service.insider.InsiderActivityCheckJobStore.InsiderActivityCheckJob;
import org.example.stockwatch247.service.insider.InsiderActivityScheduleRecoveryService.RecoveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class InsiderActivityPollingService {
    private static final Logger log = LoggerFactory.getLogger(InsiderActivityPollingService.class);

    private final InsiderActivityService activityService;
    private final InsiderActivityScheduleRecoveryService recoveryService;
    private final InsiderActivityCheckJobStore jobStore;
    private final boolean enabled;
    private final Duration jobLease;
    private final Duration retryDelay;
    private final int maximumAttempts;

    public InsiderActivityPollingService(
            InsiderActivityService activityService,
            InsiderActivityScheduleRecoveryService recoveryService,
            InsiderActivityCheckJobStore jobStore,
            @Value("${insider-activity.enabled:true}") boolean enabled,
            @Value("${insider-activity.schedule-enabled:${alerts.schedule.enabled:true}}")
            boolean scheduleEnabled,
            @Value("${insider-activity.job-lease-seconds:1200}") long jobLeaseSeconds,
            @Value("${insider-activity.retry-delay-seconds:300}") long retryDelaySeconds,
            @Value("${insider-activity.maximum-attempts:3}") int maximumAttempts) {
        this.activityService = activityService;
        this.recoveryService = recoveryService;
        this.jobStore = jobStore;
        this.enabled = enabled && scheduleEnabled;
        this.jobLease = Duration.ofSeconds(Math.max(30L, jobLeaseSeconds));
        this.retryDelay = Duration.ofSeconds(Math.max(1L, retryDelaySeconds));
        this.maximumAttempts = Math.max(1, maximumAttempts);
    }

    @Scheduled(
            cron = "${insider-activity.daily-cron:0 15 0 * * *}",
            zone = "${insider-activity.schedule-zone:${alerts.schedule.zone:Europe/Brussels}}")
    public void enqueueDailyCheck() {
        enqueueLatestDueCheck();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverMissedChecksOnStartup() {
        enqueueLatestDueCheck();
    }

    @Scheduled(
            fixedDelayString = "${insider-activity.recovery-delay-ms:60000}",
            initialDelayString = "${insider-activity.recovery-initial-delay-ms:5000}")
    public void recoverMissedChecks() {
        enqueueLatestDueCheck();
    }

    @Scheduled(
            fixedDelayString = "${insider-activity.worker-delay-ms:60000}",
            initialDelayString = "${insider-activity.worker-initial-delay-ms:30000}")
    public void processNextQueuedCheck() {
        if (!enabled) {
            return;
        }
        var claimedJob = jobStore.claimNext(jobLease);
        if (claimedJob.isEmpty()) {
            return;
        }

        InsiderActivityCheckJob job = claimedJob.get();
        try {
            boolean checked = activityService.pollScheduledActivity(
                    job.stockAssetId(),
                    job.tickerSymbol());
            jobStore.complete(job.id());
            if (checked) {
                log.info("Insider activity job completed for {} scheduled for {}.",
                        job.tickerSymbol(), job.scheduledFor());
            } else {
                log.info("Insider activity job skipped for {} because it is no longer followed.",
                        job.tickerSymbol());
            }
        } catch (RuntimeException exception) {
            jobStore.retryOrFail(
                    job,
                    exception.getClass().getSimpleName()
                            + (exception.getMessage() == null
                            ? ""
                            : ": " + exception.getMessage()),
                    maximumAttempts,
                    retryDelay);
            log.warn("Insider activity job failed for {} scheduled for {} on attempt {} after {}.",
                    job.tickerSymbol(),
                    job.scheduledFor(),
                    job.attempts(),
                    exception.getClass().getSimpleName());
        } finally {
            int remainingJobs = jobStore.pendingCount();
            if (remainingJobs == 0) {
                log.info("All queued insider activity checks completed.");
            } else {
                log.info("{} insider activity check(s) remain queued.", remainingJobs);
            }
        }
    }

    @Scheduled(cron = "${insider-activity.cleanup-cron:0 45 2 * * *}")
    public void cleanupFinishedJobs() {
        if (enabled) {
            jobStore.removeFinishedBefore(Duration.ofDays(30));
        }
    }

    private void enqueueLatestDueCheck() {
        if (!enabled) {
            return;
        }
        RecoveryResult result = recoveryService.enqueueLatestDueRun();
        if (result.insertedRuns() == 0) {
            return;
        }
        if (result.dueRuns() > 1) {
            log.info("Recovered {} missed insider activity schedule(s) as one current check "
                            + "scheduled for {}; queued {} followed stock check(s).",
                    result.dueRuns(), result.scheduledFor(), result.queuedJobs());
        } else {
            log.info("Queued {} insider activity stock check(s) for schedule {}.",
                    result.queuedJobs(), result.scheduledFor());
        }
    }
}
