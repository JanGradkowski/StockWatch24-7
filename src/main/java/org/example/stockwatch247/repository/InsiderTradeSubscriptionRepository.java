package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.InsiderTradeSubscription;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InsiderTradeSubscriptionRepository extends JpaRepository<InsiderTradeSubscription, Long> {
    Optional<InsiderTradeSubscription> findByUserAndStockAsset(User user, StockAsset stockAsset);

    long countByUserAndActiveTrue(User user);

    long countByActiveTrue();

    @EntityGraph(attributePaths = {"stockAsset", "user"})
    List<InsiderTradeSubscription> findByActiveTrueOrderByStockAsset_TickerSymbolAsc();

    @EntityGraph(attributePaths = "stockAsset")
    List<InsiderTradeSubscription> findByUserAndActiveTrueOrderByStockAsset_TickerSymbolAsc(User user);

    @EntityGraph(attributePaths = "user")
    List<InsiderTradeSubscription> findByStockAssetAndActiveTrue(StockAsset stockAsset);
}
