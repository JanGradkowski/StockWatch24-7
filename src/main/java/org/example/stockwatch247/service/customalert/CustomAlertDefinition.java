package org.example.stockwatch247.service.customalert;

import org.example.stockwatch247.model.enums.CandlePattern;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.UserInputException;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputKind;
import org.example.stockwatch247.service.chartindicator.PriceSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A custom alert's rule: groups of conditions joined with all/any, the groups joined the same way.
 *
 * <p>Stored as versioned JSON. {@link #parse} validates every field against the indicator catalog and the
 * pattern lists, so a stored rule can always be evaluated; {@link #toJson} writes the normalized form.</p>
 */
public record CustomAlertDefinition(String match, List<Group> groups) {
    public static final int VERSION = 1;
    public static final int MAX_GROUPS = 4;
    public static final int MAX_CONDITIONS = 12;
    public static final int MAX_INDICATORS = 8;
    public static final int MAX_WINDOW_CANDLES = 60;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Set<String> MATCHES = Set.of("ALL", "ANY");
    private static final Set<String> PRICE_FIELDS = Set.of("CLOSE", "OPEN", "HIGH", "LOW", "VOLUME");
    /** Operators that compare the subject with another value at the same candle. */
    static final Set<String> COMPARISONS = Set.of("ABOVE", "BELOW", "CROSSES_ABOVE", "CROSSES_BELOW");
    static final Set<String> OPERATORS = Set.of("ABOVE", "BELOW", "CROSSES_ABOVE", "CROSSES_BELOW", "BETWEEN", "OUTSIDE",
            "RISING", "FALLING", "CHANGE_ABOVE", "CHANGE_BELOW", "NEW_HIGH", "NEW_LOW");
    static final List<String> FAMILIES = List.of("CANDLESTICK", "ELLIOTT_WAVE", "HARMONIC_FORMATION");
    private static final Set<String> DIRECTIONS = Set.of("BULLISH", "BEARISH", "ANY");
    private static final Set<String> STRENGTHS = Set.of("SLIGHT", "MODERATE", "STRONG");
    private static final Set<String> TRANSACTIONS = Set.of("PURCHASE", "SALE");
    static final Set<String> ROLES = Set.of("OFFICER", "DIRECTOR", "OWNER");
    static final Set<String> CHAMBERS = Set.of("HOUSE", "SENATE");

    public CustomAlertDefinition {
        groups = List.copyOf(groups);
    }

    public record Group(String id, String match, List<Condition> conditions) {
        public Group {
            conditions = List.copyOf(conditions);
        }
    }

    /** One test; {@code negate} turns "is" into "is not" and "had" into "had no". */
    public sealed interface Condition permits ValueCondition, PatternCondition, OutlookCondition, ActivityCondition {
        String id();

        boolean negate();

        /** VALUE, PATTERN, OUTLOOK, INSIDER or CONGRESS. */
        String kind();
    }

    /**
     * What a value condition measures or compares with: a fixed number, a price field, or one output of a
     * catalog indicator with its settings.
     */
    public record Operand(String kind, Double value, String field, String type, Map<String, Object> parameters, String output) {
        public static Operand number(double value) { return new Operand("VALUE", value, null, null, null, null); }

        public static Operand price(String field) { return new Operand("PRICE", null, field, null, null, null); }

        public static Operand indicator(String type, Map<String, Object> parameters, String output) {
            return new Operand("INDICATOR", null, null, type, parameters, output);
        }

        public boolean isIndicator() { return "INDICATOR".equals(kind); }

        /** Identity of the series this operand reads, shared by equal indicator settings across alerts. */
        public String seriesKey() {
            return switch (kind) {
                case "PRICE" -> "price:" + field;
                case "INDICATOR" -> "indicator:" + type + ":" + new java.util.TreeMap<>(parameters) + ":" + output;
                default -> "value:" + value;
            };
        }
    }

    /**
     * LATEST: on the evaluated candle. ANY: at least once in the last {@code candles}. EVERY: on each of the
     * last {@code candles}. DAYS: filings in the last {@code days} calendar days.
     */
    public record Window(String mode, int candles, int days) {
        public static Window latest() { return new Window("LATEST", 1, 0); }

        public int span() { return "LATEST".equals(mode) ? 1 : Math.max(1, candles); }
    }

    public record ValueCondition(String id, boolean negate, Operand subject, String operator, Operand compare,
                                 Double lower, Double upper, Integer candles, Double percent, Window window)
            implements Condition {
        public String kind() { return "VALUE"; }
    }

    /**
     * A pattern detected (became known) within the window. {@code patterns} are CandlePattern names for
     * candlesticks, ElliottSignalStage names for Elliott waves and HarmonicPatternType names for harmonic
     * formations; empty means any.
     */
    public record PatternCondition(String id, boolean negate, String family, String direction, List<String> patterns,
                                   Integer minScore, boolean confirmedOnly, boolean stillValid, Window window)
            implements Condition {
        public PatternCondition {
            patterns = List.copyOf(patterns);
        }

        public String kind() { return "PATTERN"; }
    }

    /** The automated technical outlook is at least {@code strength} in {@code direction} on the latest candle. */
    public record OutlookCondition(String id, boolean negate, String direction, String strength) implements Condition {
        public String kind() { return "OUTLOOK"; }
    }

    /**
     * Insider or congressional purchases or sales made public in the last {@code days} days: at least
     * {@code minCount} different people and at least {@code minTotal} dollars (insiders: total filed value;
     * Congress: the lower bound of each reported range).
     */
    public record ActivityCondition(String id, boolean negate, String source, String transaction, int days,
                                    Double minTotal, int minCount, List<String> roles, List<String> chambers)
            implements Condition {
        public ActivityCondition {
            roles = List.copyOf(roles);
            chambers = List.copyOf(chambers);
        }

        public String kind() { return source; }
    }

    // ------------------------------------------------------------------ derived

    public List<Condition> conditions() {
        return groups.stream().flatMap(group -> group.conditions().stream()).toList();
    }

    public boolean usesActivity() {
        return conditions().stream().anyMatch(condition -> condition instanceof ActivityCondition);
    }

    /** Alerts made only of filings can be checked whenever filings arrive, without waiting for a candle. */
    public boolean activityOnly() {
        return conditions().stream().allMatch(condition -> condition instanceof ActivityCondition);
    }

    public Set<String> activitySources() {
        Set<String> sources = new HashSet<>();
        conditions().forEach(condition -> {
            if (condition instanceof ActivityCondition activity) sources.add(activity.source());
        });
        return sources;
    }

    /** Every indicator and price operand the rule reads, each once. */
    public List<Operand> operands() {
        Map<String, Operand> found = new LinkedHashMap<>();
        for (Condition condition : conditions()) {
            if (condition instanceof ValueCondition value) {
                found.putIfAbsent(value.subject().seriesKey(), value.subject());
                if (value.compare() != null && !"VALUE".equals(value.compare().kind())) {
                    found.putIfAbsent(value.compare().seriesKey(), value.compare());
                }
            }
        }
        return List.copyOf(found.values());
    }

    /** Completed candles the evaluation looks back over, beyond the evaluated one. */
    public int lookbackCandles() {
        int lookback = 1;
        for (Condition condition : conditions()) {
            int span = switch (condition) {
                case ValueCondition value -> value.window().span() + Math.max(1, value.candles() == null ? 1 : value.candles());
                case PatternCondition pattern -> pattern.window().span();
                default -> 1;
            };
            lookback = Math.max(lookback, span);
        }
        return lookback;
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Reads and validates a rule. Indicator settings are checked against the catalog for the alert's
     * interval (a weekly alert cannot read a daily timeframe).
     */
    public static CustomAlertDefinition parse(JsonNode node, ChartIndicatorCatalog catalog, TimeInterval interval) {
        if (node == null || !node.isObject()) throw new UserInputException("Add at least one condition.");
        int version = node.path("version").asInt(VERSION);
        if (version != VERSION) throw new UserInputException("This alert was saved by a newer version of StockWatch.");
        String match = upper(node.path("match").asString("ALL"));
        if (!MATCHES.contains(match)) throw new UserInputException("Choose whether all or any of the groups must be true.");
        JsonNode groupNodes = node.path("groups");
        if (!groupNodes.isArray() || groupNodes.isEmpty()) throw new UserInputException("Add at least one condition.");
        if (groupNodes.size() > MAX_GROUPS) throw new UserInputException("An alert can have at most 4 groups.");
        String chartInterval = switch (interval) {
            case WEEKLY -> "1wk";
            case MONTHLY -> "1mo";
            default -> "1d";
        };
        Set<String> ids = new HashSet<>();
        List<Group> groups = new ArrayList<>();
        int total = 0;
        for (JsonNode groupNode : groupNodes) {
            String groupMatch = upper(groupNode.path("match").asString("ALL"));
            if (!MATCHES.contains(groupMatch)) throw new UserInputException("Choose whether all or any of the groups must be true.");
            JsonNode conditionNodes = groupNode.path("conditions");
            if (!conditionNodes.isArray() || conditionNodes.isEmpty()) throw new UserInputException("Every group needs at least one condition.");
            List<Condition> conditions = new ArrayList<>();
            for (JsonNode conditionNode : conditionNodes) {
                if (++total > MAX_CONDITIONS) throw new UserInputException("An alert can have at most 12 conditions.");
                Condition condition = condition(conditionNode, catalog, chartInterval);
                if (!ids.add(condition.id())) throw new UserInputException("The alert definition is incomplete.");
                conditions.add(condition);
            }
            String groupId = id(groupNode.path("id").asString("g" + (groups.size() + 1)));
            if (!ids.add(groupId)) throw new UserInputException("The alert definition is incomplete.");
            groups.add(new Group(groupId, groupMatch, conditions));
        }
        CustomAlertDefinition definition = new CustomAlertDefinition(match, groups);
        long indicators = definition.operands().stream().filter(Operand::isIndicator).count();
        if (indicators > MAX_INDICATORS) throw new UserInputException("An alert can use at most 8 different indicators.");
        return definition;
    }

    private static Condition condition(JsonNode node, ChartIndicatorCatalog catalog, String chartInterval) {
        if (!node.isObject()) throw new UserInputException("The alert definition is incomplete.");
        String id = id(node.path("id").asString(""));
        boolean negate = node.path("negate").asBoolean(false);
        String kind = upper(node.path("kind").asString(""));
        return switch (kind) {
            case "VALUE" -> valueCondition(node, id, negate, catalog, chartInterval);
            case "PATTERN" -> patternCondition(node, id, negate);
            case "OUTLOOK" -> {
                String direction = upper(node.path("direction").asString("BUY"));
                String strength = upper(node.path("strength").asString("MODERATE"));
                if (!Set.of("BUY", "SELL").contains(direction) || !STRENGTHS.contains(strength)) {
                    throw new UserInputException("Choose the outlook direction and strength.");
                }
                yield new OutlookCondition(id, negate, direction, strength);
            }
            case "INSIDER", "CONGRESS" -> activityCondition(node, id, negate, kind);
            default -> throw new UserInputException("Choose what the condition checks.");
        };
    }

    private static ValueCondition valueCondition(JsonNode node, String id, boolean negate, ChartIndicatorCatalog catalog,
                                                 String chartInterval) {
        Operand subject = operand(node.path("subject"), catalog, chartInterval);
        if ("VALUE".equals(subject.kind())) throw new UserInputException("Choose an indicator or price to watch.");
        String operator = upper(node.path("operator").asString(""));
        if (!OPERATORS.contains(operator)) throw new UserInputException("Choose how the value is compared.");
        Operand compare = null;
        Double lower = null;
        Double upper = null;
        Integer candles = null;
        Double percent = null;
        switch (operator) {
            case "ABOVE", "BELOW", "CROSSES_ABOVE", "CROSSES_BELOW" -> {
                compare = operand(node.path("compare"), catalog, chartInterval);
                if (compare.seriesKey().equals(subject.seriesKey())) throw new UserInputException("Compare the value with something else.");
            }
            case "BETWEEN", "OUTSIDE" -> {
                lower = number(node.path("lower"), "Enter both levels of the range.");
                upper = number(node.path("upper"), "Enter both levels of the range.");
                if (lower >= upper) throw new UserInputException("The lower level must be below the upper one.");
            }
            case "RISING", "FALLING" -> candles = integer(node.path("candles"), 1, 20, "Choose 1 to 20 candles.");
            case "CHANGE_ABOVE", "CHANGE_BELOW" -> {
                candles = integer(node.path("candles"), 1, 250, "Choose 1 to 250 candles.");
                percent = number(node.path("percent"), "Enter the percentage change.");
                if (percent < 0 || percent > 1000) throw new UserInputException("Enter a percentage from 0 to 1000.");
            }
            case "NEW_HIGH", "NEW_LOW" -> candles = integer(node.path("candles"), 2, 250, "Choose 2 to 250 candles.");
            default -> { }
        }
        return new ValueCondition(id, negate, subject, operator, compare, lower, upper, candles, percent,
                window(node.path("window"), false));
    }

    private static Operand operand(JsonNode node, ChartIndicatorCatalog catalog, String chartInterval) {
        if (!node.isObject()) throw new UserInputException("The alert definition is incomplete.");
        String kind = upper(node.path("kind").asString(""));
        return switch (kind) {
            case "VALUE" -> Operand.number(number(node.path("value"), "Enter the value to compare with."));
            case "PRICE" -> {
                String field = upper(node.path("field").asString("CLOSE"));
                if (!PRICE_FIELDS.contains(field)) throw new UserInputException("Choose the close, open, high, low or volume.");
                yield Operand.price(field);
            }
            case "INDICATOR" -> indicator(node, catalog, chartInterval);
            default -> throw new UserInputException("Choose what the value is compared with.");
        };
    }

    private static Operand indicator(JsonNode node, ChartIndicatorCatalog catalog, String chartInterval) {
        String type = upper(node.path("type").asString(""));
        IndicatorDefinition definition = catalog.find(type)
                .filter(CustomAlertIndicators::alertable)
                .orElseThrow(() -> new UserInputException("This indicator cannot be used in a custom alert."));
        Map<String, Object> parameters = new LinkedHashMap<>();
        node.path("parameters").properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            parameters.put(entry.getKey(), value.isNumber() ? (Object) value.numberValue()
                    : value.isBoolean() ? (Object) value.asBoolean() : value.asString());
        });
        if (parameters.size() > 16) throw new UserInputException("The alert definition is incomplete.");
        // Validates every input and fills in defaults, with the alert's interval as the chart interval.
        var values = catalog.resolve(definition, parameters, Set.of(), "custom", chartInterval);
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var input : definition.inputs()) {
            Object value = values.asMap().get(input.key());
            if (input.kind() == InputKind.SOURCE && !PriceSource.isPrice(String.valueOf(value))) {
                throw new UserInputException("A custom alert reads indicators from the price, not from other indicators.");
            }
            // A plot moved earlier would show later prices on an earlier candle.
            if ("offset".equals(input.key()) && value instanceof Number shift && shift.intValue() != 0) {
                throw new UserInputException("Set the indicator offset to 0 for an alert.");
            }
            resolved.put(input.key(), value instanceof Number number && input.kind() == InputKind.INTEGER
                    ? (Object) number.intValue() : value);
        }
        String output = node.path("output").asString("");
        List<String> outputs = CustomAlertIndicators.outputs(definition);
        if (output.isBlank()) output = outputs.getFirst();
        if (!outputs.contains(output)) throw new UserInputException("Choose the indicator line the alert watches.");
        return Operand.indicator(type, resolved, output);
    }

    private static PatternCondition patternCondition(JsonNode node, String id, boolean negate) {
        String family = upper(node.path("family").asString(""));
        if (!FAMILIES.contains(family)) throw new UserInputException("Choose the pattern family.");
        String direction = upper(node.path("direction").asString("ANY"));
        if (!DIRECTIONS.contains(direction)) throw new UserInputException("Choose bullish, bearish or either.");
        Set<String> allowed = patternNames(family);
        List<String> patterns = new ArrayList<>();
        for (JsonNode item : node.path("patterns")) {
            String name = upper(item.asString(""));
            if (!allowed.contains(name)) throw new UserInputException("Choose patterns from the list.");
            if (!patterns.contains(name)) patterns.add(name);
        }
        Integer minScore = null;
        if (node.hasNonNull("minScore")) minScore = integer(node.path("minScore"), 0, 100, "Enter a minimum score from 0 to 100.");
        boolean confirmedOnly = "CANDLESTICK".equals(family) && node.path("confirmedOnly").asBoolean(false);
        boolean stillValid = "HARMONIC_FORMATION".equals(family) && node.path("stillValid").asBoolean(false);
        Window window = window(node.path("window"), true);
        return new PatternCondition(id, negate, family, direction, patterns, minScore, confirmedOnly, stillValid, window);
    }

    /** The names a pattern condition may list for each family. */
    public static Set<String> patternNames(String family) {
        return switch (family) {
            case "CANDLESTICK" -> Set.copyOf(Arrays.stream(CandlePattern.values())
                    .filter(pattern -> pattern != CandlePattern.ANY && !pattern.name().startsWith("ELLIOTT_")
                            && !pattern.name().startsWith("HARMONIC_"))
                    .map(Enum::name).toList());
            case "ELLIOTT_WAVE" -> Set.copyOf(Arrays.stream(ElliottSignalStage.values()).map(Enum::name).toList());
            case "HARMONIC_FORMATION" -> Set.copyOf(Arrays.stream(HarmonicPatternType.values()).map(Enum::name).toList());
            default -> Set.of();
        };
    }

    private static ActivityCondition activityCondition(JsonNode node, String id, boolean negate, String source) {
        String transaction = upper(node.path("transaction").asString("PURCHASE"));
        if (!TRANSACTIONS.contains(transaction)) throw new UserInputException("Choose purchases or sales.");
        boolean insider = "INSIDER".equals(source);
        int days = integer(node.path("days"), 1, insider ? 730 : 365,
                insider ? "Choose 1 to 730 days." : "Choose 1 to 365 days.");
        Double minTotal = null;
        if (node.hasNonNull("minTotal")) {
            minTotal = number(node.path("minTotal"), "Enter the minimum amount in dollars.");
            if (minTotal < 0 || minTotal > 1e12) throw new UserInputException("Enter the minimum amount in dollars.");
        }
        int minCount = node.hasNonNull("minCount") ? integer(node.path("minCount"), 1, 20, "Choose 1 to 20 people.") : 1;
        List<String> roles = new ArrayList<>();
        List<String> chambers = new ArrayList<>();
        for (JsonNode item : node.path(insider ? "roles" : "chambers")) {
            String value = upper(item.asString(""));
            if (!(insider ? ROLES : CHAMBERS).contains(value)) throw new UserInputException("The alert definition is incomplete.");
            List<String> target = insider ? roles : chambers;
            if (!target.contains(value)) target.add(value);
        }
        return new ActivityCondition(id, negate, source, transaction, days, minTotal, minCount, roles, chambers);
    }

    private static Window window(JsonNode node, boolean pattern) {
        if (!node.isObject()) return pattern ? new Window("ANY", 5, 0) : Window.latest();
        String mode = upper(node.path("mode").asString("LATEST"));
        return switch (mode) {
            case "LATEST" -> pattern ? new Window("ANY", 1, 0) : Window.latest();
            case "ANY" -> new Window("ANY", integer(node.path("candles"), 1, MAX_WINDOW_CANDLES, "Choose 1 to 60 candles."), 0);
            case "EVERY" -> {
                if (pattern) throw new UserInputException("The alert definition is incomplete.");
                yield new Window("EVERY", integer(node.path("candles"), 1, MAX_WINDOW_CANDLES, "Choose 1 to 60 candles."), 0);
            }
            default -> throw new UserInputException("The alert definition is incomplete.");
        };
    }

    private static String id(String raw) {
        if (raw == null || !ID.matcher(raw).matches()) throw new UserInputException("The alert definition is incomplete.");
        return raw;
    }

    private static double number(JsonNode node, String message) {
        if (!node.isNumber() || !Double.isFinite(node.asDouble())) throw new UserInputException(message);
        return node.asDouble();
    }

    private static int integer(JsonNode node, int min, int max, String message) {
        if (!node.isNumber() || node.asDouble() != Math.rint(node.asDouble()) || node.asDouble() < min || node.asDouble() > max) {
            throw new UserInputException(message);
        }
        return node.asInt();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ writing

    public ObjectNode toJson() {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode root = nodes.objectNode();
        root.put("version", VERSION);
        root.put("match", match);
        ArrayNode groupArray = root.putArray("groups");
        for (Group group : groups) {
            ObjectNode groupNode = groupArray.addObject();
            groupNode.put("id", group.id());
            groupNode.put("match", group.match());
            ArrayNode conditionArray = groupNode.putArray("conditions");
            for (Condition condition : group.conditions()) conditionArray.add(json(condition));
        }
        return root;
    }

    private static ObjectNode json(Condition condition) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", condition.id());
        node.put("kind", condition instanceof ActivityCondition activity ? activity.source() : condition.kind());
        node.put("negate", condition.negate());
        switch (condition) {
            case ValueCondition value -> {
                node.set("subject", json(value.subject()));
                node.put("operator", value.operator());
                if (value.compare() != null) node.set("compare", json(value.compare()));
                if (value.lower() != null) node.put("lower", value.lower());
                if (value.upper() != null) node.put("upper", value.upper());
                if (value.candles() != null) node.put("candles", value.candles());
                if (value.percent() != null) node.put("percent", value.percent());
                node.set("window", json(value.window()));
            }
            case PatternCondition pattern -> {
                node.put("family", pattern.family());
                node.put("direction", pattern.direction());
                ArrayNode names = node.putArray("patterns");
                pattern.patterns().forEach(names::add);
                if (pattern.minScore() != null) node.put("minScore", pattern.minScore());
                if (pattern.confirmedOnly()) node.put("confirmedOnly", true);
                if (pattern.stillValid()) node.put("stillValid", true);
                node.set("window", json(pattern.window()));
            }
            case OutlookCondition outlook -> {
                node.put("direction", outlook.direction());
                node.put("strength", outlook.strength());
            }
            case ActivityCondition activity -> {
                node.put("transaction", activity.transaction());
                node.put("days", activity.days());
                if (activity.minTotal() != null) node.put("minTotal", activity.minTotal());
                node.put("minCount", activity.minCount());
                ArrayNode list = node.putArray("INSIDER".equals(activity.source()) ? "roles" : "chambers");
                ("INSIDER".equals(activity.source()) ? activity.roles() : activity.chambers()).forEach(list::add);
            }
        }
        return node;
    }

    private static ObjectNode json(Operand operand) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("kind", operand.kind());
        switch (operand.kind()) {
            case "VALUE" -> node.put("value", operand.value());
            case "PRICE" -> node.put("field", operand.field());
            default -> {
                node.put("type", operand.type());
                ObjectNode parameters = node.putObject("parameters");
                operand.parameters().forEach((key, value) -> {
                    switch (value) {
                        case Integer number -> parameters.put(key, number);
                        case Number number -> parameters.put(key, number.doubleValue());
                        case Boolean flag -> parameters.put(key, flag);
                        default -> parameters.put(key, String.valueOf(value));
                    }
                });
                node.put("output", operand.output());
            }
        }
        return node;
    }

    private static ObjectNode json(Window window) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("mode", window.mode());
        if (!"LATEST".equals(window.mode())) node.put("candles", window.candles());
        return node;
    }
}
