package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
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

    Optional<AlertEvent> findFirstByAlertRuleAndElliottCycleKeyAndElliottSignalStageOrderByIdAsc(
            AlertRule alertRule,
            String elliottCycleKey,
            ElliottSignalStage elliottSignalStage);

    long countByAlertRule(AlertRule alertRule);

    long countByAlertRuleAndReadAtIsNull(AlertRule alertRule);

    List<AlertEvent> findByAlertRuleOrderBySignalCandleTimestampDesc(AlertRule alertRule);

    @EntityGraph(attributePaths = {"alertRule", "alertRule.stockAsset"})
    List<AlertEvent> findByAlertRule_UserAndAlertRule_IsActiveTrueAndReadAtIsNullOrderBySentAtDescIdDesc(
            User user,
            Pageable pageable);

    @EntityGraph(attributePaths = {"alertRule", "alertRule.stockAsset"})
    Page<AlertEvent> findByAlertRule_User(User user, Pageable pageable);

    @EntityGraph(attributePaths = {"alertRule", "alertRule.stockAsset"})
    List<AlertEvent> findAllByAlertRule_User(User user);

    @EntityGraph(attributePaths = {"alertRule", "alertRule.stockAsset"})
    @Query("""
            select event
            from AlertEvent event
            join event.alertRule rule
            join rule.stockAsset asset
            where rule.user = :user
              and lower(asset.tickerSymbol) = lower(:symbol)
              and rule.interval = :interval
              and rule.patternFamily = :family
              and event.elliottCycleKey is not null
              and event.elliottSignalStage is not null
            order by event.signalCandleTimestamp, event.id
            """)
    List<AlertEvent> findElliottSignalCards(
            @Param("user") User user,
            @Param("symbol") String symbol,
            @Param("interval") TimeInterval interval,
            @Param("family") AlertPatternFamily family);

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

    @EntityGraph(attributePaths = {"alertRule", "alertRule.user", "alertRule.stockAsset"})
    @Query("""
            select event
            from AlertEvent event
            join event.alertRule rule
            join rule.stockAsset asset
            where lower(asset.tickerSymbol) = lower(:symbol)
              and rule.interval = :interval
              and rule.patternFamily = :family
              and event.lifecycleStatus = :status
              and (event.confirmationWindowCandles is null or event.elliottCycleKey is null)
            order by event.signalCandleTimestamp, event.id
            """)
    List<AlertEvent> findUntrackedLifecycleEvents(
            @Param("symbol") String symbol,
            @Param("interval") TimeInterval interval,
            @Param("family") AlertPatternFamily family,
            @Param("status") SignalLifecycleStatus status);
}
