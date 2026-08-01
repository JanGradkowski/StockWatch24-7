package org.example.stockwatch247.service.congress;

import org.example.stockwatch247.model.CongressionalTradeSubscription;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.enums.CongressionalTradeType;
import org.example.stockwatch247.repository.CongressionalTradeSubscriptionRepository;
import org.example.stockwatch247.service.AlertNotificationService;
import org.example.stockwatch247.service.congress.CongressionalTradeProvider.ProviderBatch;
import org.example.stockwatch247.service.congress.CongressionalTradeProvider.ProviderTrade;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.ClaimedDelivery;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.PollClaimStatus;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.UpsertedTrade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class CongressionalActivityPollingServiceTest {

    @Test
    void pollCanRecoverARelevantTradePreviouslyStoredByHistory(CapturedOutput output) {
        CongressionalTradeProvider provider = mock(CongressionalTradeProvider.class);
        CongressionalTradeStore store = mock(CongressionalTradeStore.class);
        CongressionalTradeSubscriptionRepository subscriptions =
                mock(CongressionalTradeSubscriptionRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        CongressionalActivityPollingService service = service(provider, store, subscriptions, notifications);

        StockAsset asset = new StockAsset();
        asset.setId(8L);
        asset.setTickerSymbol("AAPL");
        CongressionalTradeSubscription subscription = new CongressionalTradeSubscription();
        subscription.setStockAsset(asset);
        subscription.setActive(true);
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        ProviderTrade trade = new ProviderTrade(
                "Example Member",
                "Senate",
                "AAPL",
                CongressionalTradeType.SALE,
                "$15,001 - $50,000",
                today.minusDays(3),
                today,
                "Apple Inc.",
                "https://efdsearch.senate.gov/search/view/ptr/example");

        when(subscriptions.countByActiveTrue()).thenReturn(1L);
        when(provider.providerName()).thenReturn("CONGRESS_INVESTS");
        when(store.claimProviderPoll(eq("CONGRESS_INVESTS"), any(), any(), any(), anyString()))
                .thenReturn(PollClaimStatus.CLAIMED);
        when(subscriptions.findByActiveTrueOrderByStockAsset_TickerSymbolAsc())
                .thenReturn(List.of(subscription));
        when(provider.fetchRecentTrades())
                .thenReturn(new ProviderBatch(List.of(trade), Instant.now()));
        // inserted=false models a disclosure first cached by a user history
        // request. The poll must still fan it out idempotently.
        when(store.upsertTrades(asset, "CONGRESS_INVESTS", List.of(trade)))
                .thenReturn(List.of(new UpsertedTrade(77L, false)));
        when(store.enqueueDeliveries(List.of(77L))).thenReturn(1);

        service.pollForNewDisclosures();

        verify(store).enqueueDeliveries(List.of(77L));
        InOrder order = inOrder(store);
        order.verify(store).enqueueDeliveries(List.of(77L));
        order.verify(store).completePendingBaselines(List.of(8L));
        order.verify(store).completeProviderPoll(eq("CONGRESS_INVESTS"), anyString());
        org.assertj.core.api.Assertions.assertThat(output)
                .contains("Congressional activity check started for 1 followed stock(s).")
                .contains("Congressional activity check completed for 1 followed stock(s): "
                        + "0 new disclosure(s) stored and 1 user notification(s) queued.");
    }

    @Test
    void deliveryWorkerSendsAndMarksTheDurableDelivery() {
        CongressionalTradeProvider provider = mock(CongressionalTradeProvider.class);
        CongressionalTradeStore store = mock(CongressionalTradeStore.class);
        CongressionalTradeSubscriptionRepository subscriptions =
                mock(CongressionalTradeSubscriptionRepository.class);
        AlertNotificationService notifications = mock(AlertNotificationService.class);
        CongressionalActivityPollingService service = service(provider, store, subscriptions, notifications);
        ClaimedDelivery delivery = new ClaimedDelivery(
                91L,
                1,
                "owner@example.com",
                "AAPL",
                "Example Member",
                "House",
                "PURCHASE",
                "$1,001 - $15,000",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 20),
                "Apple Inc.",
                "https://disclosures-clerk.house.gov/public_disc/example.pdf");
        when(store.claimNextDelivery(anyString(), any(), anyInt()))
                .thenReturn(Optional.of(delivery))
                .thenReturn(Optional.empty());
        when(notifications.isEmailDeliveryEnabled()).thenReturn(true);

        service.deliverQueuedEmails();

        verify(notifications).sendCongressionalTradeEmail(delivery);
        verify(store).markDeliverySent(eq(91L), anyString());
    }

    private CongressionalActivityPollingService service(
            CongressionalTradeProvider provider,
            CongressionalTradeStore store,
            CongressionalTradeSubscriptionRepository subscriptions,
            AlertNotificationService notifications) {
        return new CongressionalActivityPollingService(
                provider,
                store,
                subscriptions,
                notifications,
                true,
                365,
                6,
                300,
                60,
                300,
                5,
                300,
                25);
    }
}
