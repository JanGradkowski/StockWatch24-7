package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class SignalPeriodFormatter {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_MONTH_YEAR =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_YEAR =
            DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH);
    private static final String RANGE_SEPARATOR = "\u2013";

    private SignalPeriodFormatter() {
    }

    static String format(Long timestamp, TimeInterval interval, ZoneId zoneId) {
        if (timestamp == null) {
            return null;
        }

        LocalDate start = Instant.ofEpochSecond(timestamp).atZone(zoneId).toLocalDate();
        if (interval == null) {
            return start.format(DAY_MONTH_YEAR);
        }

        return switch (interval) {
            case WEEKLY -> formatWeek(start);
            case MONTHLY -> start.format(MONTH_YEAR);
            case YEARLY -> Integer.toString(start.getYear());
            default -> start.format(DAY_MONTH_YEAR);
        };
    }

    private static String formatWeek(LocalDate start) {
        LocalDate end = start.plusDays(4);
        if (start.getYear() == end.getYear() && start.getMonth() == end.getMonth()) {
            return start.format(DAY) + RANGE_SEPARATOR + end.format(DAY_MONTH_YEAR);
        }
        if (start.getYear() == end.getYear()) {
            return start.format(DAY_MONTH) + RANGE_SEPARATOR + end.format(DAY_MONTH_YEAR);
        }
        return start.format(DAY_MONTH_YEAR) + RANGE_SEPARATOR + end.format(DAY_MONTH_YEAR);
    }
}
