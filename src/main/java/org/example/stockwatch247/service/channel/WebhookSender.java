package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.security.GuardedHttpClient;
import org.example.stockwatch247.security.OutboundTargetPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posts each alert as signed JSON to the user's own HTTPS endpoint, one request per alert, in the Standard Webhooks
 * format: {@code webhook-id} (the delivery id, the same on every retry so receivers can deduplicate),
 * {@code webhook-timestamp} and {@code webhook-signature: v1,<base64 HMAC-SHA256>} over "id.timestamp.body". During a
 * secret rotation both signatures are sent for 24 hours. The address is checked again on every request: no redirects,
 * no private networks, HTTPS on 443 or 8443 in production.
 */
@Component
public class WebhookSender implements ChannelSender {
    public static final String SECRET_PREFIX = "whsec_";
    static final int RESPONSE_LIMIT = 4 * 1024;
    static final Duration MAX_WAIT = Duration.ofHours(1);

    private final GuardedHttpClient http;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    public WebhookSender(GuardedHttpClient http, ObjectMapper mapper,
                         @Value("${alert-channels.webhook.enabled:true}") boolean enabled) {
        this.http = http;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Override public ChannelType type() { return ChannelType.WEBHOOK; }
    @Override public boolean ready() { return enabled; }

    public String newSecret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getEncoder().encodeToString(bytes);
    }

    @Override
    public SendResult send(Target target, Outgoing outgoing, PartProgress progress) {
        URI uri;
        try {
            uri = http.policy().validateUrl(target.configString("url"));
        } catch (OutboundTargetPolicy.TargetRejected rejected) {
            return new SendResult.Rejected("TARGET_BLOCKED", true);
        }
        Item item = outgoing.first();
        String body = mapper.writeValueAsString(payload(item, outgoing.mode() == Mode.TEST));
        String id = item.deliveryId().toString();
        long timestamp = Instant.now().getEpochSecond();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("webhook-id", id);
        headers.put("webhook-timestamp", Long.toString(timestamp));
        headers.put("webhook-signature", signatures(target, id, timestamp, body));
        GuardedHttpClient.Response response;
        try {
            response = http.execute(GuardedHttpClient.Request.postJson(uri, headers, body, RESPONSE_LIMIT, "webhook"));
        } catch (GuardedHttpClient.OutboundException failure) {
            if (failure.failure() == GuardedHttpClient.Failure.TARGET_BLOCKED) return new SendResult.Rejected("TARGET_BLOCKED", true);
            return new SendResult.Retry(failure.failure().name(), null);
        }
        int status = response.status();
        if (response.success()) return new SendResult.Delivered(null, null);
        if (status == 410) return new SendResult.ChannelGone("WEBHOOK_GONE");
        if (status == 429 || status == 503) {
            Duration after = retryAfter(response.header("retry-after"));
            if (after != null) return new SendResult.RateLimited(after, null, "HTTP_" + status);
            return new SendResult.Retry("HTTP_" + status, null);
        }
        if (status == 408 || status >= 500) return new SendResult.Retry("HTTP_" + status, null);
        if (status >= 300 && status < 400) return new SendResult.Rejected("REDIRECT", true);
        // The receiver refused this message: retrying for days would only repeat the refusal.
        return new SendResult.Rejected("HTTP_" + status, true);
    }

    /** Version 1 of the JSON body. Field names and enum values are stable English; title and text are localized. */
    Map<String, Object> payload(Item item, boolean test) {
        ChannelMessage message = item.message();
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("kind", message.kind());
        alert.put("symbol", message.symbol());
        alert.put("direction", message.direction());
        message.facts().forEach((key, value) -> {
            if (!key.equals("test")) alert.putIfAbsent(key, value);
        });
        if (message.source() != null) alert.put("source", message.source());
        if (message.sourceId() != null) alert.put("sourceId", message.sourceId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", test ? "stockwatch.test" : "stockwatch.alert");
        payload.put("version", 1);
        payload.put("id", item.deliveryId().toString());
        payload.put("createdAt", (message.createdAt() == null ? Instant.now() : message.createdAt()).toString());
        payload.put("language", message.language());
        payload.put("title", message.subject());
        payload.put("text", message.body());
        payload.put("url", message.url());
        payload.put("alert", alert);
        if (message.groupKey() != null) payload.put("group", Map.of("key", message.groupKey()));
        if (item.lateSince() != null) payload.put("late", true);
        return payload;
    }

    private String signatures(Target target, String id, long timestamp, String body) {
        String signed = id + "." + timestamp + "." + body;
        String current = "v1," + sign(target.configString("secret"), signed);
        String previous = target.configString("previousSecret");
        String until = target.configString("previousSecretUntil");
        if (previous != null && until != null && Instant.parse(until).isAfter(Instant.now())) {
            return current + " v1," + sign(previous, signed);
        }
        return current;
    }

    static String sign(String secret, String content) {
        try {
            byte[] key = Base64.getDecoder().decode(secret.startsWith(SECRET_PREFIX) ? secret.substring(SECRET_PREFIX.length()) : secret);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not sign the webhook.", failure);
        }
    }

    /** Retry-After in seconds or as an HTTP date, at most an hour. */
    static Duration retryAfter(String value) {
        if (value == null || value.isBlank()) return null;
        Duration after;
        try {
            after = Duration.ofSeconds(Long.parseLong(value.strip()));
        } catch (NumberFormatException notSeconds) {
            try {
                after = Duration.between(Instant.now(), java.time.ZonedDateTime.parse(value.strip(),
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            } catch (java.time.format.DateTimeParseException invalid) {
                return null;
            }
        }
        if (after.isNegative() || after.isZero()) return Duration.ofSeconds(1);
        return after.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : after;
    }
}
