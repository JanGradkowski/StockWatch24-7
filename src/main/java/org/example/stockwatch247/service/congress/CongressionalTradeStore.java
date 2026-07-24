package org.example.stockwatch247.service.congress;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.service.congress.CongressionalTradeProvider.ProviderTrade;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class CongressionalTradeStore {
    private static final Duration USER_REFRESH_WINDOW = Duration.ofMinutes(1);

    private final JdbcTemplate jdbcTemplate;
    private final RequestRateLimiter requestRateLimiter;
    private final int maximumUncachedRefreshesPerUserPerMinute;

    public CongressionalTradeStore(
            JdbcTemplate jdbcTemplate,
            RequestRateLimiter requestRateLimiter,
            @Value("${congressional-activity.maximum-uncached-refreshes-per-user-per-minute:2}")
            int maximumUncachedRefreshesPerUserPerMinute) {
        this.jdbcTemplate = jdbcTemplate;
        this.requestRateLimiter = requestRateLimiter;
        this.maximumUncachedRefreshesPerUserPerMinute =
                Math.max(1, maximumUncachedRefreshesPerUserPerMinute);
    }

    @Transactional
    public HistoryCacheClaim claimHistoryRefresh(
            long stockAssetId,
            long userId,
            LocalDate coverageStart,
            LocalDate coverageEnd,
            Duration cacheTtl,
            Duration leaseDuration,
            String owner) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into congressional_trade_cache_state (stock_asset_id)
                values (?)
                on conflict (stock_asset_id) do nothing
                """, stockAssetId);

        HistoryCacheRow row = jdbcTemplate.queryForObject("""
                        select coverage_start,
                               coverage_end,
                               last_success_at,
                               last_attempt_at,
                               lease_owner,
                               lease_until,
                               last_error
                        from congressional_trade_cache_state
                        where stock_asset_id = ?
                        for update
                        """,
                (resultSet, rowNumber) -> new HistoryCacheRow(
                        resultSet.getObject("coverage_start", LocalDate.class),
                        resultSet.getObject("coverage_end", LocalDate.class),
                        instant(resultSet.getTimestamp("last_success_at")),
                        instant(resultSet.getTimestamp("last_attempt_at")),
                        resultSet.getString("lease_owner"),
                        instant(resultSet.getTimestamp("lease_until")),
                        resultSet.getString("last_error")),
                stockAssetId);

        boolean coversWindow = row != null
                && row.coverageStart() != null
                && !row.coverageStart().isAfter(coverageStart)
                && row.coverageEnd() != null
                && !row.coverageEnd().isBefore(coverageEnd);
        boolean fresh = coversWindow
                && row.lastSuccessAt() != null
                && !row.lastSuccessAt().isBefore(now.minus(cacheTtl));
        if (fresh) {
            return new HistoryCacheClaim(CacheClaimStatus.FRESH, row.lastSuccessAt(), true);
        }
        if (row != null && row.leaseUntil() != null && row.leaseUntil().isAfter(now)) {
            return new HistoryCacheClaim(CacheClaimStatus.IN_PROGRESS, row.lastSuccessAt(), coversWindow);
        }
        if (row != null
                && row.lastAttemptAt() != null
                && LocalDate.ofInstant(row.lastAttemptAt(), ZoneOffset.UTC)
                .equals(LocalDate.ofInstant(now, ZoneOffset.UTC))) {
            return new HistoryCacheClaim(CacheClaimStatus.COOLED_DOWN, row.lastSuccessAt(), coversWindow);
        }
        if (!requestRateLimiter.tryAcquire(
                "congressional-history-refresh:user:" + userId,
                maximumUncachedRefreshesPerUserPerMinute,
                USER_REFRESH_WINDOW)) {
            return new HistoryCacheClaim(CacheClaimStatus.USER_RATE_LIMITED, row.lastSuccessAt(), coversWindow);
        }

        jdbcTemplate.update("""
                        update congressional_trade_cache_state
                        set last_attempt_at = ?,
                            lease_owner = ?,
                            lease_until = ?,
                            last_error = null
                        where stock_asset_id = ?
                        """,
                Timestamp.from(now),
                owner,
                Timestamp.from(now.plus(leaseDuration)),
                stockAssetId);
        return new HistoryCacheClaim(CacheClaimStatus.CLAIMED, row == null ? null : row.lastSuccessAt(), coversWindow);
    }

    @Transactional
    public void completeHistoryRefresh(
            long stockAssetId,
            LocalDate coverageStart,
            LocalDate coverageEnd,
            String owner) {
        jdbcTemplate.update("""
                        update congressional_trade_cache_state
                        set coverage_start = ?,
                            coverage_end = ?,
                            last_success_at = current_timestamp,
                            lease_owner = null,
                            lease_until = null,
                            last_error = null
                        where stock_asset_id = ?
                          and lease_owner = ?
                        """,
                Date.valueOf(coverageStart),
                Date.valueOf(coverageEnd),
                stockAssetId,
                owner);
    }

    @Transactional
    public void failHistoryRefresh(long stockAssetId, String owner, Throwable failure) {
        jdbcTemplate.update("""
                        update congressional_trade_cache_state
                        set lease_owner = null,
                            lease_until = null,
                            last_error = ?
                        where stock_asset_id = ?
                          and lease_owner = ?
                        """,
                safeError(failure),
                stockAssetId,
                owner);
    }

    public Instant historyLastSuccess(long stockAssetId) {
        List<Instant> values = jdbcTemplate.query("""
                        select last_success_at
                        from congressional_trade_cache_state
                        where stock_asset_id = ?
                          and last_success_at is not null
                        """,
                (resultSet, rowNumber) -> instant(resultSet.getTimestamp("last_success_at")),
                stockAssetId);
        return values.isEmpty() ? null : values.getFirst();
    }

    @Transactional
    public List<UpsertedTrade> upsertTrades(
            StockAsset stockAsset,
            String provider,
            List<ProviderTrade> providerTrades) {
        List<UpsertedTrade> stored = new ArrayList<>();
        for (ProviderTrade trade : providerTrades) {
            stored.add(upsertTrade(stockAsset, provider, trade));
        }
        return List.copyOf(stored);
    }

    private UpsertedTrade upsertTrade(
            StockAsset stockAsset,
            String provider,
            ProviderTrade trade) {
        String fingerprint = fingerprint(provider, trade);
        Instant now = Instant.now();
        List<Long> insertedIds = jdbcTemplate.query("""
                        insert into congressional_trades (
                            stock_asset_id,
                            provider,
                            provider_fingerprint,
                            member_name,
                            chamber,
                            ticker_symbol,
                            asset_name,
                            transaction_type,
                            amount_range,
                            transaction_date,
                            disclosure_date,
                            source_url,
                            first_seen_at,
                            last_seen_at
                        )
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (provider, provider_fingerprint) do nothing
                        returning id
                        """,
                (resultSet, rowNumber) -> resultSet.getLong("id"),
                stockAsset.getId(),
                bounded(provider, 32),
                fingerprint,
                bounded(trade.memberName(), 255),
                bounded(trade.chamber(), 32),
                bounded(trade.ticker(), 20),
                bounded(trade.assetName(), 500),
                trade.transactionType().name(),
                bounded(trade.amountRange(), 100),
                Date.valueOf(trade.transactionDate()),
                Date.valueOf(trade.disclosureDate()),
                bounded(trade.sourceUrl(), 1000),
                Timestamp.from(now),
                Timestamp.from(now));
        if (!insertedIds.isEmpty()) {
            return new UpsertedTrade(insertedIds.getFirst(), true);
        }

        jdbcTemplate.update("""
                        update congressional_trades
                        set member_name = ?,
                            chamber = ?,
                            asset_name = ?,
                            transaction_type = ?,
                            amount_range = ?,
                            transaction_date = ?,
                            disclosure_date = ?,
                            source_url = ?,
                            last_seen_at = ?
                        where provider = ?
                          and provider_fingerprint = ?
                        """,
                bounded(trade.memberName(), 255),
                bounded(trade.chamber(), 32),
                bounded(trade.assetName(), 500),
                trade.transactionType().name(),
                bounded(trade.amountRange(), 100),
                Date.valueOf(trade.transactionDate()),
                Date.valueOf(trade.disclosureDate()),
                bounded(trade.sourceUrl(), 1000),
                Timestamp.from(now),
                bounded(provider, 32),
                fingerprint);
        Long existingId = jdbcTemplate.queryForObject("""
                        select id
                        from congressional_trades
                        where provider = ?
                          and provider_fingerprint = ?
                        """,
                Long.class,
                bounded(provider, 32),
                fingerprint);
        return new UpsertedTrade(existingId, false);
    }

    @Transactional
    public PollClaimStatus claimProviderPoll(
            String provider,
            Duration pollInterval,
            Duration leaseDuration,
            Duration failureRetryDelay,
            String owner) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into congressional_trade_poll_state (provider)
                values (?)
                on conflict (provider) do nothing
                """, provider);
        PollStateRow row = jdbcTemplate.queryForObject("""
                        select last_success_at, last_attempt_at, lease_until, last_error
                        from congressional_trade_poll_state
                        where provider = ?
                        for update
                        """,
                (resultSet, rowNumber) -> new PollStateRow(
                        instant(resultSet.getTimestamp("last_success_at")),
                        instant(resultSet.getTimestamp("last_attempt_at")),
                        instant(resultSet.getTimestamp("lease_until")),
                        resultSet.getString("last_error")),
                provider);
        if (row != null && row.lastSuccessAt() != null
                && !row.lastSuccessAt().isBefore(now.minus(pollInterval))) {
            return PollClaimStatus.NOT_DUE;
        }
        if (row != null && row.leaseUntil() != null && row.leaseUntil().isAfter(now)) {
            return PollClaimStatus.IN_PROGRESS;
        }
        if (row != null
                && row.lastError() != null
                && row.lastAttemptAt() != null
                && row.lastAttemptAt().isAfter(now.minus(failureRetryDelay))) {
            return PollClaimStatus.NOT_DUE;
        }
        jdbcTemplate.update("""
                        update congressional_trade_poll_state
                        set last_attempt_at = ?,
                            lease_owner = ?,
                            lease_until = ?,
                            last_error = null
                        where provider = ?
                        """,
                Timestamp.from(now),
                owner,
                Timestamp.from(now.plus(leaseDuration)),
                provider);
        return PollClaimStatus.CLAIMED;
    }

    @Transactional
    public void completeProviderPoll(String provider, String owner) {
        jdbcTemplate.update("""
                        update congressional_trade_poll_state
                        set last_success_at = current_timestamp,
                            lease_owner = null,
                            lease_until = null,
                            last_error = null
                        where provider = ?
                          and lease_owner = ?
                        """,
                provider,
                owner);
    }

    @Transactional
    public void failProviderPoll(String provider, String owner, Throwable failure) {
        jdbcTemplate.update("""
                        update congressional_trade_poll_state
                        set lease_owner = null,
                            lease_until = null,
                            last_error = ?
                        where provider = ?
                          and lease_owner = ?
                        """,
                safeError(failure),
                provider,
                owner);
    }

    @Transactional
    public int enqueueDeliveries(List<Long> observedTradeIds) {
        int created = 0;
        for (Long tradeId : observedTradeIds) {
            created += jdbcTemplate.update("""
                    insert into congressional_trade_deliveries (
                        subscription_id,
                        trade_id,
                        status,
                        attempts,
                        available_at,
                        created_at,
                        updated_at
                    )
                    select subscription.id,
                           trade.id,
                           'PENDING',
                           0,
                           current_timestamp,
                           current_timestamp,
                           current_timestamp
                    from congressional_trades trade
                    join congressional_trade_subscriptions subscription
                      on subscription.stock_asset_id = trade.stock_asset_id
                    where trade.id = ?
                      and subscription.active = true
                      and subscription.baseline_completed_at is not null
                      and trade.first_seen_at >= subscription.baseline_completed_at
                      and trade.disclosure_date >=
                          (subscription.activated_at at time zone 'UTC')::date
                    on conflict (subscription_id, trade_id) do nothing
                    """, tradeId);
        }
        return created;
    }

    @Transactional
    public void completePendingBaselines(Iterable<Long> stockAssetIds) {
        for (Long stockAssetId : stockAssetIds) {
            jdbcTemplate.update("""
                            update congressional_trade_subscriptions
                            set baseline_completed_at = current_timestamp,
                                updated_at = current_timestamp
                            where stock_asset_id = ?
                              and active = true
                              and baseline_completed_at is null
                            """,
                    stockAssetId);
        }
    }

    @Transactional
    public void cancelUnsentDeliveries(long subscriptionId) {
        jdbcTemplate.update("""
                        update congressional_trade_deliveries
                        set status = 'CANCELLED',
                            lease_owner = null,
                            lease_until = null,
                            updated_at = current_timestamp
                        where subscription_id = ?
                          and status in ('PENDING', 'PROCESSING', 'FAILED')
                          and sent_at is null
                        """,
                subscriptionId);
    }

    @Transactional
    public Optional<ClaimedDelivery> claimNextDelivery(
            String owner,
            Duration leaseDuration,
            int maximumAttempts) {
        Instant now = Instant.now();
        List<Long> candidates = jdbcTemplate.query("""
                        select delivery.id
                        from congressional_trade_deliveries delivery
                        join congressional_trade_subscriptions subscription
                          on subscription.id = delivery.subscription_id
                        where subscription.active = true
                          and delivery.attempts < ?
                          and (
                              (
                                  delivery.status in ('PENDING', 'FAILED')
                                  and delivery.available_at <= ?
                              )
                              or (
                                  delivery.status = 'PROCESSING'
                                  and delivery.lease_until < ?
                              )
                          )
                        order by delivery.available_at, delivery.id
                        for update of delivery skip locked
                        limit 1
                        """,
                (resultSet, rowNumber) -> resultSet.getLong("id"),
                maximumAttempts,
                Timestamp.from(now),
                Timestamp.from(now));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        long deliveryId = candidates.getFirst();
        jdbcTemplate.update("""
                        update congressional_trade_deliveries
                        set status = 'PROCESSING',
                            attempts = attempts + 1,
                            lease_owner = ?,
                            lease_until = ?,
                            updated_at = current_timestamp
                        where id = ?
                        """,
                owner,
                Timestamp.from(now.plus(leaseDuration)),
                deliveryId);

        return jdbcTemplate.query("""
                        select delivery.id,
                               delivery.attempts,
                               users.email,
                               trade.ticker_symbol,
                               trade.member_name,
                               trade.chamber,
                               trade.transaction_type,
                               trade.amount_range,
                               trade.transaction_date,
                               trade.disclosure_date,
                               trade.asset_name,
                               trade.source_url
                        from congressional_trade_deliveries delivery
                        join congressional_trade_subscriptions subscription
                          on subscription.id = delivery.subscription_id
                        join users on users.id = subscription.user_id
                        join congressional_trades trade on trade.id = delivery.trade_id
                        where delivery.id = ?
                        """,
                (resultSet, rowNumber) -> new ClaimedDelivery(
                        resultSet.getLong("id"),
                        resultSet.getInt("attempts"),
                        resultSet.getString("email"),
                        resultSet.getString("ticker_symbol"),
                        resultSet.getString("member_name"),
                        resultSet.getString("chamber"),
                        resultSet.getString("transaction_type"),
                        resultSet.getString("amount_range"),
                        resultSet.getObject("transaction_date", LocalDate.class),
                        resultSet.getObject("disclosure_date", LocalDate.class),
                        resultSet.getString("asset_name"),
                        resultSet.getString("source_url")),
                deliveryId).stream().findFirst();
    }

    @Transactional
    public void markDeliverySent(long deliveryId, String owner) {
        jdbcTemplate.update("""
                        update congressional_trade_deliveries
                        set status = 'SENT',
                            sent_at = current_timestamp,
                            lease_owner = null,
                            lease_until = null,
                            last_error = null,
                            updated_at = current_timestamp
                        where id = ?
                          and lease_owner = ?
                        """,
                deliveryId,
                owner);
    }

    @Transactional
    public void markDeliveryFailed(
            long deliveryId,
            String owner,
            Throwable failure,
            Duration retryDelay) {
        jdbcTemplate.update("""
                        update congressional_trade_deliveries
                        set status = 'FAILED',
                            available_at = ?,
                            lease_owner = null,
                            lease_until = null,
                            last_error = ?,
                            updated_at = current_timestamp
                        where id = ?
                          and lease_owner = ?
                        """,
                Timestamp.from(Instant.now().plus(retryDelay)),
                safeError(failure),
                deliveryId,
                owner);
    }

    private String fingerprint(String provider, ProviderTrade trade) {
        String canonical = String.join("|",
                normalize(provider),
                normalize(trade.ticker()),
                normalize(trade.memberName()),
                normalize(trade.chamber()),
                trade.transactionType().name(),
                normalize(trade.amountRange()),
                trade.transactionDate().toString(),
                trade.disclosureDate().toString(),
                normalize(trade.assetName()),
                normalize(trade.sourceUrl()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String bounded(String value, int maximumLength) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').trim();
        return cleaned.length() <= maximumLength
                ? cleaned
                : cleaned.substring(0, maximumLength);
    }

    private String safeError(Throwable failure) {
        if (failure == null) {
            return "Unknown failure";
        }
        String message = failure.getMessage();
        String safe = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return bounded(safe, 1000);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public enum CacheClaimStatus {
        FRESH,
        CLAIMED,
        IN_PROGRESS,
        COOLED_DOWN,
        USER_RATE_LIMITED
    }

    public enum PollClaimStatus {
        CLAIMED,
        NOT_DUE,
        IN_PROGRESS
    }

    public record HistoryCacheClaim(
            CacheClaimStatus status,
            Instant lastSuccessAt,
            boolean hasSuccessfulCoverage) {
    }

    public record UpsertedTrade(long id, boolean inserted) {
    }

    public record ClaimedDelivery(
            long deliveryId,
            int attempt,
            String recipientEmail,
            String ticker,
            String memberName,
            String chamber,
            String transactionType,
            String amountRange,
            LocalDate transactionDate,
            LocalDate disclosureDate,
            String assetName,
            String sourceUrl) {
    }

    private record HistoryCacheRow(
            LocalDate coverageStart,
            LocalDate coverageEnd,
            Instant lastSuccessAt,
            Instant lastAttemptAt,
            String leaseOwner,
            Instant leaseUntil,
            String lastError) {
    }

    private record PollStateRow(
            Instant lastSuccessAt,
            Instant lastAttemptAt,
            Instant leaseUntil,
            String lastError) {
    }
}
