package org.example.stockwatch247.service;

import org.example.stockwatch247.model.*;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.repository.*;
import org.example.stockwatch247.security.AccountSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"alerts.schedule.enabled=false", "email-outbox.worker-enabled=false"})
@AutoConfigureMockMvc
@Transactional
@org.springframework.context.annotation.Import(org.example.stockwatch247.support.DesignPreviewCapture.class)
class TechnicalWatchlistIntegrationTest {
    @Autowired UserRepository users;
    @Autowired StockAssetRepository assets;
    @Autowired TechnicalOutlookSubscriptionRepository subscriptions;
    @Autowired TechnicalOutlookNotificationRepository notifications;
    @Autowired TechnicalWatchlistService watchlist;
    @Autowired TechnicalOutlookTrackingService tracking;
    @Autowired MockMvc mvc;
    @Autowired AlertRuleService alertRules;
    @Autowired AlertRuleRepository rules;
    User owner, other;
    StockAsset asset;
    long now;

    @BeforeEach void setup() {
        owner = account(); other = account(); now = Instant.now().getEpochSecond();
        asset = new StockAsset(); asset.setTickerSymbol("WL" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        asset.setCompanyName("Watchlist test company"); asset.setExchange("NASDAQ"); assets.saveAndFlush(asset);
    }

    @Test void projectionsGroupIntervalsPreserveSignedPriceProgressAndDoNotReadLargeSnapshots() {
        var daily = subscription(owner, TimeInterval.DAILY);
        daily.setCurrentClassification("Strong sell outlook"); daily.setCurrentScore(-0.8);
        daily.setCurrentPrice(90.0); daily.setLastChangePrice(100.0); daily.setLastChangeScore(-0.6);
        // Deliberately not JSON: the dashboard must use compact persisted fields, not chart snapshots.
        daily.setBaselineSnapshot("large snapshot is not needed"); subscriptions.saveAndFlush(daily);
        subscription(owner, TimeInterval.WEEKLY);
        subscription(other, TimeInterval.MONTHLY);

        var result = watchlist.watchlist(owner);
        assertThat(result.tickers()).hasSize(1);
        assertThat(result.tickers().getFirst().intervals()).hasSize(2);
        var outlook = result.tickers().getFirst().intervals().stream()
                .filter(item -> item.interval() == TimeInterval.DAILY).findFirst().orElseThrow();
        assertThat(outlook.priceChangePercent()).isCloseTo(-10.0, within(0.0001));
        assertThat(outlook.scoreChangePoints()).isCloseTo(-20.0, within(0.0001));
        assertThat(outlook.score()).isEqualTo(-80.0);
        assertThat(outlook.available()).isTrue();
        assertThat(outlook.stale()).isFalse();
    }

    @Test void missingPricesSettingsChangesAndOldCandlesHaveExplicitStates() {
        var daily = subscription(owner, TimeInterval.DAILY);
        daily.setLastChangePrice(0.0); daily.setLastCandleTimestamp(now - 5 * 86_400); subscriptions.saveAndFlush(daily);
        var weekly = subscription(owner, TimeInterval.WEEKLY);
        weekly.setProfileFingerprint("previous-settings"); subscriptions.saveAndFlush(weekly);
        var result = watchlist.watchlist(owner).tickers().getFirst().intervals();
        var d = result.stream().filter(item -> item.interval() == TimeInterval.DAILY).findFirst().orElseThrow();
        var w = result.stream().filter(item -> item.interval() == TimeInterval.WEEKLY).findFirst().orElseThrow();
        assertThat(d.stale()).isTrue(); assertThat(d.priceChangePercent()).isNull();
        assertThat(w.available()).isFalse(); assertThat(w.settingsChanged()).isTrue();
        assertThat(w.score()).isNull(); assertThat(w.changedAt()).isNull();
    }

    @Test void aLegacySubscriptionDoesNotInventAChangeDateOrPriceReturn() {
        var legacy = subscription(owner, TimeInterval.DAILY);
        legacy.setTrackingStartedAt(null); legacy.setLastChangeCandleTimestamp(null);
        subscriptions.saveAndFlush(legacy);
        var outlook = watchlist.watchlist(owner).tickers().getFirst().intervals().getFirst();
        assertThat(outlook.available()).isTrue();
        assertThat(outlook.historyUnknown()).isTrue();
        assertThat(outlook.changedAt()).isNull();
        assertThat(outlook.priceChangePercent()).isNull();
        assertThat(outlook.scoreChangePoints()).isNull();
    }

    @Test void latestChangesIncludeReadEventsAndFilterBeforeTakingFive() {
        var daily = subscription(owner, TimeInterval.DAILY);
        var weekly = subscription(owner, TimeInterval.WEEKLY);
        var foreign = subscription(other, TimeInterval.DAILY);
        var inactive = subscription(owner, TimeInterval.MONTHLY); inactive.setActive(false); subscriptions.saveAndFlush(inactive);
        for (int index = 0; index < 7; index++) notification(daily, now - index);
        var read = notification(weekly, now - 100); read.setReadAt(LocalDateTime.now()); notifications.saveAndFlush(read);
        notification(foreign, now + 10); notification(inactive, now + 20);
        assertThat(tracking.latestFollowed(owner, null)).hasSize(5)
                .allMatch(item -> item.interval() == TimeInterval.DAILY);
        assertThat(tracking.latestFollowed(owner, TimeInterval.WEEKLY)).hasSize(1)
                .allMatch(TechnicalOutlookTrackingService.LatestOutlookChangeView::hasBeenRead);
        weekly.setTrackingStartedAt(LocalDateTime.now().plusSeconds(1)); subscriptions.saveAndFlush(weekly);
        assertThat(tracking.latestFollowed(owner, TimeInterval.WEEKLY)).isEmpty();
    }

    @Test void pageAndApiRequireAuthenticationAndUnfollowIsCsrfProtectedAndAccountScoped() throws Exception {
        subscription(owner, TimeInterval.DAILY); subscription(owner, TimeInterval.WEEKLY);
        subscription(other, TimeInterval.DAILY);
        mvc.perform(get("/technical-watchlist")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/technical-watchlist")).andExpect(status().is3xxRedirection());
        mvc.perform(signedIn(get("/technical-watchlist"))).andExpect(status().isOk())
                .andExpect(model().attribute("navigationSection", "watchlist"));
        mvc.perform(signedIn(get("/api/technical-watchlist"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.tickers.length()").value(1))
                .andExpect(jsonPath("$.tickers[0].intervals.length()").value(2));
        mvc.perform(signedIn(delete("/api/technical-watchlist/" + asset.getTickerSymbol())))
                .andExpect(status().isForbidden());
        mvc.perform(signedIn(delete("/api/technical-watchlist/" + asset.getTickerSymbol())).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subscriptionsRemoved").value(2));
        assertThat(watchlist.watchlist(owner).tickers()).isEmpty();
        assertThat(watchlist.watchlist(other).tickers()).hasSize(1);
        // Repeating a successful removal is harmless.
        mvc.perform(signedIn(delete("/api/technical-watchlist/" + asset.getTickerSymbol())).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subscriptionsRemoved").value(0));
    }

    @Test void unfollowAllLeavesOtherAccountsAndChangeHistoryIntact() throws Exception {
        var own = subscription(owner, TimeInterval.DAILY); subscription(owner, TimeInterval.MONTHLY);
        subscription(other, TimeInterval.WEEKLY);
        Long notificationId = notification(own, now).getId();
        mvc.perform(signedIn(delete("/api/technical-watchlist")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subscriptionsRemoved").value(2));
        assertThat(watchlist.watchlist(owner).tickers()).isEmpty();
        assertThat(watchlist.watchlist(other).tickers()).hasSize(1);
        assertThat(notifications.existsById(notificationId)).isTrue();
        assertThat(tracking.latestFollowed(owner, null)).isEmpty();
    }

    @Test void subscriptionMutationQueriesCanLockRowsWithFetchedAssociations() {
        subscription(owner, TimeInterval.DAILY);
        assertThat(subscriptions.findByUserAndStockAssetAndInterval(owner, asset, TimeInterval.DAILY)).isPresent();
        assertThat(subscriptions.findByStockAsset_TickerSymbolIgnoreCaseAndIntervalAndActiveTrue(
                asset.getTickerSymbol(), TimeInterval.DAILY)).hasSize(1);
    }

    @Test void dashboardIncludesAutomatedAnalysisOnlyCompaniesAndTheirUnreadChanges() throws Exception {
        var daily = subscription(owner, TimeInterval.DAILY);
        subscription(owner, TimeInterval.WEEKLY);
        subscription(other, TimeInterval.MONTHLY);
        notification(daily, now);
        var companies = alertRules.getActiveCompanyViews(owner);
        assertThat(companies).singleElement().satisfies(company -> {
            assertThat(company.familyLabels()).containsExactly("Automated Technical Analysis");
            assertThat(company.intervalLabels()).containsExactly("Daily", "Weekly");
            assertThat(company.ruleCount()).isEqualTo(2);
            assertThat(company.unreadSignalCount()).isEqualTo(1);
            assertThat(company.representativeAlertId()).isNull();
        });
        mvc.perform(signedIn(get("/home"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "href=\"/stock/" + asset.getTickerSymbol() + "/technical-outlook\"")));
        mvc.perform(signedIn(get("/api/alerts/" + asset.getTickerSymbol())))
                .andExpect(jsonPath("$.activeRules.length()").value(0))
                .andExpect(jsonPath("$.outlookSubscriptions.length()").value(2))
                .andExpect(jsonPath("$.outlookSubscriptions[0].familyLabel").value("Automated Technical Analysis"));
    }

    @Test void dashboardCanSelectivelyUnfollowAutomatedAnalysisWithoutTouchingPatternsOrOtherAccounts() throws Exception {
        var daily = subscription(owner, TimeInterval.DAILY);
        var weekly = subscription(owner, TimeInterval.WEEKLY);
        var foreign = subscription(other, TimeInterval.DAILY);
        Long historyId = notification(daily, now).getId();
        var rule = patternRule();
        assertThat(alertRules.getActiveCompanyViews(owner)).singleElement().satisfies(company -> {
            assertThat(company.familyLabels()).containsExactly("Candlestick", "Automated Technical Analysis");
            assertThat(company.ruleCount()).isEqualTo(3);
            assertThat(company.representativeAlertId()).isEqualTo(rule.getId());
        });
        mvc.perform(signedIn(get("/home"))).andExpect(status().isOk());
        String selection = "{\"ruleIds\":[],\"outlookSubscriptionIds\":[" + daily.getId() + "," + foreign.getId() + "]}";
        mvc.perform(signedIn(delete("/api/alerts/" + asset.getTickerSymbol() + "/rules"))
                        .contentType("application/json").content(selection))
                .andExpect(status().isForbidden());
        mvc.perform(signedIn(delete("/api/alerts/" + asset.getTickerSymbol() + "/rules")).with(csrf())
                        .contentType("application/json").content(selection))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unfollowedRules").value(1));
        assertThat(subscriptions.findById(daily.getId()).orElseThrow().isActive()).isFalse();
        assertThat(subscriptions.findById(weekly.getId()).orElseThrow().isActive()).isTrue();
        assertThat(subscriptions.findById(foreign.getId()).orElseThrow().isActive()).isTrue();
        assertThat(rules.findById(rule.getId()).orElseThrow().isActive()).isTrue();
        assertThat(notifications.existsById(historyId)).isTrue();
    }

    @Test void companyUnfollowAllIncludesBothKindsAndPreservesHistoryAndOtherStocks() throws Exception {
        var daily = subscription(owner, TimeInterval.DAILY);
        var foreign = subscription(other, TimeInterval.DAILY);
        var rule = patternRule();
        Long historyId = notification(daily, now).getId();
        StockAsset second = new StockAsset(); second.setTickerSymbol("SECOND" + owner.getId());
        second.setCompanyName("Other followed company"); second.setExchange("NASDAQ"); assets.saveAndFlush(second);
        var otherStock = subscription(owner, TimeInterval.WEEKLY);
        otherStock.setStockAsset(second); subscriptions.saveAndFlush(otherStock);
        mvc.perform(signedIn(delete("/api/alerts/" + asset.getTickerSymbol())).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unfollowedRules").value(2));
        assertThat(subscriptions.findById(daily.getId()).orElseThrow().isActive()).isFalse();
        assertThat(rules.findById(rule.getId()).orElseThrow().isActive()).isFalse();
        assertThat(subscriptions.findById(foreign.getId()).orElseThrow().isActive()).isTrue();
        assertThat(subscriptions.findById(otherStock.getId()).orElseThrow().isActive()).isTrue();
        assertThat(notifications.existsById(historyId)).isTrue();
        mvc.perform(signedIn(delete("/api/alerts")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unfollowedRules").value(1));
        assertThat(subscriptions.findById(foreign.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test void mixedSelectionValidatesBeforeMutatingAndRestrictsBothKindsToTheRequestedStock() throws Exception {
        var daily = subscription(owner, TimeInterval.DAILY);
        var rule = patternRule();
        String endpoint = "/api/alerts/" + asset.getTickerSymbol() + "/rules";
        mvc.perform(signedIn(delete(endpoint)).with(csrf()).contentType("application/json")
                        .content("{\"ruleIds\":[" + rule.getId() + "],\"outlookSubscriptionIds\":[-1]}"))
                .andExpect(status().isBadRequest());
        assertThat(rules.findById(rule.getId()).orElseThrow().isActive()).isTrue();
        mvc.perform(signedIn(delete("/api/alerts/OTHER/rules")).with(csrf()).contentType("application/json")
                        .content("{\"ruleIds\":[" + rule.getId() + "],\"outlookSubscriptionIds\":[" + daily.getId() + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unfollowedRules").value(0));
        assertThat(subscriptions.findById(daily.getId()).orElseThrow().isActive()).isTrue();
        mvc.perform(signedIn(delete(endpoint)).with(csrf()).contentType("application/json")
                        .content("{\"ruleIds\":[" + rule.getId() + "],\"outlookSubscriptionIds\":[" + daily.getId() + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unfollowedRules").value(2));
        assertThat(alertRules.getActiveCompanyViews(owner)).isEmpty();
    }

    private AlertRule patternRule() {
        var rule = new AlertRule(); rule.setUser(owner); rule.setStockAsset(asset); rule.setInterval(TimeInterval.DAILY);
        return rules.saveAndFlush(rule);
    }

    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(user(owner.getEmail())).sessionAttr(AccountSession.SECURITY_VERSION, owner.getSecurityVersion());
    }
    private User account() {
        User user = new User(); user.setEmail("watchlist-" + UUID.randomUUID() + "@example.com");
        user.setFirstName("Review"); user.setLastName("User"); user.setVerified(true); user.setPasswordHash("test-only");
        return users.saveAndFlush(user);
    }
    private TechnicalOutlookSubscription subscription(User user, TimeInterval interval) {
        var subscription = new TechnicalOutlookSubscription(); subscription.setUser(user); subscription.setStockAsset(asset);
        subscription.setInterval(interval); subscription.setActive(true); subscription.setCurrentClassification("Moderate buy outlook");
        subscription.setCurrentScore(0.6); subscription.setCurrentPrice(110.0); subscription.setLastCandleTimestamp(now);
        subscription.setLastChangeCandleTimestamp(now - 86_400); subscription.setLastChangePrice(100.0);
        subscription.setLastChangeScore(0.4); subscription.setLastChangePreviousClassification("Neutral outlook");
        subscription.setProfileFingerprint(tracking.profileFingerprints(user).get(interval));
        subscription.setTrackingStartedAt(LocalDateTime.now().minusDays(30));
        return subscriptions.saveAndFlush(subscription);
    }
    private TechnicalOutlookNotification notification(TechnicalOutlookSubscription subscription, long timestamp) {
        var notification = new TechnicalOutlookNotification(); notification.setSubscription(subscription);
        notification.setPreviousClassification("Neutral outlook"); notification.setCurrentClassification("Moderate buy outlook");
        notification.setPreviousCandleTimestamp(timestamp - 86_400); notification.setCurrentCandleTimestamp(timestamp);
        notification.setPreviousSnapshot("{}"); notification.setCurrentSnapshot("{}"); notification.setChangeReport("{}");
        return notifications.saveAndFlush(notification);
    }
}
