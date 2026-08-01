package org.example.stockwatch247.service.congress;

import org.example.stockwatch247.model.CongressionalTradeSubscription;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.repository.CongressionalTradeSubscriptionRepository;
import org.example.stockwatch247.service.AlertNotificationService;
import org.example.stockwatch247.service.congress.CongressionalTradeProvider.ProviderTrade;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.ClaimedDelivery;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.PollClaimStatus;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.UpsertedTrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CongressionalActivityPollingService {
    private static final Logger log = LoggerFactory.getLogger(CongressionalActivityPollingService.class);

    private final CongressionalTradeProvider provider;
    private final CongressionalTradeStore tradeStore;
    private final CongressionalTradeSubscriptionRepository subscriptionRepository;
    private final AlertNotificationService notificationService;
    private final boolean enabled;
    private final int historyDays;
    private final Duration pollInterval;
    private final Duration pollLease;
    private final Duration pollFailureRetryDelay;
    private final Duration deliveryLease;
    private final int maximumDeliveryAttempts;
    private final long retryDelaySeconds;
    private final int deliveryBatchSize;
    private final String pollOwner;
    private final String deliveryOwner;

    public CongressionalActivityPollingService(
            CongressionalTradeProvider provider,
            CongressionalTradeStore tradeStore,
            CongressionalTradeSubscriptionRepository subscriptionRepository,
            AlertNotificationService notificationService,
            @Value("${congressional-activity.enabled:true}") boolean enabled,
            @Value("${congressional-activity.history-days:365}") int historyDays,
            @Value("${congressional-activity.poll-interval-hours:6}") long pollIntervalHours,
            @Value("${congressional-activity.poll-lease-seconds:300}") long pollLeaseSeconds,
            @Value("${congressional-activity.poll-failure-retry-minutes:60}") long pollFailureRetryMinutes,
            @Value("${congressional-activity.delivery-lease-seconds:300}") long deliveryLeaseSeconds,
            @Value("${congressional-activity.maximum-delivery-attempts:5}") int maximumDeliveryAttempts,
            @Value("${congressional-activity.delivery-retry-delay-seconds:300}") long retryDelaySeconds,
            @Value("${congressional-activity.delivery-batch-size:25}") int deliveryBatchSize) {
        this.provider = provider;
        this.tradeStore = tradeStore;
        this.subscriptionRepository = subscriptionRepository;
        this.notificationService = notificationService;
        this.enabled = enabled;
        this.historyDays = Math.max(1, historyDays);
        this.pollInterval = Duration.ofHours(Math.max(1L, pollIntervalHours));
        this.pollLease = Duration.ofSeconds(Math.max(30L, pollLeaseSeconds));
        this.pollFailureRetryDelay = Duration.ofMinutes(Math.max(1L, pollFailureRetryMinutes));
        this.deliveryLease = Duration.ofSeconds(Math.max(30L, deliveryLeaseSeconds));
        this.maximumDeliveryAttempts = Math.max(1, maximumDeliveryAttempts);
        this.retryDelaySeconds = Math.max(1L, retryDelaySeconds);
        this.deliveryBatchSize = Math.max(1, Math.min(deliveryBatchSize, 100));
        String instanceId = UUID.randomUUID().toString();
        this.pollOwner = "congress-poll-" + instanceId;
        this.deliveryOwner = "congress-delivery-" + instanceId;
    }

    @Scheduled(
            fixedDelayString = "${congressional-activity.poll-check-delay-ms:60000}",
            initialDelayString = "${congressional-activity.poll-initial-delay-ms:30000}")
    public void pollForNewDisclosures() {
        if (!enabled || subscriptionRepository.countByActiveTrue() == 0) {
            return;
        }
        PollClaimStatus claim = tradeStore.claimProviderPoll(
                provider.providerName(),
                pollInterval,
                pollLease,
                pollFailureRetryDelay,
                pollOwner);
        if (claim != PollClaimStatus.CLAIMED) {
            return;
        }

        try {
            List<CongressionalTradeSubscription> subscriptions =
                    subscriptionRepository.findByActiveTrueOrderByStockAsset_TickerSymbolAsc();
            Map<String, StockAsset> trackedAssets = new LinkedHashMap<>();
            for (CongressionalTradeSubscription subscription : subscriptions) {
                StockAsset asset = subscription.getStockAsset();
                trackedAssets.putIfAbsent(asset.getTickerSymbol().toUpperCase(Locale.ROOT), asset);
            }

            log.info("Congressional activity check started for {} followed stock(s).",
                    trackedAssets.size());
            var batch = provider.fetchRecentTrades();
            LocalDate earliest = LocalDate.now(ZoneOffset.UTC).minusDays(historyDays - 1L);
            Map<Long, List<ProviderTrade>> tradesByAsset = new LinkedHashMap<>();
            for (ProviderTrade trade : batch.trades()) {
                StockAsset asset = trackedAssets.get(trade.ticker().toUpperCase(Locale.ROOT));
                if (asset == null || trade.transactionDate().isBefore(earliest)) {
                    continue;
                }
                tradesByAsset.computeIfAbsent(asset.getId(), ignored -> new ArrayList<>()).add(trade);
            }

            List<Long> observedTradeIds = new ArrayList<>();
            int insertedTradeCount = 0;
            for (StockAsset asset : trackedAssets.values()) {
                List<ProviderTrade> trades = tradesByAsset.getOrDefault(asset.getId(), List.of());
                List<UpsertedTrade> stored = tradeStore.upsertTrades(asset, provider.providerName(), trades);
                observedTradeIds.addAll(stored.stream().map(UpsertedTrade::id).toList());
                insertedTradeCount += (int) stored.stream().filter(UpsertedTrade::inserted).count();
            }

            // Enqueue every observed provider trade, not only rows inserted in
            // this transaction. The uniqueness constraint makes this
            // idempotent and recovers the case where a history refresh stored a
            // genuinely new disclosure before the scheduled poll saw it.
            int deliveries = tradeStore.enqueueDeliveries(observedTradeIds);
            tradeStore.completePendingBaselines(
                    trackedAssets.values().stream().map(StockAsset::getId).toList());
            tradeStore.completeProviderPoll(provider.providerName(), pollOwner);
            log.info("Congressional activity check completed for {} followed stock(s): "
                            + "{} new disclosure(s) stored and {} user notification(s) queued.",
                    trackedAssets.size(), insertedTradeCount, deliveries);
        } catch (RuntimeException exception) {
            tradeStore.failProviderPoll(provider.providerName(), pollOwner, exception);
            log.warn("Congressional activity poll could not be completed: {}",
                    exception.getClass().getSimpleName());
        }
    }

    @Scheduled(
            fixedDelayString = "${congressional-activity.delivery-worker-delay-ms:60000}",
            initialDelayString = "${congressional-activity.delivery-worker-initial-delay-ms:45000}")
    public void deliverQueuedEmails() {
        if (!enabled || !notificationService.isEmailDeliveryEnabled()) {
            return;
        }
        for (int processed = 0; processed < deliveryBatchSize; processed++) {
            var claimed = tradeStore.claimNextDelivery(
                    deliveryOwner,
                    deliveryLease,
                    maximumDeliveryAttempts);
            if (claimed.isEmpty()) {
                return;
            }
            ClaimedDelivery delivery = claimed.get();
            try {
                notificationService.sendCongressionalTradeEmail(delivery);
                tradeStore.markDeliverySent(delivery.deliveryId(), deliveryOwner);
            } catch (RuntimeException exception) {
                tradeStore.markDeliveryFailed(
                        delivery.deliveryId(),
                        deliveryOwner,
                        exception,
                        retryDelay(delivery.attempt()));
                log.warn("Congressional activity email delivery {} failed on attempt {}.",
                        delivery.deliveryId(), delivery.attempt());
            }
        }
    }

    private Duration retryDelay(int attempt) {
        int exponent = Math.max(0, Math.min(attempt - 1, 10));
        long multiplier = 1L << exponent;
        long seconds;
        try {
            seconds = Math.multiplyExact(retryDelaySeconds, multiplier);
        } catch (ArithmeticException ignored) {
            seconds = 86_400L;
        }
        return Duration.ofSeconds(Math.min(seconds, 86_400L));
    }
}
