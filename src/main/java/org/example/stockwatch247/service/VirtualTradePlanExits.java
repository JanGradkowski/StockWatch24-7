package org.example.stockwatch247.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Closes open demo trades whose stop loss or target a completed candle has reached. Candles arrive with the
 * market data syncs, so a check every quarter hour closes a trade soon after its candle completes. Each trade
 * closes in its own transaction; a trade that is closed or deleted meanwhile is left alone.
 */
@Component
public class VirtualTradePlanExits {
    private static final Logger log = LoggerFactory.getLogger(VirtualTradePlanExits.class);
    private final JdbcTemplate jdbc;
    private final VirtualTradeService trades;
    private final boolean enabled;

    public VirtualTradePlanExits(JdbcTemplate jdbc, VirtualTradeService trades,
                                 @Value("${virtual-trades.plan-exits.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.trades = trades;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${virtual-trades.plan-exits.delay-ms:900000}",
            initialDelayString = "${virtual-trades.plan-exits.initial-delay-ms:120000}")
    public int closeReachedPlans() {
        if (!enabled) return 0;
        List<Long> open = jdbc.queryForList("""
                select id from virtual_trades
                where status = 'TRACKING' and deleted_at is null and (stop_price is not null or target_price is not null)
                order by id limit 500
                """, Long.class);
        int closed = 0;
        for (Long id : open) {
            try {
                if (trades.closeAtPlanLevel(id)) closed++;
            } catch (RuntimeException failure) {
                log.warn("Demo trade {} plan check deferred: {}", id, failure.getClass().getSimpleName());
            }
        }
        if (closed > 0) log.info("Closed {} demo trades at their stop or target", closed);
        return closed;
    }
}
