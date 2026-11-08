package org.example.stockwatch247.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google's Gemini API ({@code models/{model}:generateContent}). The key travels in the {@code x-goog-api-key}
 * header, never in the URL, so it stays out of proxy and access logs.
 *
 * <p>Gemini 3.6 Flash and newer ignore sampling settings (temperature, top-p), so none are sent; how much the model
 * thinks is set with {@code thinkingLevel} instead. Thought parts are skipped when the answer is read.</p>
 *
 * <p>Quotas are counted per model, and a new model is often overloaded. So a fallback model can stand in: when the
 * main model answers "high demand" (503), times out, or has used its quota (429), the fallback is asked. A model out
 * of quota rests (a minute, or until Google's daily reset at midnight Pacific time) instead of being asked again.</p>
 */
@Component
public class GeminiAssistantModel implements AssistantModel {
    private static final Logger log = LoggerFactory.getLogger(GeminiAssistantModel.class);
    private static final Set<String> THINKING_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
    private static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

    private final RestTemplate http;
    private final ObjectMapper json;
    private final String baseUrl;
    private final String apiKey;
    private final List<String> models;
    private final String thinkingLevel;
    private final int maxOutputTokens;
    private final Duration retryDelay;
    private final Clock clock;
    /** Models out of quota, and until when; {@code daily} marks a used-up daily quota. */
    private final Map<String, Rest> resting = new ConcurrentHashMap<>();

    private record Rest(Instant until, boolean daily) { }

    @Autowired
    public GeminiAssistantModel(ObjectMapper json,
                                @Value("${assistant.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
                                @Value("${assistant.gemini.api-key:}") String apiKey,
                                @Value("${assistant.gemini.model:}") String model,
                                @Value("${assistant.gemini.fallback-model:}") String fallbackModel,
                                @Value("${assistant.gemini.thinking-level:LOW}") String thinkingLevel,
                                @Value("${assistant.gemini.max-output-tokens:4096}") int maxOutputTokens,
                                @Value("${assistant.timeout-seconds:30}") long timeoutSeconds) {
        this(restTemplate(timeoutSeconds), json, baseUrl, apiKey, model, fallbackModel, thinkingLevel, maxOutputTokens,
                Duration.ofSeconds(2), Clock.systemUTC());
    }

    GeminiAssistantModel(RestTemplate http, ObjectMapper json, String baseUrl, String apiKey, String model, String fallbackModel,
                         String thinkingLevel, int maxOutputTokens, Duration retryDelay, Clock clock) {
        this.http = http;
        this.json = json;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        List<String> names = new ArrayList<>();
        String primary = model == null ? "" : model.trim();
        names.add(primary);
        String fallback = fallbackModel == null ? "" : fallbackModel.trim();
        if (validName(fallback) && !fallback.equals(primary)) names.add(fallback);
        this.models = List.copyOf(names);
        String level = thinkingLevel == null ? "" : thinkingLevel.trim().toUpperCase(Locale.ROOT);
        this.thinkingLevel = THINKING_LEVELS.contains(level) ? level : null;
        this.maxOutputTokens = Math.clamp(maxOutputTokens, 256, 65_536);
        this.retryDelay = retryDelay;
        this.clock = clock;
    }

    private static RestTemplate restTemplate(long timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(Math.clamp(timeoutSeconds, 5, 120)));
        return new RestTemplate(factory);
    }

    private static boolean validName(String model) {
        return model.matches("[A-Za-z0-9._-]{1,80}");
    }

    @Override
    public boolean configured() {
        return !apiKey.isEmpty() && validName(models.getFirst());
    }

    /** Why one model did not answer. REFUSED is a problem with the request itself, which another model would share. */
    private enum Failure { OVERLOADED, UNREACHABLE, QUOTA, REFUSED }

    private static final class Attempt extends RuntimeException {
        final Failure failure;

        Attempt(Failure failure, String reason, Throwable cause) {
            super(reason, cause, false, false);
            this.failure = failure;
        }
    }

    @Override
    public Reply generate(Request request) {
        if (!configured()) throw new UnavailableException("Gemini is not configured", false, null);
        String body = json.writeValueAsString(body(request));
        boolean busy = false;
        Attempt last = null;
        for (String model : models) {
            if (resting(model) != null) {
                busy = true;
                continue;
            }
            // With no fallback, one more try after a short pause: "high demand" usually passes within seconds.
            int tries = models.size() == 1 ? 2 : 1;
            for (int attempt = 1; attempt <= tries; attempt++) {
                try {
                    return reply(model, call(model, body));
                } catch (Attempt failed) {
                    last = failed;
                    switch (failed.failure) {
                        case REFUSED -> throw new UnavailableException(failed.getMessage(), false, failed);
                        case QUOTA, OVERLOADED -> busy = true;
                        default -> { }
                    }
                    if (failed.failure != Failure.OVERLOADED) break;
                    if (attempt < tries) pause();
                }
            }
        }
        boolean daily = models.stream().map(this::resting).allMatch(rest -> rest != null && rest.daily());
        throw new UnavailableException(last == null ? "Every Gemini model is resting" : last.getMessage(), busy, daily, last);
    }

    /** The model's rest if it is out of quota now, otherwise null. */
    private Rest resting(String model) {
        Rest rest = resting.get(model);
        return rest != null && rest.until().isAfter(clock.instant()) ? rest : null;
    }

    private String call(String model, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);
        String url = baseUrl + "/models/" + model + ":generateContent";
        try {
            ResponseEntity<String> response = http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            return response.getBody() == null ? "{}" : response.getBody();
        } catch (HttpStatusCodeException failure) {
            int status = failure.getStatusCode().value();
            String detail = failure.getResponseBodyAsString();
            // The body explains a refused request; it never contains the key, which is only sent in a header.
            log.warn("Gemini {} answered {}: {}", model, status, abbreviate(detail, 400));
            if (status == 429) {
                boolean daily = detail.toLowerCase(Locale.ROOT).replace(" ", "").contains("perday");
                Instant until = daily ? clock.instant().atZone(QUOTA_ZONE).toLocalDate().plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant()
                        : clock.instant().plusSeconds(60);
                resting.put(model, new Rest(until, daily));
                throw new Attempt(Failure.QUOTA, "Gemini " + model + " is out of quota", failure);
            }
            throw new Attempt(status >= 500 ? Failure.OVERLOADED : Failure.REFUSED, "Gemini " + model + " answered " + status, failure);
        } catch (RestClientException failure) {
            log.warn("Gemini {} could not be reached: {}", model, failure.getMessage());
            throw new Attempt(Failure.UNREACHABLE, "Gemini " + model + " could not be reached", failure);
        }
    }

    private void pause() {
        try {
            Thread.sleep(retryDelay.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    ObjectNode body(Request request) {
        ObjectNode body = json.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.system());
        ArrayNode contents = body.putArray("contents");
        for (Turn turn : request.turns()) {
            ObjectNode content = contents.addObject();
            content.put("role", "model".equals(turn.role()) ? "model" : "user");
            content.putArray("parts").addObject().put("text", turn.text());
        }
        ObjectNode config = body.putObject("generationConfig");
        config.put("maxOutputTokens", maxOutputTokens);
        if (thinkingLevel != null) config.putObject("thinkingConfig").put("thinkingLevel", thinkingLevel);
        if (request.schema() != null) {
            config.put("responseMimeType", "application/json");
            config.set("responseJsonSchema", json.valueToTree(request.schema()));
        }
        return body;
    }

    Reply reply(String model, String responseBody) {
        JsonNode body = json.readTree(responseBody);
        JsonNode candidate = body.path("candidates").path(0);
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) continue;
            text.append(part.path("text").asString(""));
        }
        JsonNode usage = body.path("usageMetadata");
        Usage counts = new Usage(usage.path("promptTokenCount").asInt(0), usage.path("cachedContentTokenCount").asInt(0),
                usage.path("candidatesTokenCount").asInt(0), usage.path("thoughtsTokenCount").asInt(0));
        if (text.isEmpty()) {
            String reason = candidate.path("finishReason").asString(body.path("promptFeedback").path("blockReason").asString("EMPTY"));
            log.warn("Gemini {} returned no answer (finish reason {})", model, reason);
            throw new UnavailableException("Gemini returned no answer: " + reason, false, null);
        }
        if ("MAX_TOKENS".equals(candidate.path("finishReason").asString(""))) {
            log.warn("Gemini {} stopped at the output limit of {} tokens", model, maxOutputTokens);
        }
        return new Reply(text.toString(), counts, model);
    }

    private static String abbreviate(String text, int length) {
        if (text == null) return "";
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= length ? flat : flat.substring(0, length) + "…";
    }
}
