package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.TechnicalOutlookSubscription;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

public interface TechnicalOutlookSubscriptionRepository
        extends JpaRepository<TechnicalOutlookSubscription, Long> {
    @EntityGraph(attributePaths = {"user", "stockAsset"})
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TechnicalOutlookSubscription> findByUserAndStockAssetAndInterval(
            User user, StockAsset stockAsset, TimeInterval interval);

    @EntityGraph(attributePaths = {"user", "stockAsset"})
    List<TechnicalOutlookSubscription> findByUserAndStockAsset(User user, StockAsset stockAsset);

    @EntityGraph(attributePaths = {"user", "stockAsset"})
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<TechnicalOutlookSubscription>
    findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndActiveTrue(
            String symbol, TimeInterval interval);

    // Do not load the large chart snapshots for a watchlist with hundreds of subscriptions.
    @Query("""
            select s.stockAsset.tickerSymbol as symbol, s.stockAsset.companyName as companyName,
                   s.stockAsset.currency as currency, s.interval as interval,
                   s.currentClassification as classification, s.currentScore as score,
                   s.currentPrice as price, s.lastCandleTimestamp as candleTimestamp,
                   s.lastChangeCandleTimestamp as changedAt,
                   s.lastChangePreviousClassification as previousClassification,
                   s.lastChangePrice as changePrice, s.lastChangeScore as changeScore,
                   s.profileFingerprint as profileFingerprint, s.trackingStartedAt as trackingStartedAt
            from TechnicalOutlookSubscription s where s.user = :user and s.active = true
            order by s.stockAsset.tickerSymbol, s.interval
            """)
    List<WatchlistRow> findWatchlistByUser(@Param("user") User user);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update TechnicalOutlookSubscription s set s.active = false, s.updatedAt = :now
            where s.user = :user and s.active = true
              and (:symbol is null or upper(s.stockAsset.tickerSymbol) = :symbol)
            """)
    int unfollow(@Param("user") User user, @Param("symbol") String symbol,
                 @Param("now") LocalDateTime now);

    interface WatchlistRow {
        String getSymbol();
        String getCompanyName();
        String getCurrency();
        TimeInterval getInterval();
        String getClassification();
        Double getScore();
        Double getPrice();
        Long getCandleTimestamp();
        Long getChangedAt();
        String getPreviousClassification();
        Double getChangePrice();
        Double getChangeScore();
        String getProfileFingerprint();
        LocalDateTime getTrackingStartedAt();
    }
}
