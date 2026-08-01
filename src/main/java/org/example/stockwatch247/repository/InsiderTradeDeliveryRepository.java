package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.InsiderTrade;
import org.example.stockwatch247.model.InsiderTradeDelivery;
import org.example.stockwatch247.model.InsiderTradeSubscription;
import org.example.stockwatch247.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface InsiderTradeDeliveryRepository extends JpaRepository<InsiderTradeDelivery, Long> {
    boolean existsBySubscriptionAndTrade(InsiderTradeSubscription subscription, InsiderTrade trade);

    @Query("""
            select delivery
            from InsiderTradeDelivery delivery
            join fetch delivery.subscription subscription
            join fetch delivery.trade trade
            join fetch trade.stockAsset
            where subscription.user = :user
              and trade.transactionDate >= :earliestTransactionDate
            order by delivery.createdAt desc, delivery.id desc
            """)
    List<InsiderTradeDelivery> findLatestForUser(
            @Param("user") User user,
            @Param("earliestTransactionDate") LocalDate earliestTransactionDate,
            Pageable pageable);
}
