package org.example.stockwatch247.controller;

import org.example.stockwatch247.model.User;
import org.example.stockwatch247.repository.UserRepository;
import org.example.stockwatch247.security.AccountSession;
import org.example.stockwatch247.service.channel.ChannelStatus;
import org.example.stockwatch247.service.channel.ChannelStore;
import org.example.stockwatch247.service.channel.FakeTelegram;
import org.example.stockwatch247.service.channel.TelegramPoller;
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

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Linking a chat end to end against a fake Bot API: link, /start, confirmation on the site, /stop, a blocked bot. */
@SpringBootTest(properties = {"alerts.schedule.enabled=false", "alert-channels.enabled=true",
        "alert-channels.worker-enabled=false", "alert-channels.allow-private-targets=true",
        "alert-channels.discord.enabled=false", "alert-channels.telegram.polling-enabled=false",
        "alert-channels.telegram.bot-token=" + FakeTelegram.TOKEN, "alert-channels.telegram.bot-username=StockWatchTestBot"})
@AutoConfigureMockMvc
class TelegramLinkIntegrationTest {
    static final FakeTelegram TELEGRAM = new FakeTelegram();

    @DynamicPropertySource
    static void telegram(DynamicPropertyRegistry registry) {
        registry.add("alert-channels.telegram.api-base-url", TELEGRAM::baseUrl);
    }

    @AfterAll
    static void stopTelegram() {
        TELEGRAM.close();
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelStore store;
    @Autowired TelegramPoller poller;
    private User account;
    private long chat;

    @BeforeEach
    void account() {
        TELEGRAM.received.clear();
        chat = 5_000_000 + (long) (Math.random() * 1_000_000);
        account = new User();
        account.setEmail("telegram-" + UUID.randomUUID() + "@example.com");
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

    private String start(long chatId, long fromId, String type, String text) {
        return """
                {"update_id": %d, "message": {"message_id": 1, "text": "%s",
                 "chat": {"id": %d, "type": "%s"}, "from": {"id": %d, "username": "jan_trades", "language_code": "en"}}}
                """.formatted(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1, text, chatId, type, fromId);
    }

    private String lastReply() {
        var sent = TELEGRAM.sent();
        return sent.isEmpty() ? "" : sent.getLast().body();
    }

    @Test
    void aChatIsLinkedOnlyAfterItsOwnerConfirmsOnTheSite() throws Exception {
        String page = mvc.perform(signedIn(post("/settings/alert-delivery/telegram/link")).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var link = Pattern.compile("https://t\\.me/StockWatchTestBot\\?start=([A-Za-z0-9_-]{43})").matcher(page);
        assertThat(link.find()).isTrue();
        String token = link.group(1);
        assertThat(page).contains("data:image/png;base64,").contains("data-status-url=\"/api/alert-delivery/telegram/link-status/");
        assertThat(jdbc.queryForObject("select count(*) from telegram_link_requests where user_id = ? and token_hash <> ?",
                Integer.class, account.getId(), token)).isEqualTo(1);
        String statusUrl = page.replaceAll("(?s).*data-status-url=\"([^\"]+)\".*", "$1");
        assertThat(mvc.perform(signedIn(get(statusUrl))).andReturn().getResponse().getContentAsString()).contains("\"state\":\"WAITING\"");

        // Someone forwards the link into a group, or tries a made-up token: neither links anything.
        TELEGRAM.update(start(-100L, chat, "group", "/start " + token));
        TELEGRAM.update(start(chat, chat, "private", "/start wrong-token-wrong-token-wrong-token-wrong-to"));
        poller.pollOnce(Duration.ZERO);
        assertThat(store.list(account.getId())).isEmpty();
        assertThat(TELEGRAM.sent()).hasSize(2);
        assertThat(TELEGRAM.sent().get(0).body()).contains("private chats only");
        assertThat(TELEGRAM.sent().get(1).body()).contains("not valid or has expired");

        TELEGRAM.update(start(chat, chat, "private", "/start " + token));
        poller.pollOnce(Duration.ZERO);
        var channel = store.list(account.getId()).getFirst();
        assertThat(channel.status()).isEqualTo(ChannelStatus.PENDING);
        assertThat(channel.label()).isEqualTo("@jan_trades");
        assertThat(lastReply()).contains("confirm on the StockWatch page");
        assertThat(mvc.perform(signedIn(get(statusUrl))).andReturn().getResponse().getContentAsString())
                .contains("\"state\":\"READY\"").contains(channel.publicId().toString());
        // The token was used once: pressing Start again with it does nothing new.
        TELEGRAM.update(start(chat, chat, "private", "/start " + token));
        poller.pollOnce(Duration.ZERO);
        assertThat(store.list(account.getId())).hasSize(1);

        mvc.perform(signedIn(post("/settings/alert-delivery/" + channel.publicId() + "/confirm")).with(csrf()))
                .andExpect(flash().attribute("success", "Telegram chat @jan_trades linked. Your alerts will arrive there."));
        assertThat(store.byId(channel.id()).orElseThrow().status()).isEqualTo(ChannelStatus.ACTIVE);
        assertThat(lastReply()).contains("Linked.");
        assertThat(jdbc.queryForObject("select count(*) from security_events where user_id = ? and event_type = 'NOTIFICATION_CHANNEL_ADDED'",
                Integer.class, account.getId())).isEqualTo(1);

        TELEGRAM.update(start(chat, chat, "private", "/stop"));
        poller.pollOnce(Duration.ZERO);
        var stopped = store.byId(channel.id()).orElseThrow();
        assertThat(stopped.status()).isEqualTo(ChannelStatus.REVOKED);
        assertThat(stopped.disabledReason()).isEqualTo("STOPPED_BY_USER");
    }

    @Test
    void notMeDropsTheChatAndABlockedBotStopsALinkedOne() throws Exception {
        String page = mvc.perform(signedIn(post("/settings/alert-delivery/telegram/link")).with(csrf()))
                .andReturn().getResponse().getContentAsString();
        var link = Pattern.compile("start=([A-Za-z0-9_-]{43})").matcher(page);
        assertThat(link.find()).isTrue();
        TELEGRAM.update(start(chat, chat, "private", "/start " + link.group(1)));
        poller.pollOnce(Duration.ZERO);
        var pending = store.list(account.getId()).getFirst();
        mvc.perform(signedIn(post("/settings/alert-delivery/" + pending.publicId() + "/reject")).with(csrf()))
                .andExpect(flash().attribute("success", "The Telegram chat was refused and removed."));
        assertThat(store.list(account.getId())).isEmpty();
        assertThat(lastReply()).contains("refused on the StockWatch website");

        page = mvc.perform(signedIn(post("/settings/alert-delivery/telegram/link")).with(csrf())).andReturn().getResponse().getContentAsString();
        link = Pattern.compile("start=([A-Za-z0-9_-]{43})").matcher(page);
        assertThat(link.find()).isTrue();
        TELEGRAM.update(start(chat, chat, "private", "/start " + link.group(1)));
        poller.pollOnce(Duration.ZERO);
        var channel = store.list(account.getId()).getFirst();
        mvc.perform(signedIn(post("/settings/alert-delivery/" + channel.publicId() + "/confirm")).with(csrf()));
        TELEGRAM.update("""
                {"update_id": 900000001, "my_chat_member": {"chat": {"id": %d, "type": "private"},
                 "new_chat_member": {"status": "kicked"}}}
                """.formatted(chat));
        poller.pollOnce(Duration.ZERO);
        assertThat(store.byId(channel.id()).orElseThrow().disabledReason()).isEqualTo("BLOCKED_BY_USER");
    }
}
