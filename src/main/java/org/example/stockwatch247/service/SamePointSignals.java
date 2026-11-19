package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Technical signals that end at the same point: one account, stock, interval and pattern family, the same
 * direction, raised on the same candle and ending on the same candle ({@link AlertEvent#patternEnd()}), such as two
 * harmonic formations that share a D point, or an Elliott wave II end and an A-B-C correction end at one top. Lists
 * show such a point as one row, led by the signal with the newest news (the latest received, then the lowest id),
 * and a check emails the point's new signals together. Each signal keeps its own plan, lifecycle and page.
 */
@Component
public class SamePointSignals {

    private final JdbcTemplate jdbc;

    public SamePointSignals(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An SQL condition that the signal {@code event} (of the rule {@code rule}) leads its point: no other visible
     * signal at the point that also meets {@code other} has newer news. {@code other} refers to that signal as
     * {@code o} and its rule as {@code ro}; it is a filter's own condition (unread, still running), so each filter
     * shows a point when any of its signals matches, led by a matching one. Null or blank means any signal.
     */
    public static String leadsPoint(String event, String rule, String other) {
        return "not exists (select 1 from alert_events o join alert_rules ro on ro.id = o.alert_rule_id"
                + " where ro.user_id = " + rule + ".user_id and ro.stock_asset_id = " + rule + ".stock_asset_id"
                + " and ro.interval = " + rule + ".interval"
                + " and coalesce(ro.pattern_family, 'CANDLESTICK') = coalesce(" + rule + ".pattern_family, 'CANDLESTICK')"
                + " and o.trade_signal = " + event + ".trade_signal"
                + " and o.signal_candle_timestamp = " + event + ".signal_candle_timestamp"
                + " and o.pattern_end_timestamp = " + event + ".pattern_end_timestamp"
                + " and o.deleted_at is null and o.id <> " + event + ".id"
                + " and (o.sent_at > " + event + ".sent_at or o.sent_at = " + event + ".sent_at and o.id < " + event + ".id)"
                + (other == null || other.isBlank() ? "" : " and (" + other + ")") + ")";
    }

    /**
     * The other visible signals at each given signal's point, highest score first. Signals without others are
     * left out. Callers check that the given signals belong to the account.
     */
    public Map<Long, List<Long>> others(Collection<Long> eventIds) {
        Map<Long, List<Long>> others = new LinkedHashMap<>();
        if (eventIds == null || eventIds.isEmpty()) return others;
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                    select lead.id lead_id, o.id other_id
                    from alert_events lead
                    join alert_rules r on r.id = lead.alert_rule_id
                    join alert_rules ro on ro.user_id = r.user_id and ro.stock_asset_id = r.stock_asset_id
                        and ro.interval = r.interval
                        and coalesce(ro.pattern_family, 'CANDLESTICK') = coalesce(r.pattern_family, 'CANDLESTICK')
                    join alert_events o on o.alert_rule_id = ro.id and o.trade_signal = lead.trade_signal
                        and o.signal_candle_timestamp = lead.signal_candle_timestamp
                        and o.pattern_end_timestamp = lead.pattern_end_timestamp
                        and o.deleted_at is null and o.id <> lead.id
                    where lead.id = any(?)
                    order by lead.id, o.confidence_score desc nulls last, o.id
                    """);
            statement.setArray(1, connection.createArrayOf("bigint", eventIds.toArray()));
            return statement;
        }, rs -> {
            others.computeIfAbsent(rs.getLong("lead_id"), ignored -> new java.util.ArrayList<>())
                    .add(rs.getLong("other_id"));
        });
        return others;
    }

    /** The given signals and every other visible signal at their points, for actions on whole rows. */
    public java.util.Set<Long> withOthers(Collection<Long> eventIds) {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>(eventIds);
        others(eventIds).values().forEach(ids::addAll);
        return ids;
    }

    /** One point's identity in Java, for a check's own new signals (see {@link #leadsPoint}). */
    public record Point(Long userId, Long stockAssetId, Object interval, Object family, Object tradeSignal,
                        Long signalCandleTimestamp, Long patternEndTimestamp) {
        public static Point of(AlertEvent event) {
            AlertRule rule = event.getAlertRule();
            return new Point(rule.getUser() == null ? null : rule.getUser().getId(),
                    rule.getStockAsset() == null ? null : rule.getStockAsset().getId(), rule.getInterval(),
                    rule.getPatternFamily() == null ? org.example.stockwatch247.model.enums.AlertPatternFamily.CANDLESTICK
                            : rule.getPatternFamily(),
                    event.getTradeSignal(), event.getSignalCandleTimestamp(), event.patternEnd());
        }
    }
}
