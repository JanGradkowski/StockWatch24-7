package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/**
 * Whether the newest stored candle has fallen behind, counted in trading days rather than calendar
 * days: a Friday candle is still current on the Tuesday after a Monday holiday, while a Monday candle
 * on Thursday is not. Candle timestamps mark the period start as a UTC date.
 */
final class CandleFreshness {
    private CandleFreshness() {
    }

    static boolean isStale(long candleTimestamp, TimeInterval interval, Instant now) {
        LocalDate candleDate = Instant.ofEpochSecond(candleTimestamp).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate followingPeriodEnd = switch (interval) {
            case DAILY -> nextWeekday(candleDate);
            case WEEKLY -> candleDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(1).plusDays(4);
            case MONTHLY -> lastWeekday(candleDate.withDayOfMonth(1).plusMonths(1));
            default -> null;
        };
        if (followingPeriodEnd == null) return false;
        // Stale once the following period has closed and one further trading day has passed,
        // which absorbs a single holiday or a late provider refresh.
        return weekdaysStrictlyBetween(followingPeriodEnd, now.atZone(ZoneOffset.UTC).toLocalDate()) >= 1;
    }

    private static LocalDate nextWeekday(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (weekend(next)) next = next.plusDays(1);
        return next;
    }

    private static LocalDate lastWeekday(LocalDate dayInMonth) {
        LocalDate last = dayInMonth.with(TemporalAdjusters.lastDayOfMonth());
        while (weekend(last)) last = last.minusDays(1);
        return last;
    }

    private static int weekdaysStrictlyBetween(LocalDate start, LocalDate end) {
        int count = 0;
        for (LocalDate day = start.plusDays(1); day.isBefore(end) && count < 2; day = day.plusDays(1)) {
            if (!weekend(day)) count++;
        }
        return count;
    }

    private static boolean weekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }
}
