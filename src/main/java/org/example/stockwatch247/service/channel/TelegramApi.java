package org.example.stockwatch247.service.channel;

import org.example.stockwatch247.security.GuardedHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The StockWatch bot's calls to the Telegram Bot API. The bot token sits in every request path, so requests are
 * logged by method name only and failures never carry the URL. The token and the bot's username come from the
 * secret manager (TELEGRAM_BOT_TOKEN, TELEGRAM_BOT_USERNAME); without them Telegram is simply not offered.
 */
@Component
public class TelegramApi {
    public static final String DEFAULT_BASE = "https://api.telegram.org";
    /** A token's length is not documented, so only its shape is checked. */
    public static final Pattern TOKEN = Pattern.compile("^\\d+:[A-Za-z0-9_-]{30,}$");
    private static final int RESPONSE_LIMIT = 64 * 1024;

    /** Telegram's answer: {@code ok}, or an error code with a description and maybe a wait. */
    public record Result(int status, JsonNode body) {
        public boolean ok() { return body.path("ok").asBoolean(false); }
        public int errorCode() { return body.path("error_code").asInt(status); }
        public String description() { return body.path("description").asString(""); }
        public Duration retryAfter() {
            int seconds = body.path("parameters").path("retry_after").asInt(5);
            return Duration.ofSeconds(Math.max(1, seconds));
        }
    }

    private final GuardedHttpClient http;
    private final ObjectMapper mapper;
    private final String token;
    private final String username;
    private final String apiBase;
    private final boolean enabled;

    public TelegramApi(GuardedHttpClient http, ObjectMapper mapper,
                       @Value("${alert-channels.telegram.bot-token:}") String token,
                       @Value("${alert-channels.telegram.bot-username:}") String username,
                       @Value("${alert-channels.telegram.api-base-url:" + DEFAULT_BASE + "}") String apiBase,
                       @Value("${alert-channels.telegram.enabled:true}") boolean enabled) {
        this.http = http;
        this.mapper = mapper;
        this.token = token == null ? "" : token.strip();
        this.username = username == null ? "" : username.strip().replaceFirst("^@", "");
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.enabled = enabled;
    }

    public boolean ready() {
        return enabled && TOKEN.matcher(token).matches() && !username.isBlank();
    }

    public String botUsername() { return username; }

    public Result call(String method, Map<String, Object> parameters) throws GuardedHttpClient.OutboundException {
        return call(method, parameters, null);
    }

    public Result call(String method, Map<String, Object> parameters, Duration responseTimeout)
            throws GuardedHttpClient.OutboundException {
        if (!ready()) throw new IllegalStateException("Telegram is not configured.");
        var request = GuardedHttpClient.Request.postJson(URI.create(apiBase + "/bot" + token + "/" + method), Map.of(),
                mapper.writeValueAsString(parameters), RESPONSE_LIMIT, "telegram " + method);
        if (responseTimeout != null) request = request.withResponseTimeout(responseTimeout);
        var response = http.execute(request);
        JsonNode body;
        try {
            body = mapper.readTree(response.body().isBlank() ? "{}" : response.body());
        } catch (RuntimeException unreadable) {
            body = mapper.createObjectNode();
        }
        return new Result(response.status(), body);
    }
}
