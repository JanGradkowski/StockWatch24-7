package org.example.stockwatch247.service;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** Named lists organize shared per-account monitoring; they never create scan jobs. */
@Service
public class WatchlistService {
    private static final String UNREAD_EVENTS = """
            select e.id from alert_events e join alert_rules r on r.id=e.alert_rule_id
              where r.user_id=w.user_id and r.stock_asset_id=m.stock_asset_id and e.read_at is null and e.deleted_at is null and %s
            union all select n.id from technical_outlook_notifications n join technical_outlook_subscriptions s on s.id=n.subscription_id
              where s.user_id=w.user_id and s.stock_asset_id=m.stock_asset_id and n.read_at is null and %s
            union all select d.id from congressional_trade_deliveries d join congressional_trade_subscriptions s on s.id=d.subscription_id
              where s.user_id=w.user_id and s.stock_asset_id=m.stock_asset_id and d.read_at is null and d.deleted_at is null and %s
            union all select d.id from insider_trade_deliveries d join insider_trade_subscriptions s on s.id=d.subscription_id
              where s.user_id=w.user_id and s.stock_asset_id=m.stock_asset_id and d.read_at is null and d.deleted_at is null and %s
            """.formatted(
                WatchlistSignalScope.matches("TECHNICAL", "e.id", "r.stock_asset_id", "coalesce(r.pattern_family,'CANDLESTICK')", "r.interval", "e.trade_signal", "w.id"),
                WatchlistSignalScope.matches("OUTLOOK", "n.id", "s.stock_asset_id", "'OUTLOOK'", "s.interval", "'ANY'", "w.id"),
                WatchlistSignalScope.matches("CONGRESS", "d.id", "s.stock_asset_id", "'CONGRESS'", "'DAILY'", "'ANY'", "w.id"),
                WatchlistSignalScope.matches("INSIDER", "d.id", "s.stock_asset_id", "'INSIDER'", "'DAILY'", "'ANY'", "w.id"));
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired private WatchlistAllowedSignals permissions;
    private final JdbcTemplate db;
    private final StockAssetRepository assets;
    private final TwelveDataService market;
    private final AlertRuleService alerts;

    public WatchlistService(JdbcTemplate db, StockAssetRepository assets, TwelveDataService market, AlertRuleService alerts) {
        this.db=db; this.assets=assets; this.market=market; this.alerts=alerts;
    }

    public List<ListView> lists(User user) {
        return db.query("""
            select w.id,w.name,w.description,w.pinned,count(m.stock_asset_id) instruments,
              count(m.stock_asset_id) filter(where exists(select 1 from watchlist_member_signals s where s.watchlist_id=w.id and s.stock_asset_id=m.stock_asset_id)) monitored,
              coalesce(sum(unread.total),0) unread
            from watchlists w left join watchlist_members m on m.watchlist_id=w.id
            left join lateral (
              select count(*) total from (
                %s
              ) events
            ) unread on true
            where w.user_id=? group by w.id order by w.pinned desc,w.updated_at desc,w.id desc
            """.formatted(UNREAD_EVENTS), (r,n)->new ListView(r.getLong("id"),r.getString("name"),r.getString("description"),r.getBoolean("pinned"),
                r.getLong("instruments"),r.getLong("monitored"),r.getLong("unread"),permissions.allowed(r.getLong("id"))), user.getId());
    }

    public List<Long> memberships(User user, String rawSymbol) {
        String symbol=SecurityInputValidator.requireMarketSymbol(rawSymbol);
        return db.queryForList("""
            select w.id from watchlists w join watchlist_members m on m.watchlist_id=w.id
            join stock_assets a on a.id=m.stock_asset_id where w.user_id=? and a.ticker_symbol=? order by w.id
            """,Long.class,user.getId(),symbol);
    }

    public Map<String,String> notificationOrigins(User user) {
        Map<String,String> result=new HashMap<>();
        db.query("""
            select kind,notification_id,string_agg(name || case when watchlist_id is null then ' (deleted)' else '' end, ' · ' order by name) names
            from watchlist_notification_origins where user_id=? group by kind,notification_id
            """,r->{result.put(r.getString("kind")+":"+r.getLong("notification_id"),r.getString("names"));},user.getId());
        return result;
    }

    public Map<String,String> notificationOrigins(User user, Set<String> keys) {
        if (keys.isEmpty()) return Map.of();
        List<Object> args=new ArrayList<>(); args.add(user.getId());
        List<String> filters=new ArrayList<>();
        for(String key:keys) {
            String[] parts=key.split(":",2); filters.add("(kind=? and notification_id=?)");
            args.add(parts[0]); args.add(Long.parseLong(parts[1]));
        }
        Map<String,String> result=new HashMap<>();
        db.query("select kind,notification_id,string_agg(name || case when watchlist_id is null then ' (deleted)' else '' end, ' / ' order by name) names from watchlist_notification_origins where user_id=? and ("+
                String.join(" or ",filters)+") group by kind,notification_id",
                r->{result.put(r.getString("kind")+":"+r.getLong("notification_id"),r.getString("names"));},args.toArray());
        return result;
    }

    public Map<String,Object> technicalSummary(User user) {
        return db.queryForMap("""
                select (select count(*) from (
                    select stock_asset_id from alert_rules where user_id=? and is_active
                    union select stock_asset_id from technical_outlook_subscriptions where user_id=? and is_active
                ) assets) as instruments,
                ((select count(*) from alert_rules where user_id=? and is_active) +
                 (select count(*) from technical_outlook_subscriptions where user_id=? and is_active)) as rules,
                (select count(*) from watchlist_monitoring where user_id=?) as tracked
                """,user.getId(),user.getId(),user.getId(),user.getId(),user.getId());
    }

    @Transactional
    public long create(User user, String name, String description) {
        lock(user);
        return db.queryForObject("insert into watchlists(user_id,name,description) values (?,?,?) returning id",Long.class,
                user.getId(),validText(name,100,true),validText(description,500,false));
    }

    @Transactional
    public void update(User user,long id,String name,String description,boolean pinned) {
        lock(user); requireOwned(user,id);
        db.update("update watchlists set name=?,description=?,pinned=?,updated_at=current_timestamp where id=?",
                validText(name,100,true),validText(description,500,false),pinned,id);
    }

    @Transactional
    public void delete(User user,long id) {
        lock(user); requireOwned(user,id);
        List<Long> members=db.queryForList("select stock_asset_id from watchlist_members where watchlist_id=?",Long.class,id);
        db.update("delete from watchlists where id=?",id);
        members.forEach(asset->stopIfUnassigned(user,asset));
        if(events!=null) members.forEach(asset -> events.publishEvent(new WatchlistSignalSettingsService.MembershipChanged(user,asset,List.of())));
    }

    @Transactional
    public void remove(User user,long id,String symbol) {
        lock(user); requireOwned(user,id);
        assets.findByTickerSymbolIgnoreCase(SecurityInputValidator.requireMarketSymbol(symbol)).ifPresent(asset->{
            db.update("delete from watchlist_members where watchlist_id=? and stock_asset_id=?",id,asset.getId());
            stopIfUnassigned(user,asset.getId());
            if(events!=null) events.publishEvent(new WatchlistSignalSettingsService.MembershipChanged(user,asset.getId(),List.of()));
        });
    }

    /** Called inside the same transaction as Apply changes. Selected lists are additive. */
    @Transactional
    public void attach(User user,String symbol,List<Long> ids,String newName) {
        lock(user);
        Set<Long> selected=new LinkedHashSet<>(ids==null?List.of():ids);
        if(selected.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Choose valid watchlists.");
        selected.forEach(id->requireOwned(user,id));
        if(newName!=null && !newName.isBlank()) selected.add(create(user,newName,""));
        if(selected.isEmpty()) throw new IllegalArgumentException("Select a watchlist or create one.");
        String canonical=SecurityInputValidator.requireMarketSymbol(symbol);
        var asset=assets.findByTickerSymbolIgnoreCase(canonical).orElseGet(()->market.refreshStockAssetMetadata(canonical));
        assets.flush();
        List<Long> added=new ArrayList<>();
        for(long id:selected) {
            if(db.update("insert into watchlist_members(watchlist_id,stock_asset_id) values (?,?) on conflict do nothing",id,asset.getId())>0) added.add(id);
            db.update("update watchlists set updated_at=current_timestamp where id=?",id);
        }
        if(events!=null && !added.isEmpty()) events.publishEvent(new WatchlistSignalSettingsService.MembershipChanged(user,asset.getId(),added));
    }

    public void requireMembership(User user,String symbol) {
        if(memberships(user,symbol).isEmpty()) throw new IllegalArgumentException("Choose a watchlist before enabling monitoring.");
    }

    public Set<String> symbols(User user,long id) {
        requireOwned(user,id);
        return new HashSet<>(db.queryForList("select a.ticker_symbol from watchlist_members m join stock_assets a on a.id=m.stock_asset_id where m.watchlist_id=?",String.class,id));
    }

    public void requireOwned(User user,long id) {
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from watchlists where id=? and user_id=?)",Boolean.class,id,user.getId())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Watchlist not found.");
    }

    public MemberPage members(User user,long id,int page,String group,String query) {
        requireOwned(user,id);
        int offset=Math.max(0,Math.min(page,100000))*50;
        String search=query==null?"":query.trim().toLowerCase(Locale.ROOT);
        if(search.length()>100) throw new IllegalArgumentException("Search is too long.");
        String where="""
            from watchlist_members m join stock_assets a on a.id=m.stock_asset_id
            where m.watchlist_id=? and (?='' or lower(a.ticker_symbol) like ? or lower(a.company_name) like ?)
            and (?='all' or (?='stocks' and a.instrument_type='EQUITY') or (?='funds' and a.instrument_type in ('INDEX','ETF','MUTUAL_FUND')))
            """;
        String filter=Set.of("stocks","funds").contains(group==null?"":group)?group:"all";
        Object[] params={id,search,"%"+search+"%","%"+search+"%",filter,filter,filter};
        long count=db.queryForObject("select count(*) "+where,Long.class,params);
        List<Object> paged=new ArrayList<>(Arrays.asList(params)); paged.add(offset);
        var tracked=new HashMap<String,AlertRuleService.TrackedCompanyView>();
        List<MemberView> rows=db.query("select a.ticker_symbol,a.company_name,a.exchange,a.currency,a.instrument_type "+where+" order by a.ticker_symbol limit 50 offset ?",
            (r,n)->new MemberView(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),tracked.get(r.getString(1))),paged.toArray());
        alerts.getActiveCompanyViews(user, rows.stream().map(MemberView::symbol).collect(java.util.stream.Collectors.toSet()))
                .forEach(row -> tracked.put(row.symbol(), row));
        Map<String,List<WatchlistSignalSettingsService.Selection>> requested=new HashMap<>();
        if(!rows.isEmpty()) {
            List<Object> args=new ArrayList<>();args.add(id);rows.forEach(row->args.add(row.symbol()));
            db.query("select a.ticker_symbol,s.signal_type,s.interval,s.direction from watchlist_member_signals s join stock_assets a on a.id=s.stock_asset_id where s.watchlist_id=? and a.ticker_symbol in ("+
                    String.join(",",Collections.nCopies(rows.size(),"?"))+") order by s.signal_type,s.interval,s.direction",
                    r->{requested.computeIfAbsent(r.getString(1),key->new ArrayList<>()).add(new WatchlistSignalSettingsService.Selection(r.getString(2),r.getString(3),r.getString(4),true,false));},args.toArray());
        }
        Map<String, Long> unread = new HashMap<>();
        if (!rows.isEmpty()) {
            List<Object> args = new ArrayList<>(); args.add(id); rows.forEach(row -> args.add(row.symbol()));
            db.query("select a.ticker_symbol,(select count(*) from (" + UNREAD_EVENTS + ") events) unread "
                    + "from watchlist_members m join watchlists w on w.id=m.watchlist_id join stock_assets a on a.id=m.stock_asset_id "
                    + "where w.id=? and a.ticker_symbol in (" + String.join(",", Collections.nCopies(rows.size(), "?")) + ")",
                    r -> { unread.put(r.getString(1), r.getLong(2)); }, args.toArray());
        }
        rows = rows.stream().map(row -> new MemberView(row.symbol(),row.companyName(),row.exchange(),row.currency(),row.instrumentType(),tracked.get(row.symbol()),requested.getOrDefault(row.symbol(),List.of()),unread.getOrDefault(row.symbol(),0L))).toList();
        return new MemberPage(rows,count,Math.max(0,page),50);
    }

    private void stopIfUnassigned(User user,long asset) {
        if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from watchlist_members m join watchlists w on w.id=m.watchlist_id where w.user_id=? and m.stock_asset_id=?)",Boolean.class,user.getId(),asset))) return;
        db.update("update alert_rules set is_active=false where user_id=? and stock_asset_id=?",user.getId(),asset);
        db.update("update technical_outlook_subscriptions set is_active=false,updated_at=current_timestamp where user_id=? and stock_asset_id=?",user.getId(),asset);
        db.update("update congressional_trade_subscriptions set active=false,updated_at=current_timestamp where user_id=? and stock_asset_id=?",user.getId(),asset);
        db.update("update insider_trade_subscriptions set active=false,updated_at=current_timestamp where user_id=? and stock_asset_id=?",user.getId(),asset);
    }

    public void lock(User user) { db.queryForObject("select id from users where id=? for update",Long.class,user.getId()); }
    private String validText(String text,int length,boolean required) {
        String value=text==null?"":text.strip();
        if((required && value.isEmpty()) || value.length()>length || value.chars().anyMatch(c->Character.isISOControl(c)))
            throw new IllegalArgumentException("Use "+(required?"1–":"at most ")+length+" characters without control characters.");
        return value;
    }
    public record ListView(long id,String name,String description,boolean pinned,long instruments,long monitored,long unread,List<String> allowedTypes) {}
    public record MemberView(String symbol,String companyName,String exchange,String currency,String instrumentType,AlertRuleService.TrackedCompanyView monitoring,List<WatchlistSignalSettingsService.Selection> signals,long unreadSignalCount) {
        public MemberView(String symbol,String companyName,String exchange,String currency,String instrumentType,AlertRuleService.TrackedCompanyView monitoring) {
            this(symbol,companyName,exchange,currency,instrumentType,monitoring,List.of(),0);
        }
    }

    public String name(User user, long id) {
        requireOwned(user, id);
        return db.queryForObject("select name from watchlists where id=?", String.class, id);
    }
    public record MemberPage(List<MemberView> items,long total,int page,int pageSize) {}
}
