package org.example.stockwatch247.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Durable cache for expensive historical technical-signal scans.
 *
 * <p>A cache entry is reusable only for the same detector version, effective
 * settings and exact candle revision. Calculations use bounded local locks and expiring database leases.
 * No database transaction is held during detector computation.</p>
 */
@Service
public class HistoricalSignalCacheService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper cacheMapper;
    private final TransactionTemplate transactionTemplate;
    private final Object[] localLocks = java.util.stream.IntStream.range(0, 256)
            .mapToObj(i -> new Object()).toArray();
    private final java.util.concurrent.Semaphore computations = new java.util.concurrent.Semaphore(4);
    private final CandleRevisionService revisions;

    public HistoricalSignalCacheService(JdbcTemplate jdbcTemplate,
                                        ObjectMapper objectMapper,
                                        PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.revisions = new CandleRevisionService(jdbcTemplate);
        this.cacheMapper = tools.jackson.databind.json.JsonMapper.builder()
                .disable(MapperFeature.USE_ANNOTATIONS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();
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
                "REV2:" + detectorVersion, settingsHash);
        CandleSnapshot snapshot = candleSnapshot(normalizedSymbol, interval);
        Optional<T> cached = read(identity, snapshot, resultType);
        if (cached.isPresent()) {
            return cached.get();
        }

        String lockKey = identity.lockKey();
        Object localLock = localLocks[Math.floorMod(lockKey.hashCode(), localLocks.length)];
        synchronized (localLock) {
            try {
                computations.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Historical calculation interrupted.", e);
            }
            try {
                java.util.UUID owner = java.util.UUID.randomUUID();
                long deadline = System.nanoTime() + java.time.Duration.ofMinutes(6).toNanos();
                while (!claim(lockKey, owner)) {
                    Optional<T> available = read(identity, candleSnapshot(normalizedSymbol, interval), resultType);
                    if (available.isPresent()) return available.get();
                    if (System.nanoTime() > deadline) throw new IllegalStateException("Historical calculation is busy; retry shortly.");
                    try { Thread.sleep(250); }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Historical calculation interrupted.", e);
                    }
                }
                try {
                    for (int attempt = 0; attempt < 3; attempt++) {
                        CandleSnapshot before = candleSnapshot(normalizedSymbol, interval);
                        Optional<T> available = read(identity, before, resultType);
                        if (available.isPresent()) return available.get();
                        // No transaction/connection is retained while the detector runs.
                        T result = calculation.get();
                        if (before.equals(candleSnapshot(normalizedSymbol, interval))) {
                            transactionTemplate.executeWithoutResult(status -> {
                                Boolean owned = jdbcTemplate.query("""
                                    select owner = ? and lease_until > current_timestamp
                                    from historical_signal_cache_leases where cache_key = ? for update
                                    """, rs -> rs.next() && rs.getBoolean(1), owner, lockKey);
                                if (Boolean.TRUE.equals(owned)) write(identity, before, result);
                            });
                            return result;
                        }
                        if (attempt == 2) return result; // Changing input is never published as a valid cache entry.
                    }
                    throw new IllegalStateException("Historical calculation failed.");
                } finally {
                    jdbcTemplate.update("delete from historical_signal_cache_leases where cache_key = ? and owner = ?", lockKey, owner);
                }
            } finally { computations.release(); }
        }
    }

    private boolean claim(String key, java.util.UUID owner) {
        return jdbcTemplate.update("""
            insert into historical_signal_cache_leases values (?, ?, current_timestamp + interval '5 minutes')
            on conflict (cache_key) do update set owner = excluded.owner, lease_until = excluded.lease_until
            where historical_signal_cache_leases.lease_until < current_timestamp
            """, key, owner) == 1;
    }

    public String fingerprint(Object value) {
        try {
            byte[] canonical = cacheMapper.writeValueAsBytes(value == null ? "factory" : value);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (JacksonException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint historical signal settings.", exception);
        }
    }

    private CandleSnapshot candleSnapshot(String symbol, String interval) {
        return new CandleSnapshot(revisions.generation(symbol, interval));
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
                  and candle_generation = ?
                """,
                (rs, rowNum) -> rs.getString(1),
                identity.symbol(), identity.interval(), identity.family(), identity.detectorVersion(),
                identity.settingsHash(), snapshot.generation());
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
                      and last_accessed_at < current_timestamp - interval '1 day'
                    """,
                    identity.symbol(), identity.interval(), identity.family(),
                    identity.detectorVersion(), identity.settingsHash());
            return Optional.of(value);
        } catch (JacksonException exception) {
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
                         candle_count, candle_generation, payload,
                         calculated_at, last_accessed_at)
                    values (?, ?, ?, ?, ?, 0, ?, cast(? as jsonb), current_timestamp, current_timestamp)
                    on conflict (symbol, time_interval, signal_family, detector_version, settings_hash)
                    do update set
                        candle_generation = excluded.candle_generation,
                        payload = excluded.payload,
                        calculated_at = current_timestamp,
                        last_accessed_at = current_timestamp
                    """,
                    identity.symbol(), identity.interval(), identity.family(), identity.detectorVersion(),
                    identity.settingsHash(), snapshot.generation(), payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize historical signal cache entry.", exception);
        }
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

    @Scheduled(cron = "${historical-signal-cache.cleanup-cron:0 30 3 * * *}")
    public void removeStaleProfiles() {
        jdbcTemplate.update("delete from historical_signal_cache_leases where lease_until < current_timestamp - interval '1 day'");
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

    private record CandleSnapshot(long generation) { }
}
