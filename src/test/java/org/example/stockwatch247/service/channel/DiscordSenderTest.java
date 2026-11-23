package org.example.stockwatch247.service.channel;

import com.sun.net.httpserver.HttpServer;
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
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The sender against a fake Discord: each answer becomes the right result. */
class DiscordSenderTest {
    record Answer(int status, Map<String, String> headers, String body) { }

    private HttpServer server;
    private final Deque<Answer> answers = new ArrayDeque<>();
    private final List<String> received = new ArrayList<>();
    private GuardedHttpClient http;
    private DiscordSender sender;
    private final RequestRateLimiter limiter = mock(RequestRateLimiter.class);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/webhooks/", exchange -> {
            received.add(exchange.getRequestMethod() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Answer answer;
            synchronized (answers) { answer = answers.isEmpty() ? new Answer(204, Map.of(), "") : answers.poll(); }
            answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        http = new GuardedHttpClient(new OutboundTargetPolicy(true, true, true, ""));
        when(limiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
        @SuppressWarnings("unchecked") ObjectProvider<org.example.stockwatch247.i18n.Messages> messages = mock(ObjectProvider.class);
        sender = new DiscordSender(http, JsonMapper.builder().build(), limiter, messages,
                "http://127.0.0.1:" + server.getAddress().getPort(), true);
    }

    @AfterEach
    void stop() throws Exception {
        server.stop(0);
        http.close();
    }

    private ChannelSender.Target target() {
        return new ChannelSender.Target(1, UUID.randomUUID(), 1, ChannelType.DISCORD, "Desk", "discord",
                Map.of("webhookUrl", "https://discord.com/api/webhooks/123456789012345678/abcDEF_ghi-jkl0123456789"), null);
    }

    private Outgoing single() {
        var message = new ChannelMessage("OUTLOOK", "en", "Subject", "Body", "https://stockwatch.example/x", "AAPL", null,
                Map.of(), null, null, null, Instant.now());
        return new Outgoing(Mode.SINGLE, List.of(new Item(UUID.randomUUID(), message, Instant.now(), null, null)), 0);
    }

    @Test
    void anAcceptedMessageIsDeliveredAndAnEmptyBucketSetsThePace() {
        answers.add(new Answer(204, Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset-After", "1.5"), ""));
        var result = sender.send(target(), single(), ChannelSender.PartProgress.NONE);
        assertThat(result).isEqualTo(new SendResult.Delivered(null, Duration.ofMillis(1500)));
        assertThat(received).singleElement().asString().startsWith("POST {\"username\":\"StockWatch 24/7\"").contains("\"embeds\":[");
    }

    @Test
    void discordAnswersBecomeTheRightResult() {
        answers.add(new Answer(429, Map.of(), "{\"retry_after\": 2.5, \"global\": false}"));
        assertThat(sender.send(target(), single(), ChannelSender.PartProgress.NONE))
                .isEqualTo(new SendResult.RateLimited(Duration.ofMillis(2750), null, "DISCORD_429"));
        answers.add(new Answer(429, Map.of("X-RateLimit-Scope", "global"), "{\"retry_after\": 1, \"global\": true}"));
        assertThat(sender.send(target(), single(), ChannelSender.PartProgress.NONE))
                .isEqualTo(new SendResult.RateLimited(Duration.ofMillis(1250), "discord", "DISCORD_GLOBAL_429"));
        answers.add(new Answer(404, Map.of(), "{\"code\": 10015}"));
        assertThat(sender.send(target(), single(), ChannelSender.PartProgress.NONE)).isEqualTo(new SendResult.ChannelGone("DISCORD_GONE"));
        answers.add(new Answer(401, Map.of(), ""));
        assertThat(sender.send(target(), single(), ChannelSender.PartProgress.NONE)).isEqualTo(new SendResult.ChannelGone("DISCORD_UNAUTHORIZED"));
        answers.add(new Answer(503, Map.of(), ""));
        assertThat(sender.send(target(), single(), ChannelSender.PartProgress.NONE)).isEqualTo(new SendResult.Retry("HTTP_503", null));
        answers.add(new Answer(400, Map.of(), "{\"embeds\": [\"bad\"]}"));
        assertThat(sender.send(target(), single(), ChannelSender.PartProgress.NONE)).isEqualTo(new SendResult.Rejected("HTTP_400", false));
    }

    @Test
    void tooManyInvalidRequestsPauseAllOfDiscord() {
        when(limiter.tryAcquire(org.mockito.ArgumentMatchers.eq("discord-invalid-requests"), anyInt(), any(Duration.class)))
                .thenReturn(false);
        answers.add(new Answer(401, Map.of(), ""));
        assertThat(sender.send(target(), single(), ChannelSender.PartProgress.NONE))
                .isEqualTo(new SendResult.RateLimited(Duration.ofMinutes(10), "discord", "INVALID_BREAKER"));
    }

    @Test
    void aSplitMessageResumesAfterTheLastPartSent() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            var message = new ChannelMessage("SIGNAL_CANDLESTICK", "en", "Signal " + i, "x".repeat(1500), null, "AAPL", "BUY",
                    Map.of(), "point:1", null, null, Instant.now());
            items.add(new Item(UUID.randomUUID(), message, Instant.now(), null, null));
        }
        List<Integer> parts = new ArrayList<>();
        var result = sender.send(target(), new Outgoing(Mode.GROUP, items, 1), (sent, id) -> parts.add(sent));
        assertThat(result).isInstanceOf(SendResult.Delivered.class);
        assertThat(received).noneMatch(body -> body.contains("end at the same point"));
        assertThat(parts.getFirst()).isEqualTo(2);
        assertThat(received).hasSize(parts.size());
    }

    @Test
    void lookingUpAWebhookReadsItsName() throws Exception {
        answers.add(new Answer(200, Map.of("Content-Type", "application/json"), "{\"name\": \"Captain Hook\", \"channel_id\": \"1\"}"));
        var webhook = DiscordSender.parse("https://discord.com/api/webhooks/123456789012345678/abcDEF_ghi-jkl0123456789").orElseThrow();
        assertThat(sender.lookUp(webhook)).contains("Captain Hook");
        answers.add(new Answer(404, Map.of(), "{\"code\": 10015}"));
        assertThat(sender.lookUp(webhook)).isEmpty();
        assertThat(received.getFirst()).startsWith("GET");
    }
}
