package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.i18n.Messages;
import org.example.stockwatch247.security.GuardedHttpClient;
import org.example.stockwatch247.security.OutboundTargetPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends a short notification to one browser: a title and a line (entry, stop and target when the alert has them),
 * opening the alert when clicked. The payload is end-to-end encrypted (RFC 8291); the push service sees only its size
 * and timing. A device can hide details on its lock screen. Several waiting alerts become one count. Endpoints must be
 * on a known push service. A gone subscription (404, 410) removes the device; a push service outage or 429 waits
 * for that service only; a refused VAPID token pauses push without touching anyone's device.
 */
@Component
public class WebPushSender implements ChannelSender {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebPushSender.class);
    static final int TITLE = 120;
    static final int BODY = 240;

    private final GuardedHttpClient http;
    private final ObjectMapper mapper;
    private final VapidKeys vapid;
    private final ChannelText text;
    private final List<String> allowedHosts;
    private final String baseUrl;

    public WebPushSender(GuardedHttpClient http, ObjectMapper mapper, VapidKeys vapid, ObjectProvider<Messages> messages,
                         @Value("${alert-channels.push.allowed-hosts:fcm.googleapis.com,updates.push.services.mozilla.com,*.push.apple.com,*.notify.windows.com}") String allowedHosts,
                         @Value("${app.public-base-url:http://localhost:8080}") String baseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.vapid = vapid;
        this.text = new ChannelText(messages.getIfAvailable());
        this.allowedHosts = Arrays.stream(allowedHosts.split(",")).map(String::strip).filter(host -> !host.isEmpty())
                .map(host -> host.toLowerCase(Locale.ROOT)).toList();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    @Override public ChannelType type() { return ChannelType.PUSH; }
    @Override public boolean ready() { return vapid.ready(); }

    /** Whether an endpoint is on a known push service (the allowlist is also the SSRF defence for this URL). */
    public boolean allowed(URI endpoint) {
        String host = endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
        for (String pattern : allowedHosts) {
            if (pattern.startsWith("*.") ? host.endsWith(pattern.substring(1)) && host.length() > pattern.length() - 1
                    : host.equals(pattern)) return true;
        }
        return false;
    }

    @Override
    public SendResult send(Target target, Outgoing outgoing, PartProgress progress) {
        URI endpoint;
        try {
            endpoint = http.policy().validateUrl(target.configString("endpoint"));
        } catch (OutboundTargetPolicy.TargetRejected rejected) {
            return new SendResult.ChannelGone("PUSH_GONE");
        }
        if (!allowed(endpoint)) return new SendResult.ChannelGone("PUSH_GONE");
        Map<String, Object> notification = notification(outgoing, "true".equals(target.configString("hideDetails")));
        if ("true".equals(target.configString("quietNow"))) notification.put("silent", true);
        byte[] payload = mapper.writeValueAsString(notification).getBytes(StandardCharsets.UTF_8);
        byte[] body = WebPushCrypto.encrypt(payload, WebPushCrypto.base64Url(target.configString("p256dh")),
                WebPushCrypto.base64Url(target.configString("auth")));
        String audience = endpoint.getScheme() + "://" + endpoint.getHost() + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
        Item first = outgoing.first();
        long age = Duration.between(first.queuedAt(), Instant.now()).toSeconds();
        long ttl = Math.max(60, ChannelType.PUSH.lifetime().toSeconds() - Math.max(0, age));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", vapid.authorization(audience));
        headers.put("TTL", Long.toString(ttl));
        headers.put("Urgency", first.lateSince() == null ? "normal" : "low");
        headers.put("Content-Encoding", "aes128gcm");
        GuardedHttpClient.Response response;
        try {
            response = http.execute(new GuardedHttpClient.Request("POST", endpoint, headers, body, "application/octet-stream",
                    2048, "push service"));
        } catch (GuardedHttpClient.OutboundException failure) {
            return new SendResult.Retry(failure.failure().name(), null);
        }
        int status = response.status();
        String scope = "push:" + endpoint.getHost().toLowerCase(Locale.ROOT);
        if (response.success()) return SendResult.delivered();
        if (status == 404 || status == 410) return new SendResult.ChannelGone("PUSH_GONE");
        if (status == 429 || status == 503) {
            Duration after = WebhookSender.retryAfter(response.header("retry-after"));
            return new SendResult.RateLimited(after == null ? Duration.ofMinutes(1) : after, scope, "HTTP_" + status);
        }
        if (status >= 500) return new SendResult.Retry("HTTP_" + status, null);
        if (status == 400 || status == 401 || status == 403) {
            log.error("A push service refused our VAPID token: status={} host={}", status, endpoint.getHost());
            return new SendResult.ConfigError("PUSH_VAPID");
        }
        return new SendResult.Rejected("HTTP_" + status, false);
    }

    /** The notification: title, line, link and a tag (the delivery id) so a repeated delivery replaces itself. */
    Map<String, Object> notification(Outgoing outgoing, boolean hideDetails) {
        Item first = outgoing.first();
        ChannelMessage message = first.message();
        Locale locale = ChannelText.locale(message);
        String title;
        String line;
        String url = message.url() == null ? baseUrl + "/home" : message.url();
        String tag = first.deliveryId().toString();
        if (hideDetails) {
            title = text.get(locale, "push.hidden.title");
            line = text.get(locale, "push.hidden.body");
            if (outgoing.items().size() > 1) url = baseUrl + "/home";
        } else if (outgoing.mode() == Mode.ROLL_UP || outgoing.items().size() > 1) {
            title = text.get(locale, "push.many.title", outgoing.items().size());
            line = outgoing.items().stream().limit(3).map(item -> shortSubject(item.message().subject()))
                    .collect(Collectors.joining(" · "));
            url = baseUrl + "/home";
            tag = "stockwatch-summary";
        } else {
            title = shortSubject(message.subject());
            Map<String, String> fields = text.tradeFields(message);
            line = fields.isEmpty() ? firstLine(message.body())
                    : fields.entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue()).collect(Collectors.joining(" · "));
        }
        if (first.lateSince() != null) line = line + " · " + text.get(locale, "channel.late", first.lateSince());
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", cut(title, TITLE));
        notification.put("body", cut(line, BODY));
        notification.put("url", url);
        notification.put("tag", tag);
        notification.put("lang", message.language());
        return notification;
    }

    /** "StockWatch pattern detected: Hammer on AAPL" reads "Pattern detected: Hammer on AAPL" in a notification. */
    static String shortSubject(String subject) {
        String value = subject == null ? "" : subject.strip();
        if (value.startsWith("StockWatch ")) value = value.substring("StockWatch ".length());
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String firstLine(String body) {
        if (body == null) return "";
        return body.lines().map(String::strip).filter(line -> !line.isEmpty()).findFirst().orElse("");
    }

    private static String cut(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1).stripTrailing() + "…";
    }
}
