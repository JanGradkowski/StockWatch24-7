package org.example.stockwatch247.service;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

/** Database ordering of the same stored entry/exit prices used by archive rendering. */
@Service
public class SignalArchiveQuery {
    private final JdbcTemplate jdbc;
    private final CandleCompletionService completion;
    public SignalArchiveQuery(JdbcTemplate jdbc, CandleCompletionService completion) {
        this.jdbc = jdbc; this.completion = completion;
    }

    public Result page(long userId, Long stockId, boolean ascending, int requestedPage, int size) {
        return page(userId, stockId, "trade-return", ascending, requestedPage, size,
                new SignalArchiveFilter("all", ""));
    }

    public Result page(long userId, Long stockId, String sort, boolean ascending, int requestedPage, int size,
                       SignalArchiveFilter filter) {
        String open = """
            (case when r.pattern_family = 'ELLIOTT_WAVE' and e.elliott_trade_plan_status is not null
                then e.elliott_trade_plan_status in ('ACTIVE', 'PROJECTION_ONLY')
             when r.pattern_family = 'HARMONIC_FORMATION' and e.harmonic_stop_status is not null
                then e.harmonic_stop_status = 'ACTIVE'
             else coalesce(e.lifecycle_status, 'DETECTED') in ('POTENTIAL', 'DETECTED') end)
            """;
        String where = "r.user_id = ? and e.deleted_at is null and (cast(? as bigint) is null or r.stock_asset_id = ?)"
                + " and position(? in upper(a.ticker_symbol)) > 0"
                + switch (filter.state()) {
                    case "unread" -> " and e.read_at is null";
                    case "active" -> " and " + open;
                    case "completed" -> " and not " + open;
                    default -> "";
                };
        String tables = " from alert_events e join alert_rules r on r.id = e.alert_rule_id"
                + " join stock_assets a on a.id = r.stock_asset_id ";
        long count = jdbc.queryForObject("select count(*)" + tables + "where " + where,
                Long.class, userId, stockId, stockId, filter.ticker());
        int pages = (int) ((count + size - 1) / size);
        int page = Math.min(Math.max(0, requestedPage), Math.max(0, pages - 1));
        String sql = """
            select e.id from alert_events e
            join alert_rules r on r.id = e.alert_rule_id
            join stock_assets a on a.id = r.stock_asset_id
            left join lateral (
                select first_plan.* from (
                    select distinct on (p.elliott_stage) p.* from elliott_stage_trade_plans p
                    where p.alert_event_id = e.id
                    order by p.elliott_stage, p.entry_timestamp, p.stage_revision, p.id
                ) first_plan order by entry_timestamp desc, stage_revision desc, id desc limit 1
            ) first_plan on r.pattern_family = 'ELLIOTT_WAVE'
            left join lateral (
                select p.* from elliott_stage_trade_plans p
                where p.alert_event_id = e.id and p.elliott_stage = first_plan.elliott_stage
                order by p.entry_timestamp desc, p.stage_revision desc, p.id desc limit 1
            ) last_plan on first_plan.id is not null
            left join lateral (
                select c.close_price from (
                    select timestamp, close_price from candles
                    where symbol = a.ticker_symbol and time_interval = case r.interval
                        when 'WEEKLY' then '1wk' when 'MONTHLY' then '1mo' else '1d' end
                    order by timestamp desc limit 100
                ) c where c.timestamp > coalesce(first_plan.entry_timestamp, e.signal_candle_timestamp)
                and c.timestamp < case r.interval when 'WEEKLY' then ? when 'MONTHLY' then ? else ? end
                and c.close_price not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
                order by c.timestamp desc limit 1
            ) recent on true
            cross join lateral (
                select case when first_plan.id is not null then first_plan.entry_price else e.trade_entry_price end as entry,
                       case when first_plan.id is not null then first_plan.expected_move else e.trade_signal end as direction,
                       case
                       when r.pattern_family = 'ELLIOTT_WAVE' and first_plan.id is not null then
                           case last_plan.status when 'PROJECTION_ONLY' then null when 'ACTIVE' then recent.close_price
                           else last_plan.resolution_close_price end
                       when r.pattern_family = 'ELLIOTT_WAVE' then
                           case e.elliott_trade_plan_status when 'PROJECTION_ONLY' then null when 'ACTIVE' then recent.close_price
                           when 'TARGET_REACHED' then e.elliott_trade_resolution_close when 'STOPPED' then e.elliott_trade_resolution_close
                           when 'STRUCTURE_INVALIDATED' then e.elliott_trade_resolution_close
                           when 'STAGE_COMPLETED' then e.elliott_trade_resolution_close when 'REVISED' then e.elliott_trade_resolution_close end
                       when r.pattern_family = 'HARMONIC_FORMATION' then
                           case when e.trade_plan_version = 'HARMONIC_STOP_V1' and e.stop_loss_price is not null then
                               case e.harmonic_stop_status when 'STOPPED' then e.stop_loss_price
                               when 'TIME_STOPPED' then e.harmonic_stop_resolution_price when 'ACTIVE' then recent.close_price end end
                       else case when e.trade_plan_version in ('CANDLE_RR_V1','CANDLE_RR_V2','CANDLE_RR_V3')
                           and e.reward_risk_ratio is not null and e.stop_loss_price is not null and e.profit_target_price is not null then
                           case coalesce(e.lifecycle_status, 'DETECTED') when 'CONFIRMED' then e.profit_target_price
                           when 'DETECTED' then e.profit_target_price when 'INVALIDATED' then e.stop_loss_price
                           when 'EXPIRED' then e.resolution_close_price end end end as exit
            ) prices
            where %s
            order by (case when prices.exit not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
                then (prices.exit - prices.entry) / nullif(prices.entry, 0) * 100
                    * case prices.direction when 'SELL' then -1 else 1 end end)
            """.formatted(where) + (ascending ? " asc" : " desc") + " nulls last, e.sent_at desc, e.id desc limit ? offset ?";
        List<Long> ids;
        if ("trade-return".equals(sort)) {
            ids = jdbc.query(sql, (rs, row) -> rs.getLong(1),
                    completion.firstIncompleteCandleTimestamp(TimeInterval.WEEKLY),
                    completion.firstIncompleteCandleTimestamp(TimeInterval.MONTHLY),
                    completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY),
                    userId, stockId, stockId, filter.ticker(), size, page * size);
        } else {
            String column = switch (sort) {
                case "ticker" -> "a.ticker_symbol";
                case "interval" -> "r.interval";
                case "confidence" -> "e.confidence_score";
                case "status" -> "e.lifecycle_status";
                default -> "e.sent_at";
            };
            String order = column + (ascending ? " asc" : " desc") + " nulls last, e.sent_at desc, e.id desc";
            ids = jdbc.query("select e.id" + tables + "where " + where + " order by " + order + " limit ? offset ?",
                    (rs, row) -> rs.getLong(1), userId, stockId, stockId, filter.ticker(), size, page * size);
        }
        return new Result(ids, page, pages, count);
    }
    public record Result(List<Long> ids, int page, int pages, long count) { }
}
