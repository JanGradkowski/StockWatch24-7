package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
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

    @EntityGraph(attributePaths = {"alertRule", "alertRule.stockAsset"})
    List<AlertEvent> findByAlertRule_UserAndAlertRule_IsActiveTrueOrderBySentAtDescIdDesc(User user,
                                                                                          Pageable pageable);

    @Query("""
            select event
            from AlertEvent event
            join fetch event.alertRule rule
            join fetch rule.stockAsset
            where event.id = :eventId
              and rule.user = :user
            """)
    Optional<AlertEvent> findOwnedByIdAndUser(@Param("eventId") Long eventId, @Param("user") User user);

    @EntityGraph(attributePaths = {"alertRule", "alertRule.user", "alertRule.stockAsset"})
    @Query("""
            select event
            from AlertEvent event
            join event.alertRule rule
            join rule.stockAsset asset
            where lower(asset.tickerSymbol) = lower(:symbol)
              and rule.interval = :interval
              and event.lifecycleStatus = :status
              and event.confirmationWindowCandles is not null
              and event.patternHigh is not null
              and event.patternLow is not null
            order by event.signalCandleTimestamp, event.id
            """)
    List<AlertEvent> findTrackedLifecycleEvents(
            @Param("symbol") String symbol,
            @Param("interval") TimeInterval interval,
            @Param("status") SignalLifecycleStatus status);
}
