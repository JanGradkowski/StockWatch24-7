package org.example.stockwatch247.service.customalert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The stocks whose insider or congressional filings an active custom alert reads. Filings are otherwise
 * collected only for stocks someone follows for them, so these stocks are added to the same collection.
 */
@Component
public class CustomAlertActivityTargets {
    /** Effective targets of active alerts with at least one condition on the source (INSIDER or CONGRESS). */
    public static final String NEEDED_SQL = """
            select distinct t.stock_asset_id from custom_alert_effective_targets t
            join custom_alerts a on a.id = t.alert_id
            where a.active and jsonb_path_exists(a.definition::jsonb, '$.groups[*].conditions[*] ? (@.kind == $source)',
                                                 jsonb_build_object('source', ?::text))
            """;

    private final JdbcTemplate db;

    public CustomAlertActivityTargets(JdbcTemplate db) {
        this.db = db;
    }

    public List<Long> assets(String source) {
        return db.queryForList(NEEDED_SQL, Long.class, source);
    }

    public boolean needs(long stockAssetId, String source) {
        return Boolean.TRUE.equals(db.queryForObject("select exists(" + NEEDED_SQL + " and t.stock_asset_id = ?)",
                Boolean.class, source, stockAssetId));
    }
}
