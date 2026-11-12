package org.example.stockwatch247.template;

import org.example.stockwatch247.controller.AuthController.ActivitySignalArchivePage;
import org.example.stockwatch247.service.AlertRuleService.SignalArchivePage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The column titles of the signal archives link to the addresses the controller builds and say how the list is sorted. */
class ArchiveSortTemplateTest {
    private String render(boolean activity, String sort, String direction, Map<String, String> links) {
        var engine = TestTemplateEngines.create();
        var request = new MockHttpServletRequest();
        var app = JakartaServletWebApplication.buildApplication(request.getServletContext());
        var context = new WebContext(app.buildExchange(request, new MockHttpServletResponse()));
        context.setVariable("archive", activity
                ? new ActivitySignalArchivePage(List.of(), 3, 5, 250, sort, direction, true, true)
                : new SignalArchivePage(List.of(), 3, 5, 250, sort, direction, true, true));
        context.setVariable("archiveSortUrls", links);
        return engine.process(new TemplateSpec(activity ? "all-activity-signals" : "all-signals",
                Set.of("thead"), TemplateMode.HTML, null), context);
    }

    /** The header cell whose link goes to {@code url}, unescaped. */
    private String header(String html, String url) {
        var matcher = Pattern.compile("<th\\b[^>]*>\\s*<a class=\"ca-sort\" href=\"" + Pattern.quote(url) + "\"[^>]*>").matcher(html);
        assertThat(matcher.find()).as("column title linking to %s", url).isTrue();
        return org.springframework.web.util.HtmlUtils.htmlUnescape(matcher.group());
    }

    @Test void technicalTitlesUseTheGivenLinksAndMarkTheSortedColumn() {
        var links = Map.of("ticker", "/t", "interval", "/i", "date", "/d", "status", "/s", "confidence", "/c", "trade-return", "/r");
        String html = render(false, "date", "desc", links);
        assertThat(header(html, "/d")).contains("aria-sort=\"descending\"", "Sort ascending");
        assertThat(header(html, "/t")).contains("aria-sort=\"none\"");
        for (String url : List.of("/s", "/r")) assertThat(header(html, url)).contains("aria-sort=\"none\"");
    }

    @Test void anAscendingSortIsMarkedAndItsLinkReversesIt() {
        String html = render(false, "ticker", "asc", Map.of("ticker", "/t", "interval", "/i", "date", "/d", "status", "/s",
                "confidence", "/c", "trade-return", "/r"));
        assertThat(header(html, "/t")).contains("aria-sort=\"ascending\"", "Sort descending");
    }

    @Test void activityTitlesSortByTickerNameTradeAndDate() {
        var links = Map.of("ticker", "/t", "company", "/co", "actor", "/a", "transaction", "/x", "type", "/ty", "date", "/d");
        String html = render(true, "ticker", "asc", links);
        assertThat(header(html, "/t")).contains("aria-sort=\"ascending\"");
        for (String url : List.of("/a", "/x", "/d")) assertThat(header(html, url)).contains("aria-sort=\"none\"");
    }
}
