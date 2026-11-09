package org.example.stockwatch247.assistant;

import org.example.stockwatch247.i18n.AppLanguage;
import org.example.stockwatch247.service.chartindicator.ChartIndicatorCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assistant's knowledge files must keep up with the application: every placeholder resolves in both languages,
 * every signed-in page has a file, every route a file claims is a real page, and the prompts stay small enough to be
 * fast. A new page or a renamed message key fails here instead of making the assistant wrong.
 */
class AssistantKnowledgeTest {
    /** About 16,000 tokens at four characters a token; Polish runs a little longer than English. */
    static final int PROMPT_BUDGET_CHARACTERS = 64_000;
    private static AssistantKnowledge knowledge;

    @BeforeAll
    static void load() throws IOException {
        knowledge = new AssistantKnowledge(new ChartIndicatorCatalog());
    }

    @Test
    void everyFileResolvesInBothLanguages() {
        for (AppLanguage language : AppLanguage.values()) {
            for (String name : AssistantKnowledge.SHARED) {
                assertThat(knowledge.resolved(language, name)).as(language + " " + name).isNotBlank().doesNotContain("{{");
            }
            for (AssistantKnowledge.Page page : knowledge.pages()) {
                assertThat(knowledge.resolved(language, page.id())).as(language + " " + page.id()).isNotBlank().doesNotContain("{{");
                assertThat(knowledge.title(language, page)).isNotBlank();
                assertThat(page.suggestionKeys()).as(page.id() + " suggests questions").isNotEmpty();
            }
        }
        // Labels come from the language's own bundle.
        assertThat(knowledge.resolved(AppLanguage.EN, "custom-alerts/reference.md")).contains("**Add condition**");
        assertThat(knowledge.resolved(AppLanguage.PL, "custom-alerts/reference.md")).doesNotContain("Add condition").contains("**Dodaj warunek**");
    }

    @Test
    void generatedListsCoverTheIndicatorsAndPatterns() {
        String reference = knowledge.resolved(AppLanguage.EN, "custom-alerts/reference.md");
        assertThat(reference).contains("`RSI`", "`MACD`", "`BOLLINGER`", "`RELATIVE_VOLUME`", "`period`", "`histogram`")
                .contains("`BULLISH_ENGULFING`", "`WAVE_II_END`", "`GARTLEY`")
                .contains("at most 12 conditions, 4 groups and 8 different indicators");
    }

    @Test
    void everySignedInPageHasExactlyOneKnowledgeFile() throws IOException {
        Map<String, List<String>> claimed = new HashMap<>();
        knowledge.pages().forEach(page -> page.templates().forEach(template ->
                claimed.computeIfAbsent(template, ignored -> new ArrayList<>()).add(page.id())));
        Set<String> withNavigation = new TreeSet<>();
        try (Stream<Path> templates = Files.list(Path.of("src/main/resources/templates"))) {
            for (Path template : templates.filter(path -> path.toString().endsWith(".html")).toList()) {
                if (Files.readString(template).contains("fragments/navbar :: navbar")) {
                    withNavigation.add(template.getFileName().toString().replace(".html", ""));
                }
            }
        }
        assertThat(withNavigation).isNotEmpty();
        for (String template : withNavigation) {
            assertThat(claimed.get(template)).as("assistant/pages/*.md describing " + template + ".html").hasSize(1);
        }
        assertThat(withNavigation).as("every template a page file names exists").containsAll(claimed.keySet());
    }

    @Test
    void everyRouteIsARealPage() {
        List<String> mappings = getMappings();
        assertThat(mappings).contains("/custom-alerts/new", "/home");
        for (AssistantKnowledge.Page page : knowledge.pages()) {
            for (PathPattern route : page.patterns()) {
                assertThat(mappings.stream().anyMatch(mapping -> route.matches(PathContainer.parsePath(mapping))))
                        .as(page.id() + " route " + route.getPatternString()).isTrue();
            }
        }
    }

    @Test
    void pathsFindTheirPage() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("/home", "home"), Map.entry("/watchlists/7", "watchlists"), Map.entry("/custom-alerts", "custom-alerts"),
                Map.entry("/custom-alerts/new", "custom-alert-builder"), Map.entry("/custom-alerts/12/edit", "custom-alert-builder"),
                Map.entry("/custom-alerts/triggers/5", "custom-alert-trigger"), Map.entry("/stock/AAPL", "stock"),
                Map.entry("/stock/AAPL/technical-outlook", "technical-outlook"),
                Map.entry("/stock/AAPL/candlestick-patterns/1d/1700000000/HAMMER", "signal-detail"),
                Map.entry("/signals", "signals"), Map.entry("/ticker-alerts", "ticker-alerts"),
                Map.entry("/settings/scoring", "settings"), Map.entry("/about", "about"));
        expected.forEach((path, id) -> assertThat(knowledge.pageFor(path).map(AssistantKnowledge.Page::id)).as(path).contains(id));
        assertThat(knowledge.pageFor("/nowhere")).isEmpty();
        assertThat(knowledge.knownPath("/settings/candlestick-patterns")).isTrue();
        assertThat(knowledge.knownPath("//evil.example/home")).isFalse();
        assertThat(knowledge.knownPath("https://evil.example")).isFalse();
    }

    @Test
    void promptsStayWithinTheBudget() {
        for (AppLanguage language : AppLanguage.values()) {
            for (AssistantKnowledge.Page page : knowledge.pages()) {
                String prompt = knowledge.systemPrompt(language, page, null);
                assertThat(prompt.length()).as(language + " " + page.id()).isLessThan(PROMPT_BUDGET_CHARACTERS);
            }
        }
    }

    /** Every GET path a controller serves, with its class-level prefix. */
    private static List<String> getMappings() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> paths = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("org.example.stockwatch247")) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException missing) {
                throw new IllegalStateException(missing);
            }
            RequestMapping prefix = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] bases = prefix == null || prefix.path().length == 0 ? new String[]{""} : prefix.path();
            for (Method method : type.getDeclaredMethods()) {
                GetMapping get = AnnotatedElementUtils.findMergedAnnotation(method, GetMapping.class);
                if (get == null) continue;
                for (String base : bases) for (String path : get.path().length == 0 ? new String[]{""} : get.path()) paths.add(base + path);
            }
        }
        return paths;
    }

    static PathPattern pattern(String route) {
        return PathPatternParser.defaultInstance.parse(route);
    }
}
