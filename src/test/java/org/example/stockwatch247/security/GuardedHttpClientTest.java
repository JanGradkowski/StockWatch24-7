package org.example.stockwatch247.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardedHttpClientTest {
    private HttpServer server;
    private final AtomicInteger redirectTargetHits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] body = ("received " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
                    + " " + "x".repeat(10_000)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", "4");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/elsewhere", exchange -> {
            redirectTargetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void postsAndReadsOnlyTheStartOfTheResponse() throws Exception {
        try (var client = new GuardedHttpClient(new OutboundTargetPolicy(true, true, true, ""))) {
            var response = client.execute(GuardedHttpClient.Request.postJson(url("/ok"), Map.of("X-Test", "1"),
                    "{\"a\":1}", 64, "test"));
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body()).startsWith("received {\"a\":1}").hasSize(64);
            assertThat(response.header("x-ratelimit-remaining")).isEqualTo("4");
        }
    }

    @Test
    void neverFollowsRedirects() throws Exception {
        try (var client = new GuardedHttpClient(new OutboundTargetPolicy(true, true, true, ""))) {
            var response = client.execute(GuardedHttpClient.Request.get(url("/redirect"), 1024, "test"));
            assertThat(response.status()).isEqualTo(302);
            assertThat(redirectTargetHits).hasValue(0);
        }
    }

    @Test
    void productionRefusesToConnectToAPrivateAddress() {
        try (var client = new GuardedHttpClient(new OutboundTargetPolicy(false, true, true, ""))) {
            assertThatThrownBy(() -> client.execute(GuardedHttpClient.Request.get(
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/ok"), 1024, "test")))
                    .isInstanceOfSatisfying(GuardedHttpClient.OutboundException.class, failure -> {
                        assertThat(failure.failure()).isEqualTo(GuardedHttpClient.Failure.TARGET_BLOCKED);
                        assertThat(failure.problem()).isEqualTo(OutboundTargetPolicy.Problem.PRIVATE_ADDRESS);
                        assertThat(failure.getMessage()).doesNotContain("localhost").doesNotContain("/ok");
                    });
        } catch (java.io.IOException close) {
            throw new AssertionError(close);
        }
    }
}
