package org.example.stockwatch247.template;

import org.example.stockwatch247.model.enums.TimeInterval;
import org.example.stockwatch247.service.TechnicalOutlookTrackingService.OutlookChangeDetailView;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class OutlookChangeLegendTemplateTest {
    @Test void reportRendersLegendHostsAndLoadsTheSharedControlsBeforeChartInitialization() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode(TemplateMode.HTML);
        var engine = new SpringTemplateEngine(); engine.setTemplateResolver(resolver);
        var request = new MockHttpServletRequest();
        var app = JakartaServletWebApplication.buildApplication(request.getServletContext());
        var context = new WebContext(app.buildExchange(request, new MockHttpServletResponse()));
        context.setVariable("cspNonce", "test-nonce");
        context.setVariable("change", new OutlookChangeDetailView(1L, "TPG0", "Test Company", TimeInterval.MONTHLY,
                "Monthly", "Slight buy outlook", "Neutral outlook", .2, 0, 1, 2,
                LocalDateTime.of(2026, 9, 6, 17, 47), null, null, null));
        String html = engine.process("technical-outlook-change", context);
        assertThat(html).containsOnlyOnce("id=\"outlookChangeOverlayLegend\"")
                .containsOnlyOnce("id=\"outlookChangePanelLegend\"")
                .contains("src=\"/js/chart-legend.js\"", "nonce=\"test-nonce\"",
                        "This does not change the saved analysis.");
        assertThat(html.indexOf("src=\"/js/chart-legend.js\""))
                .isLessThan(html.indexOf("StockWatchChartLegend.mount"));
    }
}
