package org.example.stockwatch247.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Looks up company names for listings stored with their ticker as the name. Twelve Data candle syncs used to
 * overwrite names with the ticker, so pages and emails showed "AAPL AAPL". Yahoo Finance answers these lookups
 * without spending the shared Twelve Data allowance; each listing is tried at most once a day, stocks on
 * watchlists first.
 */
@Component
public class StockAssetNameRepair {
    private static final Logger log = LoggerFactory.getLogger(StockAssetNameRepair.class);
    private final JdbcTemplate jdbc;
    private final YahooFinanceService yahoo;
    private final boolean enabled;
    private final int batchSize;

    public StockAssetNameRepair(JdbcTemplate jdbc,
                                YahooFinanceService yahoo,
                                @Value("${market-data.name-repair.enabled:true}") boolean enabled,
                                @Value("${market-data.name-repair.batch-size:10}") int batchSize) {
        this.jdbc = jdbc;
        this.yahoo = yahoo;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(100, batchSize));
    }

    @Scheduled(fixedDelayString = "${market-data.name-repair.delay-ms:900000}",
            initialDelayString = "${market-data.name-repair.initial-delay-ms:300000}")
    public int repairBatch() {
        if (!enabled) return 0;
        int named = 0;
        for (String symbol : unnamed(batchSize)) {
            try {
                if (org.example.stockwatch247.model.StockAsset.hasRealName(yahoo.refreshStockAssetMetadata(symbol))) named++;
            } catch (YahooFinanceService.ProviderUnavailableException e) {
                log.debug("Company name lookups paused: {}", e.getMessage());
                break;
            } catch (RuntimeException e) {
                log.debug("No company name found for {}: {}", symbol, e.getMessage());
            } finally {
                jdbc.update("update stock_assets set metadata_refreshed_at = now() where ticker_symbol = ?", symbol);
            }
        }
        if (named > 0) log.info("Restored {} company names", named);
        return named;
    }

    List<String> unnamed(int limit) {
        return jdbc.queryForList("""
                select a.ticker_symbol from stock_assets a
                where upper(a.company_name) = upper(a.ticker_symbol)
                  and a.instrument_type <> 'INDEX'
                  and upper(a.exchange) <> 'UNKNOWN'
                  and (a.metadata_refreshed_at is null or a.metadata_refreshed_at < now() - interval '1 day')
                order by exists (select 1 from watchlist_members m where m.stock_asset_id = a.id) desc, a.id
                limit ?
                """, String.class, limit);
    }
}
