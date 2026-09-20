package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** A matching list can opt in once; global account/email delivery switches still apply. */
@Service
public class WatchlistEmailPolicy {
    private final JdbcTemplate db;
    public WatchlistEmailPolicy(JdbcTemplate db) { this.db=db; }
    public boolean allows(AlertRule rule, TradeSignal direction) {
        return allows(rule.getUser().getId(),rule.getStockAsset().getTickerSymbol(),
                rule.getPatternFamily()==null?"CANDLESTICK":rule.getPatternFamily().name(),
                rule.getInterval().name(),direction.name());
    }
    public boolean allows(Long userId,String symbol,String type,String interval,String direction) {
        if(userId==null) return true;
        return Boolean.TRUE.equals(db.queryForObject("""
                with membership as (
                  select w.id,w.settings_configured,m.stock_asset_id from watchlists w
                  join watchlist_members m on m.watchlist_id=w.id
                  join stock_assets a on a.id=m.stock_asset_id where w.user_id=? and upper(a.ticker_symbol)=upper(?)
                )
                select not exists(select 1 from membership) or exists(
                  select 1 from membership m join watchlist_member_signals s on s.watchlist_id=m.id and s.stock_asset_id=m.stock_asset_id
                  left join watchlist_signal_settings p on p.watchlist_id=s.watchlist_id and p.signal_type=s.signal_type and p.interval=s.interval and p.direction=s.direction
                  where s.signal_type=? and s.interval=? and s.direction=? and (not m.settings_configured or p.email_enabled)
                )
                """,Boolean.class,userId,symbol,type,interval,direction));
    }
    public boolean allowsQueuedEvent(long eventId) {
        var events=db.query("""
                select r.user_id,a.ticker_symbol,coalesce(r.pattern_family,'CANDLESTICK'),r.interval,e.trade_signal
                from alert_events e join alert_rules r on r.id=e.alert_rule_id join stock_assets a on a.id=r.stock_asset_id where e.id=?
                """,(r,n)->allows(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),eventId);
        return !events.isEmpty() && events.getFirst();
    }
}
