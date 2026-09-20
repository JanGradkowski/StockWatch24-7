package org.example.stockwatch247.service;

import tools.jackson.databind.ObjectMapper;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.*;
import org.example.stockwatch247.repository.*;
import org.example.stockwatch247.security.SecurityInputValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class WatchlistImportService {
    @org.springframework.beans.factory.annotation.Autowired private WatchlistAllowedSignals permissions;
    private final JdbcTemplate db;
    private final WatchlistService lists;
    private final WatchlistIndexCatalog catalog;
    private final ObjectMapper json;
    private final StockAssetRepository assets;
    private final UserRepository users;
    private final TwelveDataService market;
    private final AlertRuleService alerts;
    @org.springframework.beans.factory.annotation.Autowired private WatchlistSignalSettingsService signalSettings;
    @org.springframework.beans.factory.annotation.Autowired private WatchlistMonitoringService monitoring;
    @Value("${insider-activity.maximum-follows-per-user:50}") private int insiderLimit;
    @Value("${congressional-activity.maximum-follows-per-user:50}") private int congressLimit;
    @Value("${insider-activity.enabled:false}") private boolean insiderEnabled;
    @Value("${congressional-activity.enabled:false}") private boolean congressEnabled;
    private final int perUser,global;
    public WatchlistImportService(JdbcTemplate db,WatchlistService lists,WatchlistIndexCatalog catalog,ObjectMapper json,
            StockAssetRepository assets,UserRepository users,TwelveDataService market,AlertRuleService alerts,
            @Value("${alerts.max-tracked-stocks-per-user:300}") int perUser,@Value("${alerts.max-global-tracked-stocks:500}") int global) {
        this.db=db;this.lists=lists;this.catalog=catalog;this.json=json;this.assets=assets;this.users=users;this.market=market;this.alerts=alerts;this.perUser=perUser;this.global=global;
    }
    public Preview preview(User user,Long listId,ImportRequest request) {
        if(listId!=null) lists.requireOwned(user,listId);
        var expanded=expand(request,listId);
        Set<String> monitored=new HashSet<>(db.queryForList("select a.ticker_symbol from watchlist_monitoring m join stock_assets a on a.id=m.stock_asset_id where m.user_id=?",String.class,user.getId()));
        Set<String> globalSymbols=new HashSet<>(db.queryForList("select distinct a.ticker_symbol from watchlist_monitoring m join stock_assets a on a.id=m.stock_asset_id",String.class));
        Set<String> members=listId==null?Set.of():new HashSet<>(db.queryForList("select a.ticker_symbol from watchlist_members m join stock_assets a on a.id=m.stock_asset_id where m.watchlist_id=?",String.class,listId));
        var selected=request.selections()==null?List.<WatchlistSignalSettingsService.Selection>of():WatchlistSignalSettingsService.validate(request.selections());
        boolean technical=selected.stream().anyMatch(v->v.watch() && !WatchlistSignalSettingsService.activity(v.type()));
        boolean congress=selected.stream().anyMatch(v->v.watch() && v.type().equals("CONGRESS"));
        boolean insider=selected.stream().anyMatch(v->v.watch() && v.type().equals("INSIDER"));
        var affected=expanded.items().stream().filter(i->request.applySettings() || !members.contains(i.symbol())).toList();
        Set<String> stockSymbols=new HashSet<>();
        affected.stream().filter(i->"EQUITY".equals(i.type())).forEach(i->stockSymbols.add(i.symbol()));
        Set<String> wanted=new HashSet<>();
        for(var item:affected) if(technical || request.selections()==null && request.monitor() || (congress || insider) && stockSymbols.contains(item.symbol())) wanted.add(item.symbol());
        long fresh=wanted.stream().filter(symbol->!monitored.contains(symbol)).count();
        long freshGlobal=wanted.stream().filter(symbol->!globalSymbols.contains(symbol)).count();
        int remaining=Math.max(0,perUser-monitored.size());
        var existingCongress=activitySymbols(user,"congressional"); var existingInsider=activitySymbols(user,"insider");
        int congressRemaining=Math.max(0,congressLimit-existingCongress.size()),insiderRemaining=Math.max(0,insiderLimit-existingInsider.size());
        long congressNew=congress?stockSymbols.stream().filter(symbol->!existingCongress.contains(symbol)).count():0;
        long insiderNew=insider?stockSymbols.stream().filter(symbol->!existingInsider.contains(symbol)).count():0;
        boolean allowed=fresh<=remaining && freshGlobal<=Math.max(0,global-globalSymbols.size())
                && congressNew<=congressRemaining && insiderNew<=insiderRemaining
                && (congressNew==0 || congressEnabled) && (insiderNew==0 || insiderEnabled);
        String warning="Index selections add dated snapshots; membership will not update automatically.";
        if(!allowed) warning=(!congressEnabled && congressNew>0 || !insiderEnabled && insiderNew>0)
                ? "A selected ticker alert service is unavailable. Disable that alert type to continue."
                : "The selection exceeds available monitoring or ticker-alert capacity. Select fewer instruments or alert types.";
        long skipped=(congress || insider)?affected.size()-stockSymbols.size():0;
        return new Preview(expanded.items().size(),expanded.duplicates(),expanded.items().stream().filter(i->members.contains(i.symbol())).count(),fresh,remaining,allowed,warning,
                stockSymbols.size(),skipped,congressRemaining,insiderRemaining);
    }
    private Set<String> activitySymbols(User user,String kind) {
        return new HashSet<>(db.queryForList("select a.ticker_symbol from "+kind+"_trade_subscriptions s join stock_assets a on a.id=s.stock_asset_id where s.user_id=? and s.active",String.class,user.getId()));
    }

    @Transactional
    public long enqueue(User user,long listId,ImportRequest request) {
        lists.lock(user); lists.requireOwned(user,listId);
        if (request.selections()!=null) request.selections().stream().filter(WatchlistSignalSettingsService.Selection::watch).forEach(value -> permissions.requireAllowed(listId,value.type()));
        else if (request.monitor() && request.families()!=null) request.families().forEach(type -> permissions.requireAllowed(listId,type));
        if(!preview(user,listId,request).allowed()) throw new IllegalArgumentException("The import exceeds monitoring capacity. Save without alerts or select fewer instruments.");
        long id=db.queryForObject("insert into watchlist_imports(user_id,watchlist_id,settings) values (?,?,?) returning id",Long.class,user.getId(),listId,json.writeValueAsString(request));
        for(var item:expand(request,listId).items()) db.update("insert into watchlist_import_items(import_id,symbol,metadata) values (?,?,?)",id,item.symbol(),json.writeValueAsString(item));
        return id;
    }
    public JobView status(User user,long id) {
        owned(user,id);
        var counts=db.queryForMap("select count(*) total,count(*) filter(where status='DONE') completed,count(*) filter(where status='FAILED') failed,count(*) filter(where status in ('PENDING','PROCESSING')) pending from watchlist_import_items where import_id=?",id);
        List<Failure> errors=db.query("select symbol,error from watchlist_import_items where import_id=? and status='FAILED' order by id",(r,n)->new Failure(r.getString(1),r.getString(2)),id);
        return new JobView(id,number(counts,"total"),number(counts,"completed"),number(counts,"failed"),number(counts,"pending"),errors);
    }
    public List<JobView> recent(User user) {
        return db.queryForList("select id from watchlist_imports where user_id=? order by id desc limit 10",Long.class,user.getId()).stream().map(id->status(user,id)).toList();
    }
    @Transactional
    public void retry(User user,long id) { lists.lock(user); owned(user,id); db.update("update watchlist_import_items set status='PENDING',error=null,lease_until=null where import_id=? and status='FAILED'",id); }
    public Optional<Work> claim() {
        var found=db.query("""
            with candidate as (
              select id from watchlist_import_items where status='PENDING' or (status='PROCESSING' and lease_until<current_timestamp)
              order by id for update skip locked limit 1
            ) update watchlist_import_items i set status='PROCESSING',lease_until=current_timestamp+interval '5 minutes'
            from candidate c,watchlist_imports j where i.id=c.id and j.id=i.import_id
            returning i.id,j.user_id,j.watchlist_id,i.metadata,j.settings
            """,(r,n)->new Work(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getString(5)));
        return found.stream().findFirst();
    }
    @Transactional
    public void process(Work work) {
        User user=users.findById(work.userId()).orElseThrow(); lists.lock(user); lists.requireOwned(user,work.listId());
        // Lock the item through commit. A retry after an expired lease cannot apply it twice.
        var states=db.queryForList("select status from watchlist_import_items where id=? for update",String.class,work.id());
        if(states.isEmpty() || states.getFirst().equals("DONE")) return;
        var item=json.readValue(work.metadata(),WatchlistIndexCatalog.Instrument.class);
        var settings=json.readValue(work.settings(),ImportRequest.class);
        var existing=assets.findByTickerSymbolIgnoreCase(item.symbol());
        if(existing.isEmpty()) {
            if(item.exchange()==null) {
                var resolved=market.refreshStockAssetMetadata(item.symbol());
                if("UNKNOWN".equals(resolved.getExchange())) throw new IllegalArgumentException("Instrument metadata is unavailable. Try again later.");
            } else market.upsertStockAsset(item.symbol(),item.name(),item.exchange(),item.currency(),InstrumentType.valueOf(item.type()));
            assets.flush();
        }
        boolean alreadyMonitored=Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from watchlist_monitoring m join stock_assets a on a.id=m.stock_asset_id where m.user_id=? and a.ticker_symbol=?)",Boolean.class,user.getId(),item.symbol()));
        lists.attach(user,item.symbol(),List.of(work.listId()),null);
        if(settings.applySettings() && signalSettings.configured(work.listId())) monitoring.applyDefaults(user,work.listId(),assets.findByTickerSymbolIgnoreCase(item.symbol()).orElseThrow().getId());
        if(settings.selections()==null && settings.monitor() && !alreadyMonitored) {
            List<AlertRuleService.AlertRuleChange> changes=new ArrayList<>();
            for(String interval:settings.intervals()) for(String family:settings.families()) for(TradeSignal direction:List.of(TradeSignal.BUY,TradeSignal.SELL))
                changes.add(new AlertRuleService.AlertRuleChange(TimeInterval.valueOf(interval),direction,AlertPatternFamily.valueOf(family),true));
            alerts.applyAlertChanges(user,item.symbol(),changes);
            changes.forEach(change->signalSettings.record(user,item.symbol(),List.of(work.listId()),change.patternFamily().name(),change.interval().name(),change.signal().name(),true));
        }
        db.update("update watchlist_import_items set status='DONE',error=null,lease_until=null where id=?",work.id());
    }
    public void fail(Work work,RuntimeException error) {
        String message=error instanceof IllegalArgumentException || error instanceof IllegalStateException ? error.getMessage():"Import could not finish. Retry this instrument.";
        if(message==null) message="Import could not finish. Retry this instrument.";
        db.update("update watchlist_import_items set status='FAILED',error=?,lease_until=null where id=? and status<>'DONE'",message.substring(0,Math.min(500,message.length())),work.id());
    }
    private Expanded expand(ImportRequest request,Long listId) {
        if(request==null || request.symbols()==null || request.indexIds()==null || request.symbols().size()>10000 || request.indexIds().size()>100)
            throw new IllegalArgumentException("Choose instruments or indexes to add.");
        if(request.selections()!=null) WatchlistSignalSettingsService.validate(request.selections());
        if(request.monitor() && request.selections()==null) {
            if(request.families()==null || request.families().isEmpty() || request.families().size()>3 || request.intervals()==null || request.intervals().isEmpty() || request.intervals().size()>3)
                throw new IllegalArgumentException("Choose at least one pattern family and interval.");
            request.families().forEach(AlertPatternFamily::valueOf);
            if(!Set.of("DAILY","WEEKLY","MONTHLY").containsAll(request.intervals())) throw new IllegalArgumentException("Choose Daily, Weekly or Monthly.");
        }
        Map<String,WatchlistIndexCatalog.Instrument> items=new LinkedHashMap<>(); int total=0;
        for(String id:request.indexIds()) for(var item:catalog.get(id).constituents()) { items.putIfAbsent(item.symbol(),item); total++; }
        if(request.includeExisting()) {
            if(listId==null) throw new IllegalArgumentException("Choose a watchlist to edit.");
            var current=db.query("select a.ticker_symbol,a.company_name,a.exchange,a.currency,a.instrument_type from watchlist_members m join stock_assets a on a.id=m.stock_asset_id where m.watchlist_id=?",(r,n)->new WatchlistIndexCatalog.Instrument(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)),listId);
            for(var item:current) { items.putIfAbsent(item.symbol(),item); total++; }
        }
        for(String raw:request.symbols()) {
            String symbol=SecurityInputValidator.requireMarketSymbol(raw); var item=catalog.instrument(symbol);
            var known=assets.findByTickerSymbolIgnoreCase(symbol);
            if(known.isPresent()) { var a=known.get(); item=new WatchlistIndexCatalog.Instrument(symbol,a.getCompanyName(),a.getExchange(),a.getCurrency(),a.getInstrumentType().name()); }
            items.putIfAbsent(symbol,item==null?new WatchlistIndexCatalog.Instrument(symbol,symbol,null,null,"EQUITY"):item); total++;
        }
        if(items.isEmpty() || items.size()>10000) throw new IllegalArgumentException("Select between 1 and 10,000 unique instruments per import.");
        return new Expanded(List.copyOf(items.values()),total-items.size());
    }
    private void owned(User user,long id) {
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from watchlist_imports where id=? and user_id=?)",Boolean.class,id,user.getId())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Import not found.");
    }
    private long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    public record ImportRequest(List<String> symbols,List<String> indexIds,boolean monitor,List<String> families,List<String> intervals,
            List<WatchlistSignalSettingsService.Selection> selections,boolean includeExisting,boolean applySettings) {
        public ImportRequest(List<String> symbols,List<String> indexIds,boolean monitor,List<String> families,List<String> intervals) {
            this(symbols,indexIds,monitor,families,intervals,null,false,false);
        }
    }
    public record Preview(int uniqueCount,int duplicates,long alreadyPresent,long newlyMonitored,int capacityRemaining,boolean allowed,String warning,
            long eligibleTickerStocks,long skippedTickerInstruments,int congressRemaining,int insiderRemaining) {}
    private record Expanded(List<WatchlistIndexCatalog.Instrument> items,int duplicates) {}
    public record Work(long id,long userId,long listId,String metadata,String settings) {}
    public record Failure(String symbol,String error) {}
    public record JobView(long id,long total,long completed,long failed,long pending,List<Failure> errors) {}
}
