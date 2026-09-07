package org.example.stockwatch247.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class VirtualTradeArchiveQuery {
    private final JdbcTemplate jdbc;
    public VirtualTradeArchiveQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Result page(long userId, String sort, String direction, int requestedPage) {
        long count = jdbc.queryForObject("select count(*) from virtual_trades where user_id = ? and deleted_at is null", Long.class, userId);
        int pages = (int) ((count + 49) / 50);
        int page = Math.min(Math.max(0, requestedPage), Math.max(0, pages - 1));
        String order = switch (sort) {
            case "ticker" -> "lower(a.ticker_symbol)";
            case "side" -> "t.side";
            case "status" -> "t.status";
            case "return" -> "coalesce((case when t.status = 'CLOSED' then t.exit_price else coalesce(nullif(greatest(c.close_price, 0), 0), t.entry_price) end - t.entry_price) / nullif(t.entry_price, 0), 0) * case t.side when 'SELL' then -1 else 1 end";
            default -> "t.entry_at";
        };
        String dir = "asc".equals(direction) ? " asc" : " desc";
        var ids = jdbc.query("""
            select t.id from virtual_trades t join stock_assets a on a.id = t.stock_asset_id
            left join lateral (select close_price from candles where symbol = a.ticker_symbol and time_interval = '1d'
                order by timestamp desc limit 1) c on true
            where t.user_id = ? and t.deleted_at is null order by
            """ + order + dir + ", t.entry_at" + dir + ", t.id desc limit 50 offset ?",
                (rs, row) -> rs.getLong(1), userId, page * 50);
        return new Result(ids, page, pages, count);
    }
    public record Result(List<Long> ids, int page, int pages, long count) { }
}
