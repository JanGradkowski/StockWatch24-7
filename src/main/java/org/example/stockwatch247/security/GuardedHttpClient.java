package org.example.stockwatch247.security;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The one outgoing HTTP stack for alert channels, whether the address is ours (Telegram, Discord, push services)
 * or the user's (webhooks). Every connection resolves its host through {@link OutboundTargetPolicy}: the request
 * fails if any address is private, and connects only to the addresses that were checked, while TLS still verifies
 * the host name. Redirects, cookies, retries and system proxies are off; each request ends after 15 seconds; only
 * the first few kilobytes of a response are read. URLs are never logged: a Bot API path holds the bot token.
 */
@Component
public class GuardedHttpClient implements DisposableBean, AutoCloseable {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GuardedHttpClient.class);
    private static final long TOTAL_TIMEOUT_SECONDS = 15;

    public enum Failure { TARGET_BLOCKED, TIMEOUT, CONNECT, TLS, IO }

    /** A request that never reached a response. The message names the failure kind only, never the URL. */
    public static final class OutboundException extends Exception {
        private final Failure failure;
        private final OutboundTargetPolicy.Problem problem;

        OutboundException(Failure failure, OutboundTargetPolicy.Problem problem) {
            super("Outgoing request failed: " + failure + (problem == null ? "" : " (" + problem + ")"));
            this.failure = failure;
            this.problem = problem;
        }

        public Failure failure() { return failure; }
        public OutboundTargetPolicy.Problem problem() { return problem; }
    }

    /** {@code responseTimeout}, when set, replaces the 10-second wait for a response (a long poll needs more). */
    public record Request(String method, URI uri, Map<String, String> headers, byte[] body, String contentType,
                          int maxResponseBytes, String logLabel, java.time.Duration responseTimeout) {
        public Request(String method, URI uri, Map<String, String> headers, byte[] body, String contentType,
                       int maxResponseBytes, String logLabel) {
            this(method, uri, headers, body, contentType, maxResponseBytes, logLabel, null);
        }

        public static Request get(URI uri, int maxResponseBytes, String logLabel) {
            return new Request("GET", uri, Map.of(), null, null, maxResponseBytes, logLabel);
        }

        public static Request postJson(URI uri, Map<String, String> headers, String json, int maxResponseBytes, String logLabel) {
            return new Request("POST", uri, headers, json.getBytes(StandardCharsets.UTF_8), "application/json",
                    maxResponseBytes, logLabel);
        }

        public Request withResponseTimeout(java.time.Duration timeout) {
            return new Request(method, uri, headers, body, contentType, maxResponseBytes, logLabel, timeout);
        }
    }

    /** Header names are lower-case; the body is cut at the request's limit. */
    public record Response(int status, Map<String, String> headers, String body) {
        public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
        public boolean success() { return status >= 200 && status < 300; }
    }

    private final OutboundTargetPolicy policy;
    private final CloseableHttpClient client;
    private final PoolingHttpClientConnectionManager connections;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("outbound-deadline").factory());

    @org.springframework.beans.factory.annotation.Autowired
    public GuardedHttpClient(@Value("${alert-channels.allow-private-targets:false}") boolean allowPrivateTargets,
                             @Value("${alert-channels.webhook.allow-http:false}") boolean allowHttp,
                             @Value("${alert-channels.allow-any-port:false}") boolean allowAnyPort,
                             @Value("${app.public-base-url:}") String publicBaseUrl) {
        this(new OutboundTargetPolicy(allowPrivateTargets, allowHttp, allowAnyPort, publicBaseUrl));
    }

    public GuardedHttpClient(OutboundTargetPolicy policy) {
        this.policy = policy;
        this.connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new CheckedDnsResolver(policy))
                .setMaxConnPerRoute(2)
                .setMaxConnTotal(64)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(3))
                        .setSocketTimeout(Timeout.ofSeconds(10))
                        .setTimeToLive(TimeValue.ofMinutes(5))
                        .build())
                .build();
        this.client = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableAutomaticRetries()
                .setUserAgent("StockWatch-Alerts/1")
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofSeconds(10))
                        .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                        .build())
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofMinutes(1))
                .build();
    }

    public OutboundTargetPolicy policy() { return policy; }

    public Response execute(Request request) throws OutboundException {
        HttpUriRequestBase http = new HttpUriRequestBase(request.method(), request.uri());
        request.headers().forEach(http::setHeader);
        if (request.body() != null) {
            http.setEntity(new ByteArrayEntity(request.body(), request.contentType() == null
                    ? ContentType.APPLICATION_OCTET_STREAM : ContentType.parse(request.contentType())));
        }
        long total = TOTAL_TIMEOUT_SECONDS;
        if (request.responseTimeout() != null) {
            http.setConfig(RequestConfig.custom()
                    .setResponseTimeout(Timeout.ofMilliseconds(request.responseTimeout().toMillis()))
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                    .build());
            total = request.responseTimeout().toSeconds() + 5;
        }
        var deadline = deadlines.schedule(http::cancel, total, TimeUnit.SECONDS);
        long started = System.nanoTime();
        try {
            return client.execute(http, response -> {
                Map<String, String> headers = new LinkedHashMap<>();
                for (Header header : response.getHeaders()) {
                    headers.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
                }
                String body = "";
                if (response.getEntity() != null) {
                    try (InputStream in = response.getEntity().getContent()) {
                        body = new String(in.readNBytes(Math.max(0, request.maxResponseBytes())), StandardCharsets.UTF_8);
                    }
                }
                return new Response(response.getCode(), Map.copyOf(headers), body);
            });
        } catch (OutboundTargetPolicy.TargetRejected rejected) {
            throw new OutboundException(Failure.TARGET_BLOCKED, rejected.problem());
        } catch (IOException failure) {
            OutboundTargetPolicy.TargetRejected rejected = rejection(failure);
            if (rejected != null) throw new OutboundException(Failure.TARGET_BLOCKED, rejected.problem());
            Failure kind = classify(failure, http.isCancelled());
            log.info("Outgoing request failed: target={} failure={} elapsedMs={}", request.logLabel(), kind,
                    (System.nanoTime() - started) / 1_000_000);
            throw new OutboundException(kind, null);
        } finally {
            deadline.cancel(false);
        }
    }

    private static OutboundTargetPolicy.TargetRejected rejection(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OutboundTargetPolicy.TargetRejected rejected) return rejected;
        }
        return null;
    }

    private static Failure classify(IOException failure, boolean cancelled) {
        if (cancelled || failure instanceof InterruptedIOException) return Failure.TIMEOUT;
        if (failure instanceof SSLException) return Failure.TLS;
        if (failure instanceof ConnectException || failure instanceof UnknownHostException
                || failure instanceof org.apache.hc.client5.http.HttpHostConnectException) return Failure.CONNECT;
        return Failure.IO;
    }

    @Override
    public void destroy() throws IOException {
        deadlines.shutdownNow();
        client.close();
    }

    @Override
    public void close() throws IOException {
        destroy();
    }

    /** Resolves through the policy and connects only to the addresses it checked. */
    static final class CheckedDnsResolver implements DnsResolver {
        private final OutboundTargetPolicy policy;

        CheckedDnsResolver(OutboundTargetPolicy policy) { this.policy = policy; }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return policy.checkedAddresses(host);
        }

        @Override
        public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
            return Arrays.stream(resolve(host)).map(address -> new InetSocketAddress(address, port)).toList();
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }
}
