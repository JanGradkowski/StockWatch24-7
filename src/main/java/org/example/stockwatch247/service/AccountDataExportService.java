package org.example.stockwatch247.service;

import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The account's own records for the data export: everything the user created or received, so the
 * download is a complete copy rather than only the profile and settings. Secrets (password hashes,
 * authenticator secrets, recovery codes, tokens) are never included.
 */
@Service
public class AccountDataExportService {
    private final JdbcTemplate jdbc;

    public AccountDataExportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> collect(long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("watchlists", rows("""
                select w.name, w.description, w.pinned, w.created_at,
                       coalesce(string_agg(a.ticker_symbol, ',' order by a.ticker_symbol), '') as symbols
                from watchlists w
                left join watchlist_members m on m.watchlist_id = w.id
                left join stock_assets a on a.id = m.stock_asset_id
                where w.user_id = ? group by w.id order by w.id
                """, userId));
        data.put("signalHistory", rows("""
                select a.ticker_symbol as symbol, r.interval, r.pattern_family, e.pattern, e.trade_signal,
                       e.signal_candle_timestamp, e.confidence_score, e.close_price, e.sent_at, e.read_at
                from alert_events e
                join alert_rules r on r.id = e.alert_rule_id
                join stock_assets a on a.id = r.stock_asset_id
                where r.user_id = ? and e.deleted_at is null order by e.sent_at, e.id
                """, userId));
        data.put("technicalOutlookFollows", rows("""
                select a.ticker_symbol as symbol, s.interval, s.is_active as active, s.created_at
                from technical_outlook_subscriptions s join stock_assets a on a.id = s.stock_asset_id
                where s.user_id = ? order by a.ticker_symbol, s.interval
                """, userId));
        data.put("technicalOutlookChanges", rows("""
                select a.ticker_symbol as symbol, s.interval, n.previous_classification, n.current_classification,
                       n.previous_score, n.current_score, n.current_candle_timestamp, n.created_at, n.read_at
                from technical_outlook_notifications n
                join technical_outlook_subscriptions s on s.id = n.subscription_id
                join stock_assets a on a.id = s.stock_asset_id
                where s.user_id = ? order by n.created_at, n.id
                """, userId));
        data.put("congressionalFollows", follows("congressional_trade", userId));
        data.put("congressionalNotifications", deliveries("congressional_trade", "member_name", userId));
        data.put("insiderFollows", follows("insider_trade", userId));
        data.put("insiderNotifications", deliveries("insider_trade", "insider_name", userId));
        data.put("chartDrawings", rows("""
                select symbol, drawing_id, payload, version, updated_at
                from user_chart_drawings where user_id = ? order by symbol, id
                """, userId));
        data.put("chartLayouts", rows("""
                select scope, payload, version, updated_at from user_chart_layouts where user_id = ? order by scope
                """, userId));
        data.put("chartTemplates", rows("""
                select kind, subject, name, payload, updated_at
                from user_chart_templates where user_id = ? order by kind, subject, name
                """, userId));
        data.put("chartAlerts", rows("""
                select symbol, interval, source_kind, source_id, label, condition, definition, frequency, active,
                       trigger_count, last_triggered_at, created_at
                from chart_alerts where user_id = ? order by created_at, id
                """, userId));
        data.put("chartAlertTriggers", rows("""
                select a.symbol, a.label, e.*
                from chart_alert_events e join chart_alerts a on a.id = e.alert_id
                where a.user_id = ? order by e.id
                """, userId));
        data.put("customAlerts", rows("""
                select id, name, description, interval, definition, revision, trigger_mode, quiet_candles, outcome_candles,
                       email_enabled, active, pinned, last_triggered_at, created_at, updated_at
                from custom_alerts where user_id = ? order by created_at, id
                """, userId));
        data.put("customAlertWatchlists", rows("""
                select t.alert_id, w.name watchlist, t.scope, t.created_at
                from custom_alert_targets t join custom_alerts a on a.id = t.alert_id join watchlists w on w.id = t.watchlist_id
                where a.user_id = ? order by t.alert_id, t.id
                """, userId));
        data.put("customAlertTriggers", rows("""
                select e.alert_id, a.name alert, s.ticker_symbol, e.candle_timestamp, e.kind, e.close_price, e.rule_text,
                       e.evidence, e.forward_returns, e.outcome_return, e.created_at, e.read_at
                from custom_alert_events e join custom_alerts a on a.id = e.alert_id join stock_assets s on s.id = e.stock_asset_id
                where a.user_id = ? and e.deleted_at is null order by e.id
                """, userId));
        data.put("demoTrades", rows("""
                select * from virtual_trades where user_id = ? and deleted_at is null order by id
                """, userId));
        data.put("securityEvents", rows("""
                select event_type, description, created_at from security_events where user_id = ? order by created_at, id
                """, userId));
        return data;
    }

    private List<Map<String, Object>> follows(String prefix, long userId) {
        return rows("select a.ticker_symbol as symbol, s.active, s.created_at from " + prefix + "_subscriptions s "
                + "join stock_assets a on a.id = s.stock_asset_id where s.user_id = ? order by a.ticker_symbol", userId);
    }

    private List<Map<String, Object>> deliveries(String prefix, String actor, long userId) {
        return rows("select t.ticker_symbol as symbol, t." + actor + " as actor, t.transaction_type, t.transaction_date, "
                + "d.created_at, d.read_at from " + prefix + "_deliveries d "
                + "join " + prefix + "_subscriptions s on s.id = d.subscription_id "
                + "join " + prefix + "s t on t.id = d.trade_id "
                + "where s.user_id = ? and d.deleted_at is null order by d.created_at, d.id", userId);
    }

    /** Rows as plain JSON values; internal ids and owner columns are left out. */
    private List<Map<String, Object>> rows(String sql, Object... arguments) {
        return jdbc.query(sql, new ColumnMapRowMapper(), arguments).stream().map(row -> {
            Map<String, Object> clean = new LinkedHashMap<>();
            row.forEach((column, value) -> {
                if (column.equals("id") || column.endsWith("_id") && !column.equals("drawing_id") && !column.equals("source_id")
                        || column.equals("user_id")) return;
                clean.put(column, plain(value));
            });
            return clean;
        }).toList();
    }

    private static Object plain(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime().toString();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof java.time.temporal.TemporalAccessor temporal) return temporal.toString();
        return value;
    }
}
