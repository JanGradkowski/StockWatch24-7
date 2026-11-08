package org.example.stockwatch247.assistant;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.model.User;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.security.RequestRateLimiter;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Answers a question asked in the assistant panel: builds the prompt from the knowledge files, asks the model, and
 * checks what it suggests. A custom alert the model proposes is validated by the parser the Save button uses (with
 * one chance to repair it), and a link is kept only when it is one of the app's pages, so a wrong answer can never
 * become an invalid rule or a link elsewhere.
 *
 * <p>Every call to the model is counted twice: per account (so one person cannot use the whole allowance) and for
 * the whole site (so the provider key's own per-minute and per-day quota is never exceeded).</p>
 */
@Service
public class AssistantService {
    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    static final int MAX_QUESTION = 1_000;
    static final int MAX_REPLY = 6_000;
    static final int MAX_DRAFT = 8_000;
    static final int MAX_CONTEXT = 12_000;
    static final int MAX_TURNS = 12;
    private static final Set<String> TRIGGER_MODES = Set.of("BECOMES_TRUE", "ONCE", "EVERY_CANDLE");
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,40}");

    /** One message of the conversation as the panel keeps it; {@code draft} is the alert JSON the reply carried. */
    public record Message(String role, String text, String draft) { }

    /** {@code page} is the path the panel is open on; {@code context} is what that page reports about itself. */
    public record ChatCommand(String page, List<Message> messages, JsonNode context) { }

    /** A validated custom alert the builder can load as it is. */
    public record Draft(String name, String description, List<String> intervals, String triggerMode, int quietCandles,
                        int outcomeCandles, JsonNode definition) { }

    public record Link(String path, String label) { }

    public record Answer(String reply, Draft draft, Link link) { }

    public record FeedbackCommand(String page, String question, String reply) { }

    /** Why a question was not answered; the panel explains each one in the user's language. */
    public enum Refusal { DISABLED, ACCOUNT_MINUTE, ACCOUNT_DAY, SITE_MINUTE, SITE_DAY, MODEL_BUSY, MODEL_DOWN }

    public static class RefusedException extends RuntimeException {
        private final Refusal refusal;
        private final long retryAfterSeconds;

        RefusedException(Refusal refusal, long retryAfterSeconds) {
            super(refusal.name());
            this.refusal = refusal;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public Refusal refusal() { return refusal; }

        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    /** What the model is asked to return. Draft and link are strings so nested rules never meet schema limits. */
    static final Map<String, Object> SCHEMA = schema();

    private final AssistantKnowledge knowledge;
    private final AssistantModel model;
    private final ChartIndicatorCatalog catalog;
    private final RequestRateLimiter limiter;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean enabled;
    private final int accountMinute;
    private final int accountDay;
    private final int siteMinute;
    private final int siteDay;
    private final boolean logMessages;

    public AssistantService(AssistantKnowledge knowledge, AssistantModel model, ChartIndicatorCatalog catalog,
                            RequestRateLimiter limiter, JdbcTemplate db, ObjectMapper json,
                            @Value("${assistant.enabled:false}") boolean enabled,
                            @Value("${assistant.per-account-minute-limit:6}") int accountMinute,
                            @Value("${assistant.per-account-daily-limit:60}") int accountDay,
                            @Value("${assistant.site-minute-limit:10}") int siteMinute,
                            @Value("${assistant.site-daily-limit:200}") int siteDay,
                            @Value("${assistant.log-messages:false}") boolean logMessages) {
        this.knowledge = knowledge;
        this.model = model;
        this.catalog = catalog;
        this.limiter = limiter;
        this.db = db;
        this.json = json;
        this.enabled = enabled;
        this.accountMinute = Math.max(1, accountMinute);
        this.accountDay = Math.max(1, accountDay);
        this.siteMinute = Math.max(1, siteMinute);
        this.siteDay = Math.max(1, siteDay);
        this.logMessages = logMessages;
    }

    /** The panel is shown only when the assistant is switched on and the model is configured. */
    public boolean enabled() {
        return enabled && model.configured();
    }

    public Answer chat(User user, AppLanguage language, ChatCommand command) {
        if (!enabled()) throw new RefusedException(Refusal.DISABLED, 0);
        List<Message> history = history(command);
        String path = command.page() == null ? "" : command.page().split("[?#]", 2)[0];
        AssistantKnowledge.Page page = knowledge.pageFor(path).orElse(null);
        if (!limiter.tryAcquire("assistant-account-minute:" + user.getId(), accountMinute, Duration.ofMinutes(1))) {
            throw new RefusedException(Refusal.ACCOUNT_MINUTE, 60);
        }
        if (!limiter.tryAcquire("assistant-account-day:" + user.getId(), accountDay, Duration.ofDays(1))) {
            throw new RefusedException(Refusal.ACCOUNT_DAY, 3_600);
        }
        long started = System.nanoTime();
        List<AssistantModel.Turn> turns = turns(history, path, context(command.context()));
        Calls calls = new Calls();
        String system = knowledge.systemPrompt(language, page, null);
        Parsed parsed = ask(system, turns, calls);

        // A question about another page: that page's file is added once, and the question asked again.
        AssistantKnowledge.Page extra = knowledge.page(parsed.needPage()).filter(found -> found != page).orElse(null);
        if (extra != null) {
            system = knowledge.systemPrompt(language, page, extra);
            parsed = ask(system, turns, calls);
        }

        Draft draft = null;
        String draftOutcome = "none";
        if (!parsed.alertDraft().isBlank()) {
            try {
                draft = draft(parsed.alertDraft());
                draftOutcome = "valid";
            } catch (UserInputException | JacksonException problem) {
                String reason = problem instanceof UserInputException ? problem.getMessage() : "The alertDraft is not valid JSON.";
                List<AssistantModel.Turn> repair = new ArrayList<>(turns);
                repair.add(new AssistantModel.Turn("model", parsed.raw()));
                repair.add(new AssistantModel.Turn("user", "[Checked by StockWatch, not written by the user] The alertDraft was rejected: "
                        + reason + " Answer again in the same JSON format: correct the alertDraft (and the steps in reply if they "
                        + "change), or leave alertDraft empty and say what cannot be built."));
                // The second answer knows why the first rule was rejected, so its words are kept even without a rule.
                parsed = ask(system, repair, calls);
                draftOutcome = "repaired";
                try {
                    if (!parsed.alertDraft().isBlank()) draft = draft(parsed.alertDraft());
                } catch (UserInputException | JacksonException again) {
                    draftOutcome = "rejected";
                    log.info("Assistant draft rejected twice on {}: {}", page == null ? path : page.id(), again.getMessage());
                }
            }
        }
        Link link = null;
        if (!parsed.link().isBlank() && knowledge.knownPath(parsed.link())) {
            link = new Link(parsed.link(), knowledge.pageFor(parsed.link()).map(found -> knowledge.title(language, found)).orElse(parsed.link()));
        }
        log.info("Assistant answered on {} in {} ms with {} call(s) to {}: prompt {} tokens ({} cached), output {}, thoughts {}, draft {}",
                page == null ? "(no page)" : page.id(), (System.nanoTime() - started) / 1_000_000, calls.count, calls.models,
                calls.prompt, calls.cached, calls.output, calls.thoughts, draftOutcome);
        if (logMessages) log.info("Assistant question: {}\nAssistant reply: {}", history.getLast().text(), parsed.reply());
        return new Answer(parsed.reply(), draft, link);
    }

    /** A reply the user marked as not helpful, kept so the knowledge files can be improved. */
    public void feedback(User user, FeedbackCommand command) {
        if (command == null || command.reply() == null || command.reply().isBlank()) throw new IllegalArgumentException("Nothing to send.");
        db.update("insert into assistant_feedback (user_id, page, question, reply) values (?, ?, ?, ?)", user.getId(),
                cut(command.page(), 200), cut(command.question(), MAX_QUESTION), cut(command.reply(), MAX_REPLY));
    }

    // ------------------------------------------------------------------ conversation

    private static List<Message> history(ChatCommand command) {
        if (command == null || command.messages() == null || command.messages().isEmpty()) throw new IllegalArgumentException("Ask a question.");
        List<Message> recent = command.messages().subList(Math.max(0, command.messages().size() - MAX_TURNS), command.messages().size());
        List<Message> clean = new ArrayList<>();
        for (Message message : recent) {
            if (message == null || message.text() == null) continue;
            boolean user = "user".equals(message.role());
            if (!user && !"model".equals(message.role())) continue;
            String text = message.text().strip();
            if (user && text.length() > MAX_QUESTION) throw new IllegalArgumentException("The question is too long.");
            if (text.isEmpty()) continue;
            clean.add(new Message(message.role(), user ? text : cut(text, MAX_REPLY), user ? null : cut(message.draft(), MAX_DRAFT)));
        }
        // The model expects the conversation to start with the user and alternate.
        while (!clean.isEmpty() && !"user".equals(clean.getFirst().role())) clean.removeFirst();
        if (clean.isEmpty() || !"user".equals(clean.getLast().role())) throw new IllegalArgumentException("Ask a question.");
        return clean;
    }

    private String context(JsonNode context) {
        if (context == null || context.isNull() || !context.isObject() || context.isEmpty()) return null;
        String text = json.writeValueAsString(context);
        return text.length() > MAX_CONTEXT ? null : text;
    }

    private List<AssistantModel.Turn> turns(List<Message> history, String path, String context) {
        List<AssistantModel.Turn> turns = new ArrayList<>();
        for (int index = 0; index < history.size(); index++) {
            Message message = history.get(index);
            if ("model".equals(message.role())) {
                ObjectNode previous = json.createObjectNode();
                previous.put("reply", message.text());
                previous.put("alertDraft", message.draft() == null ? "" : message.draft());
                turns.add(new AssistantModel.Turn("model", json.writeValueAsString(previous)));
                continue;
            }
            String text = message.text();
            if (index == history.size() - 1) {
                StringBuilder framed = new StringBuilder("[Context from StockWatch, not written by the user]\nPage: ").append(path.isBlank() ? "unknown" : path);
                if (context != null) framed.append("\nWhat the page shows now: ").append(context);
                framed.append("\n[The user's message]\n").append(text);
                text = framed.toString();
            }
            // Two user messages in a row (an answer failed in between) are joined, so the roles keep alternating.
            if (!turns.isEmpty() && "user".equals(turns.getLast().role())) {
                text = turns.removeLast().text() + "\n\n" + text;
            }
            turns.add(new AssistantModel.Turn("user", text));
        }
        return turns;
    }

    // ------------------------------------------------------------------ the model

    private record Parsed(String reply, String alertDraft, String link, String needPage, String raw) { }

    /** Model calls and tokens for one question, for the log line. */
    private static final class Calls {
        final Set<String> models = new java.util.LinkedHashSet<>();
        int count;
        int prompt;
        int cached;
        int output;
        int thoughts;
    }

    private Parsed ask(String system, List<AssistantModel.Turn> turns, Calls calls) {
        if (!limiter.tryAcquire("assistant-site-minute", siteMinute, Duration.ofMinutes(1))) throw new RefusedException(Refusal.SITE_MINUTE, 60);
        if (!limiter.tryAcquire("assistant-site-day", siteDay, Duration.ofDays(1))) throw new RefusedException(Refusal.SITE_DAY, 3_600);
        AssistantModel.Reply reply;
        try {
            reply = model.generate(new AssistantModel.Request(system, turns, SCHEMA));
        } catch (AssistantModel.UnavailableException unavailable) {
            // The provider's own daily quota is gone: the same "resting until tomorrow" as the site's daily limit.
            if (unavailable.daily()) throw new RefusedException(Refusal.SITE_DAY, 3_600);
            throw new RefusedException(unavailable.busy() ? Refusal.MODEL_BUSY : Refusal.MODEL_DOWN, unavailable.busy() ? 60 : 0);
        }
        calls.count++;
        calls.models.add(reply.model());
        calls.prompt += reply.usage().prompt();
        calls.cached += reply.usage().cached();
        calls.output += reply.usage().output();
        calls.thoughts += reply.usage().thoughts();
        return parse(reply.text());
    }

    /** The model's JSON answer; anything that is not that JSON is shown as plain text. */
    Parsed parse(String text) {
        String body = text.strip();
        if (body.startsWith("```")) body = body.replaceFirst("\\A```(?:json)?\\s*", "").replaceFirst("\\s*```\\z", "");
        try {
            JsonNode node = json.readTree(body);
            if (node.isObject() && node.path("reply").isString()) {
                return new Parsed(cut(node.path("reply").asString(""), MAX_REPLY), stringOrJson(node.path("alertDraft")),
                        node.path("link").asString("").strip(), node.path("needPage").asString("").strip(), body);
            }
        } catch (JacksonException notJson) {
            // Shown as written below.
        }
        return new Parsed(cut(text.strip(), MAX_REPLY), "", "", "", text);
    }

    /** The draft is asked for as JSON text; a model that sends the object itself is accepted too. */
    private String stringOrJson(JsonNode node) {
        if (node.isObject()) return json.writeValueAsString(node);
        String text = node.asString("").strip();
        return text.equals("{}") || text.equalsIgnoreCase("null") ? "" : text;
    }

    // ------------------------------------------------------------------ drafts

    /** Reads and validates a proposed alert; throws {@link UserInputException} with the validator's reason. */
    Draft draft(String text) {
        if (text.length() > MAX_DRAFT) throw new UserInputException("The alert is too long.");
        JsonNode node = json.readTree(text);
        if (!node.isObject()) throw new UserInputException("The alert definition is incomplete.");
        // "intervals" as the reference describes it; a single "interval" is read too.
        List<String> intervalNames = new ArrayList<>();
        if (node.path("intervals").isArray()) node.path("intervals").forEach(item -> intervalNames.add(item.asString("")));
        else intervalNames.add(node.path("interval").asString("DAILY"));
        if (intervalNames.isEmpty() || intervalNames.size() > 3) throw new UserInputException("Choose daily, weekly or monthly candles.");
        List<TimeInterval> intervals = intervalNames.stream().map(name -> switch (name.strip().toUpperCase(Locale.ROOT)) {
            case "DAILY" -> TimeInterval.DAILY;
            case "WEEKLY" -> TimeInterval.WEEKLY;
            case "MONTHLY" -> TimeInterval.MONTHLY;
            default -> throw new UserInputException("Choose daily, weekly or monthly candles.");
        }).distinct().sorted().toList();
        String triggerMode = node.path("triggerMode").asString("BECOMES_TRUE").strip().toUpperCase(Locale.ROOT);
        if (!TRIGGER_MODES.contains(triggerMode)) throw new UserInputException("Choose when the alert fires.");
        int quiet = whole(node.path("quietCandles"), 0, 0, 60, "Choose a quiet period from 0 to 60 candles.");
        int outcome = whole(node.path("outcomeCandles"), 10, 1, 60, "Choose 1 to 60 candles to follow the price.");
        JsonNode definition = node.path("definition");
        if (!definition.isObject()) throw new UserInputException("Add at least one condition.");
        ObjectNode identified = withIds((ObjectNode) definition.deepCopy());
        // Valid on every interval: a condition's timeframe must not be shorter than any of them.
        ObjectNode normalized = null;
        for (TimeInterval interval : intervals) normalized = CustomAlertDefinition.parse(identified, catalog, interval).toJson();
        return new Draft(cut(node.path("name").asString("").strip(), 80), cut(node.path("description").asString("").strip(), 500),
                intervals.stream().map(Enum::name).toList(), triggerMode, quiet, outcome, normalized);
    }

    /** Conditions and groups need unique ids, which say nothing to the model; missing or repeated ones are made up. */
    private static ObjectNode withIds(ObjectNode definition) {
        if (!definition.has("version")) definition.put("version", CustomAlertDefinition.VERSION);
        Set<String> used = new HashSet<>();
        int groupNumber = 0;
        int conditionNumber = 0;
        for (JsonNode group : definition.path("groups")) {
            if (!(group instanceof ObjectNode groupNode)) continue;
            groupNumber++;
            String groupId = groupNode.path("id").asString("");
            if (!ID.matcher(groupId).matches() || !used.add(groupId)) {
                while (!used.add("g" + groupNumber)) groupNumber++;
                groupNode.put("id", "g" + groupNumber);
            }
            for (JsonNode condition : groupNode.path("conditions")) {
                if (!(condition instanceof ObjectNode conditionNode)) continue;
                conditionNumber++;
                String id = conditionNode.path("id").asString("");
                if (!ID.matcher(id).matches() || !used.add(id)) {
                    while (!used.add("c" + conditionNumber)) conditionNumber++;
                    conditionNode.put("id", "c" + conditionNumber);
                }
            }
        }
        return definition;
    }

    private static int whole(JsonNode node, int fallback, int min, int max, String message) {
        if (node.isMissingNode() || node.isNull()) return fallback;
        if (!node.isNumber() || node.asDouble() != Math.rint(node.asDouble()) || node.asDouble() < min || node.asDouble() > max) {
            throw new UserInputException(message);
        }
        return node.asInt();
    }

    private static String cut(String text, int length) {
        if (text == null) return null;
        return text.length() <= length ? text : text.substring(0, length);
    }

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reply", Map.of("type", "string",
                "description", "Your answer to the user in Markdown: short paragraphs, **bold** labels, numbered steps."));
        properties.put("alertDraft", Map.of("type", "string",
                "description", "Empty, or the complete custom alert as JSON text in the format of the reference."));
        properties.put("link", Map.of("type", "string",
                "description", "Empty, or one app path the user should open, such as /settings/candlestick-patterns."));
        properties.put("needPage", Map.of("type", "string",
                "description", "Empty, or the id of another page from the site map whose details you need to answer."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("reply", "alertDraft", "link", "needPage"));
        return schema;
    }
}
