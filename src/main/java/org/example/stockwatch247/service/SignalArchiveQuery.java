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
                + " and " + tickerMatch("a.ticker_symbol", filter)
                + switch (filter.state()) {
                    case "unread" -> " and e.read_at is null";
                    case "active" -> " and " + open;
                    case "completed" -> " and not " + open;
                    default -> "";
                };
        List<String> families = technicalFamilies(filter);
        if (families.size() < 3) {
            where += " and coalesce(r.pattern_family,'CANDLESTICK') in ("
                    + String.join(",", families.stream().map(family -> "'" + family + "'").toList()) + ")";
        }
        if (filter.watchlistId() != null) {
            // The ID is a Long, and ownership is also enforced here for non-controller callers.
            where += " and exists(select 1 from watchlists scoped where scoped.id=" + filter.watchlistId()
                    + " and scoped.user_id=r.user_id) and "
                    + WatchlistSignalScope.matches("TECHNICAL", "e.id", "r.stock_asset_id",
                        "coalesce(r.pattern_family,'CANDLESTICK')", "r.interval", "e.trade_signal",
                        filter.watchlistId().toString());
        }
        List<String> others = otherKinds(stockId, filter);
        if (!others.isEmpty() || families.isEmpty()) {
            return mixedPage(families.isEmpty() ? null : where, others, userId, stockId, sort, ascending, requestedPage, size, filter);
        }
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

    /** One ticker matches by prefix-free substring; a list, or a ticker inside a watchlist, matches exactly. */
    private static String tickerMatch(String column, SignalArchiveFilter filter) {
        return (filter.watchlistId() != null && !filter.ticker().isEmpty()) || filter.ticker().contains(",")
                ? "upper(" + column + ") = any(string_to_array(?, ','))"
                : "position(? in upper(" + column + ")) > 0";
    }

    /** Pattern families the archive shows; none when a custom alert or only other types are selected. */
    private static List<String> technicalFamilies(SignalArchiveFilter filter) {
        if (filter.customAlertId() != null) return List.of();
        List<String> families = new java.util.ArrayList<>();
        if (filter.shows("candlestick")) families.add("CANDLESTICK");
        if (filter.shows("elliott")) families.add("ELLIOTT_WAVE");
        if (filter.shows("harmonic")) families.add("HARMONIC_FORMATION");
        return families;
    }

    /**
     * The archive's other row kinds. A watchlist adds its insider and congressional filings; the account
     * archive adds chart alert triggers, which belong to a chart rather than a list. Outlook changes and
     * custom alert triggers appear in both. A company archive stays technical.
     */
    private static List<String> otherKinds(Long stockId, SignalArchiveFilter filter) {
        if (filter.customAlertId() != null) return List.of("CUSTOM");
        if (stockId != null) return List.of();
        List<String> kinds = new java.util.ArrayList<>();
        if (filter.shows("outlook")) kinds.add("OUTLOOK");
        if (filter.watchlistId() != null) {
            if (filter.shows("congress")) kinds.add("CONGRESS");
            if (filter.shows("insider")) kinds.add("INSIDER");
        } else if (filter.shows("chart")) {
            kinds.add("CHART");
        }
        if (filter.shows("custom")) kinds.add("CUSTOM");
        return kinds;
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
                ) c where c.timestamp > coalesce(
                    case when first_plan.fill_status = 'FILLED' then first_plan.entry_timestamp - 1 else first_plan.entry_timestamp end,
                    case when e.trade_fill_status = 'FILLED' then e.trade_entry_timestamp - 1 end,
                    e.signal_candle_timestamp)
                and c.timestamp < case r.interval when 'WEEKLY' then ? when 'MONTHLY' then ? else ? end
                and c.close_price not in ('NaN'::float8, 'Infinity'::float8, '-Infinity'::float8)
                order by c.timestamp desc limit 1
            ) recent on true
            cross join lateral (
                select case when first_plan.id is not null then first_plan.entry_price else e.trade_entry_price end as entry,
                       case when first_plan.id is not null then first_plan.expected_move else e.trade_signal end as direction,
                       case
                       when r.pattern_family = 'ELLIOTT_WAVE' and first_plan.id is not null then
                           case last_plan.status when 'PROJECTION_ONLY' then null when 'NOT_FILLED' then null
                           when 'ACTIVE' then case when last_plan.fill_status = 'AWAITING_FILL' then null else recent.close_price end
                           else coalesce(last_plan.resolution_fill_price, last_plan.resolution_close_price) end
                       when r.pattern_family = 'ELLIOTT_WAVE' then
                           case e.elliott_trade_plan_status when 'PROJECTION_ONLY' then null when 'ACTIVE' then recent.close_price
                           when 'TARGET_REACHED' then e.elliott_trade_resolution_close when 'STOPPED' then e.elliott_trade_resolution_close
                           when 'STRUCTURE_INVALIDATED' then e.elliott_trade_resolution_close
                           when 'TIME_STOPPED' then e.elliott_trade_resolution_close when 'STAGE_COMPLETED' then e.elliott_trade_resolution_close when 'REVISED' then e.elliott_trade_resolution_close end
                       when r.pattern_family = 'HARMONIC_FORMATION' then
                           case when e.trade_plan_version in ('HARMONIC_STOP_V1','HARMONIC_TRADE_V2','HARMONIC_TRADE_V3') and e.stop_loss_price is not null then
                               case e.harmonic_stop_status when 'STOPPED' then coalesce(e.trade_resolution_price,e.harmonic_stop_resolution_price,e.stop_loss_price) when 'TARGET_REACHED' then e.harmonic_stop_resolution_price
                               when 'TIME_STOPPED' then e.harmonic_stop_resolution_price
                               when 'ACTIVE' then case when e.trade_fill_status = 'AWAITING_FILL' then null else recent.close_price end end end
                       else case when e.trade_plan_version in ('CANDLE_RR_V1','CANDLE_RR_V2','CANDLE_RR_V3','CANDLE_RR_V4','CANDLE_RR_V5')
                           and e.trade_actionable is distinct from false and e.reward_risk_ratio is not null and e.stop_loss_price is not null and e.profit_target_price is not null then
                           case coalesce(e.lifecycle_status, 'DETECTED') when 'CONFIRMED' then e.profit_target_price
                           when 'DETECTED' then case when e.trade_fill_status = 'AWAITING_FILL' then null else recent.close_price end
                           when 'INVALIDATED' then coalesce(e.trade_resolution_price,e.stop_loss_price)
                           when 'EXPIRED' then e.resolution_close_price end end end as exit
            ) prices
            where
            """ + where;
    }

    /** One part of the archive union: its select and the values for its placeholders. */
    private record Branch(String sql, List<Object> params) { }

    private Branch activityRows(String kind, String prefix, String actor, long userId, Long stockId, SignalArchiveFilter filter) {
        String where = "s.user_id=? and d.deleted_at is null and (cast(? as bigint) is null or s.stock_asset_id=?) and "
                + tickerMatch("a.ticker_symbol", filter)
                + " and exists(select 1 from watchlists w where w.id=" + filter.watchlistId() + " and w.user_id=s.user_id) and "
                + WatchlistSignalScope.matches(kind,"d.id","s.stock_asset_id","'"+kind+"'","'DAILY'","'ANY'",filter.watchlistId().toString());
        if (filter.state().equals("unread")) where += " and d.read_at is null";
        if (filter.state().equals("active")) where += " and false";
        return new Branch("select '"+kind+"'::text kind,d.id,a.ticker_symbol ticker,a.company_name company,null::text interval,null::integer confidence,'RECORDED'::text status,"
                + "d.created_at::timestamp received,d.read_at::timestamp,t.transaction_type title,t."+actor+" actor,t.transaction_date,null::float8 trade_return"
                + " from "+prefix+"_deliveries d join "+prefix+"_subscriptions s on s.id=d.subscription_id join "+prefix+"s t on t.id=d.trade_id join stock_assets a on a.id=s.stock_asset_id where "+where,
                java.util.Arrays.asList(userId, stockId, stockId, filter.ticker()));
    }

    /** Triggers of alerts set on chart drawings and indicators; they are finished events, never active. */
    private Branch chartRows(long userId, SignalArchiveFilter filter) {
        String where = "c.user_id=? and e.deleted_at is null and " + tickerMatch("c.symbol", filter);
        if (filter.state().equals("unread")) where += " and e.read_at is null";
        if (filter.state().equals("active")) where += " and false";
        return new Branch("select 'CHART'::text kind,e.id,c.symbol ticker,s.company_name company,c.interval,null::integer confidence,"
                + "'TRIGGERED'::text status,e.created_at received,e.read_at,c.label title,c.condition actor,"
                + "(to_timestamp(e.candle_timestamp) at time zone 'UTC')::date transaction_date,null::float8 trade_return"
                + " from chart_alert_events e join chart_alerts c on c.id=e.alert_id"
                + " left join stock_assets s on upper(s.ticker_symbol)=c.symbol where " + where,
                java.util.Arrays.asList(userId, filter.ticker()));
    }

    /**
     * Changes of a followed Automated Technical Outlook. They are finished events, never active; the
     * score column holds the new outlook score on the ±100 scale.
     */
    private Branch outlookRows(long userId, SignalArchiveFilter filter) {
        String where = "s.user_id=? and " + tickerMatch("a.ticker_symbol", filter);
        if (filter.watchlistId() != null) {
            where += " and exists(select 1 from watchlists w where w.id=" + filter.watchlistId() + " and w.user_id=s.user_id) and "
                    + WatchlistSignalScope.matches("OUTLOOK", "n.id", "s.stock_asset_id", "'OUTLOOK'", "s.interval", "'ANY'",
                    filter.watchlistId().toString());
        }
        if (filter.state().equals("unread")) where += " and n.read_at is null";
        if (filter.state().equals("active")) where += " and false";
        return new Branch("select 'OUTLOOK'::text kind,n.id,a.ticker_symbol ticker,a.company_name company,s.interval,"
                + "round(n.current_score*100)::integer confidence,'CHANGED'::text status,n.created_at::timestamp received,"
                + "n.read_at::timestamp,n.previous_classification title,n.current_classification actor,"
                + "(to_timestamp(n.current_candle_timestamp) at time zone 'UTC')::date transaction_date,null::float8 trade_return"
                + " from technical_outlook_notifications n join technical_outlook_subscriptions s on s.id=n.subscription_id"
                + " join stock_assets a on a.id=s.stock_asset_id where " + where,
                java.util.Arrays.asList(userId, filter.ticker()));
    }

    /**
     * Custom alert triggers. A trigger is active while its outcome window is still running. In a watchlist
     * only triggers recorded for that list appear.
     */
    private Branch customRows(long userId, Long stockId, SignalArchiveFilter filter) {
        String where = "c.user_id=? and e.deleted_at is null and (cast(? as bigint) is null or e.stock_asset_id=?) and "
                + tickerMatch("a.ticker_symbol", filter);
        List<Object> params = new java.util.ArrayList<>(java.util.Arrays.asList(userId, stockId, stockId, filter.ticker()));
        if (filter.customAlertId() != null) {
            where += " and c.id=?";
            params.add(filter.customAlertId());
        }
        if (filter.watchlistId() != null) {
            where += " and exists(select 1 from watchlist_notification_origins origin where origin.kind='CUSTOM'"
                    + " and origin.notification_id=e.id and origin.watchlist_id=" + filter.watchlistId() + ")";
        }
        switch (filter.state()) {
            case "unread" -> where += " and e.read_at is null";
            case "active" -> where += " and e.outcome_status='PENDING'";
            case "completed" -> where += " and e.outcome_status<>'PENDING'";
            default -> { }
        }
        return new Branch("select 'CUSTOM'::text kind,e.id,a.ticker_symbol ticker,a.company_name company,c.interval,null::integer confidence,"
                + "e.kind status,e.created_at::timestamp received,e.read_at::timestamp,c.name title,"
                + "(e.matched_conditions||'/'||e.total_conditions) actor,"
                + "(to_timestamp(e.candle_timestamp) at time zone 'UTC')::date transaction_date,e.outcome_return trade_return"
                + " from custom_alert_events e join custom_alerts c on c.id=e.alert_id join stock_assets a on a.id=e.stock_asset_id"
                + " where " + where, params);
    }

    private Result mixedPage(String technicalWhere, List<String> others, long userId, Long stockId, String sort,
                             boolean ascending, int requestedPage, int size, SignalArchiveFilter filter) {
        boolean outcomes = sort.equals("trade-return");
        List<Branch> branches = new java.util.ArrayList<>();
        List<Branch> countBranches = new java.util.ArrayList<>();
        if (technicalWhere != null) {
            List<Object> params = java.util.Arrays.asList(userId, stockId, stockId, filter.ticker());
            countBranches.add(new Branch(technicalRows(technicalWhere, false), params));
            if (outcomes) {
                List<Object> withBoundaries = new java.util.ArrayList<>(List.of(
                        completion.firstIncompleteCandleTimestamp(TimeInterval.WEEKLY),
                        completion.firstIncompleteCandleTimestamp(TimeInterval.MONTHLY),
                        completion.firstIncompleteCandleTimestamp(TimeInterval.DAILY)));
                withBoundaries.addAll(params);
                branches.add(new Branch(technicalRows(technicalWhere, true), withBoundaries));
            } else {
                branches.add(new Branch(technicalRows(technicalWhere, false), params));
            }
        }
        for (String kind : others) {
            Branch branch = switch (kind) {
                case "CONGRESS" -> activityRows("CONGRESS", "congressional_trade", "member_name", userId, stockId, filter);
                case "INSIDER" -> activityRows("INSIDER", "insider_trade", "insider_name", userId, stockId, filter);
                case "CHART" -> chartRows(userId, filter);
                case "OUTLOOK" -> outlookRows(userId, filter);
                case "CUSTOM" -> customRows(userId, stockId, filter);
                default -> throw new IllegalStateException("Unknown archive kind " + kind);
            };
            branches.add(branch);
            countBranches.add(branch);
        }
        if (branches.isEmpty()) return new Result(List.of(), 0, 0, 0, List.of());
        long count = jdbc.queryForObject("select count(*) from (" + union(countBranches) + ") archive", Long.class,
                parameters(countBranches).toArray());
        int pages = (int)((count+size-1)/size), page = Math.min(Math.max(0,requestedPage),Math.max(0,pages-1));
        String column = switch(sort) { case "ticker" -> "ticker"; case "interval" -> "interval"; case "confidence" -> "confidence"; case "status" -> "status"; case "trade-return" -> "trade_return"; default -> "received"; };
        List<Object> params = parameters(branches);
        params.add(size); params.add(page*size);
        var rows = jdbc.query("select * from (" + union(branches) + ") archive order by "+column+(ascending?" asc":" desc")+" nulls last,received desc,kind,id desc limit ? offset ?",
                (r,n) -> row(r), params.toArray());
        return new Result(rows.stream().filter(row -> row.kind().equals("TECHNICAL")).map(ArchiveRow::id).toList(),page,pages,count,rows);
    }

    private static String union(List<Branch> branches) {
        return String.join(" union all ", branches.stream().map(Branch::sql).toList());
    }

    private static List<Object> parameters(List<Branch> branches) {
        List<Object> params = new java.util.ArrayList<>();
        branches.forEach(branch -> params.addAll(branch.params()));
        return params;
    }

    private static ArchiveRow row(java.sql.ResultSet r) throws java.sql.SQLException {
        String kind = r.getString("kind");
        long id = r.getLong("id");
        return switch (kind) {
            case "TECHNICAL" -> new ArchiveRow(kind, id, null, null);
            case "CONGRESS", "INSIDER" -> new ArchiveRow(kind, id, new ActivitySignal(kind, id, r.getString("ticker"),
                    r.getString("company"), r.getString("title"), r.getString("actor"),
                    r.getDate("transaction_date").toLocalDate(), r.getTimestamp("received").toLocalDateTime(),
                    r.getTimestamp("read_at") != null), null);
            default -> new ArchiveRow(kind, id, null, new AlertTrigger(kind, id, r.getString("ticker"),
                    r.getString("company"), r.getString("title"), r.getString("actor"),
                    TimeInterval.valueOf(r.getString("interval")), r.getString("status"),
                    r.getDate("transaction_date").toLocalDate(), r.getTimestamp("received").toLocalDateTime(),
                    r.getTimestamp("read_at") != null, r.getObject("trade_return") == null ? null : r.getDouble("trade_return"),
                    r.getObject("confidence") == null ? null : r.getInt("confidence")));
        };
    }

    public record ActivitySignal(String kind,long id,String symbol,String companyName,String transaction,String actor,
                                 java.time.LocalDate transactionDate,java.time.LocalDateTime received,boolean hasBeenRead) {
        public String familyLabel() { return kind.equals("CONGRESS")?"Congressional activity":"Insider activity"; }
        public String detailUrl() { return "/activity-signals/"+(kind.equals("CONGRESS")?"congressional":"insider")+"/"+id; }
        public String key() { return (kind.equals("CONGRESS")?"CONGRESSIONAL":"INSIDER")+":"+id; }
        public String title() { return transaction.replace('_',' ').toLowerCase(java.util.Locale.ROOT); }
    }

    /**
     * A chart alert or custom alert trigger, or an outlook change, in the archive.
     *
     * @param title  the alert's name; for an outlook change, the previous classification
     * @param detail the chart alert's condition, "matched/total" conditions of a custom alert, or the new
     *               outlook classification
     * @param status TRIGGERED, MATCHED_ON_APPLY for a custom alert that already matched when it was applied,
     *               or CHANGED for an outlook change
     * @param score  the new outlook score on the ±100 scale; null for alert triggers
     */
    public record AlertTrigger(String kind, long id, String symbol, String companyName, String title, String detail,
                               TimeInterval interval, String status, java.time.LocalDate candleDate,
                               java.time.LocalDateTime received, boolean hasBeenRead, Double outcomeReturn, Integer score) {
        public boolean custom() { return kind.equals("CUSTOM"); }
        public boolean outlook() { return kind.equals("OUTLOOK"); }
        public String key() { return kind + ":" + id; }
        public String detailUrl() {
            if (outlook()) return "/technical-outlook/changes/" + id;
            return custom() ? "/custom-alerts/triggers/" + id
                    : "/stock/" + org.springframework.web.util.UriUtils.encodePathSegment(symbol, java.nio.charset.StandardCharsets.UTF_8)
                    + "?chartAlert=" + id + "#general";
        }
    }

    /** A row of a mixed archive page; technical rows are loaded by id, the others carry their own view. */
    public record ArchiveRow(String kind, long id, ActivitySignal activity, AlertTrigger trigger) {}
    public record Result(List<Long> ids, int page, int pages, long count,List<ArchiveRow> rows) {
        public Result(List<Long> ids,int page,int pages,long count) { this(ids,page,pages,count,List.of()); }
    }

}
