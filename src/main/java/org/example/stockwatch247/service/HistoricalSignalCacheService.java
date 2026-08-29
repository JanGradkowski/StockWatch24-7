package org.example.stockwatch247.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Durable cache for expensive historical technical-signal scans.
 *
 * <p>A cache entry is reusable only for the same detector version, effective
 * settings and exact candle revision. Calculations are serialized per cache key
 * locally and with a PostgreSQL advisory transaction lock for multi-node runs.</p>
 */
@Service
public class HistoricalSignalCacheService {
    private static final String LOCK_NAMESPACE = "historical-signal-cache";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper cacheMapper;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<String, Object> localLocks = new ConcurrentHashMap<>();

    public HistoricalSignalCacheService(JdbcTemplate jdbcTemplate,
                                        ObjectMapper objectMapper,
                                        PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.cacheMapper = objectMapper.copy()
                .disable(MapperFeature.USE_ANNOTATIONS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public <T> T getOrCompute(Family family,
                              String symbol,
                              String interval,
                              String detectorVersion,
                              Object effectiveSettings,
                              Class<T> resultType,
                              Supplier<T> calculation) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(calculation, "calculation");
        String normalizedSymbol = symbol.toUpperCase(Locale.ROOT);
        String settingsHash = fingerprint(effectiveSettings);
        CacheIdentity identity = new CacheIdentity(normalizedSymbol, interval, family.name(),
                detectorVersion, settingsHash);
        CandleSnapshot snapshot = candleSnapshot(normalizedSymbol, interval);
        Optional<T> cached = read(identity, snapshot, resultType);
        if (cached.isPresent()) {
            return cached.get();
        }

        String lockKey = identity.lockKey();
        Object localLock = localLocks.computeIfAbsent(lockKey, ignored -> new Object());
        try {
            synchronized (localLock) {
                return transactionTemplate.execute(status -> {
                    acquireDistributedLock(identity);
                    CandleSnapshot lockedSnapshot = candleSnapshot(normalizedSymbol, interval);
                    Optional<T> afterLock = read(identity, lockedSnapshot, resultType);
                    if (afterLock.isPresent()) {
                        return afterLock.get();
                    }
                    T result = calculation.get();
                    CandleSnapshot calculatedSnapshot = candleSnapshot(normalizedSymbol, interval);
                    write(identity, calculatedSnapshot, result);
                    return result;
                });
            }
        } finally {
            localLocks.remove(lockKey, localLock);
        }
    }

    public String fingerprint(Object value) {
        try {
            byte[] canonical = cacheMapper.writeValueAsBytes(value == null ? "factory" : value);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint historical signal settings.", exception);
        }
    }

    private CandleSnapshot candleSnapshot(String symbol, String interval) {
        return jdbcTemplate.queryForObject(
                """
                select count(*), max(timestamp), max(updated_at)
                from candles
                where symbol = ? and time_interval = ?
                """,
                (rs, rowNum) -> new CandleSnapshot(
                        rs.getLong(1),
                        nullableLong(rs, 2),
                        Optional.ofNullable(rs.getTimestamp(3)).map(Timestamp::toInstant).orElse(null)),
                symbol, interval);
    }

    private <T> Optional<T> read(CacheIdentity identity,
                                 CandleSnapshot snapshot,
                                 Class<T> resultType) {
        List<String> payloads = jdbcTemplate.query(
                """
                select payload::text
                from historical_signal_cache
                where symbol = ?
                  and time_interval = ?
                  and signal_family = ?
                  and detector_version = ?
                  and settings_hash = ?
                  and candle_count = ?
                  and latest_candle_timestamp is not distinct from ?
                  and candle_revision is not distinct from ?
                """,
                (rs, rowNum) -> rs.getString(1),
                identity.symbol(), identity.interval(), identity.family(), identity.detectorVersion(),
                identity.settingsHash(), snapshot.count(), snapshot.latestTimestamp(),
                timestamp(snapshot.revision()));
        if (payloads.isEmpty()) {
            return Optional.empty();
        }
        try {
            T value = cacheMapper.readValue(payloads.getFirst(), resultType);
            jdbcTemplate.update(
                    """
                    update historical_signal_cache set last_accessed_at = current_timestamp
                    where symbol = ? and time_interval = ? and signal_family = ?
                      and detector_version = ? and settings_hash = ?
                    """,
                    identity.symbol(), identity.interval(), identity.family(),
                    identity.detectorVersion(), identity.settingsHash());
            return Optional.of(value);
        } catch (JsonProcessingException exception) {
            delete(identity);
            return Optional.empty();
        }
    }

    private void write(CacheIdentity identity, CandleSnapshot snapshot, Object value) {
        try {
            String payload = cacheMapper.writeValueAsString(value);
            jdbcTemplate.update(
                    """
                    insert into historical_signal_cache
                        (symbol, time_interval, signal_family, detector_version, settings_hash,
                         candle_count, latest_candle_timestamp, candle_revision, payload,
                         calculated_at, last_accessed_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), current_timestamp, current_timestamp)
                    on conflict (symbol, time_interval, signal_family, detector_version, settings_hash)
                    do update set
                        candle_count = excluded.candle_count,
                        latest_candle_timestamp = excluded.latest_candle_timestamp,
                        candle_revision = excluded.candle_revision,
                        payload = excluded.payload,
                        calculated_at = current_timestamp,
                        last_accessed_at = current_timestamp
                    """,
                    identity.symbol(), identity.interval(), identity.family(), identity.detectorVersion(),
                    identity.settingsHash(), snapshot.count(), snapshot.latestTimestamp(),
                    timestamp(snapshot.revision()), payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize historical signal cache entry.", exception);
        }
    }

    private void acquireDistributedLock(CacheIdentity identity) {
        jdbcTemplate.query(
                "select pg_advisory_xact_lock(hashtext(?), hashtext(?))",
                rs -> { }, LOCK_NAMESPACE, identity.lockKey());
    }

    private void delete(CacheIdentity identity) {
        jdbcTemplate.update(
                """
                delete from historical_signal_cache
                where symbol = ? and time_interval = ? and signal_family = ?
                  and detector_version = ? and settings_hash = ?
                """,
                identity.symbol(), identity.interval(), identity.family(),
                identity.detectorVersion(), identity.settingsHash());
    }

    private static Long nullableLong(java.sql.ResultSet resultSet, int column) throws java.sql.SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    @Scheduled(cron = "${historical-signal-cache.cleanup-cron:0 30 3 * * *}")
    public void removeStaleProfiles() {
        jdbcTemplate.update(
                "delete from historical_signal_cache where last_accessed_at < current_timestamp - interval '30 days'");
    }

    public enum Family {
        CANDLESTICK,
        ELLIOTT_WAVE,
        HARMONIC_FORMATION
    }

    private record CacheIdentity(String symbol, String interval, String family,
                                 String detectorVersion, String settingsHash) {
        private String lockKey() {
            return symbol + '|' + interval + '|' + family + '|' + detectorVersion + '|' + settingsHash;
        }
    }

    private record CandleSnapshot(long count, Long latestTimestamp, Instant revision) { }
}
