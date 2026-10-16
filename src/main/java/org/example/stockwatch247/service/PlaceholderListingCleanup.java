package org.example.stockwatch247.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes placeholder listings created for tickers that never resolved and that nothing uses.
 * Every reference is checked explicitly because deleting a listing cascades to its dependents.
 */
@Component
public class PlaceholderListingCleanup {
    private static final Logger log = LoggerFactory.getLogger(PlaceholderListingCleanup.class);
    private final JdbcTemplate jdbc;

    public PlaceholderListingCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${market-data.placeholder-cleanup-cron:0 40 3 * * *}")
    public int removeUnusedPlaceholders() {
        int removed = jdbc.update("""
                delete from stock_assets a
                where upper(a.exchange) = 'UNKNOWN'
                  and a.market_data_verified_at is null
                  and not exists (select 1 from candles c where c.symbol = a.ticker_symbol)
                  and not exists (select 1 from alert_rules r where r.stock_asset_id = a.id)
                  and not exists (select 1 from technical_outlook_subscriptions s where s.stock_asset_id = a.id)
                  and not exists (select 1 from congressional_trades t where t.stock_asset_id = a.id)
                  and not exists (select 1 from congressional_trade_subscriptions s where s.stock_asset_id = a.id)
                  and not exists (select 1 from insider_trades t where t.stock_asset_id = a.id)
                  and not exists (select 1 from insider_trade_subscriptions s where s.stock_asset_id = a.id)
                  and not exists (select 1 from insider_activity_check_jobs j where j.stock_asset_id = a.id)
                  and not exists (select 1 from virtual_trades v where v.stock_asset_id = a.id)
                  and not exists (select 1 from watchlist_members m where m.stock_asset_id = a.id)
                  and not exists (select 1 from alert_check_jobs j where j.symbol = a.ticker_symbol
                                  and j.status in ('PENDING', 'PROCESSING'))
                """);
        if (removed > 0) log.info("Removed {} unused placeholder listings", removed);
        return removed;
    }
}
