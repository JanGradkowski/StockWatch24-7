package org.example.stockwatch247.template;

import org.example.stockwatch247.controller.AuthController.ActivitySignalArchivePage;
import org.example.stockwatch247.service.AlertRuleService.SignalArchivePage;
import org.example.stockwatch247.service.SignalArchiveFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveSortTemplateTest {
    private String render(boolean activity, String sort, String direction, String path) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode(TemplateMode.HTML);
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var request = new MockHttpServletRequest();
        var app = JakartaServletWebApplication.buildApplication(request.getServletContext());
        var context = new WebContext(app.buildExchange(request, new MockHttpServletResponse()));
        context.setVariable("archive", activity
                ? new ActivitySignalArchivePage(List.of(), 3, 5, 250, sort, direction, true, true)
                : new SignalArchivePage(List.of(), 3, 5, 250, sort, direction, true, true));
        context.setVariable("archiveClearUrl", path);
        context.setVariable("archiveFilter", new SignalArchiveFilter("unread", "BRK.B"));
        return engine.process(new TemplateSpec(activity ? "all-activity-signals" : "all-signals",
                Set.of(activity ? ".activity-signal-archive-head" : ".signal-archive-head"), TemplateMode.HTML, null), context);
    }

    private String link(String html, String key) {
        var matcher = Pattern.compile("<a\\b[^>]*data-sort-key=\"" + key + "\"[^>]*>").matcher(html);
        assertThat(matcher.find()).as("sort link for %s", key).isTrue();
        return org.springframework.web.util.HtmlUtils.htmlUnescape(matcher.group());
    }

    @Test void technicalHeadingsKeepFiltersResetPaginationAndUseAppropriateInitialDirections() {
        String html = render(false, "date", "desc", "/signals");
        assertThat(link(html, "ticker")).contains("/signals?sort=ticker&direction=asc&page=0&state=unread&ticker=BRK.B");
        assertThat(link(html, "interval")).contains("sort=interval&direction=asc&page=0");
        assertThat(link(html, "confidence")).contains("sort=confidence&direction=desc&page=0");
        assertThat(link(html, "trade-return")).contains("sort=trade-return&direction=desc&page=0");
        assertThat(link(html, "date")).contains("sort=date&direction=asc&page=0", "data-sort-direction=\"desc\"",
                "currently descending. Sort ascending");
        assertThat(html).doesNotContain("class=\"signal-archive-head\" aria-hidden=\"true\"");
    }

    @Test void repeatedClicksReverseDirectionAndCompanyArchivesStayScoped() {
        assertThat(link(render(false, "ticker", "asc", "/alerts/42"), "ticker"))
                .contains("/alerts/42?sort=ticker&direction=desc&page=0", "data-sort-direction=\"asc\"");
        assertThat(link(render(false, "ticker", "desc", "/alerts/42"), "ticker"))
                .contains("/alerts/42?sort=ticker&direction=asc&page=0", "data-sort-direction=\"desc\"");
    }

    @Test void activityHeadingsDistinguishTickerFromCompanyAndOmitTechnicalFilters() {
        String html = render(true, "ticker", "asc", "/activity-signals");
        assertThat(link(html, "ticker")).contains("/activity-signals?sort=ticker&direction=desc&page=0");
        for (String key : List.of("company", "actor", "transaction", "type")) {
            assertThat(link(html, key)).contains("sort=" + key + "&direction=asc&page=0");
        }
        assertThat(link(html, "date")).contains("sort=date&direction=desc&page=0");
        assertThat(html).doesNotContain("state=", "ticker=BRK.B");
    }
}
