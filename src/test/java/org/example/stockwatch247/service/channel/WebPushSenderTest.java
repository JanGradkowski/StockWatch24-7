package org.example.stockwatch247.service.channel;

import com.sun.net.httpserver.HttpServer;
import org.example.stockwatch247.security.GuardedHttpClient;
import org.example.stockwatch247.security.OutboundTargetPolicy;
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
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The sender against a push service on this machine that decrypts what it receives, as a browser would. */
class WebPushSenderTest {
    record Received(Map<String, String> headers, byte[] body) { }

    private HttpServer server;
    private final List<Received> received = new ArrayList<>();
    private final Deque<int[]> answers = new ArrayDeque<>();
    private GuardedHttpClient http;
    private WebPushSender sender;
    private final KeyPair browser = WebPushCrypto.newKeyPair();
    private final byte[] auth = "auth-secret-1234".getBytes(StandardCharsets.US_ASCII);
    private VapidKeys vapid;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/push/", exchange -> {
            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
            received.add(new Received(headers, exchange.getRequestBody().readAllBytes()));
            int[] answer = answers.isEmpty() ? new int[]{201, 0} : answers.poll();
            if (answer[1] > 0) exchange.getResponseHeaders().add("Retry-After", Integer.toString(answer[1]));
            exchange.sendResponseHeaders(answer[0], -1);
            exchange.close();
        });
        server.start();
        http = new GuardedHttpClient(new OutboundTargetPolicy(true, true, true, ""));
        KeyPair pair = WebPushCrypto.newKeyPair();
        vapid = new VapidKeys(WebPushCrypto.base64Url(WebPushCrypto.uncompressed((ECPublicKey) pair.getPublic())),
                WebPushCrypto.base64Url(WebPushCrypto.scalar((ECPrivateKey) pair.getPrivate())), "mailto:alerts@example.test", true, false);
        @SuppressWarnings("unchecked") ObjectProvider<org.example.stockwatch247.i18n.Messages> messages = mock(ObjectProvider.class);
        sender = new WebPushSender(http, JsonMapper.builder().build(), vapid, messages, "127.0.0.1", "https://stockwatch.example");
    }

    @AfterEach
    void stop() throws Exception {
        server.stop(0);
        http.close();
    }

    private ChannelSender.Target target(boolean hideDetails) {
        return new ChannelSender.Target(1, UUID.randomUUID(), 1, ChannelType.PUSH, "Edge on Windows", "push:127.0.0.1",
                Map.of("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/push/abc",
                        "p256dh", WebPushCrypto.base64Url(WebPushCrypto.uncompressed((ECPublicKey) browser.getPublic())),
                        "auth", WebPushCrypto.base64Url(auth), "hideDetails", String.valueOf(hideDetails)), null);
    }

    private static Outgoing single(String lateSince) {
        var message = new ChannelMessage("SIGNAL_CANDLESTICK", "en", "StockWatch pattern detected: Hammer on AAPL", "Body",
                "https://stockwatch.example/alerts/signals/7", "AAPL", "BUY", Map.of("entry", 101.5, "stop", 99.0, "target", 106.0),
                null, null, null, Instant.now());
        return new Outgoing(Mode.SINGLE, List.of(new Item(UUID.fromString("00000000-0000-4000-8000-000000000007"), message,
                Instant.now().minusSeconds(3600), lateSince, null)), 0);
    }

    private String decrypted(Received request) throws Exception {
        byte[] uaPublic = WebPushCrypto.uncompressed((ECPublicKey) browser.getPublic());
        return new String(WebPushCryptoTest.decrypt(request.body(), (ECPrivateKey) browser.getPrivate(), uaPublic, auth),
                StandardCharsets.UTF_8);
    }

    @Test
    void anAlertArrivesEncryptedWithVapidAndAShortReadableLine() throws Exception {
        assertThat(sender.send(target(false), single(null), ChannelSender.PartProgress.NONE)).isInstanceOf(SendResult.Delivered.class);
        Received request = received.getFirst();
        assertThat(request.headers().get("content-encoding")).isEqualTo("aes128gcm");
        assertThat(request.headers().get("authorization")).startsWith("vapid t=").contains(", k=" + vapid.publicKey());
        assertThat(Long.parseLong(request.headers().get("ttl"))).isBetween(80_000L, 86_400L);
        assertThat(request.headers().get("urgency")).isEqualTo("normal");
        assertThat(decrypted(request)).isEqualTo("{\"title\":\"Pattern detected: Hammer on AAPL\",\"body\":\"Entry 101.50 · Stop 99.00 · "
                + "Target 106.00\",\"url\":\"https://stockwatch.example/alerts/signals/7\",\"tag\":\"00000000-0000-4000-8000-000000000007\","
                + "\"lang\":\"en\"}");
    }

    @Test
    void aDeviceCanHideDetailsAndALateAlertIsLowUrgency() throws Exception {
        sender.send(target(true), single("14:02"), ChannelSender.PartProgress.NONE);
        Received request = received.getFirst();
        assertThat(request.headers().get("urgency")).isEqualTo("low");
        assertThat(decrypted(request)).contains("\"title\":\"StockWatch alert\"").contains("Open StockWatch to see it.")
                .doesNotContain("Hammer").doesNotContain("101");
    }

    @Test
    void pushServiceAnswersBecomeTheRightResult() {
        answers.add(new int[]{410, 0});
        assertThat(sender.send(target(false), single(null), ChannelSender.PartProgress.NONE)).isEqualTo(new SendResult.ChannelGone("PUSH_GONE"));
        answers.add(new int[]{429, 30});
        assertThat(sender.send(target(false), single(null), ChannelSender.PartProgress.NONE))
                .isEqualTo(new SendResult.RateLimited(Duration.ofSeconds(30), "push:127.0.0.1", "HTTP_429"));
        answers.add(new int[]{403, 0});
        assertThat(sender.send(target(false), single(null), ChannelSender.PartProgress.NONE)).isEqualTo(new SendResult.ConfigError("PUSH_VAPID"));
        answers.add(new int[]{502, 0});
        assertThat(sender.send(target(false), single(null), ChannelSender.PartProgress.NONE)).isEqualTo(new SendResult.Retry("HTTP_502", null));
    }

    @Test
    void anEndpointOffTheKnownPushServicesIsNeverCalled() {
        var target = new ChannelSender.Target(1, UUID.randomUUID(), 1, ChannelType.PUSH, "x", null,
                Map.of("endpoint", "https://evil.example/push", "p256dh", "x", "auth", "x"), null);
        assertThat(sender.send(target, single(null), ChannelSender.PartProgress.NONE)).isEqualTo(new SendResult.ChannelGone("PUSH_GONE"));
        assertThat(received).isEmpty();
    }
}
