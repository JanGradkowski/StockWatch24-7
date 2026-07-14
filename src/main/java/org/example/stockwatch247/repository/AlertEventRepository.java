package org.example.stockwatch247.repository;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {
    boolean existsByAlertRuleAndPatternAndSignalCandleTimestamp(AlertRule alertRule, CandlePattern pattern,
                                                                Long signalCandleTimestamp);

    long countByAlertRule(AlertRule alertRule);

    List<AlertEvent> findByAlertRuleOrderBySignalCandleTimestampDesc(AlertRule alertRule);
}
