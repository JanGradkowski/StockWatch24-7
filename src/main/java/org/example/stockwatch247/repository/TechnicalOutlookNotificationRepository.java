package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.TechnicalOutlookNotification;
import org.example.stockwatch247.model.TechnicalOutlookSubscription;
import org.example.stockwatch247.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TechnicalOutlookNotificationRepository
        extends JpaRepository<TechnicalOutlookNotification, Long> {
    boolean existsBySubscriptionAndCurrentCandleTimestamp(
            TechnicalOutlookSubscription subscription, long currentCandleTimestamp);

    @EntityGraph(attributePaths = {"subscription", "subscription.user", "subscription.stockAsset"})
    @Query("""
            select notification from TechnicalOutlookNotification notification
            where notification.subscription.user = :user
              and notification.readAt is null
            order by notification.createdAt desc, notification.id desc
            """)
    List<TechnicalOutlookNotification> findLatestUnreadForUser(
            @Param("user") User user, Pageable pageable);

    @EntityGraph(attributePaths = {"subscription", "subscription.user", "subscription.stockAsset"})
    @Query("""
            select notification from TechnicalOutlookNotification notification
            where notification.id = :id and notification.subscription.user = :user
            """)
    Optional<TechnicalOutlookNotification> findOwnedById(
            @Param("id") Long id, @Param("user") User user);

    @Modifying
    @Query("delete from TechnicalOutlookNotification notification where notification.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
