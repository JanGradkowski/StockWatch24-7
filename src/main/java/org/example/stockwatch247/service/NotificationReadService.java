package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bulk read state uses the same origins/fallback as watchlist unread counts. */
@Service
public class NotificationReadService {
    public enum Scope { ALL, ACTIVITY }
    private final JdbcTemplate db;
    private final WatchlistService watchlists;

    public NotificationReadService(JdbcTemplate db, WatchlistService watchlists) {
        this.db = db;
        this.watchlists = watchlists;
    }

    @Transactional
    public int markAllRead(User user, Scope scope, Long watchlistId) {
        if (watchlistId != null) watchlists.requireOwned(user, watchlistId);
        int updated = 0;
        if (scope == Scope.ALL) {
            updated += mark(user, watchlistId, "TECHNICAL", "alert_events", "alert_rules", "alert_rule_id",
                    "coalesce(s.pattern_family,'CANDLESTICK')", "s.interval", "e.trade_signal", true);
            updated += mark(user, watchlistId, "OUTLOOK", "technical_outlook_notifications", "technical_outlook_subscriptions",
                    "subscription_id", "'OUTLOOK'", "s.interval", "'ANY'", false);
        }
        updated += mark(user, watchlistId, "CONGRESS", "congressional_trade_deliveries", "congressional_trade_subscriptions",
                "subscription_id", "'CONGRESS'", "'DAILY'", "'ANY'", true);
        updated += mark(user, watchlistId, "INSIDER", "insider_trade_deliveries", "insider_trade_subscriptions",
                "subscription_id", "'INSIDER'", "'DAILY'", "'ANY'", true);
        return updated;
    }

    private int mark(User user, Long list, String kind, String notifications, String subscriptions,
                     String foreignKey, String type, String interval, String direction, boolean softDeleted) {
        String sql = "update " + notifications + " e set read_at=current_timestamp from " + subscriptions
                + " s where s.id=e." + foreignKey + " and s.user_id=? and e.read_at is null"
                + (softDeleted ? " and e.deleted_at is null" : "");
        if (list != null) sql += " and " + WatchlistSignalScope.matches(kind, "e.id", "s.stock_asset_id",
                type, interval, direction, list.toString());
        return db.update(sql, user.getId());
    }
}
