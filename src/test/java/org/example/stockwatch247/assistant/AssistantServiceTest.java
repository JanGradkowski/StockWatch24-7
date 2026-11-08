package org.example.stockwatch247.assistant;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The chat service with a scripted model: drafts are validated and repaired, links checked, limits enforced. */
class AssistantServiceTest {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final ChartIndicatorCatalog CATALOG = new ChartIndicatorCatalog();
    private static final String RSI_RULE = """
            {"name": "RSI below 30", "interval": "DAILY", "definition": {"groups": [{"match": "ALL", "conditions": [
             {"kind": "VALUE", "subject": {"kind": "INDICATOR", "type": "RSI", "parameters": {"period": 14}, "output": "value"},
              "operator": "BELOW", "compare": {"kind": "VALUE", "value": 30}}]}]}}""";
    private static AssistantKnowledge knowledge;

    private final ScriptedModel model = new ScriptedModel();
    private final Limits limits = new Limits();
    private AssistantService service;
    private User user;

    @BeforeAll
    static void load() throws IOException {
        knowledge = new AssistantKnowledge(CATALOG);
    }

    @BeforeEach
    void setUp() {
        service = new AssistantService(knowledge, model, CATALOG, limits, null, JSON, true, 6, 60, 10, 200, false);
        user = new User();
        user.setId(7L);
    }

    @Test
    void aValidDraftComesBackNormalized() {
        model.answer(reply("Add the RSI condition.", RSI_RULE, "", ""));
        AssistantService.Answer answer = service.chat(user, AppLanguage.EN, question("/custom-alerts/new", "Alert me when RSI is below 30"));
        assertThat(answer.reply()).isEqualTo("Add the RSI condition.");
        assertThat(answer.draft()).isNotNull();
        assertThat(answer.draft().intervals()).containsExactly("DAILY");
        assertThat(answer.draft().triggerMode()).isEqualTo("BECOMES_TRUE");
        assertThat(answer.draft().outcomeCandles()).isEqualTo(10);
        JsonNode condition = answer.draft().definition().path("groups").path(0).path("conditions").path(0);
        assertThat(condition.path("id").asString()).as("ids are filled in").isNotBlank();
        assertThat(condition.path("subject").path("parameters").path("source").asString()).as("defaults resolved").isEqualTo("close");
        assertThat(model.calls).hasSize(1);
        assertThat(model.calls.getFirst().system()).contains("# The page the user is on").contains("custom-alert-builder");
        String asked = model.calls.getFirst().turns().getLast().text();
        assertThat(asked).contains("Page: /custom-alerts/new").contains("Alert me when RSI is below 30");
    }

    @Test
    void aRejectedDraftIsRepairedOnce() {
        String unknownIndicator = RSI_RULE.replace("\"RSI\"", "\"NOT_AN_INDICATOR\"");
        model.answer(reply("Steps…", unknownIndicator, "", ""));
        model.answer(reply("Fixed steps…", RSI_RULE, "", ""));
        AssistantService.Answer answer = service.chat(user, AppLanguage.EN, question("/home", "RSI alert"));
        assertThat(answer.reply()).isEqualTo("Fixed steps…");
        assertThat(answer.draft()).isNotNull();
        assertThat(model.calls).hasSize(2);
        String repair = model.calls.get(1).turns().getLast().text();
        assertThat(repair).contains("This indicator cannot be used in a custom alert.");
    }

    @Test
    void aDraftRejectedTwiceIsLeftOutButTheAnswerStays() {
        String broken = "{\"definition\": {\"groups\": []}}";
        model.answer(reply("First", broken, "", ""));
        model.answer(reply("Second", broken, "", ""));
        AssistantService.Answer answer = service.chat(user, AppLanguage.EN, question("/home", "Something impossible"));
        assertThat(answer.draft()).isNull();
        assertThat(answer.reply()).as("the answer that knows why the rule failed").isEqualTo("Second");
    }

    @Test
    void onlyAppPagesBecomeLinks() {
        model.answer(reply("Open the scoring settings.", "", "/settings/scoring", ""));
        AssistantService.Answer answer = service.chat(user, AppLanguage.EN, question("/home", "Where is scoring?"));
        assertThat(answer.link()).isNotNull();
        assertThat(answer.link().path()).isEqualTo("/settings/scoring");
        assertThat(answer.link().label()).isEqualTo("Settings");

        model.answer(reply("Go here.", "", "https://evil.example/login", ""));
        assertThat(service.chat(user, AppLanguage.EN, question("/home", "Where?")).link()).isNull();
    }

    @Test
    void aQuestionAboutAnotherPageFetchesThatPageOnce() {
        model.answer(reply("Let me check.", "", "", "settings"));
        model.answer(reply("Open Settings → Appearance.", "", "", "settings"));
        AssistantService.Answer answer = service.chat(user, AppLanguage.EN, question("/home", "How do I change Elliott colours?"));
        assertThat(answer.reply()).isEqualTo("Open Settings → Appearance.");
        assertThat(model.calls).hasSize(2);
        assertThat(model.calls.get(1).system()).contains("# Another page you asked for").contains("(settings)");
    }

    @Test
    void plainTextFromTheModelIsShownAsItIs() {
        model.answer("Just text, not JSON.");
        assertThat(service.chat(user, AppLanguage.EN, question("/home", "Hi")).reply()).isEqualTo("Just text, not JSON.");
    }

    @Test
    void limitsRefuseBeforeTheModelIsCalled() {
        limits.refuse("assistant-account-minute:7");
        assertThatThrownBy(() -> service.chat(user, AppLanguage.EN, question("/home", "Hi")))
                .isInstanceOfSatisfying(AssistantService.RefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(AssistantService.Refusal.ACCOUNT_MINUTE));
        limits.allowAll();
        limits.refuse("assistant-site-day");
        assertThatThrownBy(() -> service.chat(user, AppLanguage.EN, question("/home", "Hi")))
                .isInstanceOfSatisfying(AssistantService.RefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(AssistantService.Refusal.SITE_DAY));
        assertThat(model.calls).isEmpty();
    }

    @Test
    void theProvidersOwnLimitIsReportedAsBusy() {
        model.fail(new AssistantModel.UnavailableException("429", true, null));
        assertThatThrownBy(() -> service.chat(user, AppLanguage.EN, question("/home", "Hi")))
                .isInstanceOfSatisfying(AssistantService.RefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(AssistantService.Refusal.MODEL_BUSY));
    }

    @Test
    void aUsedUpDailyQuotaRestsUntilTomorrow() {
        model.fail(new AssistantModel.UnavailableException("429", true, true, null));
        assertThatThrownBy(() -> service.chat(user, AppLanguage.EN, question("/home", "Hi")))
                .isInstanceOfSatisfying(AssistantService.RefusedException.class,
                        refused -> assertThat(refused.refusal()).isEqualTo(AssistantService.Refusal.SITE_DAY));
    }

    @Test
    void theConversationIsTrimmedAndAlternates() {
        List<AssistantService.Message> messages = new ArrayList<>();
        messages.add(new AssistantService.Message("model", "A greeting the user never answered", null));
        for (int index = 0; index < 20; index++) {
            messages.add(new AssistantService.Message("user", "Question " + index, null));
            messages.add(new AssistantService.Message("model", "Answer " + index, null));
        }
        messages.add(new AssistantService.Message("user", "Failed once", null));
        messages.add(new AssistantService.Message("user", "Last question", null));
        model.answer(reply("Ok", "", "", ""));
        service.chat(user, AppLanguage.EN, new AssistantService.ChatCommand("/home", messages, null));
        List<AssistantModel.Turn> turns = model.calls.getFirst().turns();
        assertThat(turns.size()).isLessThanOrEqualTo(AssistantService.MAX_TURNS);
        assertThat(turns.getFirst().role()).isEqualTo("user");
        for (int index = 1; index < turns.size(); index++) {
            assertThat(turns.get(index).role()).as("turn " + index).isNotEqualTo(turns.get(index - 1).role());
        }
        assertThat(turns.getLast().text()).contains("Failed once").contains("Last question");
        assertThat(turns.get(1).text()).as("earlier answers are replayed in the answer format").startsWith("{\"reply\":");
    }

    @Test
    void tooLongQuestionsAreRejected() {
        String question = "x".repeat(AssistantService.MAX_QUESTION + 1);
        assertThatThrownBy(() -> service.chat(user, AppLanguage.EN, question("/home", question))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRecipesAreValidAlerts() throws IOException {
        String recipes = new String(getClass().getClassLoader().getResourceAsStream("assistant/custom-alerts/recipes.md").readAllBytes(),
                StandardCharsets.UTF_8);
        Matcher blocks = Pattern.compile("```json\\s*(.*?)```", Pattern.DOTALL).matcher(recipes);
        Set<String> names = new HashSet<>();
        int count = 0;
        while (blocks.find()) {
            String block = blocks.group(1);
            AssistantService.Draft draft;
            try {
                draft = service.draft(block);
            } catch (UserInputException invalid) {
                throw new AssertionError("Recipe is not a valid alert: " + invalid.getMessage() + "\n" + block, invalid);
            }
            assertThat(names.add(draft.name())).as("recipe names are distinct").isTrue();
            count++;
        }
        assertThat(count).isGreaterThanOrEqualTo(12);
    }

    @Test
    void aDraftCanWatchSeveralIntervalsThatAllSuitItsTimeframes() {
        String weeklyRsi = RSI_RULE.replace("\"parameters\": {\"period\": 14}", "\"parameters\": {\"period\": 14, \"timeframe\": \"1wk\"}");
        assertThat(service.draft(weeklyRsi.replace("\"interval\": \"DAILY\"", "\"intervals\": [\"WEEKLY\", \"DAILY\"]")).intervals())
                .as("shortest first").containsExactly("DAILY", "WEEKLY");
        assertThatThrownBy(() -> service.draft(weeklyRsi.replace("\"interval\": \"DAILY\"", "\"intervals\": [\"DAILY\", \"MONTHLY\"]")))
                .as("a weekly timeframe cannot be read on monthly candles").isInstanceOf(UserInputException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static AssistantService.ChatCommand question(String page, String text) {
        return new AssistantService.ChatCommand(page, List.of(new AssistantService.Message("user", text, null)), null);
    }

    private static String reply(String text, String draft, String link, String needPage) {
        var node = JSON.createObjectNode();
        node.put("reply", text);
        node.put("alertDraft", draft);
        node.put("link", link);
        node.put("needPage", needPage);
        return JSON.writeValueAsString(node);
    }

    private static final class ScriptedModel implements AssistantModel {
        final List<Request> calls = new ArrayList<>();
        private final Deque<Object> script = new ArrayDeque<>();

        void answer(String text) { script.add(text); }

        void fail(RuntimeException failure) { script.add(failure); }

        @Override
        public boolean configured() { return true; }

        @Override
        public Reply generate(Request request) {
            Object next = script.poll();
            if (next instanceof RuntimeException failure) throw failure;
            calls.add(request);
            assertThat(request.schema()).isSameAs(AssistantService.SCHEMA);
            return new Reply(next == null ? "{\"reply\": \"(no script)\"}" : (String) next, Usage.NONE, "scripted");
        }
    }

    private static final class Limits extends RequestRateLimiter {
        private final Set<String> refused = new HashSet<>();

        Limits() { super(null); }

        void refuse(String key) { refused.add(key); }

        void allowAll() { refused.clear(); }

        @Override
        public boolean tryAcquire(String key, int maximumRequests, Duration window) {
            return !refused.contains(key);
        }
    }
}
