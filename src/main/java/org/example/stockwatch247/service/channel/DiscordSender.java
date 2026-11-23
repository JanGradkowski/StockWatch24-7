package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.i18n.Messages;
import org.example.stockwatch247.security.GuardedHttpClient;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends to Discord channel webhooks. It follows each webhook's rate-limit headers, waits out a 429 (blocking all
 * Discord sends when Discord says the limit is global), and keeps the whole server under 40 requests a second.
 * Discord bans an address after 10,000 invalid requests (401, 403, 429) in ten minutes, and every account's
 * webhooks share this server's address, so above 1,000 in ten minutes all Discord sending pauses. A deleted
 * webhook (404) or a revoked one (401) removes the channel at once.
 */
@Component
public class DiscordSender implements ChannelSender {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DiscordSender.class);
    public static final String CANONICAL_BASE = "https://discord.com";
    static final Pattern WEBHOOK = Pattern.compile(
            "^https://(?:discord\\.com|discordapp\\.com|ptb\\.discord\\.com|canary\\.discord\\.com)/api(?:/v\\d+)?/webhooks/(\\d{5,25})/([A-Za-z0-9._-]{20,120})/?$");
    static final String SCOPE = "discord";
    private static final int RESPONSE_LIMIT = 16 * 1024;

    /** A pasted webhook URL, in its canonical discord.com form, or empty when it is not a Discord webhook. */
    public record Webhook(String id, String token) {
        public String url() { return CANONICAL_BASE + "/api/webhooks/" + id + "/" + token; }

        /** Not secret: enough to tell two webhooks apart on the settings page. */
        public String hint() { return "discord.com · …/" + id.substring(Math.max(0, id.length() - 4)); }
    }

    public static Optional<Webhook> parse(String pasted) {
        if (pasted == null) return Optional.empty();
        Matcher matcher = WEBHOOK.matcher(pasted.strip());
        return matcher.matches() ? Optional.of(new Webhook(matcher.group(1), matcher.group(2))) : Optional.empty();
    }

    private final GuardedHttpClient http;
    private final ObjectMapper mapper;
    private final RequestRateLimiter limiter;
    private final DiscordFormatter formatter;
    private final String apiBase;
    private final boolean enabled;

    @Autowired
    public DiscordSender(GuardedHttpClient http, ObjectMapper mapper, RequestRateLimiter limiter,
                         org.springframework.beans.factory.ObjectProvider<Messages> messages,
                         @Value("${alert-channels.discord.api-base-url:https://discord.com}") String apiBase,
                         @Value("${alert-channels.discord.enabled:true}") boolean enabled) {
        this.http = http;
        this.mapper = mapper;
        this.limiter = limiter;
        this.formatter = new DiscordFormatter(new ChannelText(messages.getIfAvailable()));
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.enabled = enabled;
    }

    @Override public ChannelType type() { return ChannelType.DISCORD; }
    @Override public boolean ready() { return enabled; }

    /** Where a stored canonical URL is sent: discord.com, or a local fake in tests. */
    URI endpoint(String canonicalUrl) {
        return URI.create(apiBase + canonicalUrl.substring(CANONICAL_BASE.length()));
    }

    /** Asks Discord for the webhook's name; empty when Discord does not know it. Throws when Discord is unreachable. */
    public Optional<String> lookUp(Webhook webhook) throws GuardedHttpClient.OutboundException {
        var response = http.execute(GuardedHttpClient.Request.get(endpoint(webhook.url()), RESPONSE_LIMIT, "discord webhook"));
        if (response.status() == 404 || response.status() == 401) return Optional.empty();
        if (!response.success()) throw new IllegalStateException("Discord answered " + response.status() + ".");
        JsonNode body = mapper.readTree(response.body().isEmpty() ? "{}" : response.body());
        String name = body.path("name").asString("");
        return Optional.of(name.isBlank() ? "Discord" : name);
    }

    @Override
    public SendResult send(Target target, Outgoing outgoing, PartProgress progress) {
        String url = target.configString("webhookUrl");
        if (url == null || !url.startsWith(CANONICAL_BASE)) return new SendResult.ChannelGone("DISCORD_GONE");
        List<Map<String, Object>> parts = formatter.messages(outgoing);
        if ("true".equals(target.configString("quietNow"))) {
            // SUPPRESS_NOTIFICATIONS: the message arrives without a sound or a push to members' phones.
            parts = parts.stream().map(payload -> {
                Map<String, Object> quiet = new java.util.LinkedHashMap<>(payload);
                quiet.put("flags", 4096);
                return quiet;
            }).toList();
        }
        Duration pace = null;
        for (int part = Math.min(outgoing.partsSent(), parts.size()); part < parts.size(); part++) {
            if (!limiter.tryAcquire("discord-global-pace", 40, Duration.ofSeconds(1))) {
                return new SendResult.RateLimited(Duration.ofSeconds(1), null, "LOCAL_PACE");
            }
            GuardedHttpClient.Response response;
            try {
                response = http.execute(GuardedHttpClient.Request.postJson(endpoint(url), Map.of(),
                        mapper.writeValueAsString(parts.get(part)), RESPONSE_LIMIT, "discord webhook"));
            } catch (GuardedHttpClient.OutboundException failure) {
                return new SendResult.Retry(failure.failure().name(), null);
            }
            int status = response.status();
            if (status == 401 || status == 403 || status == 429 && !"shared".equalsIgnoreCase(response.header("x-ratelimit-scope"))) {
                if (!limiter.tryAcquire("discord-invalid-requests", 1000, Duration.ofMinutes(10))) {
                    log.error("Discord invalid requests over the safety limit; pausing all Discord sending for 10 minutes");
                    return new SendResult.RateLimited(Duration.ofMinutes(10), SCOPE, "INVALID_BREAKER");
                }
            }
            if (response.success()) {
                pace = pace(response);
                progress.sent(part + 1, null);
                continue;
            }
            if (status == 429) {
                JsonNode body = json(response.body());
                Duration after = retryAfter(response, body);
                boolean global = body.path("global").asBoolean(false) || "global".equalsIgnoreCase(response.header("x-ratelimit-scope"));
                return new SendResult.RateLimited(after, global ? SCOPE : null, global ? "DISCORD_GLOBAL_429" : "DISCORD_429");
            }
            if (status == 404) return new SendResult.ChannelGone("DISCORD_GONE");
            if (status == 401) return new SendResult.ChannelGone("DISCORD_UNAUTHORIZED");
            if (status == 403) return new SendResult.Rejected("HTTP_403", true);
            if (status >= 300 && status < 400) return new SendResult.Rejected("REDIRECT", true);
            if (status >= 500) return new SendResult.Retry("HTTP_" + status, null);
            log.warn("Discord refused a message: status={} channel={}", status, target.id());
            return new SendResult.Rejected("HTTP_" + status, false);
        }
        return new SendResult.Delivered(null, pace);
    }

    /** The webhook's own bucket: when it is empty, wait until it resets before the next message. */
    private static Duration pace(GuardedHttpClient.Response response) {
        if (!"0".equals(response.header("x-ratelimit-remaining"))) return null;
        return seconds(response.header("x-ratelimit-reset-after"));
    }

    private Duration retryAfter(GuardedHttpClient.Response response, JsonNode body) {
        Duration fromBody = body.has("retry_after") ? Duration.ofMillis((long) Math.ceil(body.path("retry_after").asDouble(1) * 1000)) : null;
        Duration fromHeader = seconds(response.header("retry-after"));
        Duration after = fromBody != null ? fromBody : fromHeader != null ? fromHeader : Duration.ofSeconds(5);
        return after.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : after.plusMillis(250);
    }

    static Duration seconds(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Duration.ofMillis((long) Math.ceil(Double.parseDouble(value.strip()) * 1000));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private JsonNode json(String body) {
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (RuntimeException invalid) {
            return mapper.createObjectNode();
        }
    }
}
