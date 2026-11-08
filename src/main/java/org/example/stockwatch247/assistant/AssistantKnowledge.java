package org.example.stockwatch247.assistant;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.model.enums.ElliottSignalStage;
import org.example.stockwatch247.model.enums.HarmonicPatternType;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputKind;
import org.example.stockwatch247.service.chartindicator.IndicatorDefinition.InputSpec;
import org.example.stockwatch247.service.customalert.CustomAlertDefinition;
import org.example.stockwatch247.service.customalert.CustomAlertIndicators;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the assistant knows: the Markdown files under {@code classpath:assistant/}. Shared files (rules, site map,
 * custom-alert reference, recipes) go into every prompt; each page file describes the pages its {@code routes} match.
 *
 * <p>Files name visible labels by their message key ({@code {{msg:customAlerts.builder.addCondition}}}) instead of
 * copying the words, so a renamed button changes the assistant too, and Polish users read Polish labels. Lists that
 * live in code (alertable indicators, pattern names, rule limits) are generated from it. Every file is resolved for
 * both languages when the application starts, so a missing key fails startup rather than a user's question.</p>
 */
@Component
public class AssistantKnowledge {
    static final String ROOT = "assistant/";
    /** The shared files, in prompt order. Stable content first keeps the prompt prefix cacheable. */
    static final List<String> SHARED = List.of("system.md", "site-map.md", "custom-alerts/reference.md", "custom-alerts/recipes.md");
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\R(.*?)\\R---\\R", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(msg|learn|glossary|catalog|limits)(?::([A-Za-z0-9_.\\-]+))?}}");
    private static final Pattern ARGUMENT = Pattern.compile("\\{\\d+}");
    private static final PathPatternParser ROUTES = PathPatternParser.defaultInstance;

    /** One page file: the pages it describes and what the panel offers there. */
    public record Page(String id, String titleKey, List<String> routes, List<String> templates, List<String> suggestionKeys,
                       String source, List<PathPattern> patterns) {
        boolean matches(String path) {
            PathContainer container = PathContainer.parsePath(path);
            return patterns.stream().anyMatch(pattern -> pattern.matches(container));
        }

        /** The most specific route of this page that matches, for choosing between pages that both match. */
        Optional<PathPattern> match(String path) {
            PathContainer container = PathContainer.parsePath(path);
            return patterns.stream().filter(pattern -> pattern.matches(container)).min(PathPattern.SPECIFICITY_COMPARATOR);
        }
    }

    private final ChartIndicatorCatalog catalog;
    private final Map<String, String> shared = new LinkedHashMap<>();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<AppLanguage, Map<String, String>> resolved = new EnumMap<>(AppLanguage.class);
    private final Map<String, String> prompts = new ConcurrentHashMap<>();

    public AssistantKnowledge(ChartIndicatorCatalog catalog) throws IOException {
        this.catalog = catalog;
        var resolver = new PathMatchingResourcePatternResolver(getClass().getClassLoader());
        for (String name : SHARED) {
            Resource resource = resolver.getResource("classpath:" + ROOT + name);
            if (!resource.exists()) throw new IllegalStateException("Missing assistant knowledge file " + name);
            shared.put(name, resource.getContentAsString(StandardCharsets.UTF_8));
        }
        Resource[] files = resolver.getResources("classpath*:" + ROOT + "pages/*.md");
        Arrays.sort(files, Comparator.comparing(Resource::getFilename));
        for (Resource file : files) {
            Page page = page(file.getFilename(), file.getContentAsString(StandardCharsets.UTF_8));
            if (pages.put(page.id(), page) != null) throw new IllegalStateException("Two assistant pages are called " + page.id());
        }
        for (AppLanguage language : AppLanguage.values()) {
            Map<String, String> texts = new LinkedHashMap<>();
            shared.forEach((name, text) -> texts.put(name, resolve(language, name, text)));
            pages.values().forEach(page -> texts.put(page.id(), resolve(language, page.id(), page.source())));
            resolved.put(language, texts);
        }
    }

    public List<Page> pages() {
        return List.copyOf(pages.values());
    }

    public Optional<Page> page(String id) {
        return Optional.ofNullable(id == null ? null : pages.get(id));
    }

    /** The page file describing an app path; the most specific route wins when two files match. */
    public Optional<Page> pageFor(String path) {
        if (path == null || !path.startsWith("/") || path.length() > 300) return Optional.empty();
        String clean = path.split("[?#]", 2)[0];
        Page best = null;
        PathPattern bestPattern = null;
        for (Page page : pages.values()) {
            PathPattern pattern = page.match(clean).orElse(null);
            if (pattern != null && (bestPattern == null || PathPattern.SPECIFICITY_COMPARATOR.compare(pattern, bestPattern) < 0)) {
                best = page;
                bestPattern = pattern;
            }
        }
        return Optional.ofNullable(best);
    }

    /** True when the path is one of the app's pages, so the assistant may offer it as a link. */
    public boolean knownPath(String path) {
        return path != null && path.startsWith("/") && !path.startsWith("//") && path.length() <= 200
                && path.matches("[A-Za-z0-9/_.\\-]+") && pageFor(path).isPresent();
    }

    public String title(AppLanguage language, Page page) {
        return message(language, page.titleKey());
    }

    public List<String> suggestions(AppLanguage language, String path) {
        return pageFor(path).map(page -> page.suggestionKeys().stream().map(key -> message(language, key)).toList()).orElse(List.of());
    }

    /**
     * The system instruction for one page: the shared files, then the page's own file and any page fetched for a
     * cross-page question. Built once per combination.
     */
    public String systemPrompt(AppLanguage language, Page page, Page extra) {
        String key = language + "|" + (page == null ? "" : page.id()) + "|" + (extra == null ? "" : extra.id());
        return prompts.computeIfAbsent(key, ignored -> {
            Map<String, String> texts = resolved.get(language);
            StringBuilder prompt = new StringBuilder();
            SHARED.forEach(name -> prompt.append(texts.get(name).strip()).append("\n\n"));
            if (page != null) prompt.append("# The page the user is on: ").append(title(language, page)).append(" (")
                    .append(page.id()).append(")\n\n").append(texts.get(page.id()).strip()).append("\n\n");
            if (extra != null && extra != page) prompt.append("# Another page you asked for: ").append(title(language, extra)).append(" (")
                    .append(extra.id()).append(")\n\n").append(texts.get(extra.id()).strip()).append("\n\n");
            return prompt.toString().strip();
        });
    }

    /** A shared or page file after its placeholders are filled, for tests and the evaluation. */
    String resolved(AppLanguage language, String name) {
        return resolved.get(language).get(name);
    }

    // ------------------------------------------------------------------ files

    private static Page page(String fileName, String text) {
        Matcher matcher = FRONT_MATTER.matcher(text);
        if (!matcher.find()) throw new IllegalStateException("Assistant page " + fileName + " has no front matter");
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : matcher.group(1).split("\\R")) {
            if (line.isBlank()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) throw new IllegalStateException("Bad front matter line in " + fileName + ": " + line);
            fields.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        String id = required(fields, "id", fileName);
        if (!(id + ".md").equals(fileName)) throw new IllegalStateException("Assistant page " + fileName + " must have id " + fileName.replace(".md", ""));
        List<String> routes = list(fields.get("routes"));
        if (routes.isEmpty()) throw new IllegalStateException("Assistant page " + fileName + " has no routes");
        return new Page(id, required(fields, "title", fileName), routes, list(fields.get("templates")), list(fields.get("suggestions")),
                text.substring(matcher.end()), routes.stream().map(ROUTES::parse).toList());
    }

    private static String required(Map<String, String> fields, String key, String fileName) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Assistant page " + fileName + " needs " + key);
        return value;
    }

    private static List<String> list(String value) {
        if (value == null || value.isBlank()) return List.of();
        String inner = value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
        return Arrays.stream(inner.split(",")).map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    // ------------------------------------------------------------------ placeholders

    private String resolve(AppLanguage language, String file, String text) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String kind = matcher.group(1);
            String argument = matcher.group(2);
            String value = switch (kind) {
                case "msg" -> label(language, file, argument);
                case "learn" -> label(language, file, "js.learn." + argument);
                case "glossary" -> "**" + label(language, file, "js.glossary." + argument + ".term") + "**: "
                        + label(language, file, "js.glossary." + argument + ".text");
                case "catalog" -> switch (argument == null ? "" : argument) {
                    case "indicators" -> indicators(language);
                    case "patterns" -> patterns(language);
                    default -> throw new IllegalStateException("Unknown catalog list in assistant file " + file + ": " + argument);
                };
                default -> limits();
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        if (out.indexOf("{{") >= 0) throw new IllegalStateException("Unresolved placeholder in assistant file " + file);
        return out.toString();
    }

    private String label(AppLanguage language, String file, String key) {
        if (key == null) throw new IllegalStateException("A placeholder in assistant file " + file + " has no key");
        try {
            return message(language, key);
        } catch (MissingResourceException missing) {
            throw new IllegalStateException("Unknown message key in assistant file " + file + ": " + key, missing);
        }
    }

    /** The interface text for a key as people see it: arguments as "…", formatted apostrophes undoubled. */
    static String message(AppLanguage language, String key) {
        ResourceBundle bundle = ResourceBundle.getBundle("messages", language.locale(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        String text = bundle.getString(key);
        if (ARGUMENT.matcher(text).find()) text = ARGUMENT.matcher(text.replace("''", "'")).replaceAll("…");
        return text.strip();
    }

    private static String optional(AppLanguage language, String key) {
        try {
            return message(language, key);
        } catch (MissingResourceException missing) {
            return null;
        }
    }

    /** Every indicator a custom alert can read, with the JSON names the rule uses and the labels people see. */
    private String indicators(AppLanguage language) {
        List<IndicatorDefinition> alertable = new ArrayList<>(catalog.all().stream().filter(CustomAlertIndicators::alertable).toList());
        alertable.sort(Comparator.comparing((IndicatorDefinition definition) -> definition.category().ordinal())
                .thenComparing(IndicatorDefinition::type));
        StringBuilder out = new StringBuilder();
        IndicatorDefinition.Category category = null;
        for (IndicatorDefinition definition : alertable) {
            if (definition.category() != category) {
                category = definition.category();
                String name = optional(language, "js.ind.category." + category.name());
                out.append("\n### ").append(name == null ? category.name() : name).append('\n');
            }
            String type = definition.type();
            String name = Optional.ofNullable(optional(language, "js.ind." + type + ".name")).orElse(definition.shortName());
            out.append("- `").append(type).append("` ").append(name);
            if (!name.contains(definition.shortName())) out.append(" (").append(definition.shortName()).append(')');
            List<String> inputs = new ArrayList<>();
            for (InputSpec input : definition.inputs()) {
                if ("offset".equals(input.key())) continue;
                inputs.add(input(language, type, input));
            }
            if (!inputs.isEmpty()) out.append(": ").append(String.join("; ", inputs));
            String plainValue = optional(language, "js.ind.output.value");
            List<String> outputs = CustomAlertIndicators.outputs(definition).stream().map(key -> {
                String label = Optional.ofNullable(optional(language, "js.ind." + type + ".output." + key))
                        .or(() -> Optional.ofNullable(optional(language, "js.ind.output." + key))).orElse(key);
                return label.equalsIgnoreCase(key) || label.equals(plainValue) ? "`" + key + "`" : "`" + key + "` " + label;
            }).toList();
            out.append(" → ").append(String.join(", ", outputs));
            if (definition.zones() != null) out.append(" (usual levels: settings `").append(definition.zones().lowerInput())
                    .append("` and `").append(definition.zones().upperInput()).append("`)");
            out.append('\n');
        }
        return out.toString().strip();
    }

    /**
     * One setting as {@code `key` Label = default}. What every indicator shares is said once above the list: a source is
     * a price, a timeframe is chart/1wk/1mo, and lengths run 1 or 2 to 500, so only other ranges are written out.
     */
    private static String input(AppLanguage language, String type, InputSpec input) {
        String label = Optional.ofNullable(optional(language, "js.ind." + type + ".input." + input.key()))
                .or(() -> Optional.ofNullable(optional(language, "js.ind.input." + input.key()))).orElse(input.key());
        StringBuilder text = new StringBuilder("`").append(input.key()).append('`');
        if (input.kind() == InputKind.TIMEFRAME) return text.toString();
        // `period` is the "Length" field of nearly every indicator, which the header says once.
        if (!label.equalsIgnoreCase(input.key()) && !"period".equals(input.key())) text.append(" ").append(label);
        if (input.defaultValue() != null) text.append(" = ").append(number(input.defaultValue()));
        if (input.kind() == InputKind.SOURCE) return text.toString();
        boolean usualLength = input.kind() == InputKind.INTEGER && input.min() != null && input.min() <= 2 && input.max() != null && input.max() == 500;
        if (input.min() != null && input.max() != null && !usualLength) {
            text.append(" [").append(number(input.min())).append("–").append(number(input.max())).append(']');
        }
        if (input.options() != null && !input.options().isEmpty()) text.append(" {").append(String.join(", ", input.options())).append('}');
        return text.toString();
    }

    private static String number(Object value) {
        if (value instanceof Double number && number == Math.rint(number) && Math.abs(number) < 1e9) return Long.toString(number.longValue());
        return String.valueOf(value);
    }

    /** The pattern names a pattern condition may list, with their labels. */
    private static String patterns(AppLanguage language) {
        StringBuilder out = new StringBuilder();
        for (String family : List.of("CANDLESTICK", "ELLIOTT_WAVE", "HARMONIC_FORMATION")) {
            out.append("\n### `").append(family).append("` ").append(message(language, "js.ca.kind." + family)).append('\n');
            List<String> names = switch (family) {
                case "CANDLESTICK" -> CustomAlertDefinition.patternNames(family).stream().sorted().toList();
                case "ELLIOTT_WAVE" -> Arrays.stream(ElliottSignalStage.values()).map(Enum::name).toList();
                default -> Arrays.stream(HarmonicPatternType.values()).map(Enum::name).toList();
            };
            List<String> items = new ArrayList<>();
            for (String name : names) {
                String key = switch (family) {
                    case "ELLIOTT_WAVE" -> "CORRECTION_END".equals(name) ? "js.ca.elliott.CORRECTION_END" : "js.glossary.elliott." + name + ".term";
                    case "HARMONIC_FORMATION" -> "js.glossary.pattern.HARMONIC_" + name + ".term";
                    default -> "js.glossary.pattern." + name + ".term";
                };
                String label = optional(language, key);
                items.add(label == null ? "`" + name + "`" : "`" + name + "` " + label);
            }
            out.append(String.join(" · ", items)).append('\n');
        }
        return out.toString().strip();
    }

    private static String limits() {
        return String.format(Locale.ROOT, "at most %d conditions, %d groups and %d different indicators in one alert; "
                        + "a sequence (\"in order\" group) has 2 to %d steps and fits in at most %d candles; "
                        + "\"in the last N candles\" windows and step timings go up to %d candles",
                CustomAlertDefinition.MAX_CONDITIONS, CustomAlertDefinition.MAX_GROUPS, CustomAlertDefinition.MAX_INDICATORS,
                CustomAlertDefinition.MAX_STEPS, CustomAlertDefinition.MAX_SPAN_CANDLES, CustomAlertDefinition.MAX_WINDOW_CANDLES);
    }
}
