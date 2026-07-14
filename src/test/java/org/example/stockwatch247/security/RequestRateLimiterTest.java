package org.example.stockwatch247.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestRateLimiterTest {

    @Test
    void rejectsRequestsBeyondTheWindowLimit() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1, 2, 3, 1);
        RequestRateLimiter limiter = new RequestRateLimiter(jdbcTemplate);

        assertTrue(limiter.tryAcquire("login:127.0.0.1", 2, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire("login:127.0.0.1", 2, Duration.ofMinutes(1)));
        assertFalse(limiter.tryAcquire("login:127.0.0.1", 2, Duration.ofMinutes(1)));
        assertTrue(limiter.tryAcquire("login:127.0.0.2", 2, Duration.ofMinutes(1)));
    }
}
