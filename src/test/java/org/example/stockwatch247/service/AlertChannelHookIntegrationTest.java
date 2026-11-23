package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.SignalStength;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.service.CandlePatternDetectionService.DetectedSignal;
import org.example.stockwatch247.service.channel.AlertKind;
import org.example.stockwatch247.service.channel.ChannelFanout;
import org.example.stockwatch247.service.channel.ChannelMessage;
import org.example.stockwatch247.service.channel.ChannelStatus;
import org.example.stockwatch247.service.channel.ChannelStore;
import org.example.stockwatch247.service.channel.ChannelType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Every kind of alert reaches the account's channels, with email off as well as on, and email stays as it was. */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "email-outbox.worker-enabled=false",
        "alert-channels.enabled=true", "alert-channels.worker-enabled=false"})
class AlertChannelHookIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelStore store;
    @Autowired ChannelFanout fanout;
    @Autowired SecurityCryptoService crypto;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired WatchlistEmailPolicy watchlists;

    private long userId;
    private long assetId;
    private long ruleId;
    private String tag;
    private ChannelStore.Channel channel;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString();
        userId = jdbc.queryForObject("insert into users(email,password_hash,first_name,last_name) values (?,'unused','Hook','Test') returning id",
                Long.class, tag + "@example.test");
        assetId = jdbc.queryForObject("insert into stock_assets(ticker_symbol,company_name,exchange) values (?,'Hook test','TEST') returning id",
                Long.class, "HK" + tag.substring(0, 8).toUpperCase());
        ruleId = jdbc.queryForObject("insert into alert_rules(user_id,stock_asset_id,interval,target_pattern,pattern_family,trade_signal) "
                + "values (?,?,'DAILY','HAMMER','CANDLESTICK','BUY') returning id", Long.class, userId, assetId);
        String url = "https://discord.com/api/webhooks/1/" + tag;
        channel = store.create(new ChannelStore.NewChannel(userId, ChannelType.DISCORD, "Desk", "discord.com", "discord",
                ChannelStatus.ACTIVE, EnumSet.allOf(AlertKind.class), Set.of("BUY", "SELL"), "en", Map.of("webhookUrl", url), url));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from users where id = ?", userId);
        jdbc.update("delete from stock_assets where id = ?", assetId);
    }

    private AlertNotificationService service(boolean email, boolean withChannels) {
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(JavaMailSender.class));
        var service = new AlertNotificationService(provider, email, "alerts@example.test", "UTC");
        service.setOutbox(new EmailOutboxService(jdbc, crypto, mapper, provider, manager));
        if (withChannels) service.setChannels(fanout);
        ReflectionTestUtils.setField(service, "watchlistEmails", watchlists);
        return service;
    }

    private AlertRule rule() {
        User user = new User();
        user.setId(userId);
        user.setEmail(tag + "@example.test");
        StockAsset asset = new StockAsset();
        asset.setId(assetId);
        asset.setTickerSymbol("HK" + tag.substring(0, 8).toUpperCase());
        AlertRule rule = new AlertRule();
        rule.setId(ruleId);
        rule.setUser(user);
        rule.setStockAsset(asset);
        rule.setInterval(TimeInterval.DAILY);
        rule.setPatternFamily(AlertPatternFamily.CANDLESTICK);
        rule.setTradeSignal(TradeSignal.BUY);
        return rule;
    }

    private AlertEvent event(AlertRule rule, long candle) {
        return event(rule, candle, CandlePattern.HAMMER);
    }

    private AlertEvent event(AlertRule rule, long candle, CandlePattern pattern) {
        long id = jdbc.queryForObject("insert into alert_events(alert_rule_id,pattern,trade_signal,signal_candle_timestamp,"
                + "confidence_score,close_price) values (?,?,'BUY',?,88,100) returning id", Long.class, ruleId, pattern.name(), candle);
        AlertEvent event = new AlertEvent();
        event.setId(id);
        event.setAlertRule(rule);
        event.setPattern(pattern);
        event.setTradeSignal(TradeSignal.BUY);
        event.setSignalCandleTimestamp(candle);
        event.setConfidenceScore(88);
        event.setClosePrice(100.0);
        return event;
    }

    private DetectedSignal signal(long candle) {
        return new DetectedSignal(CandlePattern.HAMMER, TradeSignal.BUY, SignalStength.HIGH_CONFIDENCE, 88,
                List.of("long lower shadow"), candle, 100.0);
    }

    private List<Map<String, Object>> channelRows() {
        return jdbc.queryForList("select deduplication_key, alert_kind, group_key, alert_event_id, chart_alert_event_id, "
                + "available_at > current_timestamp as held, ciphertext, iv, id from alert_channel_deliveries "
                + "where channel_id = ? order by created_at, deduplication_key", channel.id());
    }

    private ChannelMessage message(Map<String, Object> row) {
        return mapper.readValue(crypto.decrypt((String) row.get("ciphertext"), (String) row.get("iv"),
                "channel-delivery:" + row.get("id")), ChannelMessage.class);
    }

    private String emailText(long eventId) {
        var row = jdbc.queryForMap("select id, ciphertext, iv from email_outbox where alert_event_id = ?", eventId);
        return mapper.readValue(crypto.decrypt((String) row.get("ciphertext"), (String) row.get("iv"),
                "email-outbox:" + row.get("id")), EmailOutboxService.Payload.class).text();
    }

    @Test
    void aSignalReachesTheChannelWithEmailOffAndTheEmailIsUnchangedWithChannels() {
        long candle = Instant.parse("2026-07-20T00:00:00Z").getEpochSecond();
        AlertRule rule = rule();
        AlertEvent emailOff = event(rule, candle);

        assertThat(service(false, true).sendSignalEmail(rule, signal(candle), emailOff)).isFalse();
        var rows = channelRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("deduplication_key", "initial-alert:" + emailOff.getId())
                .containsEntry("alert_kind", "SIGNAL_CANDLESTICK").containsEntry("alert_event_id", emailOff.getId());
        assertThat((String) rows.getFirst().get("group_key")).startsWith("point:");
        ChannelMessage sent = message(rows.getFirst());
        assertThat(sent.subject()).startsWith("StockWatch ").endsWith("Hammer on " + rule.getStockAsset().getTickerSymbol());
        assertThat(sent.body()).contains("Heuristic setup score: 88/100").doesNotContain("Manage alert emails");
        assertThat(sent.url()).endsWith("/alerts/signals/" + emailOff.getId());
        assertThat(sent.facts()).containsEntry("pattern", "Hammer").containsEntry("direction", "Buy");
        assertThat(jdbc.queryForObject("select count(*) from email_outbox where alert_event_id = ?", Integer.class,
                emailOff.getId())).isZero();

        // The same alert with and without channels produces the same email.
        AlertEvent both = event(rule, candle + 86400);
        assertThat(service(true, false).sendSignalEmail(rule, signal(candle + 86400), both)).isTrue();
        String withoutChannels = emailText(both.getId());
        jdbc.update("delete from email_outbox where alert_event_id = ?", both.getId());
        assertThat(service(true, true).sendSignalEmail(rule, signal(candle + 86400), both)).isTrue();
        assertThat(emailText(both.getId())).isEqualTo(withoutChannels);
        assertThat(channelRows()).hasSize(2);
    }

    @Test
    void aLifecycleFollowUpReachesTheChannelAsItsOutcomeKind() {
        AlertRule rule = rule();
        AlertEvent event = event(rule, Instant.parse("2026-07-20T00:00:00Z").getEpochSecond());
        event.setLifecycleStatus(SignalLifecycleStatus.INVALIDATED);
        event.setPatternHigh(105.0);
        event.setPatternLow(95.0);
        event.setConfirmationTriggerPrice(105.0);
        event.setInvalidationPrice(95.0);
        event.setConfirmationWindowCandles(10);
        event.setResolutionCandleTimestamp(Instant.parse("2026-07-21T00:00:00Z").getEpochSecond());
        event.setResolutionClosePrice(94.0);

        assertThat(service(false, true).sendSignalLifecycleEmail(event)).isFalse();
        var rows = channelRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("alert_kind", "OUTCOME_INVALIDATED");
        assertThat((String) rows.getFirst().get("deduplication_key")).startsWith("follow-up:" + event.getId() + ":lifecycle:INVALIDATED");
        assertThat(rows.getFirst().get("group_key")).isNull();
        // An account that does not want invalidations on this channel gets nothing new.
        store.updateChoices(channel, EnumSet.of(AlertKind.OUTCOME_CONFIRMED), Set.of("BUY", "SELL"), null);
        event.setResolutionCandleTimestamp(Instant.parse("2026-07-22T00:00:00Z").getEpochSecond());
        assertThat(service(false, true).sendSignalLifecycleEmail(event)).isFalse();
        assertThat(channelRows()).hasSize(1);
    }

    @Test
    void signalsAtTheSamePointWaitForTheirCheckAndShareAGroupWithEmailOff() {
        long candle = Instant.parse("2026-07-20T00:00:00Z").getEpochSecond();
        AlertRule rule = rule();
        AlertNotificationService service = service(false, true);
        AlertEvent first = event(rule, candle);
        AlertEvent second = event(rule, candle, CandlePattern.BULLISH_ENGULFING);
        try (var batch = service.openSamePointBatch()) {
            assertThat(batch).isNotNull();
            service.sendSignalEmail(rule, signal(candle), first);
            service.sendSignalEmail(rule, signal(candle), second);
            var held = channelRows();
            assertThat(held).hasSize(2).allSatisfy(row -> assertThat(row).containsEntry("held", true));
            assertThat(held.get(0).get("group_key")).isEqualTo(held.get(1).get("group_key"));
        }
        assertThat(channelRows()).allSatisfy(row -> assertThat(row).containsEntry("held", false));
    }

    @Test
    void channelsFollowWhatAWatchlistFollowsWhateverItsEmailSwitchSays() {
        String symbol = "HK" + tag.substring(0, 8).toUpperCase();
        long list = jdbc.queryForObject("insert into watchlists(user_id,name,settings_configured) values (?,?,true) returning id",
                Long.class, userId, "List " + tag);
        jdbc.update("insert into watchlist_members(watchlist_id,stock_asset_id) values (?,?)", list, assetId);
        jdbc.update("insert into watchlist_member_signals(watchlist_id,stock_asset_id,signal_type,interval,direction) "
                + "values (?,?,'CANDLESTICK','DAILY','BUY')", list, assetId);
        jdbc.update("insert into watchlist_signal_settings(watchlist_id,signal_type,interval,direction,watched,email_enabled) "
                + "values (?,'CANDLESTICK','DAILY','BUY',true,false)", list);

        assertThat(watchlists.allows(userId, symbol, "CANDLESTICK", "DAILY", "BUY")).isFalse();
        assertThat(watchlists.follows(userId, symbol, "CANDLESTICK", "DAILY", "BUY")).isTrue();
        assertThat(watchlists.follows(userId, symbol, "CANDLESTICK", "DAILY", "SELL")).isFalse();

        long candle = Instant.parse("2026-07-20T00:00:00Z").getEpochSecond();
        AlertRule rule = rule();
        assertThat(service(true, true).sendSignalEmail(rule, signal(candle), event(rule, candle))).isFalse();
        assertThat(channelRows()).hasSize(1);
    }

    @Test
    void aChartAlertReachesTheChannelWithALinkToItsChart() {
        long alert = jdbc.queryForObject("insert into chart_alerts(user_id,symbol,interval,source_kind,source_id,label,condition,"
                + "definition,frequency) values (?,'AAPL','DAILY','DRAWING','d1','Trend line','CROSSES_UP','{}','ONCE') returning id",
                Long.class, userId);
        long trigger = jdbc.queryForObject("insert into chart_alert_events(alert_id,candle_timestamp,close_price) values (?,1,10) returning id",
                Long.class, alert);
        User user = rule().getUser();
        assertThat(service(false, true).sendChartAlertEmail(user, TimeInterval.DAILY, "Chart alert for AAPL: Trend line",
                "Chart alert: Trend line", trigger, "AAPL")).isFalse();
        var rows = channelRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("alert_kind", "CHART").containsEntry("chart_alert_event_id", trigger)
                .containsEntry("deduplication_key", "chart-alert:" + trigger);
        assertThat(message(rows.getFirst()).url()).endsWith("/stock/AAPL");
        jdbc.update("delete from chart_alert_events where id = ?", trigger);
        assertThat(channelRows()).isEmpty();
    }
}
