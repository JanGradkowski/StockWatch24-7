package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.TechnicalOutlookSubscription;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TechnicalOutlookSubscriptionRepository
        extends JpaRepository<TechnicalOutlookSubscription, Long> {
    @EntityGraph(attributePaths = {"user", "stockAsset"})
    Optional<TechnicalOutlookSubscription> findByUserAndStockAssetAndInterval(
            User user, StockAsset stockAsset, TimeInterval interval);

    @EntityGraph(attributePaths = {"user", "stockAsset"})
    List<TechnicalOutlookSubscription> findByUserAndStockAsset(User user, StockAsset stockAsset);

    @EntityGraph(attributePaths = {"user", "stockAsset"})
    List<TechnicalOutlookSubscription>
    findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndActiveTrue(
            String symbol, TimeInterval interval);
}

