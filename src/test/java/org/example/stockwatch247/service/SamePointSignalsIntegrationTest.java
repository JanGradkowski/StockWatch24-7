package org.example.stockwatch247.service;

import org.example.stockwatch247.model.AlertEvent;
import org.example.stockwatch247.model.AlertRule;
import org.example.stockwatch247.model.StockAsset;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.AlertPatternFamily;
import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.SignalLifecycleStatus;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.model.enums.TradeSignal;
import org.example.stockwatch247.repository.AlertEventRepository;
import org.example.stockwatch247.repository.AlertRuleRepository;
import org.example.stockwatch247.repository.StockAssetRepository;
import org.example.stockwatch247.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Signals that end at the same point, as on KO (an AB=CD and an Alternate AB=CD sharing the 16 Nov D point) and on
 * TPG0 (a developing Wave II end and a completed A-B-C correction end at one top), read from the database.
 */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "email-outbox.worker-enabled=false"})
class SamePointSignalsIntegrationTest {
    private static final long CONFIRMATION = 1_794_960_000L;
    private static final long TOP = 1_794_787_200L;
    private static final LocalDateTime RECEIVED = LocalDateTime.of(2026, 11, 19, 0, 0);

    @Autowired UserRepository users;
    @Autowired StockAssetRepository assets;
    @Autowired AlertRuleRepository rules;
    @Autowired AlertEventRepository events;
    @Autowired SignalArchiveQuery query;
    @Autowired AlertRuleService archives;
    @Autowired UnreadCountService unread;
    @Autowired SignalArchiveDeletionService deletion;
    @Autowired SamePointSignals samePoint;
    @Autowired JdbcTemplate jdbc;
    @Autowired SecurityCryptoService crypto;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;

    @Test
    @Transactional
    void aPointIsOneRowLedByItsNewestSignalThatFitsTheTabAndActsAsOne() {
        User user = user();
        StockAsset asset = asset("KO");
        AlertRule harmonic = rule(user, asset, AlertPatternFamily.HARMONIC_FORMATION);
        AlertRule elliott = rule(user, asset, AlertPatternFamily.ELLIOTT_WAVE);
        AlertEvent abcd = harmonic(harmonic, CandlePattern.HARMONIC_AB_CD, 93, TOP, RECEIVED);
        AlertEvent alternate = harmonic(harmonic, CandlePattern.HARMONIC_ALTERNATE_AB_CD, 99, TOP, RECEIVED.plusSeconds(1));
        AlertEvent shark = harmonic(harmonic, CandlePattern.HARMONIC_SHARK, 81, TOP - 86_400L, RECEIVED);
        AlertEvent waveTwo = waveTwo(elliott, RECEIVED);
        AlertEvent correction = correction(elliott, RECEIVED.plusSeconds(1));
        events.flush();

        assertThat(jdbc.queryForList("select pattern_end_timestamp from alert_events where id in (?,?,?,?) order by id",
                Long.class, abcd.getId(), alternate.getId(), waveTwo.getId(), correction.getId())).containsOnly(TOP);

        // All: one row per point, led by its newest signal, naming the others.
        var all = query.page(user.getId(), null, "date", false, 0, 50, new SignalArchiveFilter("all", ""));
        assertThat(all.count()).isEqualTo(3);
        assertThat(all.ids()).containsExactlyInAnyOrder(alternate.getId(), shark.getId(), correction.getId());
        var page = archives.getSignalArchive(user, "date", "desc", 0, new SignalArchiveFilter("all", ""));
        var koRow = page.signals().stream().filter(entry -> entry.signal().id().equals(alternate.getId())).findFirst().orElseThrow();
        assertThat(koRow.samePoint()).extracting(AlertRuleService.LatestSignalView::id).containsExactly(abcd.getId());
        assertThat(page.signals().stream().filter(entry -> entry.signal().id().equals(shark.getId())).findFirst().orElseThrow()
                .samePoint()).isEmpty();
        assertThat(archives.getSamePointSignals(user, correction.getId()))
                .extracting(AlertRuleService.LatestSignalView::id).containsExactly(waveTwo.getId());

        // Each tab shows a point when one of its signals fits, led by that one.
        assertThat(query.page(user.getId(), null, "date", false, 0, 50, new SignalArchiveFilter("active", "")).ids())
                .contains(waveTwo.getId()).doesNotContain(correction.getId());
        assertThat(query.page(user.getId(), null, "date", false, 0, 50, new SignalArchiveFilter("completed", "")).ids())
                .containsExactly(correction.getId());
        var summary = query.summary(user.getId(), null, new SignalArchiveFilter("all", ""));
        assertThat(summary.total()).isEqualTo(3);
        // The TPG0 point is a row on both tabs: its wave II end is running, its correction finished.
        assertThat(summary.active()).isEqualTo(3);
        assertThat(summary.completed()).isEqualTo(1);
        assertThat(unread.counts(user).technical()).isEqualTo(3);
        assertThat(archives.getLatestSignalViews(user)).hasSize(3);
        assertThat(events.countByAlertRule(harmonic)).isEqualTo(2);

        // Opening the row reads the whole point; deleting it deletes the whole point.
        assertThat(archives.markSignalRead(user, alternate.getId())).isTrue();
        events.flush();
        assertThat(jdbc.queryForObject("select count(*) from alert_events where id in (?,?) and read_at is not null",
                Integer.class, abcd.getId(), alternate.getId())).isEqualTo(2);
        assertThat(unread.counts(user).technical()).isEqualTo(2);
        assertThat(events.countByAlertRuleAndReadAtIsNull(harmonic)).isEqualTo(1);
        assertThat(deletion.deleteTechnicalSignals(user, List.of(correction.getId()))).isEqualTo(1);
        events.flush();
        assertThat(jdbc.queryForObject("select count(*) from alert_events where id in (?,?) and deleted_at is not null",
                Integer.class, waveTwo.getId(), correction.getId())).isEqualTo(2);
        assertThat(query.page(user.getId(), null, "date", false, 0, 50, new SignalArchiveFilter("all", "")).count()).isEqualTo(2);

        // A count that moves on to its next wave leaves the point.
        assertThat(samePoint.others(List.of(alternate.getId())).get(alternate.getId())).containsExactly(abcd.getId());
        abcd.setHarmonicEndpointTimestamp(TOP - 2 * 86_400L);
        events.saveAndFlush(abcd);
        assertThat(samePoint.others(List.of(alternate.getId()))).isEmpty();
    }

    @Test
    void heldSignalEmailsAtOnePointGoOutAsOneEmailThatRecordsEverySignal() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender sender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(invocation -> new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));
        var outbox = new EmailOutboxService(jdbc, crypto, mapper, provider, manager);
        var notification = new AlertNotificationService(provider, true, "alerts@stockwatch.test", "UTC");
        notification.setOutbox(outbox);
        String tag = UUID.randomUUID().toString();
        Long userId = jdbc.queryForObject("insert into users(email,password_hash,first_name,last_name) values (?,'unused','Same','Point') returning id",
                Long.class, tag + "@example.test");
        Long assetId = jdbc.queryForObject("insert into stock_assets(ticker_symbol,company_name,exchange) values (?,'Same point test','TEST') returning id",
                Long.class, "SP" + tag.substring(0, 8));
        try {
            Long ruleId = jdbc.queryForObject("insert into alert_rules(user_id,stock_asset_id,interval,target_pattern,pattern_family,trade_signal,is_active) values (?,?,'DAILY','ANY','HARMONIC_FORMATION','SELL',true) returning id",
                    Long.class, userId, assetId);
            AlertRule rule = new AlertRule();
            User user = new User();
            user.setId(userId);
            user.setEmail(tag + "@example.test");
            StockAsset asset = new StockAsset();
            asset.setId(assetId);
            asset.setTickerSymbol("SP" + tag.substring(0, 8));
            rule.setId(ruleId);
            rule.setUser(user);
            rule.setStockAsset(asset);
            rule.setInterval(TimeInterval.DAILY);
            rule.setPatternFamily(AlertPatternFamily.HARMONIC_FORMATION);
            rule.setTradeSignal(TradeSignal.SELL);
            AlertEvent abcd = storedHarmonic(rule, "HARMONIC_AB_CD", 93, TOP);
            AlertEvent alternate = storedHarmonic(rule, "HARMONIC_ALTERNATE_AB_CD", 99, TOP);
            AlertEvent shark = storedHarmonic(rule, "HARMONIC_SHARK", 81, TOP - 86_400L);
            for (AlertEvent event : List.of(abcd, alternate, shark)) {
                var email = new org.springframework.mail.SimpleMailMessage();
                email.setFrom("alerts@stockwatch.test");
                email.setTo(tag + "@example.test");
                email.setSubject("StockWatch harmonic " + event.getPattern() + " on " + asset.getTickerSymbol());
                email.setText(event.getPattern() + " body");
                outbox.enqueueHeld(email, event.getId(), Duration.ofDays(7), "initial-alert:" + event.getId(), "INITIAL",
                        AlertNotificationService.SAME_POINT_HOLD);
            }
            outbox.deliverPending();
            verify(sender, times(0)).send(any(jakarta.mail.internet.MimeMessage.class));

            notification.sendSamePoint(List.of(new SamePointEmailBatch.Held(abcd, "initial-alert:" + abcd.getId()),
                    new SamePointEmailBatch.Held(alternate, "initial-alert:" + alternate.getId()),
                    new SamePointEmailBatch.Held(shark, "initial-alert:" + shark.getId())));

            assertThat(jdbc.queryForObject("select count(*) from email_outbox where alert_event_id in (?,?) and expired_at is not null and ciphertext is null",
                    Integer.class, abcd.getId(), alternate.getId())).isEqualTo(2);
            var sent = org.mockito.ArgumentCaptor.forClass(jakarta.mail.internet.MimeMessage.class);
            outbox.deliverPending();
            verify(sender, times(2)).send(sent.capture());
            var combined = sent.getAllValues().stream().filter(message -> {
                try { return message.getSubject().startsWith("StockWatch: 2 signals at the same point"); }
                catch (jakarta.mail.MessagingException failure) { throw new IllegalStateException(failure); }
            }).findFirst().orElseThrow();
            String text = combined.getContent().toString();
            assertThat(text.indexOf("HARMONIC_ALTERNATE_AB_CD body")).isLessThan(text.indexOf("HARMONIC_AB_CD body"));
            assertThat(jdbc.queryForObject("select count(*) from alert_events where id in (?,?,?) and initial_email_sent_at is not null",
                    Integer.class, abcd.getId(), alternate.getId(), shark.getId())).isEqualTo(3);
        } finally {
            jdbc.update("delete from email_outbox where alert_event_id in (select e.id from alert_events e join alert_rules r on r.id = e.alert_rule_id where r.user_id = ?)", userId);
            jdbc.update("delete from alert_events where alert_rule_id in (select id from alert_rules where user_id = ?)", userId);
            jdbc.update("delete from alert_rules where user_id = ?", userId);
            jdbc.update("delete from users where id = ?", userId);
            jdbc.update("delete from stock_assets where id = ?", assetId);
        }
    }

    private AlertEvent storedHarmonic(AlertRule rule, String pattern, int score, long dPoint) {
        Long id = jdbc.queryForObject("""
                insert into alert_events(alert_rule_id,pattern,trade_signal,signal_candle_timestamp,confidence_score,close_price,
                    harmonic_endpoint_timestamp,pattern_end_timestamp) values (?,?,'SELL',?,?,100,?,?) returning id
                """, Long.class, rule.getId(), pattern, CONFIRMATION, score, dPoint, dPoint);
        AlertEvent event = new AlertEvent();
        event.setId(id);
        event.setAlertRule(rule);
        event.setPattern(CandlePattern.valueOf(pattern));
        event.setTradeSignal(TradeSignal.SELL);
        event.setSignalCandleTimestamp(CONFIRMATION);
        event.setConfidenceScore(score);
        event.setHarmonicEndpointTimestamp(dPoint);
        return event;
    }

    private User user() {
        User user = new User();
        user.setEmail("same-point-" + UUID.randomUUID() + "@example.com");
        user.setFirstName("Same");
        user.setLastName("Point");
        user.setPasswordHash("test-only");
        user.setVerified(true);
        return users.saveAndFlush(user);
    }

    private StockAsset asset(String name) {
        StockAsset asset = new StockAsset();
        asset.setTickerSymbol("P" + UUID.randomUUID().toString().substring(0, 8));
        asset.setCompanyName(name + " fixture");
        asset.setExchange("NYSE");
        return assets.saveAndFlush(asset);
    }

    private AlertRule rule(User user, StockAsset asset, AlertPatternFamily family) {
        AlertRule rule = new AlertRule();
        rule.setUser(user);
        rule.setStockAsset(asset);
        rule.setInterval(TimeInterval.DAILY);
        rule.setPatternFamily(family);
        rule.setTradeSignal(TradeSignal.SELL);
        return rules.saveAndFlush(rule);
    }

    private AlertEvent harmonic(AlertRule rule, CandlePattern pattern, int score, long dPoint, LocalDateTime received) {
        AlertEvent event = base(rule, pattern, score, received);
        event.setHarmonicEndpointTimestamp(dPoint);
        return events.saveAndFlush(event);
    }

    private AlertEvent waveTwo(AlertRule rule, LocalDateTime received) {
        AlertEvent event = base(rule, CandlePattern.ELLIOTT_BEARISH_WAVE_II_END, 76, received);
        event.setElliottDeveloping(true);
        event.setElliottSignalStage(ElliottSignalStage.WAVE_II_END);
        event.setElliottEndpointTimestamp(CONFIRMATION);
        event.setElliottStructureSnapshot("0|1789516800|1.035|HIGH\nI|1793750400|0.58|LOW\nII|" + TOP + "|0.736|HIGH");
        return events.saveAndFlush(event);
    }

    private AlertEvent correction(AlertRule rule, LocalDateTime received) {
        AlertEvent event = base(rule, CandlePattern.ELLIOTT_BEARISH_CORRECTION, 80, received);
        event.setElliottSignalStage(ElliottSignalStage.CORRECTION_END);
        event.setElliottEndpointTimestamp(TOP);
        event.setLifecycleStatus(SignalLifecycleStatus.CONFIRMED);
        return events.saveAndFlush(event);
    }

    private static AlertEvent base(AlertRule rule, CandlePattern pattern, int score, LocalDateTime received) {
        AlertEvent event = new AlertEvent();
        event.setAlertRule(rule);
        event.setPattern(pattern);
        event.setTradeSignal(TradeSignal.SELL);
        event.setSignalCandleTimestamp(CONFIRMATION);
        event.setConfidenceScore(score);
        event.setClosePrice(100.0);
        event.setSentAt(received);
        return event;
    }
}
