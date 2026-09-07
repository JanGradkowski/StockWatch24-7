package org.example.stockwatch247.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

@Service
public class MarketDataProviderRequestBudget {
    private static final String TWELVE_DATA = "TWELVE_DATA";

    private final JdbcTemplate jdbcTemplate;
    private final int twelveDataMinuteLimit;
    private final int twelveDataDailyLimit;
    private final Clock clock;

    @Autowired
    public MarketDataProviderRequestBudget(
            JdbcTemplate jdbcTemplate,
            @Value("${market-data.twelve-data.minute-request-limit:7}") int twelveDataMinuteLimit,
            @Value("${market-data.twelve-data.daily-request-limit:760}") int twelveDataDailyLimit) {
        this(jdbcTemplate, twelveDataMinuteLimit, twelveDataDailyLimit, Clock.systemUTC());
    }

    MarketDataProviderRequestBudget(
            JdbcTemplate jdbcTemplate,
            int twelveDataMinuteLimit,
            int twelveDataDailyLimit,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.twelveDataMinuteLimit = Math.max(1, twelveDataMinuteLimit);
        this.twelveDataDailyLimit = Math.max(1, twelveDataDailyLimit);
        this.clock = clock;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void reserveTwelveDataRequest() {
        Instant now = clock.instant();
        Instant dayStart = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS).toInstant();
        Instant minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        consumeWindow(TWELVE_DATA, "DAY", dayStart, twelveDataDailyLimit);
        consumeWindow(TWELVE_DATA, "MINUTE", minuteStart, twelveDataMinuteLimit);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordTwelveDataFailure(String failureMessage) {
        String message = failureMessage == null ? "" : failureMessage.toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        if (message.contains("current day") || message.contains("for the day")) {
            Instant until = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.DAYS).plusDays(1).toInstant();
            blockWindow(TWELVE_DATA, "DAY", until);
        } else if (message.contains("current minute") || message.contains("for the minute")) {
            blockWindow(TWELVE_DATA, "MINUTE", now.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES));
        }
    }

    private void consumeWindow(String provider, String windowKind, Instant windowStart, int limit) {
        List<Integer> counts = jdbcTemplate.query(
                """
                insert into market_data_provider_usage
                    (provider, window_kind, window_started_at, request_count, blocked_until, updated_at)
                values (?, ?, ?, 1, null, current_timestamp)
                on conflict (provider, window_kind) do update
                set window_started_at = excluded.window_started_at,
                    request_count = case
                        when market_data_provider_usage.window_started_at < excluded.window_started_at then 1
                        else market_data_provider_usage.request_count + 1
                    end,
                    blocked_until = case
                        when market_data_provider_usage.window_started_at < excluded.window_started_at then null
                        else market_data_provider_usage.blocked_until
                    end,
                    updated_at = current_timestamp
                where (market_data_provider_usage.window_started_at < excluded.window_started_at
                       or market_data_provider_usage.request_count < ?)
                  and (market_data_provider_usage.blocked_until is null
                       or market_data_provider_usage.blocked_until <= current_timestamp)
                returning request_count
                """,
                (resultSet, rowNumber) -> resultSet.getInt("request_count"),
                provider, windowKind, windowStart.atOffset(ZoneOffset.UTC), limit);
        if (counts.isEmpty()) {
            throw new BudgetUnavailableException(
                    "The local Twelve Data " + windowKind.toLowerCase(Locale.ROOT)
                            + " request budget is exhausted; using fallback data.");
        }
    }

    private void blockWindow(String provider, String windowKind, Instant blockedUntil) {
        jdbcTemplate.update(
                """
                update market_data_provider_usage
                set blocked_until = case
                        when blocked_until is null or blocked_until < ? then ?
                        else blocked_until
                    end,
                    updated_at = current_timestamp
                where provider = ? and window_kind = ?
                """,
                blockedUntil.atOffset(ZoneOffset.UTC), blockedUntil.atOffset(ZoneOffset.UTC),
                provider, windowKind);
    }

    public static class BudgetUnavailableException extends RuntimeException {
        public BudgetUnavailableException(String message) {
            super(message);
        }
    }
}
