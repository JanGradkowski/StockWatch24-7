package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {
    boolean existsByAlertRuleAndPatternAndSignalCandleTimestamp(AlertRule alertRule, CandlePattern pattern,
                                                                Long signalCandleTimestamp);

    long countByAlertRule(AlertRule alertRule);

    List<AlertEvent> findByAlertRuleOrderBySignalCandleTimestampDesc(AlertRule alertRule);

    @Query("""
            select event
            from AlertEvent event
            join fetch event.alertRule rule
            join fetch rule.stockAsset
            where event.id = :eventId
              and rule.user = :user
            """)
    Optional<AlertEvent> findOwnedByIdAndUser(@Param("eventId") Long eventId, @Param("user") User user);
}
