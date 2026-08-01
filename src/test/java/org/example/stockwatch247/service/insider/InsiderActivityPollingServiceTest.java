package org.example.stockwatch247.service.insider;

import org.example.stockwatch247.service.insider.InsiderActivityCheckJobStore.InsiderActivityCheckJob;
import org.example.stockwatch247.service.insider.InsiderActivityScheduleRecoveryService.RecoveryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class InsiderActivityPollingServiceTest {

    @Test
    void startupRecoveryQueuesTheLatestMissedCheck(CapturedOutput output) {
        Fixture fixture = fixture();
        Instant scheduledFor = Instant.parse("2026-07-30T22:15:00Z");
        when(fixture.recoveryService().enqueueLatestDueRun())
                .thenReturn(new RecoveryResult(2, 1, 3, scheduledFor));

        fixture.service().recoverMissedChecksOnStartup();

        assertThat(output)
                .contains("Recovered 2 missed insider activity schedule(s)")
                .contains("queued 3 followed stock check(s)");
    }

    @Test
    void workerCompletesADurableStockCheck(CapturedOutput output) {
        Fixture fixture = fixture();
        InsiderActivityCheckJob job = new InsiderActivityCheckJob(
                19L,
                7L,
                "AAPL",
                Instant.parse("2026-07-29T22:15:00Z"),
                1);
        when(fixture.jobStore().claimNext(any())).thenReturn(Optional.of(job));
        when(fixture.activityService().pollScheduledActivity(7L, "AAPL")).thenReturn(true);
        when(fixture.jobStore().pendingCount()).thenReturn(0);

        fixture.service().processNextQueuedCheck();

        verify(fixture.activityService()).pollScheduledActivity(7L, "AAPL");
        verify(fixture.jobStore()).complete(19L);
        assertThat(output)
                .contains("Insider activity job completed for AAPL")
                .contains("All queued insider activity checks completed.");
    }

    @Test
    void failedCheckIsReturnedToTheDurableRetryPolicy() {
        Fixture fixture = fixture();
        InsiderActivityCheckJob job = new InsiderActivityCheckJob(
                21L,
                7L,
                "AAPL",
                Instant.parse("2026-07-29T22:15:00Z"),
                1);
        when(fixture.jobStore().claimNext(any())).thenReturn(Optional.of(job));
        when(fixture.activityService().pollScheduledActivity(7L, "AAPL"))
                .thenThrow(new IllegalStateException("provider unavailable"));

        fixture.service().processNextQueuedCheck();

        verify(fixture.jobStore()).retryOrFail(
                eq(job),
                eq("IllegalStateException: provider unavailable"),
                eq(3),
                any());
    }

    private Fixture fixture() {
        InsiderActivityService activityService = mock(InsiderActivityService.class);
        InsiderActivityScheduleRecoveryService recoveryService =
                mock(InsiderActivityScheduleRecoveryService.class);
        InsiderActivityCheckJobStore jobStore = mock(InsiderActivityCheckJobStore.class);
        InsiderActivityPollingService service = new InsiderActivityPollingService(
                activityService,
                recoveryService,
                jobStore,
                true,
                true,
                1200,
                300,
                3);
        return new Fixture(service, activityService, recoveryService, jobStore);
    }

    private record Fixture(
            InsiderActivityPollingService service,
            InsiderActivityService activityService,
            InsiderActivityScheduleRecoveryService recoveryService,
            InsiderActivityCheckJobStore jobStore) {
    }
}
