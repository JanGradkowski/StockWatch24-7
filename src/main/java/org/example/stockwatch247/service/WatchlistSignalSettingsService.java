package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** List defaults and per-member requests; the existing subscriptions are their deduplicated union. */
@Service
public class WatchlistSignalSettingsService {
    @org.springframework.beans.factory.annotation.Autowired private WatchlistAllowedSignals permissions;
    private final JdbcTemplate db;
    public WatchlistSignalSettingsService(JdbcTemplate db) { this.db = db; }
    public record Selection(String type, String interval, String direction, boolean watch, boolean email) {
        public String key() { return type + ":" + interval + ":" + direction; }
    }
    public record SettingsView(List<Selection> selections, boolean mixed, boolean configured, List<String> allowedTypes, Map<String, Long> followCounts) {}
    public record MembershipChanged(User user, long assetId, List<Long> addedLists) {}

    public static List<Selection> empty() {
        List<Selection> values = new ArrayList<>();
        for (String type : List.of("CANDLESTICK", "ELLIOTT_WAVE", "HARMONIC_FORMATION", "OUTLOOK"))
            for (String interval : List.of("DAILY", "WEEKLY", "MONTHLY"))
                for (String direction : type.equals("OUTLOOK") ? List.of("ANY") : List.of("BUY", "SELL"))
                    values.add(new Selection(type, interval, direction, false, false));
        for (String type : List.of("CONGRESS", "INSIDER")) values.add(new Selection(type, "DAILY", "ANY", false, false));
        return List.copyOf(values);
    }

    public static List<Selection> validate(List<Selection> values) {
        if (values == null || values.size() > 23) throw new IllegalArgumentException("Choose valid watchlist settings.");
        Set<String> allowed = new HashSet<>(), seen = new HashSet<>();
        empty().forEach(value -> allowed.add(value.key()));
        for (Selection value : values) {
            if (value == null || !allowed.contains(value.key()) || !seen.add(value.key()))
                throw new IllegalArgumentException("Choose each signal type, direction and interval once.");
        }
        return List.copyOf(values);
    }

    public SettingsView get(User user, long listId) {
        owned(user, listId);
        boolean configured = configured(listId);
        Map<String, Selection> saved = new HashMap<>();
        defaults(listId).forEach(value -> saved.put(value.key(), value));
        Map<String, Long> counts = new HashMap<>();
        db.query("select signal_type,interval,direction,count(*) n from watchlist_member_signals where watchlist_id=? group by signal_type,interval,direction",
                r -> { counts.put(r.getString(1) + ":" + r.getString(2) + ":" + r.getString(3), r.getLong(4)); }, listId);
        long members = db.queryForObject("select count(*) from watchlist_members where watchlist_id=?", Long.class, listId);
        long stocks = db.queryForObject("select count(*) from watchlist_members m join stock_assets a on a.id=m.stock_asset_id where m.watchlist_id=? and a.instrument_type='EQUITY'", Long.class, listId);
        var selections = empty().stream().map(value -> configured
                ? saved.getOrDefault(value.key(), value)
                : new Selection(value.type(), value.interval(), value.direction(), counts.containsKey(value.key()), true)).toList();
        boolean mixed = selections.stream().anyMatch(value -> counts.getOrDefault(value.key(), 0L) != (value.watch() ? (activity(value.type()) ? stocks : members) : 0));
        return new SettingsView(selections, mixed, configured, permissions.allowed(listId), permissions.followCounts(listId));
    }

    public void save(User user, long listId, List<Selection> values) {
        owned(user, listId); validate(values);
        values.stream().filter(Selection::watch).forEach(value -> permissions.requireAllowed(listId, value.type()));
        db.update("delete from watchlist_signal_settings where watchlist_id=?", listId);
        for (Selection value : values) db.update("insert into watchlist_signal_settings values (?,?,?,?,?,?)",
                listId, value.type(), value.interval(), value.direction(), value.watch(), value.email());
        db.update("update watchlists set settings_configured=true where id=?", listId);
    }

    public List<Selection> defaults(long listId) {
        return db.query("select signal_type,interval,direction,watched,email_enabled from watchlist_signal_settings where watchlist_id=?",
                (r,n) -> new Selection(r.getString(1),r.getString(2),r.getString(3),r.getBoolean(4),r.getBoolean(5)), listId);
    }
    public boolean configured(long listId) {
        return Boolean.TRUE.equals(db.queryForObject("select settings_configured from watchlists where id=?", Boolean.class, listId));
    }
    public void applyDefaults(long listId, long assetId, boolean equity) {
        db.update("delete from watchlist_member_signals where watchlist_id=? and stock_asset_id=?", listId, assetId);
        for (Selection value : defaults(listId)) if (value.watch() && permissions.allows(listId, value.type()) && (equity || !activity(value.type()))) add(listId, assetId, value);
    }
    public void add(long listId, long assetId, Selection value) {
        permissions.requireAllowed(listId, value.type());
        db.update("insert into watchlist_member_signals values (?,?,?,?,?) on conflict do nothing", listId, assetId, value.type(), value.interval(), value.direction());
    }
    public List<Selection> requested(User user, long assetId) {
        return db.query("select distinct s.signal_type,s.interval,s.direction from watchlist_member_signals s join watchlists w on w.id=s.watchlist_id where w.user_id=? and s.stock_asset_id=?",
                (r,n) -> new Selection(r.getString(1),r.getString(2),r.getString(3),true,false), user.getId(), assetId);
    }
    public List<Selection> active(User user, long assetId) {
        return db.query("""
                select coalesce(pattern_family,'CANDLESTICK'),interval,trade_signal from alert_rules where user_id=? and stock_asset_id=? and is_active and trade_signal in ('BUY','SELL')
                union select 'OUTLOOK',interval,'ANY' from technical_outlook_subscriptions where user_id=? and stock_asset_id=? and is_active
                union select 'CONGRESS','DAILY','ANY' from congressional_trade_subscriptions where user_id=? and stock_asset_id=? and active
                union select 'INSIDER','DAILY','ANY' from insider_trade_subscriptions where user_id=? and stock_asset_id=? and active
                """, (r,n) -> new Selection(r.getString(1),r.getString(2),r.getString(3),true,false),
                user.getId(),assetId,user.getId(),assetId,user.getId(),assetId,user.getId(),assetId);
    }
    public void record(User user, long assetId, List<Long> listIds, Selection value) {
        if (!value.watch()) {
            db.update("delete from watchlist_member_signals s using watchlists w where s.watchlist_id=w.id and w.user_id=? and s.stock_asset_id=? and s.signal_type=? and s.interval=? and s.direction=?",
                    user.getId(), assetId, value.type(), value.interval(), value.direction());
        } else {
            var memberships = db.queryForList("select m.watchlist_id from watchlist_members m join watchlists w on w.id=m.watchlist_id where w.user_id=? and m.stock_asset_id=?", Long.class,user.getId(),assetId);
            memberships.stream().filter(id -> listIds == null || listIds.isEmpty() || listIds.contains(id)).forEach(id -> add(id,assetId,value));
        }
    }
    public void record(User user, String symbol, List<Long> listIds, String type, String interval, String direction, boolean watched) {
        Long assetId=db.queryForObject("select id from stock_assets where upper(ticker_symbol)=upper(?)",Long.class,symbol);
        record(user,assetId,listIds,new Selection(type,interval,direction,watched,false));
    }
    public static boolean activity(String type) { return type.equals("CONGRESS") || type.equals("INSIDER"); }
    private void owned(User user, long listId) {
        if (!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from watchlists where id=? and user_id=?)",Boolean.class,listId,user.getId())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Watchlist not found.");
    }
}
