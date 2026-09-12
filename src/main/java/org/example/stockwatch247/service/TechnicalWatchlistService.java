package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.TechnicalOutlookSubscriptionRepository;
import org.example.stockwatch247.repository.TechnicalOutlookSubscriptionRepository.WatchlistRow;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TechnicalWatchlistService {
    private final TechnicalOutlookSubscriptionRepository subscriptions;
    private final TechnicalOutlookTrackingService tracking;

    public TechnicalWatchlistService(TechnicalOutlookSubscriptionRepository subscriptions,
                                    TechnicalOutlookTrackingService tracking) {
        this.subscriptions = subscriptions;
        this.tracking = tracking;
    }

    @Transactional(readOnly = true)
    public WatchlistView watchlist(User user) {
        var rows = subscriptions.findWatchlistByUser(user);
        var fingerprints = rows.isEmpty() ? Map.<TimeInterval, String>of() : tracking.profileFingerprints(user);
        Map<String, List<IntervalView>> intervals = new LinkedHashMap<>();
        Map<String, WatchlistRow> companies = new LinkedHashMap<>();
        long now = Instant.now().getEpochSecond();
        for (WatchlistRow row : rows) {
            companies.putIfAbsent(row.getSymbol(), row);
            intervals.computeIfAbsent(row.getSymbol(), ignored -> new ArrayList<>())
                    .add(interval(row, fingerprints.get(row.getInterval()), now));
        }
        List<TickerView> tickers = companies.values().stream().map(row -> new TickerView(
                row.getSymbol(), row.getCompanyName(), row.getCurrency(), intervals.get(row.getSymbol()))).toList();
        return new WatchlistView(now, tickers);
    }

    private IntervalView interval(WatchlistRow row, String fingerprint, long now) {
        boolean settingsChanged = row.getProfileFingerprint() != null
                && !Objects.equals(fingerprint, row.getProfileFingerprint());
        boolean available = !settingsChanged && row.getClassification() != null
                && finite(row.getScore()) && row.getCandleTimestamp() != null;
        long expectedAge = switch (row.getInterval()) {
            case DAILY -> 4L * 86_400;
            case WEEKLY -> 12L * 86_400;
            case MONTHLY -> 40L * 86_400;
            default -> 0;
        };
        boolean stale = available && now - row.getCandleTimestamp() > expectedAge;
        Double priceChange = available && row.getChangedAt() != null
                && positive(row.getChangePrice()) && positive(row.getPrice())
                ? (row.getPrice() / row.getChangePrice() - 1) * 100 : null;
        Double scoreChange = available && row.getChangedAt() != null && finite(row.getChangeScore())
                ? (row.getScore() - row.getChangeScore()) * 100 : null;
        return new IntervalView(row.getInterval(), TechnicalOutlookTrackingService.apiInterval(row.getInterval()),
                available, settingsChanged, stale, row.getTrackingStartedAt() == null && row.getChangedAt() == null,
                available ? row.getClassification() : null, available ? row.getScore() * 100 : null,
                available ? row.getPrice() : null, row.getCandleTimestamp(),
                available ? row.getChangedAt() : null, available ? row.getPreviousClassification() : null,
                available ? row.getChangePrice() : null, priceChange, scoreChange);
    }

    @Transactional
    public UnfollowResult unfollow(User user, String symbol) {
        String normalized = symbol == null ? null : SecurityInputValidator.requireMarketSymbol(symbol);
        return new UnfollowResult(subscriptions.unfollow(user, normalized, LocalDateTime.now()));
    }

    private static boolean finite(Double value) { return value != null && Double.isFinite(value); }
    private static boolean positive(Double value) { return finite(value) && value > 0; }

    public record WatchlistView(long fetchedAt, List<TickerView> tickers) { }
    public record TickerView(String symbol, String companyName, String currency, List<IntervalView> intervals) { }
    public record IntervalView(TimeInterval interval, String apiInterval, boolean available,
                               boolean settingsChanged, boolean stale, boolean historyUnknown, String classification, Double score,
                               Double price, Long candleTimestamp, Long changedAt, String previousClassification,
                               Double changePrice, Double priceChangePercent, Double scoreChangePoints) { }
    public record UnfollowResult(int subscriptionsRemoved) { }
}
