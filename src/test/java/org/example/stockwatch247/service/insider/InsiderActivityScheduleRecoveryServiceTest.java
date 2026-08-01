package org.example.stockwatch247.service.insider;

import org.example.stockwatch247.service.insider.InsiderActivityCheckJobStore.EnqueueResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InsiderActivityScheduleRecoveryServiceTest {
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    @Test
    void coalescesSeveralMissedDailyRunsIntoTheLatestCurrentCheck() {
        InsiderActivityCheckJobStore jobStore = mock(InsiderActivityCheckJobStore.class);
        Instant lastRun = localTime(2026, 7, 28, 0, 15);
        Instant restartedAt = localTime(2026, 7, 30, 8, 0);
        Instant latestDueRun = localTime(2026, 7, 30, 0, 15);
        when(jobStore.findLatestScheduledRun()).thenReturn(Optional.of(lastRun));
        when(jobStore.enqueueScheduledRun(latestDueRun)).thenReturn(new EnqueueResult(1, 3));
        InsiderActivityScheduleRecoveryService service = service(jobStore, restartedAt);

        var result = service.enqueueLatestDueRun();

        assertThat(result).isEqualTo(
                new InsiderActivityScheduleRecoveryService.RecoveryResult(
                        2,
                        1,
                        3,
                        latestDueRun));
        verify(jobStore).enqueueScheduledRun(latestDueRun);
    }

    @Test
    void firstStartupQueuesOnlyTheMostRecentEligibleRun() {
        InsiderActivityCheckJobStore jobStore = mock(InsiderActivityCheckJobStore.class);
        Instant restartedAt = localTime(2026, 7, 30, 8, 0);
        when(jobStore.findLatestScheduledRun()).thenReturn(Optional.empty());
        when(jobStore.enqueueScheduledRun(localTime(2026, 7, 30, 0, 15)))
                .thenReturn(new EnqueueResult(1, 2));
        InsiderActivityScheduleRecoveryService service = service(jobStore, restartedAt);

        var result = service.enqueueLatestDueRun();

        assertThat(result.dueRuns()).isEqualTo(1);
        assertThat(result.queuedJobs()).isEqualTo(2);
        ArgumentCaptor<Instant> scheduledFor = ArgumentCaptor.forClass(Instant.class);
        verify(jobStore).enqueueScheduledRun(scheduledFor.capture());
        assertThat(scheduledFor.getValue()).isEqualTo(localTime(2026, 7, 30, 0, 15));
    }

    @Test
    void doesNotQueueTomorrowRunBeforeItsConfiguredTime() {
        InsiderActivityCheckJobStore jobStore = mock(InsiderActivityCheckJobStore.class);
        Instant lastRun = localTime(2026, 7, 29, 0, 15);
        when(jobStore.findLatestScheduledRun()).thenReturn(Optional.of(lastRun));
        InsiderActivityScheduleRecoveryService service =
                service(jobStore, localTime(2026, 7, 30, 0, 10));

        var result = service.enqueueLatestDueRun();

        assertThat(result.dueRuns()).isZero();
        verify(jobStore, never()).enqueueScheduledRun(org.mockito.ArgumentMatchers.any());
    }

    private InsiderActivityScheduleRecoveryService service(
            InsiderActivityCheckJobStore jobStore,
            Instant now) {
        return new InsiderActivityScheduleRecoveryService(
                jobStore,
                BRUSSELS.getId(),
                "0 15 0 * * *",
                30,
                Clock.fixed(now, BRUSSELS));
    }

    private Instant localTime(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(
                year,
                month,
                day,
                hour,
                minute,
                0,
                0,
                BRUSSELS).toInstant();
    }
}
