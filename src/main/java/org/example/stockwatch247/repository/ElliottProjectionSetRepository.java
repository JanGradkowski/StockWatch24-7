package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.ElliottProjectionSet;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.ElliottProjectionSetStatus;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ElliottProjectionSetRepository extends JpaRepository<ElliottProjectionSet, Long> {
    List<ElliottProjectionSet> findByAlertEventAndStatusInOrderByCreatedAtAscIdAsc(
            AlertEvent alertEvent, List<ElliottProjectionSetStatus> statuses);

    long countByAlertEventAndStage(AlertEvent alertEvent, ElliottSignalStage stage);

    boolean existsByAlertEventAndStageAndSourceTimestamp(
            AlertEvent alertEvent, ElliottSignalStage stage, long sourceTimestamp);

    Optional<ElliottProjectionSet> findFirstByAlertEventOrderBySourceTimestampDescStageRevisionDescIdDesc(
            AlertEvent alertEvent);

    @Query("""
            select (count(projection) > 0) from ElliottProjectionSet projection
            join projection.alertEvent event
            join event.alertRule rule
            join rule.stockAsset asset
            where lower(asset.tickerSymbol) = lower(:symbol)
              and rule.interval = :interval
              and projection.status = org.example.stockwatch247.model.enums.ElliottProjectionSetStatus.ACTIVE
              and event.deletedAt is null
            """)
    boolean existsOpenProjection(
            @Param("symbol") String symbol,
            @Param("interval") TimeInterval interval);

    @EntityGraph(attributePaths = {"alertEvent", "alertEvent.alertRule",
            "alertEvent.alertRule.user", "alertEvent.alertRule.stockAsset"})
    @Query("""
            select projection from ElliottProjectionSet projection
            join projection.alertEvent event
            join event.alertRule rule
            join rule.stockAsset asset
            where lower(asset.tickerSymbol) = lower(:symbol)
              and rule.interval = :interval
              and projection.status = :status
              and event.deletedAt is null
            order by projection.sourceTimestamp, projection.id
            """)
    List<ElliottProjectionSet> findForEvaluation(
            @Param("symbol") String symbol,
            @Param("interval") TimeInterval interval,
            @Param("status") ElliottProjectionSetStatus status);

    @EntityGraph(attributePaths = {"alertEvent"})
    @Query("""
            select projection from ElliottProjectionSet projection
            join projection.alertEvent event
            join event.alertRule rule
            where event.id = :eventId and rule.user = :user
            order by projection.sourceTimestamp desc, projection.stageRevision desc, projection.id desc
            """)
    List<ElliottProjectionSet> findOwnedHistory(
            @Param("eventId") Long eventId,
            @Param("user") User user);
}
