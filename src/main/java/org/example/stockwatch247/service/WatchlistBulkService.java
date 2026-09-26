package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import static org.example.stockwatch247.service.WatchlistSignalSettingsService.*;

/** Changes only selected members of an owned list, then reconciles shared subscriptions. */
@Service
public class WatchlistBulkService {
    private final WatchlistService lists;
    private final WatchlistSignalSettingsService settings;
    private final WatchlistMonitoringService monitoring;
    private final WatchlistAllowedSignals permissions;
    private final JdbcTemplate db;

    public WatchlistBulkService(WatchlistService lists, WatchlistSignalSettingsService settings,
            WatchlistMonitoringService monitoring, WatchlistAllowedSignals permissions, JdbcTemplate db) {
        this.lists = lists; this.settings = settings; this.monitoring = monitoring;
        this.permissions = permissions; this.db = db;
    }

    private Map<String, Member> members(User user, long id, List<String> symbols) {
        lists.requireOwned(user, id);
        if (symbols == null || symbols.isEmpty()) throw new IllegalArgumentException("Select at least one instrument.");
        var selected = new LinkedHashSet<String>();
        symbols.forEach(symbol -> selected.add(SecurityInputValidator.requireMarketSymbol(symbol)));
        Map<String, Member> members = new LinkedHashMap<>();
        db.query("select a.ticker_symbol,a.id,a.instrument_type from watchlist_members m join stock_assets a on a.id=m.stock_asset_id where m.watchlist_id=?",
                r -> { if (selected.contains(r.getString(1))) members.put(r.getString(1), new Member(r.getLong(2), "EQUITY".equals(r.getString(3)))); }, id);
        if (members.size() != selected.size()) throw new IllegalArgumentException("Some selected instruments are no longer in this watchlist. Refresh and select them again.");
        return members;
    }

    @Transactional(readOnly = true)
    public View preview(User user, long id, List<String> symbols) {
        var members = members(user, id, symbols);
        var ids = new HashSet<Long>(); members.values().forEach(member -> ids.add(member.id()));
        Map<String, Long> counts = new HashMap<>();
        db.query("select stock_asset_id,signal_type,interval,direction from watchlist_member_signals where watchlist_id=?",
                r -> { if (ids.contains(r.getLong(1))) counts.merge(r.getString(2) + ":" + r.getString(3) + ":" + r.getString(4), 1L, Long::sum); }, id);
        long equities = members.values().stream().filter(Member::equity).count();
        var mixed = new ArrayList<String>();
        var selections = empty().stream().map(value -> {
            long eligible = activity(value.type()) ? equities : members.size();
            long count = counts.getOrDefault(value.key(), 0L);
            if (count > 0 && count < eligible) mixed.add(value.key());
            return new Selection(value.type(), value.interval(), value.direction(), count > 0 && count == eligible, false);
        }).toList();
        return new View(selections, mixed, permissions.allowed(id), members.size() - equities);
    }

    @Transactional
    public void apply(User user, long id, List<String> symbols, List<Selection> changes) {
        lists.lock(user);
        var members = members(user, id, symbols);
        validate(changes);
        changes.stream().filter(Selection::watch).forEach(value -> permissions.requireAllowed(id, value.type()));
        for (Member member : members.values()) {
            for (Selection value : changes) {
                if (value.watch()) {
                    if (member.equity() || !activity(value.type())) settings.add(id, member.id(), value);
                } else {
                    db.update("delete from watchlist_member_signals where watchlist_id=? and stock_asset_id=? and signal_type=? and interval=? and direction=?",
                            id, member.id(), value.type(), value.interval(), value.direction());
                }
            }
            monitoring.synchronize(user, member.id());
        }
    }

    @Transactional
    public void remove(User user, long id, List<String> symbols) {
        lists.lock(user);
        var members = members(user, id, symbols);
        members.keySet().forEach(symbol -> lists.remove(user, id, symbol));
    }

    private record Member(long id, boolean equity) {}
    public record View(List<Selection> selections, List<String> mixedKeys, List<String> allowedTypes, long nonStocks) {}
}
