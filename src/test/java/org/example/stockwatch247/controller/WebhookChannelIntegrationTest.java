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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;

/** A webhook added through the page, confirmed by a test and receiving signed alerts, against a local receiver. */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alert-channels.enabled=true",
        "alert-channels.worker-enabled=false", "alert-channels.allow-private-targets=true",
        "alert-channels.webhook.allow-http=true", "alert-channels.allow-any-port=true", "alert-channels.discord.enabled=false"})
@AutoConfigureMockMvc
class WebhookChannelIntegrationTest {
    record Received(Map<String, String> headers, String body) { }

    static final HttpServer RECEIVER;
    static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    static final Deque<Integer> STATUSES = new ArrayDeque<>();

    static {
        try {
            RECEIVER = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        RECEIVER.createContext("/hook", exchange -> {
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
            RECEIVED.add(new Received(headers, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Integer status;
            synchronized (STATUSES) { status = STATUSES.isEmpty() ? 204 : STATUSES.poll(); }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        RECEIVER.start();
    }

    @AfterAll
    static void stopReceiver() {
        RECEIVER.stop(0);
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
        account = new User();
        account.setEmail("webhook-" + UUID.randomUUID() + "@example.com");
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

    private static String signature(String secret, Received received) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring("whsec_".length())), "HmacSHA256"));
        String signed = received.headers().get("webhook-id") + "." + received.headers().get("webhook-timestamp") + "." + received.body();
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aWebhookShowsItsSecretOnceWaitsForATestAndThenReceivesSignedAlerts() throws Exception {
        String url = "http://127.0.0.1:" + RECEIVER.getAddress().getPort() + "/hook";
        String page = mvc.perform(signedIn(post("/settings/alert-delivery/webhook")).with(csrf()).param("url", url).param("label", "Home"))
                .andReturn().getResponse().getContentAsString();
        var match = Pattern.compile("(whsec_[A-Za-z0-9+/=]{40,})").matcher(page);
        assertThat(match.find()).isTrue();
        String secret = match.group(1);
        var channel = store.list(account.getId()).getFirst();
        assertThat(channel.status()).isEqualTo(ChannelStatus.PENDING);
        assertThat(page).doesNotContain("/hook\"");

        mvc.perform(signedIn(post("/settings/alert-delivery/" + channel.publicId() + "/test")).with(csrf()))
                .andExpect(flash().attribute("success", "Test sent to Home. Check that it arrived."));
        assertThat(store.byId(channel.id()).orElseThrow().status()).isEqualTo(ChannelStatus.ACTIVE);
        Received test = RECEIVED.getFirst();
        assertThat(test.body()).contains("\"type\":\"stockwatch.test\"", "\"version\":1");
        assertThat(test.headers().get("webhook-signature")).isEqualTo(signature(secret, test));

        fanout.publish(new AlertNotice(account.getId(), AlertKind.SIGNAL_CANDLESTICK, "BUY", "AAPL", "StockWatch pattern detected: Hammer on AAPL",
                "Body", "/alerts/signals/9", "webhook-test:" + UUID.randomUUID(), null, null, "point:x", Map.of("entry", 101.5)));
        assertThat(worker.deliver(EnumSet.of(ChannelType.WEBHOOK))).isEqualTo(1);
        Received alert = RECEIVED.getLast();
        assertThat(alert.body()).contains("\"type\":\"stockwatch.alert\"", "\"kind\":\"SIGNAL_CANDLESTICK\"", "\"symbol\":\"AAPL\"",
                "\"entry\":101.5", "\"group\":{\"key\":\"point:x\"}");
        assertThat(alert.headers().get("webhook-signature")).isEqualTo(signature(secret, alert));
        assertThat(alert.headers().get("content-type")).startsWith("application/json");

        // A new secret: both signatures go out for a day.
        String rotated = mvc.perform(signedIn(post("/settings/alert-delivery/" + channel.publicId() + "/rotate-secret")).with(csrf()))
                .andReturn().getResponse().getContentAsString();
        var newSecret = Pattern.compile("(whsec_[A-Za-z0-9+/=]{40,})").matcher(rotated);
        assertThat(newSecret.find()).isTrue();
        mvc.perform(signedIn(post("/settings/alert-delivery/" + channel.publicId() + "/test")).with(csrf()));
        Received both = RECEIVED.getLast();
        assertThat(both.headers().get("webhook-signature")).isEqualTo(signature(newSecret.group(1), both) + " " + signature(secret, both));
    }

    @Test
    void addressesThatCouldReachSomethingElseAreRefusedAndAGoneReceiverStopsTheWebhook() throws Exception {
        mvc.perform(signedIn(post("/settings/alert-delivery/webhook")).with(csrf()).param("url", "ftp://hooks.example.com/x"))
                .andExpect(flash().attribute("error", "Use an https:// address."));
        mvc.perform(signedIn(post("/settings/alert-delivery/webhook")).with(csrf()).param("url", "https://user:pw@hooks.example.com/x"))
                .andExpect(flash().attribute("error", "Remove the user name and password from the address."));

        String url = "http://127.0.0.1:" + RECEIVER.getAddress().getPort() + "/hook";
        mvc.perform(signedIn(post("/settings/alert-delivery/webhook")).with(csrf()).param("url", url));
        var channel = store.list(account.getId()).getFirst();
        store.activate(channel);
        synchronized (STATUSES) { STATUSES.add(410); }
        fanout.publish(new AlertNotice(account.getId(), AlertKind.OUTLOOK, null, "AAPL", "Outlook changed", "Body", "/home",
                "webhook-gone:" + UUID.randomUUID(), null, null, null, Map.of()));
        worker.deliver(EnumSet.of(ChannelType.WEBHOOK));
        var stopped = store.byId(channel.id()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(ChannelStatus.REVOKED);
        assertThat(stopped.disabledReason()).isEqualTo("WEBHOOK_GONE");
    }
}
