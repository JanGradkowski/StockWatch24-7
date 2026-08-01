package org.example.stockwatch247.service.insider;

import org.example.stockwatch247.model.Candle;
import org.example.stockwatch247.model.InsiderActivityRefreshState;
import org.example.stockwatch247.model.InsiderTrade;
import org.example.stockwatch247.model.InsiderTradeDelivery;
import org.example.stockwatch247.model.InsiderTradeSubscription;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.InsiderDeliveryStatus;
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
import org.example.stockwatch247.service.insider.InsiderTradeProvider.ProviderTrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InsiderActivityService {
    private static final Logger log = LoggerFactory.getLogger(InsiderActivityService.class);

    private final StockAssetRepository stockAssetRepository;
    private final InsiderTradeRepository tradeRepository;
    private final InsiderTradeSubscriptionRepository subscriptionRepository;
    private final InsiderTradeDeliveryRepository deliveryRepository;
    private final InsiderActivityRefreshStateRepository refreshStateRepository;
    private final CandleRepository candleRepository;
    private final CandleCompletionService candleCompletionService;
    private final MarketDataService marketDataService;
    private final InsiderTradeProvider provider;
    private final AlertNotificationService notificationService;
    private final boolean enabled;
    private final int historyDays;
    private final int maximumFollows;
    private final Map<Long, Object> assetLocks = new ConcurrentHashMap<>();

    public InsiderActivityService(
            StockAssetRepository stockAssetRepository,
            InsiderTradeRepository tradeRepository,
            InsiderTradeSubscriptionRepository subscriptionRepository,
            InsiderTradeDeliveryRepository deliveryRepository,
            InsiderActivityRefreshStateRepository refreshStateRepository,
            CandleRepository candleRepository,
            CandleCompletionService candleCompletionService,
            MarketDataService marketDataService,
            InsiderTradeProvider provider,
            AlertNotificationService notificationService,
            @Value("${insider-activity.enabled:true}") boolean enabled,
            @Value("${insider-activity.history-days:730}") int historyDays,
            @Value("${insider-activity.maximum-follows-per-user:50}") int maximumFollows) {
        this.stockAssetRepository = stockAssetRepository;
        this.tradeRepository = tradeRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.refreshStateRepository = refreshStateRepository;
        this.candleRepository = candleRepository;
        this.candleCompletionService = candleCompletionService;
        this.marketDataService = marketDataService;
        this.provider = provider;
        this.notificationService = notificationService;
        this.enabled = enabled;
        this.historyDays = Math.max(30, historyDays);
        this.maximumFollows = Math.max(1, maximumFollows);
    }

    @Transactional(readOnly = true)
    public ActivityState getState(User user, String symbol) {
        return stateFor(user, requireEligibleStock(symbol));
    }

    public ActivityState setFollowing(User user, String symbol, boolean following) {
        StockAsset asset = requireEligibleStock(symbol);
        InsiderTradeSubscription subscription = subscriptionRepository
                .findByUserAndStockAsset(user, asset)
                .orElse(null);
        Instant now = Instant.now();
        boolean needsBaseline = false;

        if (following) {
            if (subscription == null || !subscription.isActive()) {
                if (subscriptionRepository.countByUserAndActiveTrue(user) >= maximumFollows) {
                    throw new IllegalStateException(
                            "The maximum number of insider activity follows has been reached.");
                }
                if (subscription == null) {
                    subscription = new InsiderTradeSubscription();
                    subscription.setUser(user);
                    subscription.setStockAsset(asset);
                    subscription.setCreatedAt(now);
                }
                subscription.setActive(true);
                subscription.setActivatedAt(now);
                subscription.setBaselineCompletedAt(null);
                needsBaseline = true;
            }
        } else if (subscription != null && subscription.isActive()) {
            subscription.setActive(false);
        }

        if (subscription != null) {
            subscription.setUpdatedAt(now);
            subscription = subscriptionRepository.save(subscription);
        }

        if (following && needsBaseline && subscription != null) {
            try {
                refreshAsset(asset, false);
                subscription.setBaselineCompletedAt(Instant.now());
                subscription.setUpdatedAt(Instant.now());
                subscriptionRepository.save(subscription);
            } catch (RuntimeException exception) {
                log.warn("Insider baseline for {} remains pending after {}.",
                        asset.getTickerSymbol(), exception.getClass().getSimpleName());
            }
        }
        return stateFor(user, asset);
    }

    public HistoryResponse getHistory(User user, String symbol) {
        StockAsset asset = requireEligibleStock(symbol);
        return historyResponse(user, asset, RefreshStatus.CACHE);
    }

    public HistoryResponse refreshHistory(User user, String symbol) {
        StockAsset asset = requireEligibleStock(symbol);
        InsiderActivityRefreshState stateBefore =
                refreshStateRepository.findById(asset.getId()).orElse(null);
        RefreshStatus refreshStatus;
        try {
            refreshAsset(asset, true);
            refreshStatus = RefreshStatus.REFRESHED;
        } catch (RuntimeException exception) {
            if (stateBefore == null || stateBefore.getLastSuccessAt() == null) {
                throw exception;
            }
            refreshStatus = RefreshStatus.STALE;
        }
        return historyResponse(user, asset, refreshStatus);
    }

    private HistoryResponse historyResponse(
            User user,
            StockAsset asset,
            RefreshStatus refreshStatus) {
        LocalDate windowStart = todayUtc().minusDays(historyDays - 1L);
        List<TradeView> trades = tradeRepository
                .findByStockAssetAndTransactionDateGreaterThanEqualOrderByFilingDateDescTransactionDateDescIdDesc(
                        asset, windowStart)
                .stream()
                .map(this::toTradeView)
                .toList();
        ActivityState activityState = stateFor(user, asset);
        Instant cachedAt = refreshStateRepository.findById(asset.getId())
                .map(InsiderActivityRefreshState::getLastSuccessAt)
                .orElse(null);
        return new HistoryResponse(
                activityState.eligible(),
                activityState.following(),
                activityState.baselinePending(),
                activityState.followedStocks(),
                activityState.maximumFollowedStocks(),
                historyDays,
                windowStart,
                todayUtc(),
                refreshStatus,
                cachedAt,
                trades,
                "The archive keeps every observed open-market purchase and sale for the configured "
                        + "history window. Each refresh merges the 10 latest API Ninjas rows without "
                        + "duplicates. API Ninjas does not provide the transaction date on the free "
                        + "response, so the SEC filing date is used as the effective date. Returns "
                        + "use the filed price and latest completed daily close.",
                "Following begins after the current latest-10 baseline. Daily and manual checks grow "
                        + "the archive, but more than 10 new rows between checks can cause activity "
                        + "to be missed.");
    }

    public void pollFollowedActivity() {
        if (!enabled || subscriptionRepository.countByActiveTrue() == 0) {
            return;
        }
        Map<Long, StockAsset> assets = new LinkedHashMap<>();
        subscriptionRepository.findByActiveTrueOrderByStockAsset_TickerSymbolAsc()
                .forEach(subscription -> assets.putIfAbsent(
                        subscription.getStockAsset().getId(), subscription.getStockAsset()));
        log.info("Insider activity check started for {} followed stock(s).", assets.size());
        int successfulChecks = 0;
        int failedChecks = 0;
        for (StockAsset asset : assets.values()) {
            try {
                marketDataService.syncCandles(asset.getTickerSymbol(), "1d", null);
                refreshAsset(asset, true);
                successfulChecks++;
            } catch (RuntimeException exception) {
                failedChecks++;
                log.warn("Daily insider activity check for {} failed after {}.",
                        asset.getTickerSymbol(), exception.getClass().getSimpleName());
            }
        }
        log.info("Insider activity check completed: {} stock(s) checked successfully and {} failed.",
                successfulChecks, failedChecks);
    }

    public boolean pollScheduledActivity(long stockAssetId, String expectedTickerSymbol) {
        if (!enabled) {
            return false;
        }
        StockAsset asset = stockAssetRepository.findById(stockAssetId).orElse(null);
        if (asset == null
                || !asset.getTickerSymbol().equalsIgnoreCase(expectedTickerSymbol)
                || subscriptionRepository.findByStockAssetAndActiveTrue(asset).isEmpty()) {
            return false;
        }

        log.info("Insider activity check started for {}.", asset.getTickerSymbol());
        marketDataService.syncCandles(asset.getTickerSymbol(), "1d", null);
        refreshAsset(asset, true);
        log.info("Insider activity check completed for {}.", asset.getTickerSymbol());
        return true;
    }

    @Transactional(readOnly = true)
    public List<FollowedStockView> getFollowedStocks(User user) {
        return subscriptionRepository.findByUserAndActiveTrueOrderByStockAsset_TickerSymbolAsc(user)
                .stream()
                .map(subscription -> new FollowedStockView(
                        subscription.getStockAsset().getTickerSymbol(),
                        subscription.getStockAsset().getCompanyName(),
                        subscription.getBaselineCompletedAt() == null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DashboardActivityView> getLatestDashboardActivity(User user, int limit) {
        LocalDate earliest = todayUtc().minusDays(historyDays - 1L);
        return deliveryRepository.findLatestForUser(
                        user,
                        earliest,
                        PageRequest.of(0, Math.max(1, Math.min(limit, 50))))
                .stream()
                .map(this::toDashboardView)
                .toList();
    }

    private void refreshAsset(StockAsset asset, boolean notifyFollowers) {
        synchronized (assetLocks.computeIfAbsent(asset.getId(), ignored -> new Object())) {
            updateRefreshAttempt(asset.getId(), null);
            try {
                LocalDate earliest = todayUtc().minusDays(historyDays - 1L);
                List<InsiderTrade> inserted = new ArrayList<>();
                for (ProviderTrade providerTrade : provider.fetchTickerTrades(asset.getTickerSymbol())) {
                    if (!asset.getTickerSymbol().equalsIgnoreCase(providerTrade.ticker())
                            || providerTrade.transactionDate().isBefore(earliest)
                            || providerTrade.transactionDate().isAfter(todayUtc())) {
                        continue;
                    }
                    StoredTrade stored = storeTrade(asset, providerTrade);
                    if (stored.inserted()) {
                        inserted.add(stored.trade());
                    }
                }
                updateRefreshSuccess(asset.getId());
                if (notifyFollowers) {
                    inserted.forEach(trade -> createFollowerDeliveries(asset, trade));
                }
                completePendingBaselines(asset);
            } catch (RuntimeException exception) {
                updateRefreshAttempt(asset.getId(), exception.getClass().getSimpleName());
                throw exception;
            }
        }
    }

    private StoredTrade storeTrade(StockAsset asset, ProviderTrade source) {
        String fingerprint = fingerprint(source);
        InsiderTrade trade = tradeRepository
                .findByProviderAndProviderFingerprint(provider.providerName(), fingerprint)
                .orElse(null);
        boolean inserted = trade == null;
        Instant now = Instant.now();
        if (inserted) {
            trade = new InsiderTrade();
            trade.setStockAsset(asset);
            trade.setProvider(provider.providerName());
            trade.setProviderFingerprint(fingerprint);
            trade.setFirstSeenAt(now);
        }
        trade.setTickerSymbol(source.ticker());
        trade.setInsiderName(source.insiderName());
        trade.setOwnerRole(source.ownerRole());
        trade.setTransactionType(source.transactionType());
        trade.setTransactionCode(source.transactionCode());
        trade.setTransactionDate(source.transactionDate());
        trade.setFilingDate(source.filingDate());
        trade.setShares(source.shares());
        trade.setTransactionPrice(source.transactionPrice());
        trade.setSecuritiesOwned(source.securitiesOwned());
        trade.setSecurityName(source.securityName());
        trade.setSourceUrl(source.sourceUrl());
        trade.setLastSeenAt(now);
        try {
            return new StoredTrade(tradeRepository.save(trade), inserted);
        } catch (DataIntegrityViolationException race) {
            return tradeRepository
                    .findByProviderAndProviderFingerprint(provider.providerName(), fingerprint)
                    .map(existing -> new StoredTrade(existing, false))
                    .orElseThrow(() -> race);
        }
    }

    private void createFollowerDeliveries(StockAsset asset, InsiderTrade trade) {
        for (InsiderTradeSubscription subscription
                : subscriptionRepository.findByStockAssetAndActiveTrue(asset)) {
            if (subscription.getBaselineCompletedAt() == null
                    || deliveryRepository.existsBySubscriptionAndTrade(subscription, trade)) {
                continue;
            }
            InsiderTradeDelivery delivery = new InsiderTradeDelivery();
            delivery.setSubscription(subscription);
            delivery.setTrade(trade);
            delivery.setStatus(InsiderDeliveryStatus.PENDING);
            delivery.setCreatedAt(Instant.now());
            delivery.setUpdatedAt(Instant.now());
            try {
                delivery = deliveryRepository.save(delivery);
            } catch (DataIntegrityViolationException race) {
                continue;
            }
            if (notificationService.isEmailDeliveryEnabled()) {
                try {
                    notificationService.sendInsiderTradeEmail(delivery);
                    delivery.setStatus(InsiderDeliveryStatus.SENT);
                    delivery.setSentAt(Instant.now());
                } catch (RuntimeException exception) {
                    delivery.setStatus(InsiderDeliveryStatus.FAILED);
                    delivery.setLastError(exception.getClass().getSimpleName());
                }
                delivery.setUpdatedAt(Instant.now());
                deliveryRepository.save(delivery);
            }
        }
    }

    private void completePendingBaselines(StockAsset asset) {
        Instant now = Instant.now();
        for (InsiderTradeSubscription subscription
                : subscriptionRepository.findByStockAssetAndActiveTrue(asset)) {
            if (subscription.getBaselineCompletedAt() == null) {
                subscription.setBaselineCompletedAt(now);
                subscription.setUpdatedAt(now);
                subscriptionRepository.save(subscription);
            }
        }
    }

    private ActivityState stateFor(User user, StockAsset asset) {
        InsiderTradeSubscription subscription = subscriptionRepository
                .findByUserAndStockAsset(user, asset)
                .orElse(null);
        boolean following = subscription != null && subscription.isActive();
        return new ActivityState(
                true,
                following,
                following && subscription.getBaselineCompletedAt() == null,
                subscriptionRepository.countByUserAndActiveTrue(user),
                maximumFollows,
                historyDays);
    }

    private StockAsset requireEligibleStock(String symbol) {
        if (!enabled) {
            throw new IllegalStateException("Insider activity tracking is disabled.");
        }
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        StockAsset asset = stockAssetRepository.findByTickerSymbolIgnoreCase(normalized)
                .orElseThrow(() -> new IllegalArgumentException("Unknown stock."));
        if (asset.getInstrumentType() != InstrumentType.EQUITY) {
            throw new IllegalArgumentException("Insider activity is only available for stocks.");
        }
        return asset;
    }

    private TradeView toTradeView(InsiderTrade trade) {
        ReturnSnapshot snapshot = calculateReturn(trade);
        BigDecimal transactionValue = trade.getShares() != null && trade.getTransactionPrice() != null
                ? trade.getShares().multiply(trade.getTransactionPrice())
                : null;
        return new TradeView(
                trade.getId(),
                trade.getTickerSymbol(),
                trade.getInsiderName(),
                trade.getOwnerRole(),
                trade.getTransactionType().name(),
                trade.getTransactionType().getLabel(),
                trade.getTransactionDate(),
                trade.getFilingDate(),
                trade.getShares(),
                trade.getTransactionPrice(),
                transactionValue,
                trade.getSecuritiesOwned(),
                trade.getSecurityName(),
                snapshot.returnPercent(),
                snapshot.latestClose(),
                snapshot.asOf(),
                trade.getSourceUrl());
    }

    private DashboardActivityView toDashboardView(InsiderTradeDelivery delivery) {
        TradeView trade = toTradeView(delivery.getTrade());
        return new DashboardActivityView(
                delivery.getId(),
                trade.ticker(),
                delivery.getTrade().getStockAsset().getCompanyName(),
                trade.insiderName(),
                trade.ownerRole(),
                trade.transactionType(),
                trade.transactionTypeLabel(),
                trade.transactionDate(),
                trade.filingDate(),
                trade.shares(),
                trade.transactionPrice(),
                trade.transactionValue(),
                trade.returnPercent(),
                trade.returnAsOf(),
                delivery.getCreatedAt(),
                deliveryStatusLabel(delivery.getStatus()),
                trade.sourceUrl());
    }

    private ReturnSnapshot calculateReturn(InsiderTrade trade) {
        BigDecimal price = trade.getTransactionPrice();
        if (price == null || price.signum() <= 0) {
            return ReturnSnapshot.unavailable();
        }
        Candle latest = candleRepository
                .findBySymbolAndTimeIntervalOrderByTimestampDesc(
                        trade.getTickerSymbol(), "1d", PageRequest.of(0, 10))
                .stream()
                .filter(candle -> candle.getClosePrice() != null && candle.getClosePrice() > 0)
                .filter(candle -> candleCompletionService.isComplete(
                        candle.getTimestamp(), TimeInterval.DAILY))
                .findFirst()
                .orElse(null);
        if (latest == null) {
            return ReturnSnapshot.unavailable();
        }
        LocalDate asOf = Instant.ofEpochSecond(latest.getTimestamp())
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
        if (asOf.isBefore(trade.getTransactionDate())) {
            return ReturnSnapshot.unavailable();
        }
        BigDecimal latestClose = BigDecimal.valueOf(latest.getClosePrice());
        BigDecimal rawReturn = latestClose.subtract(price)
                .divide(price, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        if (trade.getTransactionType() == InsiderTradeType.SALE) {
            rawReturn = rawReturn.negate();
        }
        return new ReturnSnapshot(rawReturn.setScale(2, RoundingMode.HALF_UP), latestClose, asOf);
    }

    private void updateRefreshAttempt(long assetId, String error) {
        InsiderActivityRefreshState state = refreshStateRepository.findById(assetId)
                .orElseGet(InsiderActivityRefreshState::new);
        state.setStockAssetId(assetId);
        state.setLastAttemptAt(Instant.now());
        state.setLastError(error);
        refreshStateRepository.save(state);
    }

    private void updateRefreshSuccess(long assetId) {
        InsiderActivityRefreshState state = refreshStateRepository.findById(assetId)
                .orElseGet(InsiderActivityRefreshState::new);
        state.setStockAssetId(assetId);
        state.setLastAttemptAt(Instant.now());
        state.setLastSuccessAt(Instant.now());
        state.setLastError(null);
        refreshStateRepository.save(state);
    }

    private String fingerprint(ProviderTrade trade) {
        String identity = String.join("|",
                trade.ticker(),
                trade.insiderName().toUpperCase(Locale.ROOT),
                trade.transactionCode(),
                trade.transactionDate().toString(),
                trade.filingDate().toString(),
                trade.shares() == null ? "" : trade.shares().toPlainString(),
                trade.transactionPrice() == null ? "" : trade.transactionPrice().toPlainString(),
                trade.sourceUrl() == null ? "" : trade.sourceUrl());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String deliveryStatusLabel(InsiderDeliveryStatus status) {
        return switch (status) {
            case PENDING -> "Email queued";
            case SENT -> "Email sent";
            case FAILED -> "Email delivery issue";
            case CANCELLED -> "Email cancelled";
        };
    }

    private LocalDate todayUtc() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private record StoredTrade(InsiderTrade trade, boolean inserted) {
    }

    private record ReturnSnapshot(BigDecimal returnPercent, BigDecimal latestClose, LocalDate asOf) {
        private static ReturnSnapshot unavailable() {
            return new ReturnSnapshot(null, null, null);
        }
    }

    public enum RefreshStatus {
        CACHE,
        REFRESHED,
        STALE
    }

    public record ActivityState(
            boolean eligible,
            boolean following,
            boolean baselinePending,
            long followedStocks,
            int maximumFollowedStocks,
            int historyDays) {
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
            RefreshStatus refreshStatus,
            Instant cachedAt,
            List<TradeView> trades,
            String returnMethodology,
            String alertBaselineNotice) {
    }

    public record TradeView(
            long id,
            String ticker,
            String insiderName,
            String ownerRole,
            String transactionType,
            String transactionTypeLabel,
            LocalDate transactionDate,
            LocalDate filingDate,
            BigDecimal shares,
            BigDecimal transactionPrice,
            BigDecimal transactionValue,
            BigDecimal securitiesOwned,
            String securityName,
            BigDecimal returnPercent,
            BigDecimal latestCompletedClose,
            LocalDate returnAsOf,
            String sourceUrl) {
    }

    public record FollowedStockView(String symbol, String companyName, boolean baselinePending) {
    }

    public record DashboardActivityView(
            long id,
            String symbol,
            String companyName,
            String insiderName,
            String ownerRole,
            String transactionType,
            String transactionTypeLabel,
            LocalDate transactionDate,
            LocalDate filingDate,
            BigDecimal shares,
            BigDecimal transactionPrice,
            BigDecimal transactionValue,
            BigDecimal returnPercent,
            LocalDate returnAsOf,
            Instant detectedAt,
            String deliveryStatus,
            String sourceUrl) {
    }
}
