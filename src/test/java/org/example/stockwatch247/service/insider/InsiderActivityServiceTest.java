package org.example.stockwatch247.service.insider;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.InsiderActivityRefreshState;
import org.example.stockwatch247.model.InsiderTrade;
import org.example.stockwatch247.model.InsiderTradeSubscription;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.InsiderTradeType;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.CandleRepository;
import org.example.stockwatch247.repository.InsiderActivityRefreshStateRepository;
import org.example.stockwatch247.repository.InsiderTradeDeliveryRepository;
import org.example.stockwatch247.repository.InsiderTradeRepository;
import org.example.stockwatch247.repository.InsiderTradeSubscriptionRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.AlertNotificationService;
import org.example.stockwatch247.service.CandleCompletionService;
import org.example.stockwatch247.service.MarketDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class InsiderActivityServiceTest {

    @Test
    void dailyPollReportsWhenTheProviderCheckStartsAndCompletes(CapturedOutput output) {
        StockAsset asset = stock();
        Fixture fixture = fixture(asset);
        InsiderTradeSubscription subscription = new InsiderTradeSubscription();
        subscription.setStockAsset(asset);
        subscription.setActive(true);
        when(fixture.subscriptions().countByActiveTrue()).thenReturn(1L);
        when(fixture.subscriptions().findByActiveTrueOrderByStockAsset_TickerSymbolAsc())
                .thenReturn(List.of(subscription));
        when(fixture.subscriptions().findByStockAssetAndActiveTrue(asset)).thenReturn(List.of());
        when(fixture.refreshStates().findById(asset.getId())).thenReturn(Optional.empty());
        when(fixture.refreshStates().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.provider().fetchTickerTrades("AAPL")).thenReturn(List.of());

        fixture.service().pollFollowedActivity();

        assertThat(output)
                .contains("Insider activity check started for 1 followed stock(s).")
                .contains("Insider activity check completed: "
                        + "1 stock(s) checked successfully and 0 failed.");
        verify(fixture.marketData()).syncCandles("AAPL", "1d", null);
        verify(fixture.provider()).fetchTickerTrades("AAPL");
    }

    @Test
    void scheduledStockCheckRunsOnlyWhileTheStockIsStillFollowed(CapturedOutput output) {
        StockAsset asset = stock();
        Fixture fixture = fixture(asset);
        InsiderTradeSubscription subscription = new InsiderTradeSubscription();
        subscription.setStockAsset(asset);
        subscription.setActive(true);
        when(fixture.stocks().findById(asset.getId())).thenReturn(Optional.of(asset));
        when(fixture.subscriptions().findByStockAssetAndActiveTrue(asset))
                .thenReturn(List.of(subscription));
        when(fixture.refreshStates().findById(asset.getId())).thenReturn(Optional.empty());
        when(fixture.refreshStates().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.provider().fetchTickerTrades("AAPL")).thenReturn(List.of());

        boolean checked = fixture.service().pollScheduledActivity(asset.getId(), "AAPL");

        assertThat(checked).isTrue();
        assertThat(output)
                .contains("Insider activity check started for AAPL.")
                .contains("Insider activity check completed for AAPL.");
        verify(fixture.marketData()).syncCandles("AAPL", "1d", null);
        verify(fixture.provider()).fetchTickerTrades("AAPL");
    }

    @Test
    void calculatesDirectionalReturnsFromFiledPriceToLatestCompletedClose() {
        StockAsset asset = stock();
        User user = new User();
        user.setId(9L);
        Fixture fixture = fixture(asset);
        InsiderActivityRefreshState fresh = new InsiderActivityRefreshState();
        fresh.setStockAssetId(asset.getId());
        fresh.setLastSuccessAt(Instant.now());
        when(fixture.refreshStates().findById(asset.getId())).thenReturn(Optional.of(fresh));

        InsiderTrade purchase = trade(asset, 1L, InsiderTradeType.PURCHASE, "100");
        InsiderTrade sale = trade(asset, 2L, InsiderTradeType.SALE, "100");
        when(fixture.trades()
                .findByStockAssetAndTransactionDateGreaterThanEqualOrderByFilingDateDescTransactionDateDescIdDesc(
                        eq(asset), any(LocalDate.class)))
                .thenReturn(List.of(purchase, sale));
        long completedTimestamp = LocalDate.now(ZoneOffset.UTC).minusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        Candle latest = new Candle("AAPL", "1d", completedTimestamp, 80, 82, 78, 80.0, 1000L);
        when(fixture.candles().findBySymbolAndTimeIntervalOrderByTimestampDesc(
                eq("AAPL"), eq("1d"), any(Pageable.class))).thenReturn(List.of(latest));
        when(fixture.completion().isComplete(completedTimestamp, TimeInterval.DAILY)).thenReturn(true);

        var response = fixture.service().getHistory(user, "AAPL");

        assertThat(response.trades()).hasSize(2);
        assertThat(response.trades().getFirst().returnPercent())
                .isEqualByComparingTo("-20.00");
        assertThat(response.trades().getLast().returnPercent())
                .isEqualByComparingTo("20.00");
        assertThat(response.trades().getFirst().returnAsOf())
                .isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(1));
    }

    @Test
    void cachedHistoryReturnsTheAccumulatedArchiveWithoutCallingTheProvider() {
        StockAsset asset = stock();
        User user = new User();
        user.setId(9L);
        Fixture fixture = fixture(asset);
        InsiderActivityRefreshState fresh = new InsiderActivityRefreshState();
        fresh.setStockAssetId(asset.getId());
        fresh.setLastSuccessAt(Instant.now());
        when(fixture.refreshStates().findById(asset.getId())).thenReturn(Optional.of(fresh));
        List<InsiderTrade> latestFirst = LongStream.rangeClosed(1, 12)
                .mapToObj(id -> trade(asset, id, InsiderTradeType.PURCHASE, "100"))
                .toList();
        when(fixture.trades()
                .findByStockAssetAndTransactionDateGreaterThanEqualOrderByFilingDateDescTransactionDateDescIdDesc(
                        eq(asset), any(LocalDate.class)))
                .thenReturn(latestFirst);

        var response = fixture.service().getHistory(user, "AAPL");

        assertThat(response.trades()).hasSize(12);
        assertThat(response.returnMethodology()).contains("archive keeps every observed");
        assertThat(response.alertBaselineNotice()).contains("more than 10 new rows");
        verify(fixture.provider(), never()).fetchTickerTrades("AAPL");
    }

    @Test
    void explicitRefreshLoadsProviderRowsAndReturnsTheMergedArchive() {
        StockAsset asset = stock();
        User user = new User();
        user.setId(9L);
        Fixture fixture = fixture(asset);
        InsiderActivityRefreshState state = new InsiderActivityRefreshState();
        state.setStockAssetId(asset.getId());
        state.setLastSuccessAt(Instant.now().minusSeconds(3600));
        when(fixture.refreshStates().findById(asset.getId())).thenReturn(Optional.of(state));
        when(fixture.refreshStates().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.provider().fetchTickerTrades("AAPL")).thenReturn(List.of());
        when(fixture.trades()
                .findByStockAssetAndTransactionDateGreaterThanEqualOrderByFilingDateDescTransactionDateDescIdDesc(
                        eq(asset), any(LocalDate.class)))
                .thenReturn(List.of());

        var response = fixture.service().refreshHistory(user, "AAPL");

        assertThat(response.refreshStatus())
                .isEqualTo(InsiderActivityService.RefreshStatus.REFRESHED);
        verify(fixture.provider()).fetchTickerTrades("AAPL");
    }

    @Test
    void repeatedRefreshMergesTheSameProviderRowWithoutGrowingTheArchiveTwice() {
        StockAsset asset = stock();
        User user = new User();
        user.setId(9L);
        Fixture fixture = fixture(asset);
        InsiderActivityRefreshState state = new InsiderActivityRefreshState();
        state.setStockAssetId(asset.getId());
        state.setLastSuccessAt(Instant.now().minusSeconds(3600));
        when(fixture.refreshStates().findById(asset.getId())).thenReturn(Optional.of(state));
        when(fixture.refreshStates().save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.provider().providerName()).thenReturn("API_NINJAS");
        InsiderTradeProvider.ProviderTrade providerTrade =
                new InsiderTradeProvider.ProviderTrade(
                        "AAPL",
                        "Example Insider",
                        "Director",
                        InsiderTradeType.PURCHASE,
                        "P",
                        LocalDate.now(ZoneOffset.UTC).minusDays(2),
                        LocalDate.now(ZoneOffset.UTC).minusDays(2),
                        BigDecimal.TEN,
                        new BigDecimal("100"),
                        new BigDecimal("500"),
                        "Open Market Purchase",
                        "https://www.sec.gov/Archives/example");
        when(fixture.provider().fetchTickerTrades("AAPL"))
                .thenReturn(List.of(providerTrade));
        AtomicReference<InsiderTrade> stored = new AtomicReference<>();
        when(fixture.trades().findByProviderAndProviderFingerprint(
                eq("API_NINJAS"), any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(fixture.trades().save(any(InsiderTrade.class))).thenAnswer(invocation -> {
            InsiderTrade trade = invocation.getArgument(0);
            if (trade.getId() == null) {
                trade.setId(99L);
            }
            stored.set(trade);
            return trade;
        });
        when(fixture.trades()
                .findByStockAssetAndTransactionDateGreaterThanEqualOrderByFilingDateDescTransactionDateDescIdDesc(
                        eq(asset), any(LocalDate.class)))
                .thenAnswer(invocation -> stored.get() == null
                        ? List.of()
                        : List.of(stored.get()));

        var first = fixture.service().refreshHistory(user, "AAPL");
        var second = fixture.service().refreshHistory(user, "AAPL");

        assertThat(first.trades()).hasSize(1);
        assertThat(second.trades()).hasSize(1);
        assertThat(second.trades().getFirst().id()).isEqualTo(99L);
        verify(fixture.provider(), times(2)).fetchTickerTrades("AAPL");
    }

    private Fixture fixture(StockAsset asset) {
        StockAssetRepository stocks = mock(StockAssetRepository.class);
        InsiderTradeRepository trades = mock(InsiderTradeRepository.class);
        InsiderTradeSubscriptionRepository subscriptions =
                mock(InsiderTradeSubscriptionRepository.class);
        InsiderTradeDeliveryRepository deliveries = mock(InsiderTradeDeliveryRepository.class);
        InsiderActivityRefreshStateRepository refreshStates =
                mock(InsiderActivityRefreshStateRepository.class);
        CandleRepository candles = mock(CandleRepository.class);
        CandleCompletionService completion = mock(CandleCompletionService.class);
        MarketDataService marketData = mock(MarketDataService.class);
        InsiderTradeProvider provider = mock(InsiderTradeProvider.class);
        when(stocks.findByTickerSymbolIgnoreCase("AAPL")).thenReturn(Optional.of(asset));
        when(subscriptions.findByUserAndStockAsset(any(), eq(asset))).thenReturn(Optional.empty());
        InsiderActivityService service = new InsiderActivityService(
                stocks,
                trades,
                subscriptions,
                deliveries,
                refreshStates,
                candles,
                completion,
                marketData,
                provider,
                mock(AlertNotificationService.class),
                true,
                730,
                50);
        return new Fixture(
                service,
                stocks,
                trades,
                subscriptions,
                refreshStates,
                candles,
                completion,
                marketData,
                provider);
    }

    private StockAsset stock() {
        StockAsset asset = new StockAsset();
        asset.setId(7L);
        asset.setTickerSymbol("AAPL");
        asset.setCompanyName("Apple Inc.");
        asset.setExchange("NASDAQ");
        asset.setCurrency("USD");
        asset.setInstrumentType(InstrumentType.EQUITY);
        return asset;
    }

    private InsiderTrade trade(
            StockAsset asset,
            long id,
            InsiderTradeType type,
            String price) {
        InsiderTrade trade = new InsiderTrade();
        trade.setId(id);
        trade.setStockAsset(asset);
        trade.setTickerSymbol("AAPL");
        trade.setInsiderName("Example Insider");
        trade.setTransactionType(type);
        trade.setTransactionCode(type == InsiderTradeType.PURCHASE ? "P-Purchase" : "S-Sale");
        trade.setTransactionDate(LocalDate.now(ZoneOffset.UTC).minusDays(30));
        trade.setFilingDate(LocalDate.now(ZoneOffset.UTC).minusDays(29));
        trade.setShares(BigDecimal.TEN);
        trade.setTransactionPrice(new BigDecimal(price));
        trade.setFirstSeenAt(Instant.now());
        trade.setLastSeenAt(Instant.now());
        return trade;
    }

    private record Fixture(
            InsiderActivityService service,
            StockAssetRepository stocks,
            InsiderTradeRepository trades,
            InsiderTradeSubscriptionRepository subscriptions,
            InsiderActivityRefreshStateRepository refreshStates,
            CandleRepository candles,
            CandleCompletionService completion,
            MarketDataService marketData,
            InsiderTradeProvider provider) {
    }
}
