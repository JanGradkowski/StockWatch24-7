package org.example.stockwatch247.service.chartindicator;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Calendar arithmetic on the canonical candle timestamps (midnight UTC; weeks start on Monday,
 * months on the first). Future daily bars skip weekends only, so a projection can land on an
 * exchange holiday — the chart labels projected areas as approximate.
 */
public final class MarketCalendar {
    private MarketCalendar() { }

    public static LocalDate date(long timestamp) {
        return Instant.ofEpochSecond(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
    }

    public static long timestamp(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    /** The next {@code count} bar timestamps after the last bar. */
    public static List<Long> futureTimestamps(long lastTimestamp, String interval, int count) {
        List<Long> result = new ArrayList<>(Math.max(0, count));
        LocalDate date = date(lastTimestamp);
        for (int step = 0; step < count; step++) {
            date = next(date, interval);
            result.add(timestamp(date));
        }
        return result;
    }

    static LocalDate next(LocalDate date, String interval) {
        return switch (interval) {
            case "1wk" -> date.plusWeeks(1);
            case "1mo" -> date.plusMonths(1).withDayOfMonth(1);
            default -> {
                LocalDate next = date.plusDays(1);
                while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) next = next.plusDays(1);
                yield next;
            }
        };
    }

    /** Number of chart bars after the last bar and before {@code endExclusive}. */
    public static int barsUntil(long lastTimestamp, String interval, long endExclusive) {
        int count = 0;
        LocalDate date = date(lastTimestamp);
        LocalDate end = date(endExclusive);
        while (count < 400) {
            date = next(date, interval);
            if (!date.isBefore(end)) break;
            count++;
        }
        return count;
    }

    /** First day of the calendar period (WEEK, MONTH, QUARTER, YEAR) containing the timestamp. */
    public static LocalDate periodStart(long timestamp, String period) {
        LocalDate date = date(timestamp);
        return switch (period) {
            case "WEEK" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "MONTH" -> date.withDayOfMonth(1);
            case "QUARTER" -> LocalDate.of(date.getYear(), ((date.getMonthValue() - 1) / 3) * 3 + 1, 1);
            case "YEAR" -> LocalDate.of(date.getYear(), 1, 1);
            default -> throw new IllegalArgumentException("Unsupported period: " + period);
        };
    }

    public static LocalDate nextPeriodStart(LocalDate start, String period) {
        return switch (period) {
            case "WEEK" -> start.plusWeeks(1);
            case "MONTH" -> start.plusMonths(1);
            case "QUARTER" -> start.plusMonths(3);
            case "YEAR" -> start.plusYears(1);
            default -> throw new IllegalArgumentException("Unsupported period: " + period);
        };
    }

    /** Bars per year used to annualise volatility on each interval. */
    public static double periodsPerYear(String interval) {
        return switch (interval) {
            case "1wk" -> 365.0 / 7;
            case "1mo" -> 12;
            default -> 365;
        };
    }
}
