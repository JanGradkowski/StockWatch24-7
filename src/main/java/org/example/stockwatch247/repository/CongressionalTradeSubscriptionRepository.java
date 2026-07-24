package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.CongressionalTradeSubscription;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CongressionalTradeSubscriptionRepository
        extends JpaRepository<CongressionalTradeSubscription, Long> {

    Optional<CongressionalTradeSubscription> findByUserAndStockAsset(User user, StockAsset stockAsset);

    long countByUserAndActiveTrue(User user);

    long countByActiveTrue();

    @EntityGraph(attributePaths = {"stockAsset", "user"})
    List<CongressionalTradeSubscription> findByActiveTrueOrderByStockAsset_TickerSymbolAsc();

    @EntityGraph(attributePaths = "stockAsset")
    List<CongressionalTradeSubscription> findByUserAndActiveTrueOrderByStockAsset_TickerSymbolAsc(User user);
}
