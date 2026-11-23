package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.service.AccountDataExportService;
import org.example.stockwatch247.service.SecurityCryptoService;
import org.example.stockwatch247.service.channel.ChannelSender.Mode;
import org.example.stockwatch247.service.channel.ChannelSender.Outgoing;
import org.example.stockwatch247.service.channel.ChannelSender.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {"alerts.schedule.enabled=false", "email-outbox.worker-enabled=false",
        "alert-channels.enabled=true", "alert-channels.worker-enabled=false"})
class ChannelOutboxIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelStore store;
    @Autowired ChannelFanout fanout;
    @Autowired SecurityCryptoService crypto;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired AccountDataExportService export;

    private final Recorder recorder = new Recorder();
    private final ChannelNotices notices = mock(ChannelNotices.class);
    private ChannelDeliveryWorker worker;
    private long user;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString();
        user = jdbc.queryForObject("insert into users(email,password_hash,first_name,last_name) values (?,'unused','Channel','Test') returning id",
                Long.class, tag + "@example.test");
        worker = new ChannelDeliveryWorker(jdbc, store, crypto, mapper, manager, List.of(recorder), notices, "UTC", true);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from users where id = ?", user);
    }

    private ChannelStore.Channel discord(String label, Set<AlertKind> kinds, Set<String> directions, String language) {
        String url = "https://discord.com/api/webhooks/" + Math.abs(label.hashCode()) + "/" + tag;
        return store.create(new ChannelStore.NewChannel(user, ChannelType.DISCORD, label, "discord.com", "discord",
                ChannelStatus.ACTIVE, kinds, directions, language, Map.of("webhookUrl", url), url));
    }

    private ChannelStore.Channel discord(String label) {
        return discord(label, EnumSet.allOf(AlertKind.class), Set.of("BUY", "SELL"), null);
    }

    private AlertNotice notice(String key, String direction, String group) {
        return new AlertNotice(user, AlertKind.SIGNAL_CANDLESTICK, direction, "AAPL",
                "StockWatch pattern detected: Hammer on AAPL", "Entry 101.5\nStop 99", "/alerts/signals/1",
                "test:" + tag + ":" + key, null, null, group, Map.of("entry", 101.5, "pattern", "Hammer"));
    }

    private int pending(long channel) {
        return jdbc.queryForObject("select count(*) from alert_channel_deliveries where channel_id = ? "
                + "and delivered_at is null and expired_at is null", Integer.class, channel);
    }

    private void makeDue(long channel) {
        jdbc.update("update alert_channel_deliveries set available_at = current_timestamp - interval '1 second' "
                + "where channel_id = ? and delivered_at is null and expired_at is null", channel);
        jdbc.update("update alert_channels set available_at = current_timestamp - interval '1 second' where id = ?", channel);
    }

    @Test
    void anAlertReachesEachChannelThatTakesItOnceInItsLanguageAndRollsBackWithTheAlert() {
        var english = discord("English desk", EnumSet.of(AlertKind.SIGNAL_CANDLESTICK), Set.of("BUY"), "en");
        var polish = discord("Polish desk", EnumSet.of(AlertKind.SIGNAL_CANDLESTICK), Set.of("BUY", "SELL"), "pl");
        var sellOnly = discord("Shorts", EnumSet.of(AlertKind.SIGNAL_CANDLESTICK), Set.of("SELL"), null);
        var outlooksOnly = discord("Outlooks", EnumSet.of(AlertKind.OUTLOOK), Set.of("BUY", "SELL"), null);
        var transactions = new TransactionTemplate(manager);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            fanout.publish(notice("1", "BUY", null));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from alert_channel_deliveries d join alert_channels c "
                + "on c.id = d.channel_id where c.user_id = ?", Integer.class, user)).isZero();

        transactions.executeWithoutResult(status -> assertThat(fanout.publish(notice("1", "BUY", null))).isEqualTo(2));
        // A retried check queues the same alert again: nothing new is written.
        assertThat(fanout.publish(notice("1", "BUY", null))).isZero();
        assertThat(pending(english.id())).isEqualTo(1);
        assertThat(pending(polish.id())).isEqualTo(1);
        assertThat(pending(sellOnly.id())).isZero();
        assertThat(pending(outlooksOnly.id())).isZero();

        var stored = jdbc.queryForMap("select id, ciphertext, iv from alert_channel_deliveries where channel_id = ?", polish.id());
        assertThat((String) stored.get("ciphertext")).doesNotContain("AAPL");
        var message = mapper.readValue(crypto.decrypt((String) stored.get("ciphertext"), (String) stored.get("iv"),
                "channel-delivery:" + stored.get("id")), ChannelMessage.class);
        assertThat(message.language()).isEqualTo("pl");
        assertThat(message.url()).endsWith("/alerts/signals/1");
        assertThat(message.facts()).containsEntry("pattern", "Hammer");
        assertThat(fanout.wants(user, AlertKind.SIGNAL_CANDLESTICK, "SELL")).isTrue();
        assertThat(fanout.wants(user, AlertKind.CHART, null)).isFalse();
    }

    @Test
    void theWorkerSendsWhatIsDueAndKeepsNoContentAfterwards() {
        var channel = discord("Desk");
        fanout.publish(notice("1", "BUY", null));

        assertThat(worker.deliver(EnumSet.of(ChannelType.DISCORD))).isEqualTo(1);

        assertThat(recorder.sent).hasSize(1);
        assertThat(recorder.sent.getFirst().mode()).isEqualTo(Mode.SINGLE);
        assertThat(recorder.sent.getFirst().first().message().subject()).contains("AAPL");
        assertThat(jdbc.queryForObject("select delivered_at is not null and ciphertext is null and outcome = 'DELIVERED' "
                + "from alert_channel_deliveries where channel_id = ?", Boolean.class, channel.id())).isTrue();
        assertThat(store.byId(channel.id()).orElseThrow().lastSuccessAt()).isNotNull();
        assertThat(worker.deliver(EnumSet.of(ChannelType.DISCORD))).isZero();
    }

    @Test
    void signalsAtTheSamePointGoTogetherAndABurstBecomesOneList() {
        var channel = discord("Desk");
        for (int i = 0; i < 3; i++) fanout.publish(notice("g" + i, "BUY", "point-1"));
        worker.deliver(EnumSet.of(ChannelType.DISCORD));
        assertThat(recorder.sent).hasSize(1);
        assertThat(recorder.sent.getFirst().mode()).isEqualTo(Mode.GROUP);
        assertThat(recorder.sent.getFirst().items()).hasSize(3);

        for (int i = 0; i < 10; i++) fanout.publish(notice("b" + i, "SELL", null));
        makeDue(channel.id());
        worker.deliver(EnumSet.of(ChannelType.DISCORD));
        assertThat(recorder.sent).hasSize(2);
        assertThat(recorder.sent.get(1).mode()).isEqualTo(Mode.ROLL_UP);
        assertThat(recorder.sent.get(1).items()).hasSize(10);
        assertThat(pending(channel.id())).isZero();
    }

    @Test
    void failuresBackOffAndRateLimitsWaitWithoutCountingAFailure() {
        var channel = discord("Desk");
        fanout.publish(notice("1", "BUY", null));
        recorder.next.add(new SendResult.Retry("HTTP_502", null));
        worker.deliver(EnumSet.of(ChannelType.DISCORD));
        assertThat(jdbc.queryForObject("select available_at > current_timestamp + interval '10 seconds' "
                + "from alert_channel_deliveries where channel_id = ?", Boolean.class, channel.id())).isTrue();
        assertThat(store.byId(channel.id()).orElseThrow().consecutiveFailures()).isEqualTo(1);

        makeDue(channel.id());
        recorder.next.add(new SendResult.RateLimited(Duration.ofSeconds(30), "discord", "GLOBAL_429"));
        worker.deliver(EnumSet.of(ChannelType.DISCORD));
        assertThat(store.byId(channel.id()).orElseThrow().consecutiveFailures()).isEqualTo(1);
        assertThat(worker.blocked("discord")).isTrue();
        makeDue(channel.id());
        assertThat(worker.deliver(EnumSet.of(ChannelType.DISCORD))).isZero();
        assertThat(recorder.sent).hasSize(2);
        jdbc.update("delete from alert_channel_provider_state where scope = 'discord'");
    }

    @Test
    void aGoneChannelIsRemovedWithItsQueueAndItsOwnerIsTold() {
        var channel = discord("Desk");
        fanout.publish(notice("1", "BUY", null));
        fanout.publish(notice("2", "BUY", null));
        recorder.next.add(new SendResult.ChannelGone("DISCORD_404"));
        worker.deliver(EnumSet.of(ChannelType.DISCORD));

        var stored = store.byId(channel.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(ChannelStatus.REVOKED);
        assertThat(stored.disabledReason()).isEqualTo("DISCORD_404");
        assertThat(store.config(stored)).isEmpty();
        assertThat(pending(channel.id())).isZero();
        verify(notices).stopped(any(), eq(ChannelStatus.REVOKED), eq("DISCORD_404"));
    }

    @Test
    void aChannelFailingForMoreThanADayStopsOnce() {
        var channel = discord("Desk");
        fanout.publish(notice("1", "BUY", null));
        jdbc.update("update alert_channels set consecutive_failures = 7, first_failure_at = current_timestamp - interval '25 hours' "
                + "where id = ?", channel.id());
        recorder.next.add(new SendResult.Retry("TIMEOUT", null));
        worker.deliver(EnumSet.of(ChannelType.DISCORD));

        assertThat(store.byId(channel.id()).orElseThrow().status()).isEqualTo(ChannelStatus.FAILING);
        assertThat(pending(channel.id())).isZero();
        verify(notices, times(1)).stopped(any(), eq(ChannelStatus.FAILING), eq("TIMEOUT"));
        assertThat(store.activate(store.byId(channel.id()).orElseThrow())).isTrue();
        assertThat(store.byId(channel.id()).orElseThrow().consecutiveFailures()).isZero();
    }

    @Test
    void pausingDropsWhatIsQueuedSoResumingSendsNoBurst() {
        var channel = discord("Desk");
        fanout.publish(notice("1", "BUY", null));
        assertThat(store.pauseAll(user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select outcome from alert_channel_deliveries where channel_id = ?", String.class,
                channel.id())).isEqualTo("PAUSED");
        assertThat(fanout.publish(notice("2", "BUY", null))).isZero();
        assertThat(store.resumeAll(user)).isEqualTo(1);
        assertThat(worker.deliver(EnumSet.of(ChannelType.DISCORD))).isZero();
        assertThat(recorder.sent).isEmpty();
    }

    @Test
    void twoInstancesSendEachAlertOnceAndOneAtATimePerChannel() throws Exception {
        discord("Desk");
        for (int i = 0; i < 3; i++) fanout.publish(notice("p" + i, "BUY", null));
        recorder.delay = 300;
        var second = new ChannelDeliveryWorker(jdbc, store, crypto, mapper, manager, List.of(recorder), notices, "UTC", true);
        var start = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();
        for (var instance : List.of(worker, second)) {
            threads.add(Thread.ofPlatform().start(() -> {
                try { start.await(); } catch (InterruptedException interrupted) { return; }
                instance.deliver(EnumSet.of(ChannelType.DISCORD));
            }));
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertThat(recorder.sent).hasSize(1);
        assertThat(recorder.maxInFlight).isEqualTo(1);
    }

    @Test
    void aMessageSentInPartsResumesAfterTheLastPartThatWentOut() {
        var channel = discord("Desk");
        fanout.publish(notice("1", "BUY", null));
        recorder.behaviour.add((outgoing, progress) -> {
            progress.sent(1, "message-1");
            throw new IllegalStateException("crash after the first part");
        });
        worker.deliver(EnumSet.of(ChannelType.DISCORD));
        assertThat(jdbc.queryForObject("select parts_sent from alert_channel_deliveries where channel_id = ?", Integer.class,
                channel.id())).isEqualTo(1);

        makeDue(channel.id());
        worker.deliver(EnumSet.of(ChannelType.DISCORD));
        assertThat(recorder.sent.getLast().partsSent()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select provider_message_id from alert_channel_deliveries where channel_id = ?",
                String.class, channel.id())).isEqualTo("message-1");
    }

    @Test
    void heldAlertsWaitForTheirCheckAndAlertsTheChannelNoLongerWantsAreDropped() {
        var channel = discord("Desk");
        fanout.publish(notice("held", "BUY", "point-2"), Duration.ofMinutes(5));
        assertThat(worker.deliver(EnumSet.of(ChannelType.DISCORD))).isZero();
        assertThat(fanout.release(List.of("test:" + tag + ":held"), user)).isEqualTo(1);

        store.updateChoices(channel, EnumSet.of(AlertKind.OUTLOOK), Set.of("BUY", "SELL"), null);
        makeDue(channel.id());
        worker.deliver(EnumSet.of(ChannelType.DISCORD));
        assertThat(recorder.sent).isEmpty();
        assertThat(jdbc.queryForObject("select outcome from alert_channel_deliveries where channel_id = ?", String.class,
                channel.id())).isEqualTo("FILTERED");
    }

    @Test
    void anAccountBeingDeletedGetsNothingAndOverdueAlertsExpire() {
        var channel = discord("Desk");
        fanout.publish(notice("1", "BUY", null));
        jdbc.update("update users set deletion_requested_at = current_timestamp where id = ?", user);
        assertThat(worker.deliver(EnumSet.of(ChannelType.DISCORD))).isZero();
        assertThat(fanout.publish(notice("2", "BUY", null))).isZero();

        jdbc.update("update alert_channel_deliveries set expires_at = current_timestamp - interval '1 minute' where channel_id = ?",
                channel.id());
        worker.cleanup();
        assertThat(jdbc.queryForObject("select outcome from alert_channel_deliveries where channel_id = ?", String.class,
                channel.id())).isEqualTo("EXPIRED");
    }

    @Test
    void theSchemaRefusesUnknownValuesAndOneBrowserBelongsToOneAccount() {
        assertThatThrownBy(() -> jdbc.update("insert into alert_channels(public_id,user_id,channel_type,label,alert_kinds,"
                + "config_ciphertext,config_iv) values (?,?,'DISCORD','x',array['NOT_A_KIND'],'c','i')", UUID.randomUUID(), user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into alert_channels(public_id,user_id,channel_type,label,alert_kinds) "
                + "values (?,?,'DISCORD','x',array['OUTLOOK'])", UUID.randomUUID(), user))
                .isInstanceOf(DataIntegrityViolationException.class);
        long other = jdbc.queryForObject("insert into users(email,password_hash,first_name,last_name) values (?,'unused','Other','Test') returning id",
                Long.class, tag + "-other@example.test");
        try {
            jdbc.update("insert into alert_channels(public_id,user_id,channel_type,label,alert_kinds,config_ciphertext,config_iv,lookup_hash) "
                    + "values (?,?,'PUSH','Phone',array['OUTLOOK'],'c','i',?)", UUID.randomUUID(), user, "same-" + tag);
            assertThatThrownBy(() -> jdbc.update("insert into alert_channels(public_id,user_id,channel_type,label,alert_kinds,"
                    + "config_ciphertext,config_iv,lookup_hash) values (?,?,'PUSH','Phone',array['OUTLOOK'],'c','i',?)",
                    UUID.randomUUID(), other, "same-" + tag)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.update("delete from users where id = ?", other);
        }
        var channel = discord("Desk");
        fanout.publish(notice("1", "BUY", null));
        jdbc.update("delete from users where id = ?", user);
        assertThat(jdbc.queryForObject("select count(*) from alert_channel_deliveries where channel_id = ?", Integer.class,
                channel.id())).isZero();
    }

    @Test
    void theExportListsChannelsWithoutTheirTargetsAndQuotasHold() {
        discord("Desk");
        @SuppressWarnings("unchecked")
        var channels = (List<Map<String, Object>>) export.collect(user).get("alertChannels");
        assertThat(channels).hasSize(1);
        assertThat(channels.getFirst()).containsEntry("label", "Desk").containsEntry("type", "DISCORD");
        assertThat(channels.toString()).doesNotContain("discord.com/api/webhooks").doesNotContain(tag);
        for (int i = 1; i < ChannelType.DISCORD.perAccount(); i++) discord("Desk " + i);
        assertThatThrownBy(() -> discord("One too many")).hasMessageContaining("most channels");
    }

    /** Stands in for a provider: answers with queued results (Delivered by default) and records what it was given. */
    static final class Recorder implements ChannelSender {
        final List<Outgoing> sent = Collections.synchronizedList(new ArrayList<>());
        final Deque<SendResult> next = new ArrayDeque<>();
        final Deque<BiFunction<Outgoing, PartProgress, SendResult>> behaviour = new ArrayDeque<>();
        volatile long delay;
        private int inFlight;
        volatile int maxInFlight;

        @Override public ChannelType type() { return ChannelType.DISCORD; }

        @Override
        public SendResult send(Target target, Outgoing outgoing, PartProgress progress) {
            synchronized (this) { maxInFlight = Math.max(maxInFlight, ++inFlight); }
            try {
                sent.add(outgoing);
                if (delay > 0) Thread.sleep(delay);
                BiFunction<Outgoing, PartProgress, SendResult> custom;
                synchronized (this) { custom = behaviour.poll(); }
                if (custom != null) return custom.apply(outgoing, progress);
                synchronized (this) { return next.isEmpty() ? SendResult.delivered() : next.poll(); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new SendResult.Retry("INTERRUPTED", null);
            } finally {
                synchronized (this) { inFlight--; }
            }
        }
    }
}
