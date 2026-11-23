package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.AccountSession;
import org.example.stockwatch247.service.channel.ChannelStatus;
import org.example.stockwatch247.service.channel.ChannelStore;
import org.example.stockwatch247.service.channel.ChannelType;
import org.example.stockwatch247.service.channel.WebPushCrypto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** This browser's subscription through the page's API: validation, one browser per account, removal and security. */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alert-channels.enabled=true", "alert-channels.worker-enabled=false",
        "alert-channels.discord.enabled=false", "alert-channels.webhook.enabled=false", "alert-channels.push.vapid-subject=mailto:alerts@example.test"})
@AutoConfigureMockMvc
class PushApiIntegrationTest {
    static final KeyPair VAPID = WebPushCrypto.newKeyPair();

    @DynamicPropertySource
    static void vapid(DynamicPropertyRegistry registry) {
        registry.add("alert-channels.push.vapid-public-key",
                () -> WebPushCrypto.base64Url(WebPushCrypto.uncompressed((ECPublicKey) VAPID.getPublic())));
        registry.add("alert-channels.push.vapid-private-key",
                () -> WebPushCrypto.base64Url(WebPushCrypto.scalar((ECPrivateKey) VAPID.getPrivate())));
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelStore store;
    private User account;
    private User other;
    private final String p256dh = WebPushCrypto.base64Url(WebPushCrypto.uncompressed((ECPublicKey) WebPushCrypto.newKeyPair().getPublic()));
    private final String endpoint = "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();

    @BeforeEach
    void accounts() {
        account = newUser("push");
        other = newUser("push-other");
    }

    private User newUser(String prefix) {
        User user = new User();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setFirstName("Jan");
        user.setLastName("Test");
        user.setVerified(true);
        user.setPasswordHash(encoder.encode("Current-password-for-test42!"));
        return users.saveAndFlush(user);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from users where id in (?, ?)", account.getId(), other.getId());
    }

    private MockHttpServletRequestBuilder as(User who, MockHttpServletRequestBuilder request) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AccountSession.SECURITY_VERSION, users.findById(who.getId()).orElseThrow().getSecurityVersion());
        return request.with(user(who.getEmail())).session(session).contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0 Safari/537.36 Edg/140.0");
    }

    private String subscription(String url, String key, boolean enable) {
        return "{\"endpoint\":\"" + url + "\",\"keys\":{\"p256dh\":\"" + key + "\",\"auth\":\"YXV0aC1zZWNyZXQtMTIzNA\"},\"enable\":" + enable + "}";
    }

    @Test
    void turningNotificationsOnAddsThisDeviceAndTheTabOffersPush() throws Exception {
        mvc.perform(as(account, get("/settings/alert-delivery")))
                .andExpect(content().string(containsString("data-vapid-key=\"" + WebPushCrypto.base64Url(
                        WebPushCrypto.uncompressed((ECPublicKey) VAPID.getPublic())) + "\"")));
        mvc.perform(as(account, post("/api/push/subscriptions")).with(csrf()).content(subscription(endpoint, p256dh, true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.hideDetails").value(false));
        var device = store.list(account.getId()).getFirst();
        assertThat(device.type()).isEqualTo(ChannelType.PUSH);
        assertThat(device.label()).isEqualTo("Edge on Windows");
        assertThat(device.providerScope()).isEqualTo("push:fcm.googleapis.com");
        assertThat(jdbc.queryForObject("select count(*) from security_events where user_id = ? and event_type = 'NOTIFICATION_CHANNEL_ADDED'",
                Integer.class, account.getId())).isEqualTo(1);

        // The quiet re-sync of a known device changes nothing and announces nothing.
        mvc.perform(as(account, post("/api/push/subscriptions")).with(csrf()).content(subscription(endpoint, p256dh, false)))
                .andExpect(status().isOk());
        assertThat(store.list(account.getId())).hasSize(1);
        mvc.perform(as(account, post("/api/push/subscriptions/details")).with(csrf())
                .content("{\"endpoint\":\"" + endpoint + "\",\"hideDetails\":true}")).andExpect(status().isOk());
        assertThat(store.config(device)).containsEntry("hideDetails", "true");
    }

    @Test
    void addressesAndKeysAreCheckedAndTheFormTokenIsRequired() throws Exception {
        mvc.perform(as(account, post("/api/push/subscriptions")).with(csrf()).content(subscription("https://evil.example/push", p256dh, true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("This browser's push address is not supported."));
        mvc.perform(as(account, post("/api/push/subscriptions")).with(csrf()).content(subscription(endpoint, "BBBB", true)))
                .andExpect(status().isBadRequest());
        mvc.perform(as(account, post("/api/push/subscriptions")).content(subscription(endpoint, p256dh, true)))
                .andExpect(status().isForbidden());
        assertThat(store.list(account.getId())).isEmpty();
    }

    @Test
    void oneBrowserBelongsToOneAccountAndASecurityChangeRemovesDevices() throws Exception {
        mvc.perform(as(account, post("/api/push/subscriptions")).with(csrf()).content(subscription(endpoint, p256dh, true)));
        mvc.perform(as(other, post("/api/push/subscriptions")).with(csrf()).content(subscription(endpoint, p256dh, true)));
        assertThat(store.list(account.getId())).isEmpty();
        assertThat(store.list(other.getId())).singleElement().extracting(ChannelStore.Channel::status).isEqualTo(ChannelStatus.ACTIVE);

        jdbc.update("update users set security_version = security_version + 1 where id = ?", other.getId());
        assertThat(store.list(other.getId())).isEmpty();

        mvc.perform(as(other, post("/api/push/subscriptions")).with(csrf()).content(subscription(endpoint, p256dh, false)));
        mvc.perform(as(other, post("/api/push/subscriptions/remove")).with(csrf()).content("{\"endpoint\":\"" + endpoint + "\"}"))
                .andExpect(jsonPath("$.removed").value(true));
        assertThat(store.list(other.getId())).isEmpty();
    }
}
