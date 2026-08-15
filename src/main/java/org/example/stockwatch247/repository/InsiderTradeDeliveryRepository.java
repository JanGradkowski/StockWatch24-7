package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.InsiderTrade;
import org.example.stockwatch247.model.InsiderTradeDelivery;
import org.example.stockwatch247.model.InsiderTradeSubscription;
import org.example.stockwatch247.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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
              and delivery.readAt is null
              and delivery.deletedAt is null
            order by delivery.createdAt desc, delivery.id desc
            """)
    List<InsiderTradeDelivery> findLatestUnreadForUser(
            @Param("user") User user,
            @Param("earliestTransactionDate") LocalDate earliestTransactionDate,
            Pageable pageable);

    @Query("""
            select delivery
            from InsiderTradeDelivery delivery
            join fetch delivery.subscription subscription
            join fetch delivery.trade trade
            join fetch trade.stockAsset
            where subscription.user = :user
              and delivery.deletedAt is null
            order by delivery.createdAt desc, delivery.id desc
            """)
    List<InsiderTradeDelivery> findAllForUser(@Param("user") User user);

    @Query("""
            select delivery
            from InsiderTradeDelivery delivery
            join fetch delivery.subscription subscription
            where delivery.id = :deliveryId
              and subscription.user = :user
              and delivery.deletedAt is null
            """)
    Optional<InsiderTradeDelivery> findOwnedByIdAndUser(
            @Param("deliveryId") Long deliveryId,
            @Param("user") User user);

    @Query("""
            select delivery
            from InsiderTradeDelivery delivery
            join delivery.subscription subscription
            where delivery.id in :deliveryIds
              and subscription.user = :user
              and delivery.deletedAt is null
            """)
    List<InsiderTradeDelivery> findOwnedByIdsAndUser(
            @Param("deliveryIds") List<Long> deliveryIds,
            @Param("user") User user);

    @Query("""
            select delivery
            from InsiderTradeDelivery delivery
            join fetch delivery.trade trade
            where delivery.subscription.user = :user
              and trade.id in :tradeIds
              and delivery.deletedAt is null
            """)
    List<InsiderTradeDelivery> findOwnedByTradeIds(
            @Param("user") User user,
            @Param("tradeIds") List<Long> tradeIds);

    @Query("""
            select count(delivery)
            from InsiderTradeDelivery delivery
            join delivery.subscription subscription
            join delivery.trade trade
            where subscription.user = :user
              and trade.transactionDate >= :earliestTransactionDate
              and delivery.readAt is null
              and delivery.deletedAt is null
            """)
    long countUnreadForUser(
            @Param("user") User user,
            @Param("earliestTransactionDate") LocalDate earliestTransactionDate);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update InsiderTradeDelivery delivery
            set delivery.readAt = :readAt
            where delivery.subscription in (
                select subscription
                from InsiderTradeSubscription subscription
                where subscription.user = :user
            )
              and delivery.readAt is null
              and delivery.deletedAt is null
            """)
    int markAllUnreadForUser(
            @Param("user") User user,
            @Param("readAt") java.time.Instant readAt);
}
