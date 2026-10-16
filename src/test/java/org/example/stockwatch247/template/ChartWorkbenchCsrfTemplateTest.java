package org.example.stockwatch247.template;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Chart pages save drawings, alerts and demo trades with the CSRF token read from meta tags. */
class ChartWorkbenchCsrfTemplateTest {
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    /** Scripts that read the token from {@code meta[name="_csrf"]}. */
    private static final List<String> TOKEN_READERS = List.of(
            "chart-drawing-core.js", "chart-alerts.js", "chart-page-tools.js", "chart-indicators.js",
            "stock-workspace.js", "meta[name=\"_csrf\"]");

    @Test
    void chartAssetsRenderTheCsrfMetaTags() {
        var engine = TestTemplateEngines.create();
        var request = new MockHttpServletRequest();
        var application = JakartaServletWebApplication.buildApplication(request.getServletContext());
        var context = new WebContext(application.buildExchange(request, new MockHttpServletResponse()));
        context.setVariable("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-value"));

        String rendered = engine.process(new TemplateSpec(
                "fragments/chart-workbench", Set.of("pageAssets"), TemplateMode.HTML, null), context);

        assertThat(rendered)
                .contains("<meta name=\"_csrf\" content=\"token-value\">")
                .contains("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\">");
    }

    @Test
    void everyPageThatReadsTheTokenRendersIt() throws IOException {
        try (Stream<Path> files = Files.list(TEMPLATES)) {
            List<Path> pages = files.filter(path -> path.toString().endsWith(".html")).toList();
            assertThat(pages).isNotEmpty();
            for (Path page : pages) {
                String html = Files.readString(page, StandardCharsets.UTF_8);
                if (TOKEN_READERS.stream().noneMatch(html::contains)) continue;
                boolean rendersToken = html.contains("<meta name=\"_csrf\"")
                        || html.contains("chart-workbench :: drawingAssets")
                        || html.contains("chart-workbench :: pageAssets")
                        || html.contains("chart-workbench :: csrfMeta");
                assertThat(rendersToken).as("%s reads the CSRF meta tags but does not render them", page.getFileName())
                        .isTrue();
            }
        }
    }
}
