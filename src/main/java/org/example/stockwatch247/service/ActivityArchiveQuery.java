package org.example.stockwatch247.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
                   t.transaction_date, d.created_at as detected_at
            from %4$s_deliveries d
            join %4$s_subscriptions s on s.id = d.subscription_id
            join %4$ss t on t.id = d.trade_id
            join stock_assets a on a.id = t.stock_asset_id
            where s.user_id = ? and d.deleted_at is null
            """;
    private static final String ARCHIVE = "("
            + ROWS.formatted(CONGRESSIONAL, "member_name", "Congressional", "congressional_trade")
            + " union all "
            + ROWS.formatted(INSIDER, "insider_name", "Corporate insider", "insider_trade")
            + ") archive";

    private final JdbcTemplate jdbc;

    public ActivityArchiveQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page page(long userId, String sort, boolean ascending, int requestedPage, int size) {
        Long counted = jdbc.queryForObject("select count(*) from " + ARCHIVE, Long.class, userId, userId);
        long count = counted == null ? 0 : counted;
        int pages = (int) ((count + size - 1) / size);
        int page = Math.min(Math.max(0, requestedPage), Math.max(0, pages - 1));
        List<Row> rows = jdbc.query("select kind, id from " + ARCHIVE + " order by " + order(sort, ascending)
                        + " limit ? offset ?",
                (result, index) -> new Row(result.getString("kind"), result.getLong("id")),
                userId, userId, size, (long) page * size);
        return new Page(rows, page, pages, count);
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
}
