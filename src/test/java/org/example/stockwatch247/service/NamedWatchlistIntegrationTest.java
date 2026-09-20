package org.example.stockwatch247.service;

import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.*;
import org.example.stockwatch247.repository.*;
import org.example.stockwatch247.security.AccountSession;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"alerts.schedule.enabled=false","email-outbox.worker-enabled=false","watchlists.imports.worker-enabled=false","congressional-activity.enabled=true","insider-activity.enabled=true"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(org.example.stockwatch247.support.DesignPreviewCapture.class)
class NamedWatchlistIntegrationTest {
    @Autowired WatchlistService lists;
    @Autowired WatchlistFollowService follows;
    @Autowired WatchlistImportService imports;
    @Autowired WatchlistEditorService editor;
    @Autowired WatchlistSignalSettingsService settings;
    @Autowired WatchlistEmailPolicy emails;
    @Autowired SignalArchiveQuery archiveQuery;
    @Autowired WatchlistAllowedSignals permissions;
    @Autowired SignalArchiveDeletionService deletion;
    @org.springframework.test.context.bean.override.mockito.MockitoBean org.example.stockwatch247.service.congress.CongressionalTradeProvider congressProvider;
    @org.springframework.test.context.bean.override.mockito.MockitoBean org.example.stockwatch247.service.insider.InsiderTradeProvider insiderProvider;
    @Autowired SecurityCryptoService crypto;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired WatchlistIndexCatalog catalog;
    @Autowired AlertCheckJobStore jobs;
    @Autowired UserRepository users;
    @Autowired StockAssetRepository assets;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired javax.sql.DataSource dataSource;
    User owner,other;
    StockAsset asset;

    @BeforeEach void setup() {
        owner=account();other=account();
        asset=new StockAsset();asset.setTickerSymbol("WL"+UUID.randomUUID().toString().substring(0,8).toUpperCase());
        asset.setCompanyName("Named watchlist test");asset.setExchange("NASDAQ");asset.setCurrency("USD");assets.saveAndFlush(asset);
    }
    @AfterEach void cleanup() {
        db.update("delete from users where id in (?,?)",owner.getId(),other.getId());
        db.update("delete from alert_check_jobs where symbol=?",asset.getTickerSymbol());
        assets.deleteById(asset.getId());
    }
    private User account() {
        User u=new User();u.setEmail("watchlist-"+UUID.randomUUID()+"@example.test");u.setPasswordHash("test-not-used");
        u.setFirstName("Watchlist");u.setLastName("Test");u.setVerified(true);return users.saveAndFlush(u);
    }
    private AlertRuleService.AlertRuleChange buy() { return new AlertRuleService.AlertRuleChange(TimeInterval.DAILY,TradeSignal.BUY,AlertPatternFamily.CANDLESTICK,true); }

    private WatchlistSignalSettingsService.Selection choice(String type,String interval,String direction,boolean watch,boolean email) {
        return new WatchlistSignalSettingsService.Selection(type,interval,direction,watch,email);
    }
    private WatchlistEditorService.Saved save(Long id,List<WatchlistSignalSettingsService.Selection> choices,boolean apply,List<String> symbols) {
        return editor.save(owner,new WatchlistEditorService.SaveRequest(id,"Settings research","",false,symbols,List.of(),choices,apply,true));
    }
    private void finish(WatchlistEditorService.Saved saved) {
        if(saved.importId()!=null) {
            while(imports.status(owner,saved.importId()).pending()>0) imports.process(imports.claim().orElseThrow());
            assertThat(imports.status(owner,saved.importId()).failed()).isZero();
        }
    }
    private boolean email(String type,String interval,String direction) {
        return emails.allows(owner.getId(),asset.getTickerSymbol(),type,interval,direction);
    }

    private long readAllFixture() {
        var saved=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,false),choice("OUTLOOK","DAILY","ANY",true,false),
                choice("CONGRESS","DAILY","ANY",true,false),choice("INSIDER","DAILY","ANY",true,false)),true,List.of(asset.getTickerSymbol()));
        finish(saved);
        long rule=db.queryForObject("select id from alert_rules where user_id=? and pattern_family='CANDLESTICK'",Long.class,owner.getId());
        db.update("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) select ?,n,current_timestamp,'BULLISH_ENGULFING','BUY' from generate_series(1,60) n",rule);
        long outlook=db.queryForObject("select id from technical_outlook_subscriptions where user_id=?",Long.class,owner.getId());
        db.update("""
                insert into technical_outlook_notifications(subscription_id,previous_classification,current_classification,
                    previous_score,current_score,previous_candle_timestamp,current_candle_timestamp,previous_snapshot,current_snapshot,change_report)
                values (?,'Neutral outlook','Moderate buy outlook',0,0.5,1,2,'{}','{}','{}')
                """,outlook);
        activityDelivery("CONGRESS"); activityDelivery("INSIDER");
        return saved.id();
    }

    @Test void scopedMarkAllReadCoversEveryPageAndKindWithoutTouchingOtherScopes() throws Exception {
        long selected=readAllFixture();
        var overlap=save(null,List.of(choice("CONGRESS","DAILY","ANY",true,false)),true,List.of(asset.getTickerSymbol())); finish(overlap);
        var unrelated=save(null,List.of(choice("HARMONIC_FORMATION","WEEKLY","SELL",true,false)),true,List.of(asset.getTickerSymbol())); finish(unrelated);
        long unrelatedRule=db.queryForObject("select id from alert_rules where user_id=? and pattern_family='HARMONIC_FORMATION'",Long.class,owner.getId());
        long unrelatedEvent=db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) values (?,1,current_timestamp,'BEARISH_ENGULFING','SELL') returning id",Long.class,unrelatedRule);
        long foreignList=lists.create(other,"Private","");
        follows.apply(other,asset.getTickerSymbol(),List.of(buy()),List.of(foreignList),null);
        long foreignRule=db.queryForObject("select id from alert_rules where user_id=?",Long.class,other.getId());
        long foreignEvent=db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) values (?,1,current_timestamp,'BULLISH_ENGULFING','BUY') returning id",Long.class,foreignRule);
        // Legacy activity uses matching follows; captured technical history survives unfollowing.
        db.update("delete from watchlist_notification_origins where user_id=? and kind='CONGRESS'",owner.getId());
        follows.apply(owner,asset.getTickerSymbol(),List.of(new AlertRuleService.AlertRuleChange(TimeInterval.DAILY,TradeSignal.BUY,AlertPatternFamily.CANDLESTICK,false)),List.of(),null);
        assertThat(lists.members(owner,selected,0,"all","").items().getFirst().unreadSignalCount()).isEqualTo(63);
        mvc.perform(post("/api/notifications/read-all").param("watchlistId",String.valueOf(selected)).with(csrf())
                .with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(63));
        assertThat(lists.members(owner,selected,0,"all","").items().getFirst().unreadSignalCount()).isZero();
        assertThat(lists.members(owner,overlap.id(),0,"all","").items().getFirst().unreadSignalCount()).isZero();
        assertThat(lists.members(owner,unrelated.id(),0,"all","").items().getFirst().unreadSignalCount()).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from alert_events where id in (?,?) and read_at is null",Long.class,foreignEvent,unrelatedEvent)).isEqualTo(2);
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,50,new SignalArchiveFilter("all","",selected)).count()).isEqualTo(62);
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,50,new SignalArchiveFilter("unread","",selected)).count()).isZero();
        mvc.perform(post("/api/notifications/read-all").param("watchlistId",String.valueOf(selected)).with(csrf())
                .with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(0));
    }

    @Test void dashboardReadAllOnlyMarksActivityAndAccountReadAllPreservesDeletedAndAlreadyReadRows() throws Exception {
        long list=readAllFixture();
        long rule=db.queryForObject("select id from alert_rules where user_id=?",Long.class,owner.getId());
        long deleted=db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal,deleted_at) values (?,61,current_timestamp,'BULLISH_ENGULFING','BUY',current_timestamp) returning id",Long.class,rule);
        long read=db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal,read_at) values (?,62,current_timestamp,'BULLISH_ENGULFING','BUY',timestamp '2020-01-01 12:00:00') returning id",Long.class,rule);
        mvc.perform(post("/api/notifications/read-all").param("scope","ACTIVITY").with(csrf())
                .with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2));
        assertThat(lists.members(owner,list,0,"all","").items().getFirst().unreadSignalCount()).isEqualTo(61);
        mvc.perform(post("/api/notifications/read-all").with(csrf())
                .with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(61));
        assertThat(lists.members(owner,list,0,"all","").items().getFirst().unreadSignalCount()).isZero();
        assertThat(db.queryForObject("select read_at is null from alert_events where id=?",Boolean.class,deleted)).isTrue();
        assertThat(db.queryForObject("select read_at=timestamp '2020-01-01 12:00:00' from alert_events where id=?",Boolean.class,read)).isTrue();
        assertThat(db.queryForObject("select count(*) from alert_events where alert_rule_id=?",Long.class,rule)).isEqualTo(62);
        assertThat(settings.active(owner,asset.getId())).hasSize(4);
        mvc.perform(get("/signals").with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("data-notifications-read-all")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Mark every notification in your account")));
        mvc.perform(get("/signals").param("watchlistId",String.valueOf(list)).param("state","unread")
                .with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("data-read-watchlist=\""+list+"\"")));
    }

    @Test void markAllReadRequiresCsrfAndOwnershipAndRejectsUnknownScopes() throws Exception {
        long list=readAllFixture();
        mvc.perform(post("/api/notifications/read-all").param("watchlistId",String.valueOf(list))
                .with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/notifications/read-all").param("watchlistId",String.valueOf(list)).with(csrf())
                .with(user(other.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,other.getSecurityVersion()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/notifications/read-all").param("scope","EVERYONE").with(csrf())
                .with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isBadRequest());
        assertThat(lists.members(owner,list,0,"all","").items().getFirst().unreadSignalCount()).isEqualTo(63);
    }

    @Test void mixedWatchlistHistoryIncludesOnlyRequestedActivityAndKeepsOnePagination() throws Exception {
        var watching=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,false),choice("CONGRESS","DAILY","ANY",true,false),choice("INSIDER","DAILY","ANY",true,false)),true,List.of(asset.getTickerSymbol())); finish(watching);
        var unrelated=save(null,List.of(),true,List.of(asset.getTickerSymbol())); finish(unrelated);
        long rule=db.queryForObject("select id from alert_rules where user_id=?",Long.class,owner.getId());
        long event=db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) values (?,1,current_timestamp,'BULLISH_ENGULFING','BUY') returning id",Long.class,rule);
        long congress=activityDelivery("CONGRESS"), insider=activityDelivery("INSIDER");
        db.update("update alert_events set sent_at=timestamp '2026-09-16 10:00:00' where id=?",event);
        db.update("update congressional_trade_deliveries set created_at=timestamp '2026-09-16 11:00:00' where id=?",congress);
        db.update("update insider_trade_deliveries set created_at=timestamp '2026-09-16 12:00:00' where id=?",insider);
        var filter=new SignalArchiveFilter("all",asset.getTickerSymbol(),watching.id());
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,50,filter).rows()).extracting(SignalArchiveQuery.ArchiveRow::kind).containsExactly("INSIDER","CONGRESS","TECHNICAL");
        for(String sort:List.of("date","ticker","interval","confidence","status","trade-return")) for(boolean ascending:List.of(true,false)) {
            var page=archiveQuery.page(owner.getId(),null,sort,ascending,0,50,filter);
            assertThat(page.count()).isEqualTo(3);
            assertThat(page.rows()).extracting(SignalArchiveQuery.ArchiveRow::kind).containsExactlyInAnyOrder("TECHNICAL","CONGRESS","INSIDER");
        }
        var keys=new HashSet<String>();
        for(int page=0;page<3;page++) {
            var result=archiveQuery.page(owner.getId(),null,"date",false,page,1,filter);
            assertThat(result.pages()).isEqualTo(3);
            var row=result.rows().getFirst(); keys.add(row.kind()+":"+row.id());
        }
        assertThat(keys).hasSize(3);
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,50,new SignalArchiveFilter("all","",unrelated.id())).count()).isZero();
        assertThat(archiveQuery.page(other.getId(),null,"date",false,0,50,filter).count()).isZero();
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,50,new SignalArchiveFilter("all",asset.getTickerSymbol().substring(0,4),watching.id())).count()).isZero();
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,50,new SignalArchiveFilter("active","",watching.id())).rows()).allMatch(row -> row.kind().equals("TECHNICAL"));
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,50,new SignalArchiveFilter("completed","",watching.id())).count()).isEqualTo(2);
        mvc.perform(get("/signals").param("watchlistId",String.valueOf(watching.id())).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Congressional activity")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Insider activity")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/signals/mixed/delete")));
        String returnTo="/signals?watchlistId="+watching.id()+"&state=unread";
        mvc.perform(get("/activity-signals/congressional/"+congress).param("returnTo",returnTo).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(model().attribute("archiveReturnUrl",returnTo));
        assertThat(lists.members(owner,watching.id(),0,"all","").items().getFirst().unreadSignalCount()).isEqualTo(2);
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,50,new SignalArchiveFilter("unread","",watching.id())).count()).isEqualTo(2);
        assertThatThrownBy(() -> deletion.deleteMixedSignals(owner,List.of(event),List.of("INSIDER:9223372036854775807"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,50,filter).count()).isEqualTo(3);
        mvc.perform(post("/signals/mixed/delete").with(csrf()).param("watchlistId",String.valueOf(watching.id())).param("signalIds",String.valueOf(event))
                .param("signalKeys","INSIDER:"+insider).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().is3xxRedirection());
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,50,filter).count()).isEqualTo(1);
    }

    private long activityDelivery(String kind) {
        String prefix=kind.equals("CONGRESS")?"congressional_trade":"insider_trade";
        long subscription=db.queryForObject("select id from "+prefix+"_subscriptions where user_id=? and stock_asset_id=?",Long.class,owner.getId(),asset.getId());
        String extra=kind.equals("CONGRESS")?"member_name,chamber,amount_range,disclosure_date":"insider_name,owner_role,transaction_code,filing_date";
        long trade=db.queryForObject("insert into "+prefix+"s(stock_asset_id,provider,provider_fingerprint,ticker_symbol,transaction_type,transaction_date,"+extra+") values (?,'TEST',?,?,'PURCHASE',current_date,'Test trader','HOUSE','P',current_date) returning id",Long.class,asset.getId(),UUID.randomUUID().toString(),asset.getTickerSymbol());
        return db.queryForObject("insert into "+prefix+"_deliveries(subscription_id,trade_id) values (?,?) returning id",Long.class,subscription,trade);
    }

    @Test void queuedImportsUseLatestRestrictionsAndDefaultMembershipDoesNotCopyForbiddenFollows() {
        var queued=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,false)),true,List.of(asset.getTickerSymbol()));
        editor.save(owner,new WatchlistEditorService.SaveRequest(queued.id(),"Restricted before import","",false,List.of(),List.of(),List.of(),false,true,List.of("CONGRESS")));
        finish(queued);
        assertThat(lists.memberships(owner,asset.getTickerSymbol())).contains(queued.id());
        assertThat(settings.active(owner,asset.getId())).isEmpty();
        var active=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,false)),true,List.of(asset.getTickerSymbol())); finish(active);
        long restricted=lists.create(owner,"Membership only","");
        permissions.save(owner,restricted,List.of("CONGRESS"));
        lists.attach(owner,asset.getTickerSymbol(),List.of(restricted),null);
        assertThat(lists.members(owner,restricted,0,"all","").items().getFirst().signals()).isEmpty();
        assertThat(settings.active(owner,asset.getId())).extracting(WatchlistSignalSettingsService.Selection::type).containsExactly("CANDLESTICK");
    }

    @Test void restrictionsPreserveOtherFollowsHistoryAndMembershipsAndRejectEveryAddPath() {
        var selected=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,false),choice("CONGRESS","DAILY","ANY",true,false)),true,List.of(asset.getTickerSymbol())); finish(selected);
        var otherList=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,false)),true,List.of(asset.getTickerSymbol())); finish(otherList);
        assertThat(settings.get(owner,selected.id()).allowedTypes()).containsExactlyElementsOf(WatchlistAllowedSignals.TYPES);
        long rule=db.queryForObject("select id from alert_rules where user_id=?",Long.class,owner.getId());
        long event=db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) values (?,1,current_timestamp,'BULLISH_ENGULFING','BUY') returning id",Long.class,rule);
        db.update("delete from watchlist_notification_origins where kind='TECHNICAL' and notification_id=?",event);
        var allowed=List.of("CONGRESS");
        editor.save(owner,new WatchlistEditorService.SaveRequest(selected.id(),"Restricted","",false,List.of(),List.of(),List.of(choice("CONGRESS","DAILY","ANY",true,false)),false,true,allowed));
        assertThat(settings.get(owner,selected.id()).allowedTypes()).containsExactly("CONGRESS");
        assertThat(lists.members(owner,selected.id(),0,"all","").items().getFirst().signals()).extracting(WatchlistSignalSettingsService.Selection::type).containsExactly("CONGRESS");
        assertThat(lists.memberships(owner,asset.getTickerSymbol())).contains(selected.id(),otherList.id());
        assertThat(db.queryForObject("select is_active from alert_rules where id=?",Boolean.class,rule)).isTrue();
        for(long id:List.of(selected.id(),otherList.id())) assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,50,new SignalArchiveFilter("all","",id)).ids()).contains(event);
        assertThatThrownBy(() -> follows.applyDraft(owner,asset.getTickerSymbol(),List.of(buy()),List.of(),List.of(selected.id()),null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not allow");
        assertThatThrownBy(() -> follows.outlook(owner,asset.getTickerSymbol(),TimeInterval.DAILY,true,List.of(selected.id()),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> follows.insider(owner,asset.getTickerSymbol(),true,List.of(selected.id()),null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> imports.enqueue(owner,selected.id(),new WatchlistImportService.ImportRequest(List.of(asset.getTickerSymbol()),List.of(),true,List.of("CANDLESTICK"),List.of("DAILY")))).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.active(owner,asset.getId())).extracting(WatchlistSignalSettingsService.Selection::type).doesNotContain("INSIDER","OUTLOOK");
        // Disallow everything in the second list: the remaining congressional follow survives.
        editor.save(owner,new WatchlistEditorService.SaveRequest(otherList.id(),"None","",false,List.of(),List.of(),List.of(),false,true,List.of()));
        assertThat(db.queryForObject("select is_active from alert_rules where id=?",Boolean.class,rule)).isFalse();
        assertThat(settings.active(owner,asset.getId())).extracting(WatchlistSignalSettingsService.Selection::type).containsExactly("CONGRESS");
        // Allowing again does not restart any subscriptions.
        editor.save(owner,new WatchlistEditorService.SaveRequest(otherList.id(),"All allowed","",false,List.of(),List.of(),List.of(),false,true,WatchlistAllowedSignals.TYPES));
        assertThat(db.queryForObject("select is_active from alert_rules where id=?",Boolean.class,rule)).isFalse();
    }

    @Test void watchlistArchiveAndUnreadCountsStayScopedWhenEmailIsDisabled() throws Exception {
        var watching=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,false)),true,List.of(asset.getTickerSymbol())); finish(watching);
        var unrelated=save(null,List.of(choice("HARMONIC_FORMATION","WEEKLY","SELL",true,false)),true,List.of(asset.getTickerSymbol())); finish(unrelated);
        long rule=db.queryForObject("select id from alert_rules where user_id=? and pattern_family='CANDLESTICK'",Long.class,owner.getId());
        long event=db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) values (?,1,current_timestamp,'BULLISH_ENGULFING','BUY') returning id",Long.class,rule);
        assertThat(email("CANDLESTICK","DAILY","BUY")).isFalse();
        assertThat(lists.members(owner,watching.id(),0,"all","").items().getFirst().unreadSignalCount()).isEqualTo(1);
        assertThat(lists.members(owner,unrelated.id(),0,"all","").items().getFirst().unreadSignalCount()).isZero();
        assertThat(lists.lists(owner).stream().filter(l->l.id()==watching.id()).findFirst().orElseThrow().unread()).isEqualTo(1);
        assertThat(lists.lists(owner).stream().filter(l->l.id()==unrelated.id()).findFirst().orElseThrow().unread()).isZero();
        var filter=new SignalArchiveFilter("all",asset.getTickerSymbol(),watching.id());
        for(String sort:List.of("date","ticker","interval","confidence","status","trade-return")) {
            assertThat(archiveQuery.page(owner.getId(),null,sort,false,0,20,filter).ids()).containsExactly(event);
            assertThat(archiveQuery.page(owner.getId(),null,sort,true,0,20,new SignalArchiveFilter("all","",unrelated.id())).ids()).isEmpty();
        }
        // A ticker link matches exactly, never another symbol containing the same substring.
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,20,new SignalArchiveFilter("all",asset.getTickerSymbol().substring(0,4),watching.id())).ids()).isEmpty();
        assertThat(archiveQuery.page(other.getId(),null,"date",false,0,20,filter).ids()).isEmpty();
        mvc.perform(get("/signals").param("watchlistId",String.valueOf(watching.id())).param("ticker",asset.getTickerSymbol())
                .with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(model().attribute("archiveFilter",filter))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"watchlistId\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/watchlists/"+watching.id())));
        mvc.perform(get("/signals").param("watchlistId",String.valueOf(watching.id()))
                .with(user(other.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,other.getSecurityVersion()))
                .andExpect(status().isNotFound());
        db.update("update alert_events set read_at=current_timestamp where id=?",event);
        assertThat(lists.members(owner,watching.id(),0,"all","").items().getFirst().unreadSignalCount()).isZero();
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,20,new SignalArchiveFilter("unread","",watching.id())).ids()).isEmpty();
        // Recorded history survives later changes to the follows.
        finish(save(watching.id(),List.of(),true,List.of()));
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,20,filter).ids()).containsExactly(event);
        db.update("update alert_events set deleted_at=current_timestamp where id=?",event);
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,20,filter).ids()).isEmpty();
    }

    @Test void legacySignalsWithoutOriginsUseOnlyMatchingWatchlistFollows() {
        var watching=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,false)),true,List.of(asset.getTickerSymbol()));finish(watching);
        var unrelated=save(null,List.of(),true,List.of(asset.getTickerSymbol()));finish(unrelated);
        long rule=db.queryForObject("select id from alert_rules where user_id=?",Long.class,owner.getId());
        long event=db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) values (?,1,current_timestamp,'BULLISH_ENGULFING','BUY') returning id",Long.class,rule);
        db.update("delete from watchlist_notification_origins where kind='TECHNICAL' and notification_id=?",event);
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,20,new SignalArchiveFilter("all","",watching.id())).ids()).containsExactly(event);
        assertThat(archiveQuery.page(owner.getId(),null,"date",false,0,20,new SignalArchiveFilter("all","",unrelated.id())).ids()).isEmpty();
    }

    @Test void watchlistScopeSurvivesArchivePagingSortingAndDetailReturnLinks() throws Exception {
        var watching=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,false)),true,List.of(asset.getTickerSymbol()));finish(watching);
        long rule=db.queryForObject("select id from alert_rules where user_id=?",Long.class,owner.getId());
        db.update("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) select ?,n,current_timestamp,'BULLISH_ENGULFING','BUY' from generate_series(1,51) n",rule);
        var result=mvc.perform(get("/signals").param("watchlistId",String.valueOf(watching.id())).param("ticker",asset.getTickerSymbol())
                .param("sort","ticker").param("direction","asc").param("page","1").param("state","unread")
                .with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isOk()).andReturn();
        var archive=(AlertRuleService.SignalArchivePage)result.getModelAndView().getModel().get("archive");
        assertThat(archive.totalSignals()).isEqualTo(51);
        assertThat(archive.signals()).hasSize(1);
        String html=result.getResponse().getContentAsString();
        assertThat(html).contains("page=0&amp;state=unread&amp;ticker="+asset.getTickerSymbol()+"&amp;watchlistId="+watching.id());
        assertThat(html).contains("watchlistId="+watching.id()+"&amp;sort=confidence");
        String returnUrl=(String)result.getModelAndView().getModel().get("archiveReturnUrl");
        assertThat(returnUrl).contains("watchlistId="+watching.id(),"ticker="+asset.getTickerSymbol(),"page=1","state=unread");
        assertThat(html).contains("returnTo=");
    }

    @Test void editorAppliesIndependentDirectionsIntervalsAndOutlookWithEmailOptIns() {
        var choices=List.of(choice("CANDLESTICK","DAILY","BUY",true,true),choice("HARMONIC_FORMATION","WEEKLY","SELL",true,false),
                choice("OUTLOOK","MONTHLY","ANY",true,true),choice("ELLIOTT_WAVE","DAILY","BUY",false,true));
        var saved=save(null,choices,true,List.of(asset.getTickerSymbol())); finish(saved);
        assertThat(settings.active(owner,asset.getId())).extracting(WatchlistSignalSettingsService.Selection::key)
                .containsExactlyInAnyOrder("CANDLESTICK:DAILY:BUY","HARMONIC_FORMATION:WEEKLY:SELL","OUTLOOK:MONTHLY:ANY");
        assertThat(email("CANDLESTICK","DAILY","BUY")).isTrue();
        assertThat(email("HARMONIC_FORMATION","WEEKLY","SELL")).isFalse();
        assertThat(email("OUTLOOK","MONTHLY","ANY")).isTrue();
        assertThat(email("ELLIOTT_WAVE","DAILY","BUY")).isFalse();
        assertThat(settings.get(owner,saved.id()).mixed()).isFalse();
    }

    @Test void overlappingListSettingsCombineWithoutDuplicateRulesAndOptOutIsPerMatchingList() {
        var enabled=choice("CANDLESTICK","DAILY","BUY",true,true);
        var silent=choice("CANDLESTICK","DAILY","BUY",true,false);
        var first=save(null,List.of(enabled),true,List.of(asset.getTickerSymbol())); finish(first);
        var second=save(null,List.of(silent),true,List.of(asset.getTickerSymbol())); finish(second);
        assertThat(db.queryForObject("select count(*) from alert_rules where user_id=?",Long.class,owner.getId())).isEqualTo(1);
        assertThat(email("CANDLESTICK","DAILY","BUY")).isTrue();
        // Email-only edit is immediate and does not enqueue a monitoring change.
        var mailEdit=save(first.id(),List.of(silent),false,List.of());
        assertThat(mailEdit.importId()).isNull();
        assertThat(email("CANDLESTICK","DAILY","BUY")).isFalse();
        finish(save(first.id(),List.of(choice("CANDLESTICK","DAILY","BUY",false,true)),true,List.of()));
        assertThat(settings.active(owner,asset.getId())).hasSize(1);
        assertThat(lists.lists(owner).stream().filter(l->l.id()==first.id()).findFirst().orElseThrow().monitored()).isZero();
        assertThat(email("CANDLESTICK","DAILY","BUY")).isFalse();
        lists.delete(owner,second.id());
        assertThat(settings.active(owner,asset.getId())).isEmpty();
    }

    @Test void tickerOnlyChoicesSkipEtfsAndKeepTechnicalSelections() {
        asset.setInstrumentType(InstrumentType.ETF);assets.saveAndFlush(asset);
        var choices=List.of(choice("CANDLESTICK","DAILY","BUY",true,false),choice("CONGRESS","DAILY","ANY",true,true),choice("INSIDER","DAILY","ANY",true,true));
        var preview=imports.preview(owner,null,new WatchlistImportService.ImportRequest(List.of(asset.getTickerSymbol()),List.of(),false,List.of(),List.of(),choices,false,true));
        assertThat(preview.skippedTickerInstruments()).isEqualTo(1);
        assertThat(preview.eligibleTickerStocks()).isZero();
        var saved=save(null,choices,true,List.of(asset.getTickerSymbol())); finish(saved);
        assertThat(settings.active(owner,asset.getId())).extracting(WatchlistSignalSettingsService.Selection::type).containsExactly("CANDLESTICK");
        assertThat(email("INSIDER","DAILY","ANY")).isFalse();
        assertThat(settings.get(owner,saved.id()).mixed()).isFalse();
    }

    @Test void stockApplyAddsCandleFollowsToSelectedExistingListAndPreservesActivityFollows() throws Exception {
        org.mockito.Mockito.when(congressProvider.fetchTickerHistory(asset.getTickerSymbol())).thenThrow(new IllegalStateException("Test provider unavailable"));
        org.mockito.Mockito.when(insiderProvider.fetchTickerTrades(asset.getTickerSymbol())).thenThrow(new IllegalStateException("Test provider unavailable"));
        var activity=List.of(choice("CONGRESS","DAILY","ANY",true,false),choice("INSIDER","DAILY","ANY",true,false));
        var selected=save(null,activity,true,List.of(asset.getTickerSymbol())); finish(selected);
        var skipped=save(null,activity,true,List.of(asset.getTickerSymbol())); finish(skipped);
        String request="""
                {"changes":[{"interval":"DAILY","signal":"BUY","patternFamily":"CANDLESTICK","active":true}],
                 "watchlistIds":[%d],"outlookChanges":[]}
                """.formatted(selected.id());
        // Reapplying the same follow must not duplicate memberships or monitoring rules.
        for (int attempt=0; attempt<2; attempt++) {
            mvc.perform(put("/api/alerts/"+asset.getTickerSymbol()).with(user(owner.getEmail()))
                    .sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()).with(csrf())
                    .contentType("application/json").content(request)).andExpect(status().isOk());
        }
        assertThat(lists.members(owner,selected.id(),0,"all","").items().getFirst().signals())
                .extracting(WatchlistSignalSettingsService.Selection::key)
                .containsExactlyInAnyOrder("CONGRESS:DAILY:ANY","INSIDER:DAILY:ANY","CANDLESTICK:DAILY:BUY");
        assertThat(lists.members(owner,skipped.id(),0,"all","").items().getFirst().signals())
                .extracting(WatchlistSignalSettingsService.Selection::key)
                .containsExactlyInAnyOrder("CONGRESS:DAILY:ANY","INSIDER:DAILY:ANY");
        assertThat(lists.memberships(owner,asset.getTickerSymbol())).containsExactlyInAnyOrder(selected.id(),skipped.id());
        assertThat(settings.active(owner,asset.getId())).extracting(WatchlistSignalSettingsService.Selection::key)
                .containsExactlyInAnyOrder("CONGRESS:DAILY:ANY","INSIDER:DAILY:ANY","CANDLESTICK:DAILY:BUY");
        assertThat(db.queryForObject("select count(*) from alert_rules where user_id=? and stock_asset_id=?",Long.class,owner.getId(),asset.getId())).isEqualTo(1);
    }

    @Test void stockTickerAlertsPersistThroughTheEditorAndCanBeDisabledLater() {
        org.mockito.Mockito.when(congressProvider.fetchTickerHistory(asset.getTickerSymbol())).thenThrow(new IllegalStateException("Test provider unavailable"));
        org.mockito.Mockito.when(insiderProvider.fetchTickerTrades(asset.getTickerSymbol())).thenThrow(new IllegalStateException("Test provider unavailable"));
        var saved=save(null,List.of(choice("CONGRESS","DAILY","ANY",true,true),choice("INSIDER","DAILY","ANY",true,false)),true,List.of(asset.getTickerSymbol()));
        finish(saved);
        assertThat(settings.active(owner,asset.getId())).extracting(WatchlistSignalSettingsService.Selection::type).containsExactlyInAnyOrder("CONGRESS","INSIDER");
        assertThat(email("CONGRESS","DAILY","ANY")).isTrue();
        assertThat(email("INSIDER","DAILY","ANY")).isFalse();
        assertThat(lists.members(owner,saved.id(),0,"all","").items().getFirst().signals()).hasSize(2);
        finish(save(saved.id(),List.of(),true,List.of()));
        assertThat(settings.active(owner,asset.getId())).isEmpty();
    }

    @Test void outboxDiscardsAlreadyQueuedEmailAfterAListOptsOut() {
        var saved=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,true)),true,List.of(asset.getTickerSymbol()));finish(saved);
        long rule=db.queryForObject("select id from alert_rules where user_id=?",Long.class,owner.getId());
        long event=db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) values (?,1,current_timestamp,'BULLISH_ENGULFING','BUY') returning id",Long.class,rule);
        var sender=org.mockito.Mockito.mock(org.springframework.mail.javamail.JavaMailSender.class);
        @SuppressWarnings("unchecked") var provider=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(sender);
        var outbox=new EmailOutboxService(db,crypto,json,provider,transactions);
        org.springframework.test.util.ReflectionTestUtils.setField(outbox,"watchlistEmails",emails);
        var message=new org.springframework.mail.SimpleMailMessage();message.setFrom("alerts@example.test");message.setTo(owner.getEmail());message.setSubject("Test");message.setText("Test signal");
        outbox.enqueue(message,event,java.time.Duration.ofMinutes(5));
        save(saved.id(),List.of(choice("CANDLESTICK","DAILY","BUY",true,false)),false,List.of());
        outbox.deliverPending();
        assertThat(db.queryForObject("select expired_at is not null and ciphertext is null and iv is null and delivered_at is null from email_outbox where alert_event_id=?",Boolean.class,event)).isTrue();
        org.mockito.Mockito.verifyNoInteractions(sender);
        db.update("delete from email_outbox where alert_event_id=?",event);
    }

    @Test void pendingWorkUsesLatestDefaultsAndLegacyEmailEditsPreserveIndividualFollows() {
        var saved=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,true)),true,List.of(asset.getTickerSymbol()));
        save(saved.id(),List.of(choice("OUTLOOK","WEEKLY","ANY",true,false)),true,List.of());
        finish(saved);
        assertThat(settings.active(owner,asset.getId())).extracting(WatchlistSignalSettingsService.Selection::key).containsExactly("OUTLOOK:WEEKLY:ANY");
        long legacy=lists.create(owner,"Individual follows","");
        follows.apply(owner,asset.getTickerSymbol(),List.of(buy()),List.of(legacy),null);
        var before=settings.requested(owner,asset.getId());
        var mailOnly=settings.get(owner,legacy).selections().stream().map(v->choice(v.type(),v.interval(),v.direction(),v.watch(),false)).toList();
        assertThat(save(legacy,mailOnly,false,List.of()).importId()).isNull();
        assertThat(settings.requested(owner,asset.getId())).containsExactlyInAnyOrderElementsOf(before);
        assertThat(email("CANDLESTICK","DAILY","BUY")).isFalse();
    }

    @Test void configuredNotificationOriginsAndQueuedMailFollowTheMatchingSettings() {
        var watching=save(null,List.of(choice("CANDLESTICK","DAILY","BUY",true,true)),true,List.of(asset.getTickerSymbol()));finish(watching);
        var unrelated=save(null,List.of(),true,List.of(asset.getTickerSymbol()));finish(unrelated);
        lists.update(owner,unrelated.id(),"Unrelated list","",false);
        long rule=db.queryForObject("select id from alert_rules where user_id=?",Long.class,owner.getId());
        long event=db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) values (?,1,current_timestamp,'BULLISH_ENGULFING','BUY') returning id",Long.class,rule);
        assertThat(lists.notificationOrigins(owner).get("TECHNICAL:"+event)).isEqualTo("Settings research");
        assertThat(emails.allowsQueuedEvent(event)).isTrue();
        save(watching.id(),List.of(choice("CANDLESTICK","DAILY","BUY",true,false)),false,List.of());
        assertThat(emails.allowsQueuedEvent(event)).isFalse();
    }

    @Test void invalidSettingsRollBackCreationAndSettingsEndpointsEnforceOwnership() throws Exception {
        assertThatThrownBy(()->save(null,List.of(choice("OUTLOOK","ONE_HOUR","ANY",true,true)),true,List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(lists.lists(owner)).isEmpty();
        long foreign=lists.create(other,"Private","");
        assertThatThrownBy(()->save(foreign,List.of(),true,List.of())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(get("/api/watchlists/"+foreign+"/settings").with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/watchlists/save").with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion())
                .contentType("application/json").content("{}")) .andExpect(status().isForbidden());
    }

    @Test void signupAccountsStartEmptyAndMembershipsExistWithoutAlerts() {
        assertThat(lists.lists(owner)).isEmpty();
        long id=lists.create(owner,"Long term","Independent of alert settings");
        lists.attach(owner,asset.getTickerSymbol(),List.of(id,id),null);
        assertThat(lists.lists(owner)).singleElement().satisfies(list->{assertThat(list.instruments()).isEqualTo(1);assertThat(list.monitored()).isZero();});
        assertThat(lists.members(owner,id,0,"all","").items()).singleElement().satisfies(item->assertThat(item.monitoring()).isNull());
    }
    @Test void overlappingListsReuseRulesAndOneScheduledJobAndRemovingOnlyTheLastStopsMonitoring() {
        long a=lists.create(owner,"Growth",""),b=lists.create(owner,"Long term","");
        follows.apply(owner,asset.getTickerSymbol(),List.of(buy()),List.of(a,b),null);
        follows.apply(owner,asset.getTickerSymbol(),List.of(buy()),List.of(a,b),null);
        assertThat(db.queryForObject("select count(*) from alert_rules where user_id=? and stock_asset_id=?",Long.class,owner.getId(),asset.getId())).isEqualTo(1);
        Instant run=Instant.now(); jobs.enqueueScheduledRun(TimeInterval.DAILY,run);
        assertThat(db.queryForObject("select count(*) from alert_check_jobs where symbol=?",Long.class,asset.getTickerSymbol())).isEqualTo(1);
        lists.remove(owner,a,asset.getTickerSymbol());
        assertThat(lists.lists(owner).stream().filter(l->l.id()==b).findFirst().orElseThrow().monitored()).isEqualTo(1);
        lists.delete(owner,b);
        assertThat(db.queryForObject("select count(*) from alert_rules where user_id=? and is_active",Long.class,owner.getId())).isZero();
    }
    @Test void applyChangesSavesInlineListAndMembershipTogetherAndRollsBackInvalidRules() throws Exception {
        String url="/api/alerts/"+asset.getTickerSymbol();
        mvc.perform(put(url).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion()).with(csrf()).contentType("application/json")
                .content("""
                {"changes":[{"interval":"DAILY","signal":"BUY","patternFamily":"CANDLESTICK","active":true}],"newWatchlistName":"US growth","watchlistIds":[]}
                """)).andExpect(status().isOk());
        assertThat(lists.lists(owner)).singleElement().satisfies(l->assertThat(l.name()).isEqualTo("US growth"));
        mvc.perform(put(url).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion()).with(csrf()).contentType("application/json")
                .content("""
                {"changes":[{"interval":"DAILY","signal":"HOLD","patternFamily":"CANDLESTICK","active":true}],"newWatchlistName":"Must roll back","watchlistIds":[]}
                """)).andExpect(status().isBadRequest());
        assertThat(lists.lists(owner)).hasSize(1);
    }
    @Test void ownershipAndCsrfAreEnforcedAndEmptySelectionCannotStartMonitoring() throws Exception {
        long foreign=lists.create(other,"Private","");
        assertThatThrownBy(()->lists.attach(owner,asset.getTickerSymbol(),List.of(foreign),"Must not exist")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(lists.lists(owner)).isEmpty();
        assertThatThrownBy(()->follows.apply(owner,asset.getTickerSymbol(),List.of(buy()),List.of(),null)).isInstanceOf(IllegalArgumentException.class);
        mvc.perform(get("/api/watchlists")).andExpect(status().is3xxRedirection());
        mvc.perform(delete("/api/watchlists/"+foreign).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion()).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/watchlists").with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion()).contentType("application/json").content("{\"name\":\"CSRF\"}")).andExpect(status().isForbidden());
    }
    @Test void notificationOriginsAreSnapshotsAndDeletedListsDoNotDeleteHistory() {
        long a=lists.create(owner,"Original name",""),b=lists.create(owner,"Other list","");
        follows.apply(owner,asset.getTickerSymbol(),List.of(buy()),List.of(a,b),null);
        long rule=db.queryForObject("select id from alert_rules where user_id=?",Long.class,owner.getId());
        long event=db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) values (?,?,current_timestamp,'BULLISH_ENGULFING','BUY') returning id",Long.class,rule,Instant.now().getEpochSecond());
        lists.update(owner,a,"Renamed","",false);lists.delete(owner,b);
        assertThat(lists.notificationOrigins(owner).get("TECHNICAL:"+event)).contains("Original name","Other list (deleted)").doesNotContain("Renamed");
        assertThat(lists.notificationOrigins(other)).isEmpty();
    }
    @Test void importPreviewDeduplicatesAndBackgroundRetryIsIdempotent() {
        long id=lists.create(owner,"Import test","");
        var request=new WatchlistImportService.ImportRequest(List.of(asset.getTickerSymbol(),asset.getTickerSymbol()),List.of(),true,List.of("CANDLESTICK"),List.of("DAILY"));
        var preview=imports.preview(owner,id,request);assertThat(preview.uniqueCount()).isEqualTo(1);assertThat(preview.duplicates()).isEqualTo(1);
        long job=imports.enqueue(owner,id,request);var work=imports.claim().orElseThrow();imports.process(work);imports.process(work);
        assertThat(imports.status(owner,job).completed()).isEqualTo(1);
        assertThat(lists.memberships(owner,asset.getTickerSymbol())).containsExactly(id);
        assertThat(db.queryForObject("select count(*) from alert_rules where user_id=?",Long.class,owner.getId())).isEqualTo(2);
    }
    @Test void internationalCatalogContainsCompleteDatedSnapshotsAndExchangeQualifiedSymbols() {
        assertThat(catalog.get("sp500").constituents()).hasSizeGreaterThanOrEqualTo(500);
        assertThat(catalog.get("tsx60").constituents()).hasSize(60).allMatch(i->i.symbol().endsWith(".TO"));
        assertThat(catalog.get("nikkei225").constituents()).hasSize(225).allMatch(i->i.symbol().endsWith(".T"));
        var request=new WatchlistImportService.ImportRequest(List.of("AAPL"),List.of("sp500","nasdaq100"),false,List.of(),List.of());
        var preview=imports.preview(owner,null,request);assertThat(preview.duplicates()).isPositive();assertThat(preview.allowed()).isTrue();
    }

    @Test void applyCanSaveAnOutlookOnlyDraftWithItsFirstWatchlist() throws Exception {
        String endpoint = "/api/alerts/" + asset.getTickerSymbol();
        mvc.perform(put(endpoint).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion())
                .with(csrf()).contentType("application/json").content("""
                {"changes":[],"outlookChanges":[{"interval":"DAILY","active":true}],"newWatchlistName":"Outlook research"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outlookState.intervals.DAILY").value(true));
        assertThat(lists.lists(owner)).singleElement().satisfies(list -> {
            assertThat(list.name()).isEqualTo("Outlook research");
            assertThat(list.monitored()).isEqualTo(1);
        });
        assertThat(db.queryForObject("select count(*) from alert_rules where user_id=?", Long.class, owner.getId())).isZero();
        mvc.perform(put(endpoint).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion())
                .with(csrf()).contentType("application/json").content("""
                {"changes":[],"outlookChanges":[{"interval":"DAILY","active":false}]}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outlookState.intervals.DAILY").value(false));
        assertThat(lists.lists(owner)).singleElement().satisfies(list -> assertThat(list.monitored()).isZero());
    }

    @Test void mixedDraftCommitsTogetherAndInvalidOutlookRollsBackPatternsAndMemberships() throws Exception {
        String endpoint = "/api/alerts/" + asset.getTickerSymbol();
        String draft = """
                {"changes":[{"interval":"DAILY","signal":"BUY","patternFamily":"CANDLESTICK","active":true}],
                 "outlookChanges":[{"interval":"%s","active":true}],"newWatchlistName":"Combined research"}
                """;
        mvc.perform(put(endpoint).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion())
                .with(csrf()).contentType("application/json").content(draft.formatted("ONE_HOUR")))
                .andExpect(status().isBadRequest());
        assertThat(lists.lists(owner)).isEmpty();
        assertThat(db.queryForObject("select count(*) from alert_rules where user_id=?", Long.class, owner.getId())).isZero();
        mvc.perform(put(endpoint).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion())
                .with(csrf()).contentType("application/json").content(draft.formatted("WEEKLY")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outlookState.intervals.WEEKLY").value(true));
        assertThat(lists.lists(owner)).hasSize(1);
        assertThat(db.queryForObject("select count(*) from alert_rules where user_id=? and is_active", Long.class, owner.getId())).isEqualTo(1);
    }

    @Test void failedImportsCanBeRetriedAndCannotBeReadByAnotherAccount() {
        long list = lists.create(owner, "Retry research", "");
        long job = imports.enqueue(owner, list, new WatchlistImportService.ImportRequest(
                List.of(asset.getTickerSymbol()), List.of(), false, List.of(), List.of()));
        var work = imports.claim().orElseThrow();
        imports.fail(work, new IllegalStateException("Temporary test failure"));
        assertThat(imports.status(owner, job).failed()).isEqualTo(1);
        assertThat(lists.memberships(owner, asset.getTickerSymbol())).isEmpty();
        assertThatThrownBy(() -> imports.status(other, job)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        imports.retry(owner, job);
        imports.process(imports.claim().orElseThrow());
        assertThat(imports.status(owner, job).completed()).isEqualTo(1);
        assertThat(imports.status(owner, job).failed()).isZero();
    }

    @Test void membershipPagesFilterWithinTheOwnedList() {
        long id = lists.create(owner, "Search", "");
        lists.attach(owner, asset.getTickerSymbol(), List.of(id), null);
        assertThat(lists.members(owner, id, 0, "stocks", asset.getTickerSymbol().toLowerCase()).total()).isEqualTo(1);
        assertThat(lists.members(owner, id, 0, "funds", "").total()).isZero();
        assertThat(lists.members(owner, id, 0, "all", "does not match").items()).isEmpty();
        assertThat(lists.members(owner, id, 1, "all", "").items()).isEmpty();
        assertThatThrownBy(() -> lists.members(other, id, 0, "all", "")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void allNotificationKindsCaptureMembershipsAndHomeRendersTheirOrigins() throws Exception {
        long list = lists.create(owner, "Notification research", "");
        follows.apply(owner, asset.getTickerSymbol(), List.of(buy()), List.of(list), null);
        long rule = db.queryForObject("select id from alert_rules where user_id=?", Long.class, owner.getId());
        long event = db.queryForObject("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) values (?,1,current_timestamp,'BULLISH_ENGULFING','BUY') returning id", Long.class, rule);
        long outlook = db.queryForObject("insert into technical_outlook_subscriptions(user_id,stock_asset_id,interval) values (?,?,'DAILY') returning id", Long.class, owner.getId(), asset.getId());
        long change = db.queryForObject("""
                insert into technical_outlook_notifications(subscription_id,previous_classification,current_classification,
                    previous_score,current_score,previous_candle_timestamp,current_candle_timestamp,
                    previous_snapshot,current_snapshot,change_report)
                values (?,'Neutral outlook','Moderate buy outlook',0,0.5,1,2,'{}','{}','{}') returning id
                """, Long.class, outlook);
        Map<String, Long> events = new HashMap<>(Map.of("TECHNICAL", event, "OUTLOOK", change));
        for (String kind : List.of("congressional", "insider")) {
            long subscription = db.queryForObject("insert into " + kind + "_trade_subscriptions(user_id,stock_asset_id) values (?,?) returning id", Long.class, owner.getId(), asset.getId());
            long trade;
            if (kind.equals("congressional")) {
                trade = db.queryForObject("""
                        insert into congressional_trades(stock_asset_id,provider,provider_fingerprint,member_name,chamber,
                            ticker_symbol,transaction_type,amount_range,transaction_date,disclosure_date)
                        values (?,'TEST',?,'Test member','HOUSE',?,'PURCHASE','$1,001 - $15,000',current_date,current_date) returning id
                        """, Long.class, asset.getId(), UUID.randomUUID().toString(), asset.getTickerSymbol());
            } else {
                trade = db.queryForObject("""
                        insert into insider_trades(stock_asset_id,provider,provider_fingerprint,ticker_symbol,insider_name,
                            transaction_type,transaction_code,transaction_date,filing_date)
                        values (?,'TEST',?,?,'Test insider','PURCHASE','P',current_date,current_date) returning id
                        """, Long.class, asset.getId(), UUID.randomUUID().toString(), asset.getTickerSymbol());
            }
            long delivery = db.queryForObject("insert into " + kind + "_trade_deliveries(subscription_id,trade_id,status) values (?,?,'SENT') returning id", Long.class, subscription, trade);
            events.put(kind.equals("congressional") ? "CONGRESS" : "INSIDER", delivery);
        }
        Set<String> keys = new HashSet<>(); events.forEach((kind, id) -> keys.add(kind + ":" + id));
        assertThat(lists.notificationOrigins(owner, keys)).hasSize(4).allSatisfy((key, name) -> assertThat(name).isEqualTo("Notification research"));
        assertThat(lists.technicalSummary(owner)).containsEntry("instruments", 1L).containsEntry("rules", 2L).containsEntry("tracked", 1L);
        mvc.perform(get("/home").with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Notification research")));
        lists.delete(owner, list);
        assertThat(lists.notificationOrigins(owner, keys)).hasSize(4).allSatisfy((key, name) -> assertThat(name).endsWith("(deleted)"));
        assertThat(lists.technicalSummary(owner).get("tracked")).isEqualTo(0L);
    }

    @Test void migrationPreservesAllExistingFollowTypesAndLeavesEmptyAccountsEmpty() throws Exception {
        String schema = "watchlist_migration_" + UUID.randomUUID().toString().replace("-", "");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .target("61").load().migrate();
            try (var connection = dataSource.getConnection()) {
                String originalSchema = connection.getSchema();
                connection.setSchema(schema);
                var isolated = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
                for (int index = 1; index <= 6; index++) {
                    isolated.update("insert into users(id,email,password_hash,first_name,last_name) values (?,?,'unused','Migration','Test')", index, "migration" + index + "@example.test");
                }
                isolated.update("insert into stock_assets(id,ticker_symbol,company_name,exchange) values (1,'MIGTEST','Migration test','NASDAQ')");
                isolated.update("insert into alert_rules(user_id,stock_asset_id,interval,target_pattern,pattern_family,trade_signal,is_active) values (1,1,'DAILY','BULLISH_ENGULFING','CANDLESTICK','BUY',true),(5,1,'DAILY','BULLISH_ENGULFING','CANDLESTICK','BUY',false)");
                isolated.update("insert into technical_outlook_subscriptions(user_id,stock_asset_id,interval) values (1,1,'DAILY'),(2,1,'DAILY')");
                isolated.update("insert into congressional_trade_subscriptions(user_id,stock_asset_id) values (3,1)");
                isolated.update("insert into insider_trade_subscriptions(user_id,stock_asset_id) values (4,1)");
                connection.setSchema(originalSchema);
            }
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(db.queryForList("select user_id from " + schema + ".watchlists order by user_id", Long.class)).containsExactly(1L, 2L, 3L, 4L);
            assertThat(db.queryForObject("select count(*) from " + schema + ".watchlist_members", Long.class)).isEqualTo(4);
            assertThat(db.queryForList("select distinct name from " + schema + ".watchlists", String.class)).containsExactly("My watchlist");
            assertThat(db.queryForObject("select count(*) from " + schema + ".watchlist_member_signals",Long.class)).isEqualTo(5);
            assertThat(db.queryForList("select distinct signal_type from " + schema + ".watchlist_member_signals",String.class))
                    .containsExactlyInAnyOrder("CANDLESTICK","OUTLOOK","CONGRESS","INSIDER");
            assertThat(db.queryForObject("select count(*) from " + schema + ".watchlists where settings_configured",Long.class)).isZero();
        } finally {
            // A fixed prefix and UUID keep cleanup restricted to this test's schema.
            if (!schema.matches("watchlist_migration_[a-f0-9]{32}")) throw new IllegalStateException("Unexpected test schema");
            db.execute("drop schema if exists " + schema + " cascade");
        }
    }
    @Test void pagesRenderNewNavigationAccordionAndPopupEntryPoint() throws Exception {
        long id=lists.create(owner,"My research","Test description");
        mvc.perform(get("/watchlists/"+id).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-named-watchlists")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-watchlist-shortcuts")));
        mvc.perform(get("/home").with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your watchlists")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-watchlist-shortcuts"))));
        mvc.perform(get("/stock/"+asset.getTickerSymbol()).with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-stock-watchlists")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-watchlist-shortcuts"))));
        mvc.perform(get("/technical-watchlist").with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-watchlist-shortcuts"))));
    }
}
