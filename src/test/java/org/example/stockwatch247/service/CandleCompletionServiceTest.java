package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandleCompletionServiceTest {
    private static final Instant FRIDAY_AFTERNOON =
            Instant.parse("2026-07-24T16:44:00Z");

    private final CandleCompletionService service = new CandleCompletionService(
            "Europe/Brussels",
            Clock.fixed(FRIDAY_AFTERNOON, ZoneOffset.UTC)
    );

    @Test
    void dailyCandleIsIncompleteUntilTheNextLocalCalendarDay() {
        long july23 = Instant.parse("2026-07-23T00:00:00Z").getEpochSecond();
        long july24 = Instant.parse("2026-07-24T00:00:00Z").getEpochSecond();

        assertThat(service.firstIncompleteCandleTimestamp(TimeInterval.DAILY))
                .isEqualTo(july24);
        assertThat(service.isComplete(july23, TimeInterval.DAILY)).isTrue();
        assertThat(service.isComplete(july24, TimeInterval.DAILY)).isFalse();
    }

    @Test
    void weeklyCandleRemainsIncompleteDuringTheTradingWeek() {
        long previousWeek = Instant.parse("2026-07-13T00:00:00Z").getEpochSecond();
        long currentWeek = Instant.parse("2026-07-20T00:00:00Z").getEpochSecond();

        assertThat(service.firstIncompleteCandleTimestamp(TimeInterval.WEEKLY))
                .isEqualTo(currentWeek);
        assertThat(service.isComplete(previousWeek, TimeInterval.WEEKLY)).isTrue();
        assertThat(service.isComplete(currentWeek, TimeInterval.WEEKLY)).isFalse();
    }

    @Test
    void weeklyCandleIsCompleteOnSaturdayAfterMarketsHaveClosedForTheWeek() {
        CandleCompletionService saturday = new CandleCompletionService(
                "Europe/Brussels",
                Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZoneOffset.UTC)
        );
        long currentWeek = Instant.parse("2026-07-20T00:00:00Z").getEpochSecond();
        long nextWeek = Instant.parse("2026-07-27T00:00:00Z").getEpochSecond();

        assertThat(saturday.firstIncompleteCandleTimestamp(TimeInterval.WEEKLY))
                .isEqualTo(nextWeek);
        assertThat(saturday.isComplete(currentWeek, TimeInterval.WEEKLY)).isTrue();
        assertThat(saturday.isComplete(nextWeek, TimeInterval.WEEKLY)).isFalse();
    }

    @Test
    void monthlyCandleIsIncompleteUntilTheNextMonth() {
        long june = Instant.parse("2026-06-01T00:00:00Z").getEpochSecond();
        long july = Instant.parse("2026-07-01T00:00:00Z").getEpochSecond();

        assertThat(service.firstIncompleteCandleTimestamp(TimeInterval.MONTHLY))
                .isEqualTo(july);
        assertThat(service.isComplete(june, TimeInterval.MONTHLY)).isTrue();
        assertThat(service.isComplete(july, TimeInterval.MONTHLY)).isFalse();
    }

    @Test
    void completedDailyCandleBecomesEligibleAtLocalMidnight() {
        CandleCompletionService nextDay = new CandleCompletionService(
                "Europe/Brussels",
                Clock.fixed(Instant.parse("2026-07-24T22:00:00Z"), ZoneOffset.UTC)
        );
        long july24 = Instant.parse("2026-07-24T00:00:00Z").getEpochSecond();

        assertThat(nextDay.isComplete(july24, TimeInterval.DAILY)).isTrue();
    }

    @Test
    void unsupportedIntervalsAreRejected() {
        assertThatThrownBy(() ->
                service.firstIncompleteCandleTimestamp(TimeInterval.ONE_HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daily, weekly, and monthly");
    }
}
