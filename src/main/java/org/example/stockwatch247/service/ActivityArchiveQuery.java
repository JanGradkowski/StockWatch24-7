package org.example.stockwatch247.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orders and pages a user's congressional and insider notifications in the database, so the archive
 * page loads only the rows it shows. The ordering matches the archive's columns: the chosen column,
 * then newest detection, source and id.
 */
@Service
public class ActivityArchiveQuery {
    public static final String CONGRESSIONAL = "CONGRESSIONAL";
    public static final String INSIDER = "INSIDER";

    private static final String ROWS = """
            select '%1$s' as kind, d.id, t.ticker_symbol as ticker, a.company_name as company,
                   t.%2$s as actor, t.transaction_type as transaction, '%3$s' as source_label,
                   t.transaction_date, d.created_at as detected_at, d.read_at
            from %4$s_deliveries d
            join %4$s_subscriptions s on s.id = d.subscription_id
            join %4$ss t on t.id = d.trade_id
            join stock_assets a on a.id = t.stock_asset_id
            where s.user_id = ? and d.deleted_at is null
            """;

    private final JdbcTemplate jdbc;

    public ActivityArchiveQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What the archive shows: a tab (all, unread, purchase, sale), a ticker matched anywhere in the symbol, and a
     * source (all, insider, congress).
     */
    public record Filter(String state, String ticker, String source) {
        public Filter {
            state = state == null ? "all" : state.toLowerCase(Locale.ROOT);
            if (!List.of("all", "unread", "purchase", "sale").contains(state)) state = "all";
            ticker = ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
            if (ticker.length() > 32) ticker = ticker.substring(0, 32);
            source = source == null ? "all" : source.toLowerCase(Locale.ROOT);
            if (!List.of("all", "insider", "congress").contains(source)) source = "all";
        }

        public Filter(String state, String ticker) { this(state, ticker, "all"); }

        public static Filter all() { return new Filter("all", ""); }

        public Filter withState(String other) { return new Filter(other, ticker, source); }

        public Filter withSource(String other) { return new Filter(state, ticker, other); }

        public boolean applied() { return !"all".equals(state) || !ticker.isEmpty() || !"all".equals(source); }

        private boolean shows(String kind) {
            return "all".equals(source) || (INSIDER.equals(kind) ? "insider" : "congress").equals(source);
        }
    }

    /** The union of both sources for a filter, and its placeholders (the user, then the ticker when set). */
    private static String archive(Filter filter) {
        String extra = switch (filter.state()) {
            case "unread" -> " and d.read_at is null";
            case "purchase" -> " and t.transaction_type = 'PURCHASE'";
            case "sale" -> " and t.transaction_type <> 'PURCHASE'";
            default -> "";
        };
        if (!filter.ticker().isEmpty()) extra += " and position(? in upper(t.ticker_symbol)) > 0";
        List<String> parts = new ArrayList<>();
        if (filter.shows(CONGRESSIONAL)) parts.add(ROWS.formatted(CONGRESSIONAL, "member_name", "Congressional", "congressional_trade") + extra);
        if (filter.shows(INSIDER)) parts.add(ROWS.formatted(INSIDER, "insider_name", "Corporate insider", "insider_trade") + extra);
        return "(" + String.join(" union all ", parts) + ") archive";
    }

    private static Object[] parameters(long userId, Filter filter, Object... more) {
        List<Object> params = new ArrayList<>();
        int sources = "all".equals(filter.source()) ? 2 : 1;
        for (int source = 0; source < sources; source++) {
            params.add(userId);
            if (!filter.ticker().isEmpty()) params.add(filter.ticker());
        }
        params.addAll(List.of(more));
        return params.toArray();
    }

    public Page page(long userId, String sort, boolean ascending, int requestedPage, int size) {
        return page(userId, sort, ascending, requestedPage, size, Filter.all());
    }

    public Page page(long userId, String sort, boolean ascending, int requestedPage, int size, Filter filter) {
        Long counted = jdbc.queryForObject("select count(*) from " + archive(filter), Long.class, parameters(userId, filter));
        long count = counted == null ? 0 : counted;
        int pages = (int) ((count + size - 1) / size);
        int page = Math.min(Math.max(0, requestedPage), Math.max(0, pages - 1));
        List<Row> rows = jdbc.query("select kind, id from " + archive(filter) + " order by " + order(sort, ascending)
                        + " limit ? offset ?",
                (result, index) -> new Row(result.getString("kind"), result.getLong("id")),
                parameters(userId, filter, size, (long) page * size));
        return new Page(rows, page, pages, count);
    }

    /**
     * The archive's figures for a ticker filter on every tab: how many filings, unread, purchases and from insiders,
     * and how many arrived each day.
     */
    public Summary summary(long userId, Filter filter) {
        Filter all = filter.withState("all");
        Summary counts = jdbc.queryForObject("select count(*) total, count(*) filter (where read_at is null) unread,"
                        + " count(*) filter (where transaction = 'PURCHASE') purchases, 0 insiders from " + archive(all),
                (result, index) -> new Summary(result.getLong("total"), result.getLong("unread"), result.getLong("purchases"),
                        0, 0, List.of()),
                parameters(userId, all));
        // By source whatever source is chosen, for the source menu.
        Filter anySource = all.withSource("all");
        long[] sources = jdbc.queryForObject("select count(*) filter (where kind = '" + INSIDER + "') insiders,"
                        + " count(*) filter (where kind = '" + CONGRESSIONAL + "') congress from " + archive(anySource),
                (result, index) -> new long[] {result.getLong("insiders"), result.getLong("congress")}, parameters(userId, anySource));
        List<SignalArchiveQuery.Day> days = jdbc.query("select cast(detected_at as date) as signal_day, count(*) as signals from "
                        + archive(all) + " group by 1 order by 1",
                (result, index) -> new SignalArchiveQuery.Day(result.getDate("signal_day").toLocalDate(), result.getLong("signals")),
                parameters(userId, all));
        return new Summary(counts.total(), counts.unread(), counts.purchases(), sources[0], sources[1], days);
    }

    /** Text columns compare without regard to case; descending puts missing values first, like a reversed list sort. */
    static String order(String sort, boolean ascending) {
        String direction = ascending ? " asc nulls last" : " desc nulls first";
        String primary = switch (sort == null ? "" : sort.toLowerCase(Locale.ROOT)) {
            case "ticker" -> text("ticker") + direction;
            case "company" -> text("company") + direction + ", " + text("ticker") + direction;
            case "transaction" -> text("transaction") + direction;
            case "type" -> text("source_label") + direction;
            case "actor" -> text("actor") + direction;
            default -> "transaction_date" + direction;
        };
        return primary + ", detected_at desc nulls last, kind asc, id desc";
    }

    private static String text(String column) {
        return "lower(" + column + ") collate \"C\"";
    }

    public record Row(String kind, long id) { }

    public record Page(List<Row> rows, int page, int pages, long count) { }

    /**
     * Filings in the archive for a ticker and source: all, unread and purchases, and per day; and by source for the
     * ticker alone.
     */
    public record Summary(long total, long unread, long purchases, long insiders, long congress, List<SignalArchiveQuery.Day> days) {
        public long sales() { return total - purchases; }

        public static Summary empty() { return new Summary(0, 0, 0, 0, 0, List.of()); }
    }
}
