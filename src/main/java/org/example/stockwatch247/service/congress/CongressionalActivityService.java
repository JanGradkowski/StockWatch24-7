package org.example.stockwatch247.service.congress;

import org.example.stockwatch247.model.CongressionalTrade;
import org.example.stockwatch247.model.CongressionalTradeDelivery;
import org.example.stockwatch247.model.CongressionalTradeSubscription;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.CongressionalDeliveryStatus;
import org.example.stockwatch247.model.enums.InstrumentType;
import org.example.stockwatch247.repository.CongressionalTradeDeliveryRepository;
import org.example.stockwatch247.repository.CongressionalTradeRepository;
import org.example.stockwatch247.repository.CongressionalTradeSubscriptionRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.service.congress.CongressionalSubscriptionManager.SubscriptionChange;
import org.example.stockwatch247.service.congress.CongressionalTradeProvider.ProviderBatch;
import org.example.stockwatch247.service.congress.CongressionalTradeProvider.ProviderTrade;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.CacheClaimStatus;
import org.example.stockwatch247.service.congress.CongressionalTradeStore.HistoryCacheClaim;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CongressionalActivityService {
    private static final Logger log = LoggerFactory.getLogger(CongressionalActivityService.class);

    private final StockAssetRepository stockAssetRepository;
    private final CongressionalTradeRepository tradeRepository;
    private final CongressionalTradeSubscriptionRepository subscriptionRepository;
    private final CongressionalTradeDeliveryRepository deliveryRepository;
    private final CongressionalTradeProvider provider;
    private final CongressionalTradeStore tradeStore;
    private final CongressionalSubscriptionManager subscriptionManager;
    private final boolean enabled;
    private final int historyDays;
    private final Duration cacheTtl;
    private final Duration cacheLease;
    private final int maximumFollows;
    private final String attributionUrl;
    private final String cacheOwner;

    public CongressionalActivityService(
            StockAssetRepository stockAssetRepository,
            CongressionalTradeRepository tradeRepository,
            CongressionalTradeSubscriptionRepository subscriptionRepository,
            CongressionalTradeDeliveryRepository deliveryRepository,
            CongressionalTradeProvider provider,
            CongressionalTradeStore tradeStore,
            CongressionalSubscriptionManager subscriptionManager,
            @Value("${congressional-activity.enabled:true}") boolean enabled,
            @Value("${congressional-activity.history-days:365}") int historyDays,
            @Value("${congressional-activity.history-cache-ttl-hours:24}") long cacheTtlHours,
            @Value("${congressional-activity.history-cache-lease-seconds:120}") long cacheLeaseSeconds,
            @Value("${congressional-activity.maximum-follows-per-user:50}") int maximumFollows,
            @Value("${congressional-activity.attribution-url:https://congressinvests.com/}") String attributionUrl) {
        this.stockAssetRepository = stockAssetRepository;
        this.tradeRepository = tradeRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.provider = provider;
        this.tradeStore = tradeStore;
        this.subscriptionManager = subscriptionManager;
        this.enabled = enabled;
        this.historyDays = Math.max(1, historyDays);
        this.cacheTtl = Duration.ofHours(Math.max(1L, cacheTtlHours));
        this.cacheLease = Duration.ofSeconds(Math.max(15L, cacheLeaseSeconds));
        this.maximumFollows = Math.max(1, maximumFollows);
        this.attributionUrl = attributionUrl;
        this.cacheOwner = "congress-history-" + UUID.randomUUID();
    }

    @Transactional(readOnly = true)
    public ActivityState getState(User user, String symbol) {
        StockAsset asset = requireEligibleStock(symbol);
        return stateFor(user, asset);
    }

    public ActivityState setFollowing(User user, String symbol, boolean following) {
        StockAsset asset = requireEligibleStock(symbol);
        SubscriptionChange change = subscriptionManager.setFollowing(
                user,
                asset,
                following,
                maximumFollows);
        if (change.active() && change.needsBaseline()) {
            try {
                HistoryResponse baseline = getHistory(user, symbol);
                if (baseline.successfulCoverage()) {
                    subscriptionManager.markBaselineComplete(change.subscriptionId());
                }
            } catch (RuntimeException exception) {
                // The subscription remains active and explicitly baseline-pending.
                // A successful global poll will establish the baseline without
                // producing historical notifications.
                log.warn("Congressional history baseline for {} is pending after {}.",
                        asset.getTickerSymbol(), exception.getClass().getSimpleName());
            }
        }
        return getState(user, symbol);
    }

    public HistoryResponse getHistory(User user, String symbol) {
        StockAsset asset = requireEligibleStock(symbol);
        LocalDate windowEnd = LocalDate.now(ZoneOffset.UTC);
        LocalDate windowStart = windowEnd.minusDays(historyDays - 1L);
        String owner = cacheOwner + "-" + asset.getId();
        HistoryCacheClaim claim = tradeStore.claimHistoryRefresh(
                asset.getId(),
                user.getId(),
                windowStart,
                windowEnd,
                cacheTtl,
                cacheLease,
                owner);

        HistoryCacheStatus cacheStatus;
        boolean successfulCoverage = claim.hasSuccessfulCoverage();
        if (claim.status() == CacheClaimStatus.FRESH) {
            cacheStatus = HistoryCacheStatus.CACHE;
            successfulCoverage = true;
        } else if (claim.status() == CacheClaimStatus.IN_PROGRESS) {
            cacheStatus = HistoryCacheStatus.REFRESHING;
        } else if (claim.status() == CacheClaimStatus.COOLED_DOWN) {
            if (claim.lastSuccessAt() == null) {
                throw tickerCooldownException();
            }
            cacheStatus = HistoryCacheStatus.COOLDOWN;
        } else if (claim.status() == CacheClaimStatus.USER_RATE_LIMITED) {
            throw new CongressionalRefreshLimitException(
                    "Only two uncached congressional-history refreshes are allowed per minute. "
                            + "Cached requests do not count.",
                    60L);
        } else {
            try {
                ProviderBatch batch = provider.fetchTickerHistory(asset.getTickerSymbol());
                List<ProviderTrade> relevantTrades = batch.trades().stream()
                        .filter(trade -> asset.getTickerSymbol().equalsIgnoreCase(trade.ticker()))
                        .filter(trade -> !trade.transactionDate().isBefore(windowStart))
                        .filter(trade -> !trade.transactionDate().isAfter(windowEnd))
                        .toList();
                tradeStore.upsertTrades(asset, provider.providerName(), relevantTrades);
                tradeStore.completeHistoryRefresh(asset.getId(), windowStart, windowEnd, owner);
                cacheStatus = HistoryCacheStatus.REFRESHED;
                successfulCoverage = true;
            } catch (RuntimeException exception) {
                tradeStore.failHistoryRefresh(asset.getId(), owner, exception);
                if (claim.lastSuccessAt() != null) {
                    cacheStatus = HistoryCacheStatus.STALE;
                    log.warn("Using stale congressional history for {} after {}.",
                            asset.getTickerSymbol(), exception.getClass().getSimpleName());
                } else {
                    throw exception;
                }
            }
        }

        List<TradeView> trades = tradeRepository
                .findByStockAssetAndTransactionDateGreaterThanEqualOrderByDisclosureDateDescTransactionDateDescIdDesc(
                        asset,
                        windowStart)
                .stream()
                .filter(trade -> !trade.getTransactionDate().isAfter(windowEnd))
                .map(this::toTradeView)
                .toList();
        CongressionalTradeSubscription subscription = subscriptionRepository
                .findByUserAndStockAsset(user, asset)
                .orElse(null);
        boolean following = subscription != null && subscription.isActive();
        boolean baselinePending = following && subscription.getBaselineCompletedAt() == null;
        long followedStocks = subscriptionRepository.countByUserAndActiveTrue(user);
        return new HistoryResponse(
                true,
                following,
                baselinePending,
                followedStocks,
                maximumFollows,
                historyDays,
                windowStart,
                windowEnd,
                relevanceNotice(),
                alertBaselineNotice(),
                cacheStatus,
                successfulCoverage,
                tradeStore.historyLastSuccess(asset.getId()),
                trades,
                attribution());
    }

    @Transactional(readOnly = true)
    public List<FollowedStockView> getFollowedStocks(User user) {
        return subscriptionRepository
                .findByUserAndActiveTrueOrderByStockAsset_TickerSymbolAsc(user)
                .stream()
                .map(subscription -> new FollowedStockView(
                        subscription.getStockAsset().getTickerSymbol(),
                        subscription.getStockAsset().getCompanyName(),
                        subscription.getBaselineCompletedAt() == null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DashboardActivityView> getLatestDashboardActivity(User user, int limit) {
        LocalDate earliest = LocalDate.now(ZoneOffset.UTC).minusDays(historyDays - 1L);
        return deliveryRepository.findLatestForUser(
                        user,
                        earliest,
                        PageRequest.of(0, Math.max(1, Math.min(limit, 50))))
                .stream()
                .map(this::toDashboardView)
                .toList();
    }

    @Transactional(readOnly = true)
    public long followedStockCount(User user) {
        return subscriptionRepository.countByUserAndActiveTrue(user);
    }

    private ActivityState stateFor(User user, StockAsset asset) {
        CongressionalTradeSubscription subscription = subscriptionRepository
                .findByUserAndStockAsset(user, asset)
                .orElse(null);
        boolean following = subscription != null && subscription.isActive();
        return new ActivityState(
                true,
                following,
                following && subscription.getBaselineCompletedAt() == null,
                subscriptionRepository.countByUserAndActiveTrue(user),
                maximumFollows,
                historyDays,
                relevanceNotice(),
                alertBaselineNotice(),
                attribution());
    }

    private StockAsset requireEligibleStock(String symbol) {
        if (!enabled) {
            throw new IllegalStateException("Congressional activity tracking is disabled.");
        }
        String normalizedSymbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        StockAsset asset = stockAssetRepository.findByTickerSymbolIgnoreCase(normalizedSymbol)
                .orElseThrow(() -> new IllegalArgumentException("Unknown stock."));
        if (asset.getInstrumentType() != InstrumentType.EQUITY) {
            throw new IllegalArgumentException("Congressional activity is only available for stocks.");
        }
        return asset;
    }

    private TradeView toTradeView(CongressionalTrade trade) {
        return new TradeView(
                trade.getId(),
                trade.getMemberName(),
                trade.getChamber(),
                trade.getTickerSymbol(),
                trade.getTransactionType().name(),
                trade.getTransactionType().getLabel(),
                trade.getAmountRange(),
                trade.getTransactionDate(),
                trade.getDisclosureDate(),
                trade.getAssetName(),
                trade.getSourceUrl());
    }

    private DashboardActivityView toDashboardView(CongressionalTradeDelivery delivery) {
        CongressionalTrade trade = delivery.getTrade();
        return new DashboardActivityView(
                delivery.getId(),
                trade.getTickerSymbol(),
                trade.getStockAsset().getCompanyName(),
                trade.getMemberName(),
                trade.getChamber(),
                trade.getTransactionType().name(),
                trade.getTransactionType().getLabel(),
                trade.getAmountRange(),
                trade.getTransactionDate(),
                trade.getDisclosureDate(),
                delivery.getCreatedAt(),
                deliveryStatusLabel(delivery.getStatus()),
                trade.getSourceUrl());
    }

    private String deliveryStatusLabel(CongressionalDeliveryStatus status) {
        return switch (status) {
            case PENDING, PROCESSING -> "Email queued";
            case SENT -> "Email sent";
            case FAILED -> "Email delivery issue";
            case CANCELLED -> "Email cancelled";
        };
    }

    private String relevanceNotice() {
        return "Only purchases and sales with transaction dates in the last "
                + historyDays
                + " days are shown (the current CongressInvests free-tier history window). "
                + "Disclosures can be filed up to 45 days after a trade.";
    }

    private String alertBaselineNotice() {
        return "Following starts from the time you switch it on. Existing history is used only as a baseline "
                + "and never generates old email alerts.";
    }

    private SourceAttribution attribution() {
        return new SourceAttribution(
                "CongressInvests",
                attributionUrl,
                "Data is sourced from public U.S. House and Senate disclosures. "
                        + "It is for informational and research purposes only, not financial advice.");
    }

    private CongressionalRefreshLimitException tickerCooldownException() {
        Instant nextUtcMidnight = LocalDate.now(ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        long retryAfterSeconds = Math.max(
                1L,
                Duration.between(Instant.now(), nextUtcMidnight).getSeconds());
        return new CongressionalRefreshLimitException(
                "This ticker's congressional-history refresh has already been attempted today. "
                        + "Please try again after midnight UTC.",
                retryAfterSeconds);
    }

    public enum HistoryCacheStatus {
        CACHE,
        REFRESHED,
        REFRESHING,
        COOLDOWN,
        STALE
    }

    public record ActivityState(
            boolean eligible,
            boolean following,
            boolean baselinePending,
            long followedStocks,
            int maximumFollowedStocks,
            int historyDays,
            String relevanceNotice,
            String alertBaselineNotice,
            SourceAttribution attribution) {
    }

    public record HistoryResponse(
            boolean eligible,
            boolean following,
            boolean baselinePending,
            long followedStocks,
            int maximumFollowedStocks,
            int historyDays,
            LocalDate windowStart,
            LocalDate windowEnd,
            String relevanceNotice,
            String alertBaselineNotice,
            HistoryCacheStatus cacheStatus,
            boolean successfulCoverage,
            Instant cachedAt,
            List<TradeView> trades,
            SourceAttribution attribution) {
    }

    public record TradeView(
            long id,
            String memberName,
            String chamber,
            String ticker,
            String transactionType,
            String transactionTypeLabel,
            String amountRange,
            LocalDate transactionDate,
            LocalDate disclosureDate,
            String assetName,
            String sourceUrl) {
    }

    public record FollowedStockView(String symbol, String companyName, boolean baselinePending) {
    }

    public record DashboardActivityView(
            long id,
            String symbol,
            String companyName,
            String memberName,
            String chamber,
            String transactionType,
            String transactionTypeLabel,
            String amountRange,
            LocalDate transactionDate,
            LocalDate disclosureDate,
            Instant detectedAt,
            String deliveryStatus,
            String sourceUrl) {
    }

    public record SourceAttribution(String name, String url, String disclaimer) {
    }
}
