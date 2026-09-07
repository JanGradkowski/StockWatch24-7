package org.example.stockwatch247.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalSignalCacheServiceTest {
    private JdbcTemplate jdbcTemplate;
    private PlatformTransactionManager transactionManager;
    private HistoricalSignalCacheService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        transactionManager = mock(PlatformTransactionManager.class);
        service = new HistoricalSignalCacheService(
                jdbcTemplate,
                tools.jackson.databind.json.JsonMapper.builder().build(),
                transactionManager);
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        when(jdbcTemplate.update(contains("insert into historical_signal_cache_leases"), any(Object[].class)))
                .thenReturn(1);
    }

    @Test
    void returnsFreshPayloadWithoutRunningDetectorAgain() throws Exception {
        Instant revision = Instant.parse("2026-08-26T10:15:30Z");
        ResultSet resultSet = snapshotResultSet(250L, 1_777_000_000L, revision);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> ((RowMapper<?>) invocation.getArgument(1)).mapRow(resultSet, 0));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("{\"name\":\"cached\",\"values\":[1,2,3]}"));

        AtomicInteger calculations = new AtomicInteger();
        CachedResult result = service.getOrCompute(
                HistoricalSignalCacheService.Family.HARMONIC_FORMATION,
                "nvda",
                "1d",
                "HARMONIC_V3",
                new Settings(0.08, true),
                CachedResult.class,
                () -> {
                    calculations.incrementAndGet();
                    return new CachedResult("calculated", List.of(9));
                });

        assertThat(result).isEqualTo(new CachedResult("cached", List.of(1, 2, 3)));
        assertThat(calculations).hasValue(0);
    }

    @Test
    void fingerprintsAreStableButSeparateDifferentEffectiveSettings() {
        String first = service.fingerprint(new Settings(0.08, true));
        String identical = service.fingerprint(new Settings(0.08, true));
        String changedTolerance = service.fingerprint(new Settings(0.12, true));
        String disabled = service.fingerprint(new Settings(0.08, false));

        assertThat(first).hasSize(64).isEqualTo(identical);
        assertThat(changedTolerance).isNotEqualTo(first);
        assertThat(disabled).isNotEqualTo(first);
    }

    @Test
    void recalculatesAndReplacesPayloadWhenCandleRevisionHasNoMatchingEntry() throws Exception {
        Instant revisedAt = Instant.parse("2026-08-26T11:15:30Z");
        ResultSet resultSet = snapshotResultSet(251L, 1_777_086_400L, revisedAt);
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> ((RowMapper<?>) invocation.getArgument(1)).mapRow(resultSet, 0));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(true);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);

        AtomicInteger calculations = new AtomicInteger();
        CachedResult result = service.getOrCompute(
                HistoricalSignalCacheService.Family.ELLIOTT_WAVE,
                "NVDA",
                "1d",
                "ELLIOTT_V1",
                new Settings(0.08, true),
                CachedResult.class,
                () -> {
                    calculations.incrementAndGet();
                    return new CachedResult("recalculated", List.of(4, 5));
                });

        assertThat(result).isEqualTo(new CachedResult("recalculated", List.of(4, 5)));
        assertThat(calculations).hasValue(1);
        verify(jdbcTemplate).update(contains("insert into historical_signal_cache\n"), any(Object[].class));
        verify(transactionManager).commit(transactionStatus);
    }

    private ResultSet snapshotResultSet(long count, long timestamp, Instant revision) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong(1)).thenReturn(count);
        when(resultSet.getLong(2)).thenReturn(timestamp);
        when(resultSet.wasNull()).thenReturn(false);
        when(resultSet.getTimestamp(3)).thenReturn(Timestamp.from(revision));
        return resultSet;
    }

    private record Settings(double tolerance, boolean enabled) { }
    private record CachedResult(String name, List<Integer> values) { }
}
