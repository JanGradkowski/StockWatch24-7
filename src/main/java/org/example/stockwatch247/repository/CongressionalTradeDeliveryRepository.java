package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.CongressionalTradeDelivery;
import org.example.stockwatch247.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CongressionalTradeDeliveryRepository
        extends JpaRepository<CongressionalTradeDelivery, Long> {

    @Query("""
            select delivery
            from CongressionalTradeDelivery delivery
            join fetch delivery.subscription subscription
            join fetch delivery.trade trade
            join fetch trade.stockAsset
            where subscription.user = :user
              and trade.transactionDate >= :earliestTransactionDate
            order by delivery.createdAt desc, delivery.id desc
            """)
    List<CongressionalTradeDelivery> findLatestForUser(
            @Param("user") User user,
            @Param("earliestTransactionDate") java.time.LocalDate earliestTransactionDate,
            Pageable pageable);
}
