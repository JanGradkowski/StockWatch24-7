package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/**
 * Defines the first candle timestamp that still belongs to an active,
 * incomplete calendar period.
 *
 * <p>Daily, weekly, and monthly candle timestamps are stored as canonical UTC
 * midnight period-start dates. Completion is evaluated in the configured alert
 * schedule zone, then converted back to the same canonical UTC representation
 * for repository comparisons. A weekly candle becomes eligible on Saturday,
 * after the trading week has ended, rather than waiting until Monday.</p>
 */
@Service
public class CandleCompletionService {
    private final ZoneId completionZone;
    private final Clock clock;

    @Autowired
    public CandleCompletionService(
            @Value("${alerts.schedule.zone:Europe/Brussels}") String completionZone) {
        this(completionZone, Clock.systemUTC());
    }

    CandleCompletionService(String completionZone, Clock clock) {
        this.completionZone = ZoneId.of(completionZone);
        this.clock = clock;
    }

    /**
     * Returns an exclusive upper timestamp for completed candles. A candle
     * whose timestamp is equal to or after this boundary is still active and
     * must not be used for signal detection.
     */
    public long firstIncompleteCandleTimestamp(TimeInterval interval) {
        LocalDate today = LocalDate.now(clock.withZone(completionZone));
        LocalDate activePeriodStart = switch (interval) {
            case DAILY -> today;
            case WEEKLY -> firstIncompleteWeeklyPeriodStart(today);
            case MONTHLY -> today.withDayOfMonth(1);
            default -> throw new IllegalArgumentException(
                    "Candle completion is only defined for daily, weekly, and monthly intervals.");
        };
        return activePeriodStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    private LocalDate firstIncompleteWeeklyPeriodStart(LocalDate today) {
        LocalDate currentWeekStart =
                today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return today.getDayOfWeek() == DayOfWeek.SATURDAY
                || today.getDayOfWeek() == DayOfWeek.SUNDAY
                ? currentWeekStart.plusWeeks(1)
                : currentWeekStart;
    }

    public boolean isComplete(long candleTimestamp, TimeInterval interval) {
        return candleTimestamp < firstIncompleteCandleTimestamp(interval);
    }
}
