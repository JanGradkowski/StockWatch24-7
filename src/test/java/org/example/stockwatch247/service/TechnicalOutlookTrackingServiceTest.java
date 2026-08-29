package org.example.stockwatch247.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.TechnicalOutlookNotification;
import org.example.stockwatch247.model.TechnicalOutlookSubscription;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.TechnicalOutlookNotificationRepository;
import org.example.stockwatch247.repository.TechnicalOutlookSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TechnicalOutlookTrackingServiceTest {

    @Test
    void accountBelowThreeHundredCompanyQuotaCanAddAnOutlookCompany() {
        SubscriptionFixture fixture = subscriptionFixture(201, 300);

        assertThatNoException().isThrownBy(() -> fixture.service().setSubscription(
                fixture.user(), "MARA", TimeInterval.DAILY, true));
    }

    @Test
    void accountCannotAddAnOutlookCompanyAtTheThreeHundredCompanyQuota() {
        SubscriptionFixture fixture = subscriptionFixture(300, 300);

        assertThatThrownBy(() -> fixture.service().setSubscription(
                fixture.user(), "MARA", TimeInterval.DAILY, true))
                .isInstanceOf(TechnicalOutlookCapacityException.class)
                .hasMessageContaining("at most 300 companies");
    }

    @Test
    void firstCompletedCandleCreatesQuietBaselineAndNextClassificationChangeCreatesNotification() {
        TechnicalOutlookSubscriptionRepository subscriptions = mock(TechnicalOutlookSubscriptionRepository.class);
        TechnicalOutlookNotificationRepository notifications = mock(TechnicalOutlookNotificationRepository.class);
        TechnicalOutlookService outlooks = mock(TechnicalOutlookService.class);
        AnalysisPreferencesService preferences = mock(AnalysisPreferencesService.class);
        AlertNotificationService email = mock(AlertNotificationService.class);
        ObjectMapper mapper = new ObjectMapper();
        User user = new User();
        user.setId(11L);
        user.setEmail("outlook@example.com");
        StockAsset asset = new StockAsset();
        asset.setId(22L);
        asset.setTickerSymbol("AAPL");
        asset.setCompanyName("Apple Inc.");
        TechnicalOutlookSubscription subscription = new TechnicalOutlookSubscription();
        subscription.setId(33L);
        subscription.setUser(user);
        subscription.setStockAsset(asset);
        subscription.setInterval(TimeInterval.DAILY);
        subscription.setActive(true);

        when(subscriptions.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndActiveTrue(
                "AAPL", TimeInterval.DAILY)).thenReturn(List.of(subscription));
        when(preferences.get(user)).thenReturn(AnalysisPreferencesService.factoryPreferences());
        TechnicalOutlookService.OutlookView slightBuy = outlook(1_000L, "Slight buy outlook", 0.20, 1);
        TechnicalOutlookService.OutlookView neutral = outlook(2_000L, "Neutral outlook", 0.0, 0);
        when(outlooks.getSummaryOutlook(user, "AAPL", "1d")).thenReturn(slightBuy, neutral);
        when(outlooks.getOutlook(user, "AAPL", "1d")).thenReturn(neutral);
        when(notifications.existsBySubscriptionAndCurrentCandleTimestamp(any(), anyLong())).thenReturn(false);
        when(notifications.save(any())).thenAnswer(invocation -> {
            TechnicalOutlookNotification notification = invocation.getArgument(0);
            notification.setId(44L);
            return notification;
        });
        TechnicalOutlookTrackingService service = new TechnicalOutlookTrackingService(
                subscriptions, notifications, mock(StockAssetRepository.class), mock(TwelveDataService.class),
                outlooks, preferences, email, mapper, mock(JdbcTemplate.class),
                50, 500, 90, "http://localhost:8080");

        TechnicalOutlookTrackingService.EvaluationResult baseline =
                service.evaluate("AAPL", TimeInterval.DAILY);
        assertThat(baseline.baselinesEstablished()).isEqualTo(1);
        verify(notifications, never()).save(any());

        TechnicalOutlookTrackingService.EvaluationResult changed =
                service.evaluate("AAPL", TimeInterval.DAILY);
        assertThat(changed.changesCreated()).isEqualTo(1);
        verify(notifications).save(any(TechnicalOutlookNotification.class));
        assertThat(subscription.getLastCandleTimestamp()).isEqualTo(2_000L);
        assertThat(subscription.getBaselineSnapshot()).contains("Neutral outlook");
    }

    private TechnicalOutlookService.OutlookView outlook(
            long timestamp, String classification, double normalizedScore, int vote) {
        TechnicalOutlookService.ScoreView score = new TechnicalOutlookService.ScoreView(
                vote > 0 ? 1 : 0, vote == 0 ? 1 : 0, vote < 0 ? 1 : 0,
                vote, 1, normalizedScore, classification);
        TechnicalOutlookService.IndicatorView indicator = new TechnicalOutlookService.IndicatorView(
                "rsi", "RSI 14", "MOMENTUM", "RSI", 50.0 + vote,
                vote, vote > 0 ? "BUY" : vote < 0 ? "SELL" : "NEUTRAL",
                true, "RSI completed-candle state.", "Configured RSI thresholds.",
                timestamp, 0, false,
                List.of(new TechnicalOutlookService.IndicatorPointView(timestamp, 50.0 + vote, vote)),
                List.of(30.0, 70.0));
        TechnicalOutlookService.CategoryView category = new TechnicalOutlookService.CategoryView(
                "MOMENTUM", "Momentum", vote,
                vote > 0 ? "BUY" : vote < 0 ? "SELL" : "NEUTRAL",
                vote, List.of("RSI 14"));
        return new TechnicalOutlookService.OutlookView(
                "AAPL", "Apple Inc.", "1d", "Daily", true, timestamp,
                "Current completed candle", score, score, List.of(category), List.of(indicator),
                List.of(), List.of(), List.of(),
                new TechnicalOutlookService.MarketComparisonView(
                        false, "^GSPC", "S&P 500", "UNAVAILABLE", 0, 0,
                        "UNAVAILABLE", List.of(), List.of()),
                new TechnicalOutlookService.MethodologyView(
                        "Equal vote", "Category balanced", "Configured", "Descriptive only"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SubscriptionFixture subscriptionFixture(int activeCompanyCount, int maximumCompanies) {
        TechnicalOutlookSubscriptionRepository subscriptions = mock(TechnicalOutlookSubscriptionRepository.class);
        TechnicalOutlookNotificationRepository notifications = mock(TechnicalOutlookNotificationRepository.class);
        StockAssetRepository assets = mock(StockAssetRepository.class);
        TechnicalOutlookService outlooks = mock(TechnicalOutlookService.class);
        AnalysisPreferencesService preferences = mock(AnalysisPreferencesService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        User user = new User();
        user.setId(71L);
        user.setEmail("capacity@example.com");
        StockAsset asset = new StockAsset();
        asset.setId(72L);
        asset.setTickerSymbol("MARA");
        asset.setCompanyName("MARA Holdings, Inc.");

        when(assets.findByTickerSymbolIgnoreCase("MARA")).thenReturn(Optional.of(asset));
        when(subscriptions.findByUserAndStockAssetAndInterval(user, asset, TimeInterval.DAILY))
                .thenReturn(Optional.empty());
        when(subscriptions.findByUserAndStockAsset(user, asset)).thenReturn(List.of());
        when(subscriptions.save(any(TechnicalOutlookSubscription.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(outlooks.getSummaryOutlook(user, "MARA", "1d"))
                .thenReturn(outlook(3_000L, "Neutral outlook", 0, 0));
        when(preferences.get(user)).thenReturn(AnalysisPreferencesService.factoryPreferences());
        when(jdbc.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("select lock_name")) return "alert-stock-quota";
                    if (sql.contains("count(distinct stock_asset_id)") && sql.contains("user_id = ?")) {
                        return activeCompanyCount;
                    }
                    if (sql.contains("select exists") && sql.contains("user_id = ?")) return false;
                    if (sql.contains("select exists") && sql.contains("stock_asset_id = ?")) return true;
                    throw new AssertionError("Unexpected capacity SQL: " + sql);
                });

        TechnicalOutlookTrackingService service = new TechnicalOutlookTrackingService(
                subscriptions, notifications, assets, mock(TwelveDataService.class), outlooks,
                preferences, mock(AlertNotificationService.class), new ObjectMapper(), jdbc,
                maximumCompanies, 500, 90, "http://localhost:8080");
        return new SubscriptionFixture(service, user);
    }

    private record SubscriptionFixture(TechnicalOutlookTrackingService service, User user) { }
}
