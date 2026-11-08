package org.example.stockwatch247.assistant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Gemini request as the API expects it, its answer read back without thoughts, and the fallback model. */
class GeminiAssistantModelTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String MAIN = "https://gemini.test/v1beta/models/gemini-3.8-flash:generateContent";
    private static final String FALLBACK = "https://gemini.test/v1beta/models/gemini-3.6-flash:generateContent";
    private static final String ANSWER = """
            {"candidates": [{"content": {"parts": [{"text": "thinking about it", "thought": true},
              {"text": "{\\"reply\\": \\"Hello\\"}"}]}, "finishReason": "STOP"}],
             "usageMetadata": {"promptTokenCount": 12000, "cachedContentTokenCount": 9000, "candidatesTokenCount": 80,
              "thoughtsTokenCount": 40}}""";
    private static final String DAILY_QUOTA = """
            {"error": {"code": 429, "message": "Quota exceeded for metric: generate_content_free_tier_requests, limit: 20,
             description: Generate content requests per day limit exceeded on Free Tier.", "status": "RESOURCE_EXHAUSTED"}}""";
    /** 20:00 UTC on 7 Nov 2026 is noon in California: the daily quota resets 12 hours later. */
    private static final Instant NOW = Instant.parse("2026-11-07T20:00:00Z");

    private RestTemplate http;
    private MockRestServiceServer server;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        http = new RestTemplate();
        server = MockRestServiceServer.bindTo(http).build();
        clock = new MutableClock(NOW);
    }

    private GeminiAssistantModel model(String fallback) {
        return new GeminiAssistantModel(http, JSON, "https://gemini.test/v1beta/", " secret-key ", "gemini-3.8-flash", fallback,
                "low", 4096, Duration.ZERO, clock);
    }

    private static AssistantModel.Request question() {
        return new AssistantModel.Request("Rules", List.of(new AssistantModel.Turn("user", "Hi")), null);
    }

    @Test
    void sendsTheKeyInAHeaderAndTheConversationInTheBody() {
        server.expect(requestTo(MAIN)).andExpect(method(HttpMethod.POST)).andExpect(header("x-goog-api-key", "secret-key"))
                .andExpect(request -> {
                    JsonNode body = JSON.readTree(request.getBody().toString());
                    assertThat(body.path("systemInstruction").path("parts").path(0).path("text").asString()).isEqualTo("Rules");
                    assertThat(body.path("contents").path(0).path("role").asString()).isEqualTo("user");
                    assertThat(body.path("contents").path(1).path("role").asString()).isEqualTo("model");
                    JsonNode config = body.path("generationConfig");
                    assertThat(config.path("thinkingConfig").path("thinkingLevel").asString()).isEqualTo("LOW");
                    assertThat(config.path("responseMimeType").asString()).isEqualTo("application/json");
                    assertThat(config.path("responseJsonSchema").path("required").toString()).contains("reply");
                    assertThat(config.has("temperature")).as("ignored by Gemini 3.6 Flash and newer").isFalse();
                })
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        AssistantModel.Reply reply = model("").generate(new AssistantModel.Request("Rules",
                List.of(new AssistantModel.Turn("user", "Hi"), new AssistantModel.Turn("model", "{}"), new AssistantModel.Turn("user", "Again")),
                AssistantService.SCHEMA));

        assertThat(reply.text()).isEqualTo("{\"reply\": \"Hello\"}");
        assertThat(reply.usage()).isEqualTo(new AssistantModel.Usage(12000, 9000, 80, 40));
        assertThat(reply.model()).isEqualTo("gemini-3.8-flash");
        server.verify();
    }

    @Test
    void anOverloadedModelHandsTheQuestionToTheFallback() {
        server.expect(once(), requestTo(MAIN)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("{\"error\": {\"code\": 503}}"));
        server.expect(once(), requestTo(FALLBACK)).andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));
        assertThat(model("gemini-3.6-flash").generate(question()).model()).isEqualTo("gemini-3.6-flash");
        server.verify();
    }

    @Test
    void withoutAFallbackHighDemandIsTriedOnceMore() {
        server.expect(once(), requestTo(MAIN)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(once(), requestTo(MAIN)).andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));
        assertThat(model("").generate(question()).text()).contains("Hello");
        server.verify();
    }

    @Test
    void aModelOutOfDailyQuotaRestsUntilMidnightPacificTime() {
        GeminiAssistantModel model = model("gemini-3.6-flash");
        server.expect(once(), requestTo(MAIN)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(DAILY_QUOTA));
        server.expect(once(), requestTo(FALLBACK)).andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(FALLBACK)).andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));
        assertThat(model.generate(question()).model()).isEqualTo("gemini-3.6-flash");
        clock.now = NOW.plus(Duration.ofHours(11));
        assertThat(model.generate(question()).model()).as("the main model is not asked again before the reset").isEqualTo("gemini-3.6-flash");
        server.verify();

        server.reset();
        clock.now = NOW.plus(Duration.ofHours(12)).plusSeconds(1);
        server.expect(once(), requestTo(MAIN)).andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));
        assertThat(model.generate(question()).model()).isEqualTo("gemini-3.8-flash");
        server.verify();
    }

    @Test
    void whenEveryModelHasUsedItsDayTheAnswerIsTomorrow() {
        GeminiAssistantModel model = model("gemini-3.6-flash");
        server.expect(once(), requestTo(MAIN)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(DAILY_QUOTA));
        server.expect(once(), requestTo(FALLBACK)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(DAILY_QUOTA));
        assertThatThrownBy(() -> model.generate(question())).isInstanceOfSatisfying(AssistantModel.UnavailableException.class,
                failure -> assertThat(failure.daily()).isTrue());
        // Resting models are not asked at all.
        assertThatThrownBy(() -> model.generate(question())).isInstanceOfSatisfying(AssistantModel.UnavailableException.class,
                failure -> assertThat(failure.busy()).isTrue());
        server.verify();
    }

    @Test
    void aPerMinuteLimitIsBusyButNotForTheDay() {
        server.expect(once(), requestTo(MAIN)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("{\"error\": {\"message\": \"requests per minute limit exceeded\"}}"));
        assertThatThrownBy(() -> model("").generate(question())).isInstanceOfSatisfying(AssistantModel.UnavailableException.class,
                failure -> assertThat(failure.busy() && !failure.daily()).isTrue());
    }

    @Test
    void aRefusedRequestIsNotSentToTheFallback() {
        server.expect(once(), requestTo(MAIN)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\": {\"code\": 400}}"));
        assertThatThrownBy(() -> model("gemini-3.6-flash").generate(question()))
                .isInstanceOfSatisfying(AssistantModel.UnavailableException.class, failure -> assertThat(failure.busy()).isFalse());
        server.verify();
    }

    @Test
    void anEmptyAnswerIsAFailure() {
        server.expect(requestTo(MAIN)).andRespond(withSuccess("""
                {"candidates": [{"content": {"parts": []}, "finishReason": "SAFETY"}]}""", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> model("").generate(question()))
                .isInstanceOfSatisfying(AssistantModel.UnavailableException.class, failure -> assertThat(failure.busy()).isFalse())
                .hasMessageContaining("SAFETY");
    }

    @Test
    void withoutAKeyOrModelItIsNotConfigured() {
        assertThat(model("").configured()).isTrue();
        assertThat(new GeminiAssistantModel(http, JSON, "https://gemini.test", "", "gemini-3.8-flash", "", "LOW", 4096, Duration.ZERO, clock)
                .configured()).isFalse();
        assertThat(new GeminiAssistantModel(http, JSON, "https://gemini.test", "key", "", "", "LOW", 4096, Duration.ZERO, clock)
                .configured()).isFalse();
        assertThat(new GeminiAssistantModel(http, JSON, "https://gemini.test", "key", "../other", "", "LOW", 4096, Duration.ZERO, clock)
                .configured()).isFalse();
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) { this.now = now; }

        @Override
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(java.time.ZoneId zone) { return this; }

        @Override
        public Instant instant() { return now; }
    }
}
