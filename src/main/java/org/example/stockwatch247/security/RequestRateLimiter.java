package org.example.stockwatch247.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class RequestRateLimiter {
    private final JdbcTemplate jdbcTemplate;

    public RequestRateLimiter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryAcquire(String key, int maximumRequests, Duration window) {
        Integer count = jdbcTemplate.queryForObject(
                """
                insert into rate_limit_windows (counter_key, window_started_at, request_count)
                values (?, floor(extract(epoch from clock_timestamp()) * 1000)::bigint, 1)
                on conflict (counter_key) do update
                set window_started_at = case
                        when rate_limit_windows.window_started_at <= excluded.window_started_at - ?
                            then excluded.window_started_at
                        else rate_limit_windows.window_started_at
                    end,
                    request_count = case
                        when rate_limit_windows.window_started_at <= excluded.window_started_at - ?
                            then 1
                        else rate_limit_windows.request_count + 1
                    end
                returning request_count
                """,
                Integer.class,
                hashKey(key), window.toMillis(), window.toMillis());
        return count != null && count <= Math.max(1, maximumRequests);
    }

    @Scheduled(cron = "${security.rate-limit.cleanup-cron:0 15 * * * *}")
    public void removeExpiredCounters() {
        jdbcTemplate.update(
                "delete from rate_limit_windows where window_started_at < "
                        + "floor(extract(epoch from clock_timestamp() - interval '2 hours') * 1000)::bigint");
    }

    private String hashKey(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
