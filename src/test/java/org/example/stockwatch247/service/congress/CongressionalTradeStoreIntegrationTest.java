package org.example.stockwatch247.service.congress;

import org.example.stockwatch247.model.CongressionalTradeSubscription;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.CongressionalTradeType;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.CongressionalTradeSubscriptionRepository;
import org.example.stockwatch247.repository.CongressionalTradeDeliveryRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.service.congress.CongressionalTradeProvider.ProviderTrade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "alerts.schedule.enabled=false",
        "congressional-activity.enabled=false"
})
@Transactional
class CongressionalTradeStoreIntegrationTest {

    @Autowired
    private CongressionalTradeStore store;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StockAssetRepository stockAssetRepository;

    @Autowired
    private CongressionalTradeSubscriptionRepository subscriptionRepository;

    @Autowired
    private CongressionalTradeDeliveryRepository deliveryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void historicalBaselineNeverQueuesButNewlyObservedDisclosureDoesAndIsIdempotent() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setEmail("congress-" + suffix + "@example.com");
        user.setPasswordHash("test-password-hash");
        user.setFirstName("Congress");
        user.setLastName("Test");
        user.setVerified(true);
        user = userRepository.saveAndFlush(user);

        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("CG" + suffix.toUpperCase());
        asset.setCompanyName("Congress Test Inc.");
        asset.setExchange("NASDAQ");
        asset.setInstrumentType(InstrumentType.EQUITY);
        asset = stockAssetRepository.saveAndFlush(asset);

        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        ProviderTrade historical = new ProviderTrade(
                "Baseline Member",
                "House",
                asset.getTickerSymbol(),
                CongressionalTradeType.PURCHASE,
                "$1,001 - $15,000",
                today.minusDays(5),
                today,
                "Congress Test Inc.",
                "https://disclosures-clerk.house.gov/public_disc/baseline.pdf");
        long historicalId = store.upsertTrades(asset, "CONGRESS_INVESTS", List.of(historical))
                .getFirst()
                .id();

        Instant activation = Instant.now().minusSeconds(60);
        Instant baseline = Instant.now();
        CongressionalTradeSubscription subscription = new CongressionalTradeSubscription();
        subscription.setUser(user);
        subscription.setStockAsset(asset);
        subscription.setActive(true);
        subscription.setActivatedAt(activation);
        subscription.setBaselineCompletedAt(baseline);
        subscription.setCreatedAt(activation);
        subscription.setUpdatedAt(baseline);
        subscription = subscriptionRepository.saveAndFlush(subscription);
        jdbcTemplate.update("""
                        update congressional_trades
                        set first_seen_at = ?
                        where id = ?
                        """,
                Timestamp.from(baseline.minusSeconds(1)),
                historicalId);

        assertThat(store.enqueueDeliveries(List.of(historicalId))).isZero();

        ProviderTrade newlyObserved = new ProviderTrade(
                "New Disclosure Member",
                "Senate",
                asset.getTickerSymbol(),
                CongressionalTradeType.SALE,
                "$15,001 - $50,000",
                today.minusDays(2),
                today,
                "Congress Test Inc.",
                "https://efdsearch.senate.gov/search/view/ptr/new-disclosure");
        long newTradeId = store.upsertTrades(asset, "CONGRESS_INVESTS", List.of(newlyObserved))
                .getFirst()
                .id();

        assertThat(store.enqueueDeliveries(List.of(newTradeId))).isEqualTo(1);
        assertThat(store.enqueueDeliveries(List.of(newTradeId))).isZero();
        Integer deliveryCount = jdbcTemplate.queryForObject("""
                        select count(*)
                        from congressional_trade_deliveries
                        where subscription_id = ?
                          and trade_id = ?
                        """,
                Integer.class,
                subscription.getId(),
                newTradeId);
        assertThat(deliveryCount).isEqualTo(1);
        String expectedTicker = asset.getTickerSymbol();
        assertThat(deliveryRepository.findLatestForUser(
                user,
                today.minusDays(364),
                PageRequest.of(0, 10)))
                .singleElement()
                .satisfies(delivery -> {
                    assertThat(delivery.getTrade().getMemberName()).isEqualTo("New Disclosure Member");
                    assertThat(delivery.getTrade().getStockAsset().getTickerSymbol())
                            .isEqualTo(expectedTicker);
                });
    }

    @Test
    void failedGlobalPollUsesDatabaseBackoffInsteadOfConsumingTheApiEveryMinute() {
        String provider = "TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        assertThat(store.claimProviderPoll(
                provider,
                Duration.ofHours(6),
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                "first-worker"))
                .isEqualTo(CongressionalTradeStore.PollClaimStatus.CLAIMED);
        store.failProviderPoll(provider, "first-worker", new IllegalStateException("provider unavailable"));

        assertThat(store.claimProviderPoll(
                provider,
                Duration.ofHours(6),
                Duration.ofMinutes(5),
                Duration.ofHours(1),
                "second-worker"))
                .isEqualTo(CongressionalTradeStore.PollClaimStatus.NOT_DUE);
    }

    @Test
    void failedTickerAttemptRemainsCooledDownUntilTheNextUtcDay() {
        StockAsset asset = saveEquity("CD");
        long userKey = positiveRandomLong();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate windowStart = today.minusDays(364);

        assertThat(store.claimHistoryRefresh(
                asset.getId(),
                userKey,
                windowStart,
                today,
                Duration.ofHours(24),
                Duration.ofMinutes(2),
                "first-worker").status())
                .isEqualTo(CongressionalTradeStore.CacheClaimStatus.CLAIMED);
        store.failHistoryRefresh(asset.getId(), "first-worker",
                new IllegalStateException("provider unavailable"));

        assertThat(store.claimHistoryRefresh(
                asset.getId(),
                userKey,
                windowStart,
                today,
                Duration.ofHours(24),
                Duration.ofMinutes(2),
                "second-worker").status())
                .isEqualTo(CongressionalTradeStore.CacheClaimStatus.COOLED_DOWN);

        jdbcTemplate.update("""
                        update congressional_trade_cache_state
                        set last_attempt_at = ?
                        where stock_asset_id = ?
                        """,
                Timestamp.from(today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()),
                asset.getId());

        assertThat(store.claimHistoryRefresh(
                asset.getId(),
                userKey,
                windowStart,
                today,
                Duration.ofHours(24),
                Duration.ofMinutes(2),
                "next-day-worker").status())
                .isEqualTo(CongressionalTradeStore.CacheClaimStatus.CLAIMED);
    }

    @Test
    void onlyUncachedClaimsConsumeTheTwoPerMinuteUserQuota() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate windowStart = today.minusDays(364);
        StockAsset cachedAsset = saveEquity("CA");
        String seedOwner = "cache-seed";

        assertThat(store.claimHistoryRefresh(
                cachedAsset.getId(),
                positiveRandomLong(),
                windowStart,
                today,
                Duration.ofHours(24),
                Duration.ofMinutes(2),
                seedOwner).status())
                .isEqualTo(CongressionalTradeStore.CacheClaimStatus.CLAIMED);
        store.completeHistoryRefresh(cachedAsset.getId(), windowStart, today, seedOwner);

        long userKey = positiveRandomLong();
        for (int request = 0; request < 5; request++) {
            assertThat(store.claimHistoryRefresh(
                    cachedAsset.getId(),
                    userKey,
                    windowStart,
                    today,
                    Duration.ofHours(24),
                    Duration.ofMinutes(2),
                    "cache-reader-" + request).status())
                    .isEqualTo(CongressionalTradeStore.CacheClaimStatus.FRESH);
        }

        StockAsset firstUncachedAsset = saveEquity("U1");
        StockAsset secondUncachedAsset = saveEquity("U2");
        StockAsset thirdUncachedAsset = saveEquity("U3");
        assertThat(claimStatus(firstUncachedAsset, userKey, windowStart, today, "quota-1"))
                .isEqualTo(CongressionalTradeStore.CacheClaimStatus.CLAIMED);
        assertThat(claimStatus(secondUncachedAsset, userKey, windowStart, today, "quota-2"))
                .isEqualTo(CongressionalTradeStore.CacheClaimStatus.CLAIMED);
        assertThat(claimStatus(thirdUncachedAsset, userKey, windowStart, today, "quota-3"))
                .isEqualTo(CongressionalTradeStore.CacheClaimStatus.USER_RATE_LIMITED);
    }

    private CongressionalTradeStore.CacheClaimStatus claimStatus(
            StockAsset asset,
            long userKey,
            LocalDate windowStart,
            LocalDate windowEnd,
            String owner) {
        return store.claimHistoryRefresh(
                asset.getId(),
                userKey,
                windowStart,
                windowEnd,
                Duration.ofHours(24),
                Duration.ofMinutes(2),
                owner).status();
    }

    private StockAsset saveEquity(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol(prefix + suffix);
        asset.setCompanyName("Refresh Limit Test " + suffix);
        asset.setExchange("NASDAQ");
        asset.setInstrumentType(InstrumentType.EQUITY);
        return stockAssetRepository.saveAndFlush(asset);
    }

    private long positiveRandomLong() {
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }
}
