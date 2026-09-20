package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Permissions are independent of the defaults used when following instruments. */
@Service
public class WatchlistAllowedSignals {
    public static final List<String> TYPES = List.of("CANDLESTICK", "ELLIOTT_WAVE", "HARMONIC_FORMATION", "OUTLOOK", "CONGRESS", "INSIDER");
    private final JdbcTemplate db;
    private final ApplicationEventPublisher events;
    public WatchlistAllowedSignals(JdbcTemplate db, ApplicationEventPublisher events) { this.db = db; this.events = events; }

    public List<String> allowed(long id) {
        var disabled = db.queryForList("select signal_type from watchlist_disallowed_signals where watchlist_id=?", String.class, id);
        return TYPES.stream().filter(type -> !disabled.contains(type)).toList();
    }
    public boolean allows(long id, String type) { return allowed(id).contains(type); }
    public void requireAllowed(long id, String type) {
        if (!allows(id, type)) throw new IllegalArgumentException("This watchlist does not allow " + type.replace('_', ' ').toLowerCase(Locale.ROOT) + " signals. Choose another list or edit its allowed signal types.");
    }
    public Map<String, Long> followCounts(long id) {
        Map<String, Long> counts = new LinkedHashMap<>();
        db.query("select signal_type,count(*) from watchlist_member_signals where watchlist_id=? group by signal_type",
                r -> { counts.put(r.getString(1), r.getLong(2)); }, id);
        return counts;
    }
    @org.springframework.transaction.annotation.Transactional
    public void save(User user, long id, List<String> allowed) {
        if (allowed == null) return; // Older callers preserve the current permissions.
        if (allowed.size() > TYPES.size() || allowed.stream().anyMatch(Objects::isNull)
                || new HashSet<>(allowed).size() != allowed.size() || !TYPES.containsAll(allowed))
            throw new IllegalArgumentException("Choose valid allowed signal types.");
        db.queryForObject("select id from users where id=? for update",Long.class,user.getId());
        if (!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from watchlists where id=? and user_id=?)",Boolean.class,id,user.getId())))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Watchlist not found.");
        if (new HashSet<>(allowed(id)).equals(new HashSet<>(allowed))) return;
        var disabled = TYPES.stream().filter(type -> !allowed.contains(type)).toList();
        // Snapshot legacy history before removing its fallback follows, for every matching list.
        preserveLegacyOrigins(user);
        db.update("delete from watchlist_disallowed_signals where watchlist_id=?", id);
        for (String type : disabled) db.update("insert into watchlist_disallowed_signals values (?,?)", id, type);
        var assets = db.queryForList("delete from watchlist_member_signals s using watchlist_disallowed_signals d where s.watchlist_id=? and d.watchlist_id=s.watchlist_id and d.signal_type=s.signal_type returning s.stock_asset_id", Long.class, id);
        db.update("update watchlist_signal_settings s set watched=false from watchlist_disallowed_signals d where s.watchlist_id=? and d.watchlist_id=s.watchlist_id and d.signal_type=s.signal_type", id);
        new HashSet<>(assets).forEach(asset -> events.publishEvent(new WatchlistSignalSettingsService.MembershipChanged(user, asset, List.of())));
    }
    private void preserveLegacyOrigins(User user) {
        for (String kind : List.of("TECHNICAL", "OUTLOOK", "CONGRESS", "INSIDER")) {
            String tables, type, interval, direction;
            if (kind.equals("TECHNICAL")) {
                tables = "alert_events e join alert_rules s on s.id=e.alert_rule_id";
                type = "coalesce(s.pattern_family,'CANDLESTICK')"; interval = "s.interval"; direction = "e.trade_signal";
            } else {
                String prefix = switch (kind) { case "OUTLOOK" -> "technical_outlook"; case "CONGRESS" -> "congressional_trade"; default -> "insider_trade"; };
                tables = prefix + (kind.equals("OUTLOOK") ? "_notifications" : "_deliveries") + " e join " + prefix + "_subscriptions s on s.id=e.subscription_id";
                type = "'" + kind + "'"; interval = kind.equals("OUTLOOK") ? "s.interval" : "'DAILY'"; direction = "'ANY'";
            }
            db.update("insert into watchlist_notification_origins(user_id,kind,notification_id,watchlist_id,name) "
                    + "select s.user_id,?,e.id,w.id,w.name from " + tables
                    + " join watchlist_member_signals f on f.stock_asset_id=s.stock_asset_id and f.signal_type=" + type + " and f.interval=" + interval + " and f.direction=" + direction
                    + " join watchlists w on w.id=f.watchlist_id and w.user_id=s.user_id where s.user_id=?"
                    + " and not exists(select 1 from watchlist_notification_origins o where o.kind=? and o.notification_id=e.id)", kind, user.getId(), kind);
        }
    }
}
