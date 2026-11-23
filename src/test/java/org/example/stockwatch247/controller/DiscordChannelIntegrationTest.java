package org.example.stockwatch247.controller;

import com.sun.net.httpserver.HttpServer;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.AccountSession;
import org.example.stockwatch247.service.channel.AlertKind;
import org.example.stockwatch247.service.channel.AlertNotice;
import org.example.stockwatch247.service.channel.ChannelDeliveryWorker;
import org.example.stockwatch247.service.channel.ChannelFanout;
import org.example.stockwatch247.service.channel.ChannelStatus;
import org.example.stockwatch247.service.channel.ChannelStore;
import org.example.stockwatch247.service.channel.ChannelType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;

/** Adding a Discord webhook, testing it and delivering an alert, against a fake Discord on this machine. */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alert-channels.enabled=true",
        "alert-channels.worker-enabled=false", "alert-channels.allow-private-targets=true"})
@AutoConfigureMockMvc
class DiscordChannelIntegrationTest {
    static final String WEBHOOK = "https://discord.com/api/webhooks/123456789012345678/abcDEF_ghi-jkl0123456789";
    static final HttpServer DISCORD;
    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    static volatile int lookUpStatus = 200;

    static {
        try {
            DISCORD = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        DISCORD.createContext("/api/webhooks/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RECEIVED.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body);
            if (exchange.getRequestMethod().equals("GET")) {
                byte[] json = "{\"name\": \"Captain Hook\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(lookUpStatus, lookUpStatus == 200 ? json.length : -1);
                if (lookUpStatus == 200) exchange.getResponseBody().write(json);
            } else {
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        DISCORD.start();
    }

    @DynamicPropertySource
    static void discord(DynamicPropertyRegistry registry) {
        registry.add("alert-channels.discord.api-base-url", () -> "http://127.0.0.1:" + DISCORD.getAddress().getPort());
    }

    @AfterAll
    static void stopDiscord() {
        DISCORD.stop(0);
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelStore store;
    @Autowired ChannelFanout fanout;
    @Autowired ChannelDeliveryWorker worker;
    private User account;

    @BeforeEach
    void account() {
        RECEIVED.clear();
        lookUpStatus = 200;
        account = new User();
        account.setEmail("discord-" + UUID.randomUUID() + "@example.com");
        account.setFirstName("Jan");
        account.setLastName("Test");
        account.setVerified(true);
        account.setPasswordHash(encoder.encode("Current-password-for-test42!"));
        users.saveAndFlush(account);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from users where id = ?", account.getId());
    }

    private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AccountSession.SECURITY_VERSION, account.getSecurityVersion());
        return request.with(user(account.getEmail())).session(session);
    }

    @Test
    void aPastedWebhookIsCheckedWithDiscordAddedTestedAndRecorded() throws Exception {
        mvc.perform(signedIn(post("/settings/alert-delivery/discord")).with(csrf()).param("webhookUrl", "https://example.com/hook"))
                .andExpect(flash().attribute("error", "That isn't a Discord webhook URL. It starts with https://discord.com/api/webhooks/."));
        lookUpStatus = 404;
        mvc.perform(signedIn(post("/settings/alert-delivery/discord")).with(csrf()).param("webhookUrl", WEBHOOK))
                .andExpect(flash().attribute("error", "Discord doesn't know this webhook. Copy it again."));
        lookUpStatus = 200;
        mvc.perform(signedIn(post("/settings/alert-delivery/discord")).with(csrf()).param("webhookUrl", WEBHOOK))
                .andExpect(flash().attribute("success", "Discord channel Captain Hook added. Send a test to check it."));
        mvc.perform(signedIn(post("/settings/alert-delivery/discord")).with(csrf()).param("webhookUrl", WEBHOOK))
                .andExpect(flash().attribute("error", "This Discord webhook is already one of your channels."));

        var channel = store.list(account.getId()).getFirst();
        assertThat(channel.label()).isEqualTo("Captain Hook");
        assertThat(channel.status()).isEqualTo(ChannelStatus.ACTIVE);
        assertThat(channel.displayHint()).isEqualTo("discord.com · …/5678");
        assertThat(store.config(channel)).containsEntry("webhookUrl", WEBHOOK);
        assertThat(jdbc.queryForObject("select count(*) from security_events where user_id = ? and event_type = 'NOTIFICATION_CHANNEL_ADDED'",
                Integer.class, account.getId())).isEqualTo(1);

        RECEIVED.clear();
        mvc.perform(signedIn(post("/settings/alert-delivery/" + channel.publicId() + "/test")).with(csrf()))
                .andExpect(flash().attribute("success", "Test sent to Captain Hook. Check that it arrived."));
        assertThat(RECEIVED).singleElement().asString()
                .startsWith("POST /api/webhooks/123456789012345678/abcDEF_ghi-jkl0123456789 ").contains("StockWatch test message");
    }

    @Test
    void aQueuedAlertReachesDiscordAsAnEmbed() {
        var channel = store.create(new ChannelStore.NewChannel(account.getId(), ChannelType.DISCORD, "Desk", "discord.com · …/5678",
                "discord", ChannelStatus.ACTIVE, EnumSet.allOf(AlertKind.class), java.util.Set.of("BUY", "SELL"), null,
                Map.of("webhookUrl", WEBHOOK), "discord:" + UUID.randomUUID()));
        fanout.publish(new AlertNotice(account.getId(), AlertKind.SIGNAL_CANDLESTICK, "BUY", "AAPL",
                "StockWatch pattern detected: Hammer on AAPL", "Heuristic setup score: 88/100", "/alerts/signals/1",
                "discord-test:" + UUID.randomUUID(), null, null, null, Map.of("entry", 101.5, "stop", 99.0, "target", 106.0)));
        assertThat(worker.deliver(EnumSet.of(ChannelType.DISCORD))).isEqualTo(1);
        assertThat(RECEIVED).singleElement().asString().contains("Hammer on AAPL").contains("\"name\":\"Entry\"")
                .contains("\"value\":\"101.50\"").contains("\"allowed_mentions\":{\"parse\":[]}");
        assertThat(store.byId(channel.id()).orElseThrow().lastSuccessAt()).isNotNull();
        assertThat(Collections.frequency(RECEIVED, RECEIVED.getFirst())).isEqualTo(1);
    }
}
