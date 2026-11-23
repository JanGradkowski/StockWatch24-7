package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.service.SecurityCryptoService;
import org.example.stockwatch247.service.channel.ChannelSender.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** A chat set to one summary per check run holds its signals and sends them as one list when the checks go quiet. */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "email-outbox.worker-enabled=false",
        "alert-channels.enabled=true", "alert-channels.worker-enabled=false"})
class ChannelSummariesIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelStore store;
    @Autowired ChannelFanout fanout;
    @Autowired ChannelSummaries summaries;
    @Autowired SecurityCryptoService crypto;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;

    private final ChannelOutboxIntegrationTest.Recorder recorder = new ChannelOutboxIntegrationTest.Recorder();
    private ChannelDeliveryWorker worker;
    private long user;
    private String tag;
    private ChannelStore.Channel channel;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString();
        user = jdbc.queryForObject("insert into users(email,password_hash,first_name,last_name) values (?,'unused','Summary','Test') returning id",
                Long.class, tag + "@example.test");
        worker = new ChannelDeliveryWorker(jdbc, store, crypto, mapper, manager, List.of(recorder), mock(ChannelNotices.class), "UTC", true);
        String url = "https://discord.com/api/webhooks/1/" + tag;
        channel = store.create(new ChannelStore.NewChannel(user, ChannelType.DISCORD, "Desk", "discord.com", "discord",
                ChannelStatus.ACTIVE, EnumSet.allOf(AlertKind.class), Set.of("BUY", "SELL"), null, Map.of("webhookUrl", url), url));
        store.setDeliveryMode(channel, "SUMMARY");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from users where id = ?", user);
    }

    private void publish(String key, AlertKind kind) {
        fanout.publish(new AlertNotice(user, kind, "BUY", "AAPL", "Alert " + key, "Body", "/home", "summary-test:" + tag + ":" + key,
                null, null, null, Map.of()));
    }

    private void makeDue() {
        jdbc.update("update alert_channels set available_at = current_timestamp - interval '1 second' where id = ?", channel.id());
    }

    @Test
    void signalsWaitForTheChecksToGoQuietThenGoOutAsOneListWhileOtherAlertsGoAtOnce() {
        publish("1", AlertKind.SIGNAL_CANDLESTICK);
        publish("2", AlertKind.OUTCOME_CONFIRMED);
        publish("3", AlertKind.OUTLOOK);
        publish("chart", AlertKind.CHART);
        assertThat(jdbc.queryForObject("select count(*) from alert_channel_deliveries where channel_id = ? and held", Integer.class,
                channel.id())).isEqualTo(3);

        worker.deliver(EnumSet.of(ChannelType.DISCORD));
        assertThat(recorder.sent).singleElement().satisfies(outgoing -> {
            assertThat(outgoing.mode()).isEqualTo(Mode.SINGLE);
            assertThat(outgoing.first().message().subject()).isEqualTo("Alert chart");
        });

        // The checks are still busy: nothing is released yet.
        assertThat(summaries.flush()).isZero();
        jdbc.update("update alert_channel_deliveries set created_at = current_timestamp - interval '11 minutes' where channel_id = ? and held",
                channel.id());
        assertThat(summaries.flush()).isEqualTo(3);
        makeDue();
        worker.deliver(EnumSet.of(ChannelType.DISCORD));
        assertThat(recorder.sent).hasSize(2);
        assertThat(recorder.sent.get(1).mode()).isEqualTo(Mode.ROLL_UP);
        assertThat(recorder.sent.get(1).items()).extracting(item -> item.message().subject())
                .containsExactlyInAnyOrder("Alert 1", "Alert 2", "Alert 3");
    }

    @Test
    void switchingBackToOneMessagePerAlertSendsWhatWasHeld() {
        publish("1", AlertKind.SIGNAL_ELLIOTT);
        publish("2", AlertKind.SIGNAL_HARMONIC);
        summaries.releaseNow(channel.id());
        assertThat(jdbc.queryForObject("select count(distinct group_key) from alert_channel_deliveries where channel_id = ? "
                + "and not held and group_key like 'summary:%'", Integer.class, channel.id())).isEqualTo(1);
        worker.deliver(EnumSet.of(ChannelType.DISCORD));
        assertThat(recorder.sent).singleElement().extracting(ChannelSender.Outgoing::mode).isEqualTo(Mode.ROLL_UP);
    }

    @Test
    void quietHoursMakeTheSendersSilentOnlyAtNight() {
        store.setQuietHours(channel, true);
        var quiet = store.byId(channel.id()).orElseThrow();
        var night = worker.withQuiet(quiet, Map.of("webhookUrl", "x"));
        int hour = java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC")).getHour();
        assertThat(night.containsKey("quietNow")).isEqualTo(hour >= 22 || hour < 7);
        assertThat(worker.withQuiet(channel, Map.of("webhookUrl", "x"))).doesNotContainKey("quietNow");
    }
}
