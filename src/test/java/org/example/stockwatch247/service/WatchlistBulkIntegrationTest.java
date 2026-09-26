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
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"alerts.schedule.enabled=false", "email-outbox.worker-enabled=false",
        "watchlists.imports.worker-enabled=false", "congressional-activity.enabled=false", "insider-activity.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class WatchlistBulkIntegrationTest {
    @Autowired WatchlistBulkService bulk;
    @Autowired WatchlistService lists;
    @Autowired WatchlistSignalSettingsService settings;
    @Autowired SignalArchiveQuery archive;
    @Autowired UserRepository users;
    @Autowired StockAssetRepository assets;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired jakarta.persistence.EntityManager entityManager;
    User owner, other;
    List<String> symbols;
    long list, overlap;

    @BeforeEach void setup() {
        owner = account(); other = account();
        list = lists.create(owner,"Bulk selection",""); overlap = lists.create(owner,"Overlap","");
        symbols = new ArrayList<>();
        for (int i=0;i<3;i++) {
            var asset = new StockAsset(); asset.setTickerSymbol("BK"+UUID.randomUUID().toString().substring(0,8).toUpperCase());
            asset.setCompanyName("Bulk fixture"); asset.setExchange("NASDAQ"); asset.setCurrency("USD");
            assets.saveAndFlush(asset); symbols.add(asset.getTickerSymbol());
            lists.attach(owner,asset.getTickerSymbol(),List.of(list),null);
        }
        lists.attach(owner,symbols.getFirst(),List.of(overlap),null);
    }
    private User account() {
        var u = new User(); u.setEmail("bulk-"+UUID.randomUUID()+"@example.test"); u.setPasswordHash("test-only");
        u.setFirstName("Bulk"); u.setLastName("Test"); u.setVerified(true); return users.saveAndFlush(u);
    }
    private WatchlistSignalSettingsService.Selection buy(boolean enabled) {
        return new WatchlistSignalSettingsService.Selection("CANDLESTICK","DAILY","BUY",enabled,false);
    }
    private long count(long id) { return db.queryForObject("select count(*) from watchlist_member_signals where watchlist_id=?",Long.class,id); }

    @Test void selectedFollowsPreserveOtherMembersOtherListsAndDefaults() throws Exception {
        bulk.apply(owner,overlap,List.of(symbols.getFirst()),List.of(buy(true)));
        bulk.apply(owner,list,List.of(symbols.getFirst()),List.of(buy(true)));
        assertThat(bulk.preview(owner,list,symbols.subList(0,2)).mixedKeys()).contains("CANDLESTICK:DAILY:BUY");
        mvc.perform(post("/api/watchlists/"+list+"/members/follows").with(csrf()).with(user(owner.getEmail()))
                .sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("symbols",symbols.subList(0,2),"changes",List.of(buy(true))))))
                .andExpect(status().isOk());
        assertThat(count(list)).isEqualTo(2);
        assertThat(settings.configured(list)).isFalse();
        bulk.apply(owner,list,symbols.subList(0,2),List.of(buy(false)));
        entityManager.flush();
        assertThat(count(list)).isZero(); assertThat(count(overlap)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from alert_rules where user_id=? and is_active",Long.class,owner.getId())).isEqualTo(1);
    }

    @Test void removalIsScopedAndInvalidSelectionDoesNotPartiallyMutate() throws Exception {
        assertThatThrownBy(() -> bulk.remove(owner,list,List.of(symbols.getFirst(),"MISSING"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(lists.symbols(owner,list)).hasSize(3);
        mvc.perform(post("/api/watchlists/"+list+"/members/remove").with(csrf()).with(user(other.getEmail()))
                .sessionAttr(AccountSession.SECURITY_VERSION,other.getSecurityVersion()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("symbols",symbols))))
                .andExpect(status().isNotFound());
        bulk.remove(owner,list,symbols.subList(0,2));
        assertThat(lists.symbols(owner,list)).containsExactly(symbols.get(2));
        assertThat(lists.symbols(owner,overlap)).containsExactly(symbols.getFirst());
    }

    @Test void multiTickerArchiveFiltersBeforeSortingAndPaginationAndPreservesNavigation() throws Exception {
        bulk.apply(owner,list,symbols,List.of(buy(true)));
        for (String symbol : symbols) {
            long rule = db.queryForObject("select r.id from alert_rules r join stock_assets a on a.id=r.stock_asset_id where r.user_id=? and a.ticker_symbol=?",Long.class,owner.getId(),symbol);
            db.update("insert into alert_events(alert_rule_id,signal_candle_timestamp,sent_at,pattern,trade_signal) values (?,1,current_timestamp,'BULLISH_ENGULFING','BUY')",rule);
        }
        var tickers = String.join(",", symbols.subList(0,2));
        var filter = new SignalArchiveFilter("all",tickers.toLowerCase(),list);
        for (String sort : List.of("date","ticker","confidence","trade-return")) {
            var result = archive.page(owner.getId(),null,sort,false,0,1,filter);
            assertThat(result.count()).isEqualTo(2); assertThat(result.pages()).isEqualTo(2);
            assertThat(result.ids()).hasSize(1);
            assertThat(archive.page(other.getId(),null,sort,false,0,1,filter).count()).isZero();
        }
        mvc.perform(get("/signals").param("watchlistId",String.valueOf(list)).param("ticker",tickers)
                .with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION,owner.getSecurityVersion()))
                .andExpect(status().isOk()).andExpect(model().attribute("archiveFilter",filter))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(tickers)));
    }
}
