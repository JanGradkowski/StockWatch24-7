package org.example.stockwatch247.service.congress;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.CongressionalTradeType;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.CongressionalTradeDeliveryRepository;
import org.example.stockwatch247.repository.CongressionalTradeRepository;
import org.example.stockwatch247.repository.CongressionalTradeSubscriptionRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.congress.CongressionalTradeProvider.ProviderBatch;
import org.example.stockwatch247.service.congress.CongressionalTradeProvider.ProviderTrade;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.CacheClaimStatus;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.HistoryCacheClaim;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CongressionalActivityServiceTest {

    @Test
    void validEmptyCoverageIsServedFromTheDatabaseWithoutAnotherApiCall() {
        Fixture fixture = new Fixture();
        Instant cachedAt = Instant.parse("2026-07-23T10:00:00Z");
        when(fixture.store.claimHistoryRefresh(
                eq(7L), eq(11L), any(), any(), any(), any(), any()))
                .thenReturn(new HistoryCacheClaim(CacheClaimStatus.FRESH, cachedAt, true));
        when(fixture.store.historyLastSuccess(7L)).thenReturn(cachedAt);
        when(fixture.tradeRepository
                .findByStockAssetAndTransactionDateGreaterThanEqualOrderByDisclosureDateDescTransactionDateDescIdDesc(
                        eq(fixture.asset), any()))
                .thenReturn(List.of());

        var response = fixture.service.getHistory(fixture.user, "AAPL");

        assertThat(response.cacheStatus())
                .isEqualTo(CongressionalActivityService.HistoryCacheStatus.CACHE);
        assertThat(response.successfulCoverage()).isTrue();
        assertThat(response.cachedAt()).isEqualTo(cachedAt);
        assertThat(response.trades()).isEmpty();
        verify(fixture.provider, never()).fetchTickerHistory(any());
    }

    @Test
    void historyBackfillStoresCanonicalRowsButNeverQueuesNotifications() {
        Fixture fixture = new Fixture();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        ProviderTrade trade = new ProviderTrade(
                "Example Member",
                "House",
                "AAPL",
                CongressionalTradeType.PURCHASE,
                "$1,001 - $15,000",
                today.minusDays(10),
                today.minusDays(2),
                "Apple Inc.",
                "https://disclosures-clerk.house.gov/public_disc/example.pdf");
        when(fixture.store.claimHistoryRefresh(
                eq(7L), eq(11L), any(), any(), any(), any(), any()))
                .thenReturn(new HistoryCacheClaim(CacheClaimStatus.CLAIMED, null, false));
        when(fixture.provider.fetchTickerHistory("AAPL"))
                .thenReturn(new ProviderBatch(List.of(trade), Instant.now()));
        when(fixture.tradeRepository
                .findByStockAssetAndTransactionDateGreaterThanEqualOrderByDisclosureDateDescTransactionDateDescIdDesc(
                        eq(fixture.asset), any()))
                .thenReturn(List.of());

        var response = fixture.service.getHistory(fixture.user, "AAPL");

        assertThat(response.cacheStatus())
                .isEqualTo(CongressionalActivityService.HistoryCacheStatus.REFRESHED);
        verify(fixture.store).upsertTrades(
                fixture.asset,
                "CONGRESS_INVESTS",
                List.of(trade));
        verify(fixture.store, never()).enqueueDeliveries(anyList());
    }

    @Test
    void sameDayCooldownReturnsExistingDatabaseCoverageWithoutCallingTheProvider() {
        Fixture fixture = new Fixture();
        Instant cachedAt = Instant.parse("2026-07-22T10:00:00Z");
        when(fixture.store.claimHistoryRefresh(
                eq(7L), eq(11L), any(), any(), any(), any(), any()))
                .thenReturn(new HistoryCacheClaim(CacheClaimStatus.COOLED_DOWN, cachedAt, true));
        when(fixture.store.historyLastSuccess(7L)).thenReturn(cachedAt);
        when(fixture.tradeRepository
                .findByStockAssetAndTransactionDateGreaterThanEqualOrderByDisclosureDateDescTransactionDateDescIdDesc(
                        eq(fixture.asset), any()))
                .thenReturn(List.of());

        var response = fixture.service.getHistory(fixture.user, "AAPL");

        assertThat(response.cacheStatus())
                .isEqualTo(CongressionalActivityService.HistoryCacheStatus.COOLDOWN);
        assertThat(response.successfulCoverage()).isTrue();
        verify(fixture.provider, never()).fetchTickerHistory(any());
    }

    @Test
    void perUserUncachedRefreshLimitDoesNotCallTheProvider() {
        Fixture fixture = new Fixture();
        when(fixture.store.claimHistoryRefresh(
                eq(7L), eq(11L), any(), any(), any(), any(), any()))
                .thenReturn(new HistoryCacheClaim(CacheClaimStatus.USER_RATE_LIMITED, null, false));

        assertThatThrownBy(() -> fixture.service.getHistory(fixture.user, "AAPL"))
                .isInstanceOf(CongressionalRefreshLimitException.class)
                .hasMessageContaining("two uncached");
        verify(fixture.provider, never()).fetchTickerHistory(any());
    }

    @Test
    void nonEquitiesCannotUseCongressionalActivityEndpoints() {
        Fixture fixture = new Fixture();
        fixture.asset.setInstrumentType(InstrumentType.ETF);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> fixture.service.getState(fixture.user, "AAPL"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(fixture.store, never()).claimHistoryRefresh(
                anyLong(), anyLong(), any(), any(), any(), any(), any());
    }

    private static final class Fixture {
        private final StockAssetRepository stockAssetRepository = mock(StockAssetRepository.class);
        private final CongressionalTradeRepository tradeRepository = mock(CongressionalTradeRepository.class);
        private final CongressionalTradeSubscriptionRepository subscriptionRepository =
                mock(CongressionalTradeSubscriptionRepository.class);
        private final CongressionalTradeDeliveryRepository deliveryRepository =
                mock(CongressionalTradeDeliveryRepository.class);
        private final CongressionalTradeProvider provider = mock(CongressionalTradeProvider.class);
        private final CongressionalTradeStore store = mock(CongressionalTradeStore.class);
        private final CongressionalSubscriptionManager subscriptionManager =
                mock(CongressionalSubscriptionManager.class);
        private final StockAsset asset = new StockAsset();
        private final User user = new User();
        private final CongressionalActivityService service;

        private Fixture() {
            asset.setId(7L);
            asset.setTickerSymbol("AAPL");
            asset.setCompanyName("Apple Inc.");
            asset.setExchange("NASDAQ");
            asset.setInstrumentType(InstrumentType.EQUITY);
            user.setId(11L);
            user.setEmail("owner@example.com");
            when(stockAssetRepository.findByTickerSymbolIgnoreCase("AAPL"))
                    .thenReturn(Optional.of(asset));
            when(subscriptionRepository.findByUserAndStockAsset(user, asset))
                    .thenReturn(Optional.empty());
            when(provider.providerName()).thenReturn("CONGRESS_INVESTS");
            service = new CongressionalActivityService(
                    stockAssetRepository,
                    tradeRepository,
                    subscriptionRepository,
                    deliveryRepository,
                    provider,
                    store,
                    subscriptionManager,
                    true,
                    365,
                    24,
                    120,
                    50,
                    "https://congressinvests.com/");
        }
    }
}
