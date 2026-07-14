package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertScheduleRecoveryServiceTest {
    private static final ZoneId BRUSSELS = ZoneId.of("Europe/Brussels");

    @Test
    void replaysEveryDailyRunMissedDuringAnOutageInOriginalOrder() {
        AlertCheckJobStore jobStore = mock(AlertCheckJobStore.class);
        Instant lastRun = localTime(2026, 7, 10, 0);
        Instant restartedAt = localTime(2026, 7, 15, 8);
        when(jobStore.findLatestScheduledRun(TimeInterval.DAILY)).thenReturn(Optional.of(lastRun));
        when(jobStore.enqueueScheduledRun(eq(TimeInterval.DAILY), any())).thenReturn(2);
        AlertScheduleRecoveryService service = service(jobStore, restartedAt, 100);

        AlertScheduleRecoveryService.RecoveryResult result = service.enqueueDueRuns(TimeInterval.DAILY);

        ArgumentCaptor<Instant> scheduledFor = ArgumentCaptor.forClass(Instant.class);
        verify(jobStore, times(3))
                .enqueueScheduledRun(eq(TimeInterval.DAILY), scheduledFor.capture());
        assertThat(scheduledFor.getAllValues()).containsExactly(
                localTime(2026, 7, 11, 0),
                localTime(2026, 7, 14, 0),
                localTime(2026, 7, 15, 0)
        );
        assertThat(result).isEqualTo(new AlertScheduleRecoveryService.RecoveryResult(3, 6));
    }

    @Test
    void firstStartupCheckpointsTheMostRecentRunForEverySupportedInterval() {
        AlertCheckJobStore jobStore = mock(AlertCheckJobStore.class);
        Instant restartedAt = localTime(2026, 7, 14, 10);
        when(jobStore.findLatestScheduledRun(any())).thenReturn(Optional.empty());
        AlertScheduleRecoveryService service = service(jobStore, restartedAt, 100);

        AlertScheduleRecoveryService.RecoveryResult result = service.enqueueAllDueRuns();

        verify(jobStore).enqueueScheduledRun(TimeInterval.DAILY, localTime(2026, 7, 14, 0));
        verify(jobStore).enqueueScheduledRun(TimeInterval.WEEKLY, localTime(2026, 7, 11, 0));
        verify(jobStore).enqueueScheduledRun(TimeInterval.MONTHLY, localTime(2026, 7, 1, 0));
        assertThat(result.scheduledRuns()).isEqualTo(3);
    }

    @Test
    void limitsEachRecoveryPassSoAnExtendedOutageIsDrainedIncrementally() {
        AlertCheckJobStore jobStore = mock(AlertCheckJobStore.class);
        when(jobStore.findLatestScheduledRun(TimeInterval.DAILY))
                .thenReturn(Optional.of(localTime(2026, 7, 10, 0)));
        AlertScheduleRecoveryService service = service(jobStore, localTime(2026, 7, 15, 8), 2);

        AlertScheduleRecoveryService.RecoveryResult result = service.enqueueDueRuns(TimeInterval.DAILY);

        ArgumentCaptor<Instant> scheduledFor = ArgumentCaptor.forClass(Instant.class);
        verify(jobStore, times(2))
                .enqueueScheduledRun(eq(TimeInterval.DAILY), scheduledFor.capture());
        assertThat(scheduledFor.getAllValues()).containsExactly(
                localTime(2026, 7, 11, 0),
                localTime(2026, 7, 14, 0)
        );
        assertThat(result.scheduledRuns()).isEqualTo(2);
    }

    private AlertScheduleRecoveryService service(AlertCheckJobStore jobStore,
                                                 Instant now,
                                                 int batchSize) {
        return new AlertScheduleRecoveryService(
                jobStore,
                BRUSSELS.getId(),
                "0 0 0 * * TUE-SAT",
                "0 0 0 * * SAT",
                "0 0 0 1 * *",
                batchSize,
                370,
                Clock.fixed(now, BRUSSELS)
        );
    }

    private Instant localTime(int year, int month, int day, int hour) {
        return ZonedDateTime.of(year, month, day, hour, 0, 0, 0, BRUSSELS).toInstant();
    }
}
