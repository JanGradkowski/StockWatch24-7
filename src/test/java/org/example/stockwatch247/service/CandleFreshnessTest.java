package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandleFreshnessTest {

    @Test
    void aFridayCandleIsCurrentOnTheTuesdayAfterAMondayHolidayButAMondayCandleIsBehindOnThursday() {
        long friday = start(LocalDate.of(2026, 9, 4));
        assertThat(CandleFreshness.isStale(friday, TimeInterval.DAILY, at(LocalDate.of(2026, 9, 8), 15))).isFalse();
        assertThat(CandleFreshness.isStale(friday, TimeInterval.DAILY, at(LocalDate.of(2026, 9, 9), 15))).isTrue();

        long monday = start(LocalDate.of(2026, 9, 7));
        assertThat(CandleFreshness.isStale(monday, TimeInterval.DAILY, at(LocalDate.of(2026, 9, 9), 15))).isFalse();
        assertThat(CandleFreshness.isStale(monday, TimeInterval.DAILY, at(LocalDate.of(2026, 9, 10), 1))).isTrue();
    }

    @Test
    void weeklyAndMonthlyCandlesAllowOneTradingDayAfterTheFollowingPeriodCloses() {
        long weekOfAugust31 = start(LocalDate.of(2026, 8, 31));
        assertThat(CandleFreshness.isStale(weekOfAugust31, TimeInterval.WEEKLY, at(LocalDate.of(2026, 9, 14), 12))).isFalse();
        assertThat(CandleFreshness.isStale(weekOfAugust31, TimeInterval.WEEKLY, at(LocalDate.of(2026, 9, 15), 12))).isTrue();

        long august = start(LocalDate.of(2026, 8, 1));
        assertThat(CandleFreshness.isStale(august, TimeInterval.MONTHLY, at(LocalDate.of(2026, 10, 1), 12))).isFalse();
        assertThat(CandleFreshness.isStale(august, TimeInterval.MONTHLY, at(LocalDate.of(2026, 10, 2), 12))).isTrue();
    }

    @Test
    void roundTheClockPairsAreRefusedWhileShareClassesWithAHyphenAreAccepted() {
        assertThatThrownBy(() -> TrackedInstrumentGuard.requireExchangeSession("BTC-USD"))
                .isInstanceOf(UserInputException.class).hasMessageContaining("around the clock");
        assertThatThrownBy(() -> TrackedInstrumentGuard.requireExchangeSession("EURUSD=X"))
                .isInstanceOf(UserInputException.class);
        assertThatCode(() -> TrackedInstrumentGuard.requireExchangeSession("BRK-B")).doesNotThrowAnyException();
        assertThatCode(() -> TrackedInstrumentGuard.requireExchangeSession("^GSPC")).doesNotThrowAnyException();
        assertThatCode(() -> TrackedInstrumentGuard.requireExchangeSession("PKN.WA")).doesNotThrowAnyException();
    }

    private static long start(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    private static Instant at(LocalDate date, int hourUtc) {
        return date.atTime(hourUtc, 0).toInstant(ZoneOffset.UTC);
    }
}
