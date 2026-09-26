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
                + ((filter.watchlistId() != null && !filter.ticker().isEmpty()) || filter.ticker().contains(",")
                    ? " and upper(a.ticker_symbol) = any(string_to_array(?, ','))" : " and position(? in upper(a.ticker_symbol)) > 0")
                + switch (filter.state()) {
                    case "unread" -> " and e.read_at is null";
                    case "active" -> " and " + open;
                    case "completed" -> " and not " + open;
                    default -> "";
                };
        if (filter.watchlistId() != null) {
            // The ID is a Long, and ownership is also enforced here for non-controller callers.
            where += " and exists(select 1 from watchlists scoped where scoped.id=" + filter.watchlistId()
                    + " and scoped.user_id=r.user_id) and "
                    + WatchlistSignalScope.matches("TECHNICAL", "e.id", "r.stock_asset_id",
                        "coalesce(r.pattern_family,'CANDLESTICK')", "r.interval", "e.trade_signal",
                        filter.watchlistId().toString());
        }
        if (filter.watchlistId() != null) return mixedPage(where,userId,stockId,sort,ascending,requestedPage,size,filter);
        String tables = " from alert_events e join alert_rules r on r.id = e.alert_rule_id"
                + " join stock_assets a on a.id = r.stock_asset_id ";
        long count = jdbc.queryForObject("select count(*)" + tables + "where " + where,
                Long.class, userId, stockId, stockId, filter.ticker());
        int pages = (int) ((count + size - 1) / size);
        int page = Math.min(Math.max(0, requestedPage), Math.max(0, pages - 1));
        String sql = "select id from (" + technicalRows(where,true) + ") archive order by trade_return"
                + (ascending ? " asc" : " desc") + " nulls last,received desc,id desc limit ? offset ?";
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

    private String technicalRows(String where, boolean outcomes) {
        String projection = "select 'TECHNICAL'::text kind,e.id,a.ticker_symbol ticker,a.company_name company,r.interval,e.confidence_score confidence,e.lifecycle_status status,e.sent_at received,e.read_at,null::text title,null::text actor,null::date transaction_date,";
        if (!outcomes) return projection + "null::float8 trade_return from alert_events e join alert_rules r on r.id=e.alert_rule_id join stock_assets a on a.id=r.stock_asset_id where " + where;
        return projection + "(case when prices.exit not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8) then (prices.exit - prices.entry) / nullif(prices.entry, 0) * 100 * case prices.direction when 'SELL' then -1 else 1 end end) trade_return " + """
            from alert_events e
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
                           else coalesce(last_plan.resolution_fill_price, last_plan.resolution_close_price) end
                       when r.pattern_family = 'ELLIOTT_WAVE' then
                           case e.elliott_trade_plan_status when 'PROJECTION_ONLY' then null when 'ACTIVE' then recent.close_price
                           when 'TARGET_REACHED' then e.elliott_trade_resolution_close when 'STOPPED' then e.elliott_trade_resolution_close
                           when 'STRUCTURE_INVALIDATED' then e.elliott_trade_resolution_close
                           when 'TIME_STOPPED' then e.elliott_trade_resolution_close when 'STAGE_COMPLETED' then e.elliott_trade_resolution_close when 'REVISED' then e.elliott_trade_resolution_close end
                       when r.pattern_family = 'HARMONIC_FORMATION' then
                           case when e.trade_plan_version in ('HARMONIC_STOP_V1','HARMONIC_TRADE_V2') and e.stop_loss_price is not null then
                               case e.harmonic_stop_status when 'STOPPED' then coalesce(e.trade_resolution_price,e.harmonic_stop_resolution_price,e.stop_loss_price) when 'TARGET_REACHED' then e.harmonic_stop_resolution_price
                               when 'TIME_STOPPED' then e.harmonic_stop_resolution_price when 'ACTIVE' then recent.close_price end end
                       else case when e.trade_plan_version in ('CANDLE_RR_V1','CANDLE_RR_V2','CANDLE_RR_V3','CANDLE_RR_V4')
                           and e.trade_actionable is distinct from false and e.reward_risk_ratio is not null and e.stop_loss_price is not null and e.profit_target_price is not null then
                           case coalesce(e.lifecycle_status, 'DETECTED') when 'CONFIRMED' then e.profit_target_price
                           when 'DETECTED' then e.profit_target_price when 'INVALIDATED' then coalesce(e.trade_resolution_price,e.stop_loss_price)
                           when 'EXPIRED' then e.resolution_close_price end end end as exit
            ) prices
            where
            """ + where;
    }

    private String activityRows(String kind, String prefix, String actor, SignalArchiveFilter filter) {
        String where = "s.user_id=? and d.deleted_at is null and (cast(? as bigint) is null or s.stock_asset_id=?) and (?='' or upper(a.ticker_symbol)=any(string_to_array(?, ',')))"
                + " and exists(select 1 from watchlists w where w.id=" + filter.watchlistId() + " and w.user_id=s.user_id) and "
                + WatchlistSignalScope.matches(kind,"d.id","s.stock_asset_id","'"+kind+"'","'DAILY'","'ANY'",filter.watchlistId().toString());
        if (filter.state().equals("unread")) where += " and d.read_at is null";
        if (filter.state().equals("active")) where += " and false";
        return "select '"+kind+"'::text kind,d.id,a.ticker_symbol ticker,a.company_name company,null::text interval,null::integer confidence,'RECORDED'::text status,"
                + "d.created_at::timestamp received,d.read_at::timestamp,t.transaction_type title,t."+actor+" actor,t.transaction_date,null::float8 trade_return"
                + " from "+prefix+"_deliveries d join "+prefix+"_subscriptions s on s.id=d.subscription_id join "+prefix+"s t on t.id=d.trade_id join stock_assets a on a.id=s.stock_asset_id where "+where;
    }
    private Result mixedPage(String technicalWhere,long userId,Long stockId,String sort,boolean ascending,int requestedPage,int size,SignalArchiveFilter filter) {
        String activity = " union all " + activityRows("CONGRESS","congressional_trade","member_name",filter)
                + " union all " + activityRows("INSIDER","insider_trade","insider_name",filter);
        var params = new java.util.ArrayList<Object>();
        java.util.Collections.addAll(params,userId,stockId,stockId,filter.ticker());
        for (int i=0;i<2;i++) java.util.Collections.addAll(params,userId,stockId,stockId,filter.ticker(),filter.ticker());
        long count = jdbc.queryForObject("select count(*) from ("+technicalRows(technicalWhere,false)+activity+") archive",Long.class,params.toArray());
        int pages = (int)((count+size-1)/size), page = Math.min(Math.max(0,requestedPage),Math.max(0,pages-1));
        boolean outcomes = sort.equals("trade-return");
        if (outcomes) {
            params.addAll(0,List.of(completion.firstIncompleteCandleTimestamp(TimeInterval.WEEKLY),
                    completion.firstIncompleteCandleTimestamp(TimeInterval.MONTHLY),completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY)));
        }
        String column = switch(sort) { case "ticker" -> "ticker"; case "interval" -> "interval"; case "confidence" -> "confidence"; case "status" -> "status"; case "trade-return" -> "trade_return"; default -> "received"; };
        params.add(size); params.add(page*size);
        var rows = jdbc.query("select * from ("+technicalRows(technicalWhere,outcomes)+activity+") archive order by "+column+(ascending?" asc":" desc")+" nulls last,received desc,kind,id desc limit ? offset ?",
                (r,n) -> new ArchiveRow(r.getString("kind"),r.getLong("id"),r.getString("kind").equals("TECHNICAL")?null:
                        new ActivitySignal(r.getString("kind"),r.getLong("id"),r.getString("ticker"),r.getString("company"),r.getString("title"),r.getString("actor"),r.getDate("transaction_date").toLocalDate(),r.getTimestamp("received").toLocalDateTime(),r.getTimestamp("read_at")!=null)),params.toArray());
        return new Result(rows.stream().filter(row->row.activity()==null).map(ArchiveRow::id).toList(),page,pages,count,rows);
    }
    public record ActivitySignal(String kind,long id,String symbol,String companyName,String transaction,String actor,
                                 java.time.LocalDate transactionDate,java.time.LocalDateTime received,boolean hasBeenRead) {
        public String familyLabel() { return kind.equals("CONGRESS")?"Congressional activity":"Insider activity"; }
        public String detailUrl() { return "/activity-signals/"+(kind.equals("CONGRESS")?"congressional":"insider")+"/"+id; }
        public String key() { return (kind.equals("CONGRESS")?"CONGRESSIONAL":"INSIDER")+":"+id; }
        public String title() { return transaction.replace('_',' ').toLowerCase(java.util.Locale.ROOT); }
    }
    public record ArchiveRow(String kind,long id,ActivitySignal activity) {}
    public record Result(List<Long> ids, int page, int pages, long count,List<ArchiveRow> rows) {
        public Result(List<Long> ids,int page,int pages,long count) { this(ids,page,pages,count,List.of()); }
    }

}
