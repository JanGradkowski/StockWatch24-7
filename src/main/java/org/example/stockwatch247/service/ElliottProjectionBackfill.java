package org.example.stockwatch247.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gives Elliott signals recorded before projections were stored at detection time their projection,
 * in small background batches instead of while someone opens the signal page. Signals whose chart
 * cannot support a projection are skipped for the rest of this process lifetime.
 */
@Component
public class ElliottProjectionBackfill {
    private static final Logger log = LoggerFactory.getLogger(ElliottProjectionBackfill.class);
    private static final int BATCH = 25;
    private final JdbcTemplate jdbc;
    private final AlertRuleService alerts;
    private long cursor;

    public ElliottProjectionBackfill(JdbcTemplate jdbc, AlertRuleService alerts) {
        this.jdbc = jdbc;
        this.alerts = alerts;
    }

    @Scheduled(fixedDelayString = "${alerts.elliott-projection-backfill-delay-ms:1800000}",
            initialDelayString = "${alerts.elliott-projection-backfill-initial-delay-ms:300000}")
    public synchronized int runBatch() {
        List<Long> ids = jdbc.queryForList("""
                select e.id from alert_events e
                join alert_rules r on r.id = e.alert_rule_id
                where r.pattern_family = 'ELLIOTT_WAVE' and e.id > ?
                  and not exists (select 1 from elliott_projection_sets s where s.alert_event_id = e.id)
                order by e.id
                limit ?
                """, Long.class, cursor, BATCH);
        for (Long id : ids) {
            cursor = id;
            try {
                alerts.backfillElliottProjection(id);
            } catch (RuntimeException failure) {
                log.warn("Elliott projection backfill skipped event {}: {}", id, failure.getClass().getSimpleName());
            }
        }
        return ids.size();
    }
}
