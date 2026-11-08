package org.example.stockwatch247.assistant;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asks the real model the questions in assistant/evals.json and checks the answers: the alert it proposes passes the
 * validator and has the expected conditions, the reply quotes real labels, links point where expected, and it refuses
 * investment advice. It spends the Gemini quota, so it runs only when asked:
 * {@code ./mvnw test -Dtest=AssistantLiveEvalTest -Dassistant.eval=true}. The key comes from GEMINI_API_KEY or .env.
 * Run it after changing a knowledge file or GEMINI_MODEL; it should pass at least 90%.
 */
@EnabledIfSystemProperty(named = "assistant.eval", matches = "true")
class AssistantLiveEvalTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** Spaces the calls out to stay under a free-tier key's per-minute limit. */
    private static final Duration PAUSE = Duration.ofSeconds(Long.getLong("assistant.eval.pause-seconds", 7));

    @Test
    void theAssistantAnswersTheEvaluationQuestions() throws Exception {
        Properties env = environment();
        String key = env.getProperty("GEMINI_API_KEY", "").trim();
        String modelName = System.getProperty("assistant.eval.model", env.getProperty("GEMINI_MODEL", "gemini-3.6-flash")).trim();
        assertThat(key).as("GEMINI_API_KEY in the environment or .env").isNotBlank();
        ChartIndicatorCatalog catalog = new ChartIndicatorCatalog();
        AssistantKnowledge knowledge = new AssistantKnowledge(catalog);
        String fallback = System.getProperty("assistant.eval.fallback", env.getProperty("GEMINI_FALLBACK_MODEL", "gemini-3.8-flash")).trim();
        GeminiAssistantModel model = new GeminiAssistantModel(JSON, "https://generativelanguage.googleapis.com/v1beta", key, modelName,
                fallback, env.getProperty("GEMINI_THINKING_LEVEL", "LOW"), 4096, 60);
        RequestRateLimiter unlimited = new RequestRateLimiter(null) {
            @Override
            public boolean tryAcquire(String counter, int maximumRequests, Duration window) { return true; }
        };
        AssistantService service = new AssistantService(knowledge, model, catalog, unlimited, null, JSON, true, 1, 1, 1, 1, false);
        User user = new User();
        user.setId(1L);

        JsonNode evals;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("assistant/evals.json")) {
            evals = JSON.readTree(in);
        }
        String only = System.getProperty("assistant.eval.only");
        List<String> failures = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        int run = 0;
        for (JsonNode eval : evals) {
            String id = eval.path("id").asString();
            if (only != null && !only.isBlank() && !List.of(only.split(",")).contains(id)) continue;
            if (run++ > 0) Thread.sleep(PAUSE.toMillis());
            AppLanguage language = AppLanguage.valueOf(eval.path("language").asString("EN"));
            long started = System.nanoTime();
            List<String> problems = new ArrayList<>();
            AssistantService.Answer answer = null;
            AssistantService.Refusal refusal = null;
            try {
                answer = service.chat(user, language, new AssistantService.ChatCommand(eval.path("page").asString(),
                        List.of(new AssistantService.Message("user", eval.path("question").asString(), null)), null));
                problems.addAll(check(eval, answer));
            } catch (AssistantService.RefusedException refused) {
                refusal = refused.refusal();
            } catch (RuntimeException failure) {
                problems.add("failed: " + failure);
            }
            long millis = (System.nanoTime() - started) / 1_000_000;
            // Gemini being down or out of quota says nothing about the knowledge files, so it is counted apart.
            if (refusal != null) {
                unavailable.add(id);
                System.out.printf(Locale.ROOT, "%-24s %-5s %6d ms  Gemini unavailable (%s)%n", id, "SKIP", millis, refusal);
                if (refusal == AssistantService.Refusal.SITE_DAY) break;
                continue;
            }
            System.out.printf(Locale.ROOT, "%-24s %-5s %6d ms  %s%n", id, problems.isEmpty() ? "PASS" : "FAIL", millis,
                    problems.isEmpty() ? "" : String.join("; ", problems));
            if (answer != null && Boolean.getBoolean("assistant.eval.verbose")) {
                System.out.println("    reply: " + answer.reply().replace("\n", "\n           "));
                if (answer.draft() != null) System.out.println("    draft: " + JSON.writeValueAsString(answer.draft()));
            }
            if (!problems.isEmpty()) failures.add(id);
        }
        int answered = run - unavailable.size();
        double passed = answered == 0 ? 0 : (answered - failures.size()) / (double) answered;
        System.out.printf(Locale.ROOT, "%nPassed %d of %d answered (%.0f%%). Failed: %s. Gemini unavailable for %d: %s%n",
                answered - failures.size(), answered, passed * 100, failures, unavailable.size(), unavailable);
        assertThat(answered).as("questions Gemini answered").isPositive();
        assertThat(passed).as("share of answered questions answered correctly").isGreaterThanOrEqualTo(0.9);
    }

    private static List<String> check(JsonNode eval, AssistantService.Answer answer) {
        List<String> problems = new ArrayList<>();
        String reply = answer.reply().toLowerCase(Locale.ROOT);
        boolean wantsDraft = eval.path("draft").asBoolean(false);
        if (wantsDraft && answer.draft() == null) problems.add("no valid alert");
        if (!wantsDraft && answer.draft() != null && eval.has("conditions")) problems.add("unexpected alert");
        for (JsonNode mention : eval.path("mentions")) {
            if (!reply.contains(mention.asString().toLowerCase(Locale.ROOT))) problems.add("does not mention \"" + mention.asString() + "\"");
        }
        if (eval.has("mentionsAny")) {
            boolean any = false;
            for (JsonNode mention : eval.path("mentionsAny")) any |= reply.contains(mention.asString().toLowerCase(Locale.ROOT));
            if (!any) problems.add("mentions none of " + eval.path("mentionsAny"));
        }
        if (eval.has("link") && (answer.link() == null || !answer.link().path().equals(eval.path("link").asString()))) {
            problems.add("link " + (answer.link() == null ? "missing" : answer.link().path()) + " instead of " + eval.path("link").asString());
        }
        if (answer.draft() == null) return problems;
        JsonNode definition = answer.draft().definition();
        if (eval.has("interval") && !eval.path("interval").asString().equals(answer.draft().interval())) {
            problems.add("interval " + answer.draft().interval());
        }
        // "Either A or B" is the same rule as two groups joined with ANY or as one group whose conditions are joined with ANY.
        if (eval.has("match") && "ANY".equals(eval.path("match").asString()) && !"ANY".equals(definition.path("match").asString())) {
            boolean anyGroup = false;
            for (JsonNode group : definition.path("groups")) anyGroup |= "ANY".equals(group.path("match").asString());
            if (!anyGroup) problems.add("conditions are not joined with any");
        }
        List<JsonNode> conditions = new ArrayList<>();
        boolean sequence = false;
        for (JsonNode group : definition.path("groups")) {
            sequence |= "THEN".equals(group.path("match").asString());
            group.path("conditions").forEach(conditions::add);
        }
        if (eval.has("groupMatch") && "THEN".equals(eval.path("groupMatch").asString()) && !sequence) problems.add("no sequence");
        for (JsonNode expected : eval.path("conditions")) {
            if (conditions.stream().noneMatch(condition -> matches(expected, condition))) problems.add("missing condition " + expected);
        }
        return problems;
    }

    private static boolean matches(JsonNode expected, JsonNode condition) {
        if (expected.has("kind") && !expected.path("kind").asString().equals(condition.path("kind").asString())) return false;
        if (expected.has("operator")) {
            boolean any = false;
            for (JsonNode operator : expected.path("operator")) any |= operator.asString().equals(condition.path("operator").asString());
            if (!any) return false;
        }
        if (expected.has("indicator")) {
            String type = expected.path("indicator").asString();
            if (!type.equals(condition.path("subject").path("type").asString()) && !type.equals(condition.path("compare").path("type").asString())) {
                return false;
            }
        }
        if (expected.has("timeframe")) {
            String timeframe = expected.path("timeframe").asString();
            if (!timeframe.equals(condition.path("subject").path("parameters").path("timeframe").asString())
                    && !timeframe.equals(condition.path("compare").path("parameters").path("timeframe").asString())) return false;
        }
        if (expected.has("field") && !expected.path("field").asString().equals(condition.path("subject").path("field").asString())) return false;
        for (String field : List.of("family", "status", "direction", "transaction", "strength")) {
            if (expected.has(field) && !expected.path(field).asString().equals(condition.path(field).asString())) return false;
        }
        if (expected.has("pattern")) {
            boolean listed = false;
            for (JsonNode name : condition.path("patterns")) listed |= expected.path("pattern").asString().equals(name.asString());
            if (!listed) return false;
        }
        if (expected.has("chamber")) {
            boolean listed = false;
            for (JsonNode name : condition.path("chambers")) listed |= expected.path("chamber").asString().equals(name.asString());
            if (!listed) return false;
        }
        if (expected.has("minCount") && condition.path("minCount").asInt(1) < expected.path("minCount").asInt()) return false;
        if (expected.has("negate") && expected.path("negate").asBoolean() != condition.path("negate").asBoolean(false)) return false;
        return !expected.has("stillValid") || condition.path("stillValid").asBoolean(false) == expected.path("stillValid").asBoolean();
    }

    /** GEMINI_* from the environment, falling back to the project's .env (a properties file, as the app reads it). */
    private static Properties environment() throws IOException {
        Properties properties = new Properties();
        Path file = Path.of(".env");
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        for (String name : List.of("GEMINI_API_KEY", "GEMINI_MODEL", "GEMINI_THINKING_LEVEL")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) properties.setProperty(name, value);
        }
        return properties;
    }
}
