package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.security.GuardedHttpClient;
import org.example.stockwatch247.security.OutboundTargetPolicy;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.service.channel.ChannelSender.Item;
import org.example.stockwatch247.service.channel.ChannelSender.Mode;
import org.example.stockwatch247.service.channel.ChannelSender.Outgoing;
import org.example.stockwatch247.service.channel.ChannelSender.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class TelegramSenderTest {
    private FakeTelegram telegram;
    private GuardedHttpClient http;
    private TelegramSender sender;

    @BeforeEach
    void start() {
        telegram = new FakeTelegram();
        http = new GuardedHttpClient(new OutboundTargetPolicy(true, true, true, ""));
        var limiter = mock(RequestRateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        @SuppressWarnings("unchecked") ObjectProvider<org.example.stockwatch247.i18n.Messages> messages = mock(ObjectProvider.class);
        var api = new TelegramApi(http, JsonMapper.builder().build(), FakeTelegram.TOKEN, "StockWatchTestBot", telegram.baseUrl(), true);
        sender = new TelegramSender(api, limiter, messages);
        sender.partGapMillis = 0;
    }

    @AfterEach
    void stop() throws Exception {
        telegram.close();
        http.close();
    }

    private static ChannelSender.Target target() {
        return new ChannelSender.Target(1, UUID.randomUUID(), 1, ChannelType.TELEGRAM, "@jan", "telegram",
                Map.of("chatId", "4242"), null);
    }

    private static Item item(String subject, String body, String replyTo) {
        var message = new ChannelMessage("OUTCOME_CONFIRMED", "en", subject, body, "https://stockwatch.example/a?b=1&c=\"2\"",
                "AAPL", "BUY", Map.of(), null, null, null, Instant.now());
        return new Item(UUID.randomUUID(), message, Instant.now(), null, replyTo);
    }

    @Test
    void aMessageIsEscapedHtmlThatRepliesToItsSignalAndReportsItsId() {
        var result = sender.send(target(), new Outgoing(Mode.SINGLE, List.of(item("<b>AT&T</b> confirmed", "Entry 1 < 2", "77")), 0),
                ChannelSender.PartProgress.NONE);
        assertThat(result).isEqualTo(new SendResult.Delivered("101", null));
        String body = telegram.sent().getFirst().body();
        assertThat(body).contains("\"chat_id\":\"4242\"", "\"parse_mode\":\"HTML\"", "&lt;b&gt;AT&amp;T&lt;/b&gt; confirmed",
                "Entry 1 &lt; 2", "href=\\\"https://stockwatch.example/a?b=1&amp;c=&quot;2&quot;\\\"",
                "\"reply_parameters\":{\"message_id\":77,\"allow_sending_without_reply\":true}",
                "\"link_preview_options\":{\"is_disabled\":true}");
    }

    @Test
    void telegramAnswersBecomeTheRightResult() {
        telegram.answer(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\",\"parameters\":{\"retry_after\":3}}");
        assertThat(send()).isEqualTo(new SendResult.RateLimited(Duration.ofMillis(3250), null, "TELEGRAM_429"));
        telegram.answer(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests\",\"parameters\":{\"retry_after\":60}}");
        assertThat(send()).isEqualTo(new SendResult.RateLimited(Duration.ofMillis(60250), "telegram", "TELEGRAM_429"));
        telegram.answer(403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}");
        assertThat(send()).isEqualTo(new SendResult.ChannelGone("BLOCKED_BY_USER"));
        telegram.answer(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}");
        assertThat(send()).isEqualTo(new SendResult.ChannelGone("TELEGRAM_CHAT_GONE"));
        telegram.answer(401, "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
        assertThat(send()).isEqualTo(new SendResult.ConfigError("TELEGRAM_UNAUTHORIZED"));
        telegram.answer(502, "{\"ok\":false,\"error_code\":502,\"description\":\"Bad Gateway\"}");
        assertThat(send()).isEqualTo(new SendResult.Retry("HTTP_502", null));
    }

    @Test
    void htmlTelegramCannotParseIsSentOnceMoreAsPlainText() {
        telegram.answer(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: can't parse entities\"}");
        assertThat(send()).isInstanceOf(SendResult.Delivered.class);
        assertThat(telegram.sent()).hasSize(2);
        assertThat(telegram.sent().get(1).body()).doesNotContain("parse_mode").doesNotContain("<b>");
    }

    @Test
    void manySignalsAtOnePointAreSplitBetweenAlertsAndEachPartIsCommitted() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 4; i++) items.add(item("Signal " + i, "line\n".repeat(400), null));
        List<Integer> parts = new ArrayList<>();
        var result = sender.send(target(), new Outgoing(Mode.GROUP, items, 0), (sent, id) -> parts.add(sent));
        assertThat(result).isInstanceOf(SendResult.Delivered.class);
        assertThat(telegram.sent()).hasSizeGreaterThan(1);
        assertThat(telegram.sent()).allSatisfy(request -> assertThat(request.body().length()).isLessThan(5000));
        assertThat(parts).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, telegram.sent().size()).boxed().toList());
    }

    @Test
    void quietHoursSendSilently() {
        var quiet = new ChannelSender.Target(1, UUID.randomUUID(), 1, ChannelType.TELEGRAM, "@jan", "telegram",
                Map.of("chatId", "4242", "quietNow", "true"), null);
        sender.send(quiet, new Outgoing(Mode.SINGLE, List.of(item("Subject", "Body", null)), 0), ChannelSender.PartProgress.NONE);
        assertThat(telegram.sent().getLast().body()).contains("\"disable_notification\":true");
    }

    @Test
    void theBotTokenNeverReachesTheLog(CapturedOutput output) throws Exception {
        telegram.close();
        assertThat(send()).isInstanceOf(SendResult.Retry.class);
        assertThat(output.getAll()).doesNotContain(FakeTelegram.TOKEN).doesNotContain("AAH-test-token");
    }

    private SendResult send() {
        return sender.send(target(), new Outgoing(Mode.SINGLE, List.of(item("Subject", "Body", null)), 0),
                ChannelSender.PartProgress.NONE);
    }
}
