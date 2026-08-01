package org.example.stockwatch247.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    @Test
    void loginUsesBothIpAndNormalizedAccountBuckets() throws Exception {
        RequestRateLimiter limiter = mock(RequestRateLimiter.class);
        when(limiter.tryAcquire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        RateLimitFilter filter = new RateLimitFilter(limiter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/login");
        request.setRemoteAddr("203.0.113.8");
        request.addParameter("email", " User@Example.COM ");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(limiter).tryAcquire(eq("login:ip:203.0.113.8"), eq(10), eq(java.time.Duration.ofMinutes(1)));
        verify(limiter).tryAcquire(eq("login-account:account:user@example.com"), eq(30),
                eq(java.time.Duration.ofMinutes(15)));
    }

    @Test
    void blockedAccountBucketReturnsGeneric429() throws Exception {
        RequestRateLimiter limiter = mock(RequestRateLimiter.class);
        when(limiter.tryAcquire(eq("login:ip:203.0.113.8"), eq(10), eq(java.time.Duration.ofMinutes(1))))
                .thenReturn(true);
        when(limiter.tryAcquire(eq("login-account:account:user@example.com"), eq(30),
                eq(java.time.Duration.ofMinutes(15))))
                .thenReturn(false);
        RateLimitFilter filter = new RateLimitFilter(limiter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/login");
        request.setRemoteAddr("203.0.113.8");
        request.addParameter("email", "user@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("900");
        assertThat(response.getContentAsString()).doesNotContain("user@example.com");
    }

    @Test
    void localRadixSearchUsesItsOwnHigherCapacityBucket() throws Exception {
        RequestRateLimiter limiter = mock(RequestRateLimiter.class);
        when(limiter.tryAcquire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        RateLimitFilter filter = new RateLimitFilter(limiter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/stocks/search/local");
        request.setRemoteAddr("203.0.113.8");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(limiter).tryAcquire(eq("stock-search-local:ip:203.0.113.8"), eq(120),
                eq(java.time.Duration.ofMinutes(1)));
    }

    @Test
    void congressionalRoutesRetainTheGeneralThirtyPerMinuteGuard() throws Exception {
        RequestRateLimiter limiter = mock(RequestRateLimiter.class);
        when(limiter.tryAcquire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        RateLimitFilter filter = new RateLimitFilter(limiter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/congressional-activity/AAPL/history");
        request.setRemoteAddr("203.0.113.8");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(limiter).tryAcquire(eq("congressional-activity:ip:203.0.113.8"), eq(30),
                eq(java.time.Duration.ofMinutes(1)));
    }

    @Test
    void insiderRefreshUsesPerUserMinuteAndHourlyGuards() throws Exception {
        RequestRateLimiter limiter = mock(RequestRateLimiter.class);
        when(limiter.tryAcquire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        RateLimitFilter filter = new RateLimitFilter(limiter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/insider-activity/AAPL/history/refresh");
        request.setRemoteAddr("203.0.113.8");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "User@Example.com", "unused", java.util.List.of()));

        try {
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

            verify(limiter).tryAcquire(
                    eq("insider-activity-minute:user:user@example.com"),
                    eq(5),
                    eq(java.time.Duration.ofMinutes(1)));
            verify(limiter).tryAcquire(
                    eq("insider-activity-hour:user:user@example.com"),
                    eq(100),
                    eq(java.time.Duration.ofHours(1)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void insiderRefreshHourlyGuardReturns429AfterMinuteGuardPasses() throws Exception {
        RequestRateLimiter limiter = mock(RequestRateLimiter.class);
        when(limiter.tryAcquire(
                eq("insider-activity-minute:ip:203.0.113.8"),
                eq(5),
                eq(java.time.Duration.ofMinutes(1)))).thenReturn(true);
        when(limiter.tryAcquire(
                eq("insider-activity-hour:ip:203.0.113.8"),
                eq(100),
                eq(java.time.Duration.ofHours(1)))).thenReturn(false);
        RateLimitFilter filter = new RateLimitFilter(limiter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/insider-activity/AAPL/history/refresh");
        request.setRemoteAddr("203.0.113.8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
    }

    @Test
    void insiderCacheReadDoesNotConsumeProviderBuckets() throws Exception {
        RequestRateLimiter limiter = mock(RequestRateLimiter.class);
        RateLimitFilter filter = new RateLimitFilter(limiter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI("/api/insider-activity/AAPL/history");
        request.setRemoteAddr("203.0.113.8");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        org.mockito.Mockito.verifyNoInteractions(limiter);
    }
}
