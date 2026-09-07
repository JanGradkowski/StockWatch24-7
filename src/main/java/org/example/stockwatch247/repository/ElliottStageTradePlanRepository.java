package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.ElliottStageTradePlan;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.ElliottTradePlanStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ElliottStageTradePlanRepository extends JpaRepository<ElliottStageTradePlan, Long> {
    @Query("select p from ElliottStageTradePlan p join fetch p.alertEvent e join e.alertRule r where e.id in :ids and r.user = :user order by p.stageRevision, p.id")
    List<ElliottStageTradePlan> findOwnedHistories(@Param("ids") List<Long> ids, @Param("user") User user);

    List<ElliottStageTradePlan> findByAlertEventOrderByStageRevisionAsc(AlertEvent alertEvent);

    java.util.Optional<ElliottStageTradePlan>
    findFirstByAlertEventOrderByEntryTimestampDescStageRevisionDescIdDesc(AlertEvent alertEvent);

    long countByAlertEventAndStage(AlertEvent alertEvent,
                                   org.example.stockwatch247.model.enums.ElliottSignalStage stage);

    @EntityGraph(attributePaths = {"alertEvent", "alertEvent.alertRule", "alertEvent.alertRule.user",
            "alertEvent.alertRule.stockAsset"})
    @Query("""
            select plan from ElliottStageTradePlan plan
            join plan.alertEvent event
            join event.alertRule rule
            join rule.stockAsset asset
            where lower(asset.tickerSymbol) = lower(:symbol)
              and rule.interval = :interval
              and plan.status in :statuses
              and event.deletedAt is null
            order by plan.entryTimestamp, plan.id
            """)
    List<ElliottStageTradePlan> findOpenPlans(
            @Param("symbol") String symbol,
            @Param("interval") TimeInterval interval,
            @Param("statuses") List<ElliottTradePlanStatus> statuses);

    @EntityGraph(attributePaths = {"alertEvent"})
    @Query("""
            select plan from ElliottStageTradePlan plan
            join plan.alertEvent event
            join event.alertRule rule
            where event.id = :eventId and rule.user = :user
            order by plan.stageRevision, plan.id
            """)
    List<ElliottStageTradePlan> findOwnedHistory(
            @Param("eventId") Long eventId,
            @Param("user") User user);
}
