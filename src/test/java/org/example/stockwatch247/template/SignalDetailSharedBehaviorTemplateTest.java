package org.example.stockwatch247.template;

import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SignalDetailSharedBehaviorTemplateTest {

    @Test
    void sharedBehaviorRendersWithoutANormalSignalModel() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        var application = org.thymeleaf.web.servlet.JakartaServletWebApplication.buildApplication(request.getServletContext());
        var context = new org.thymeleaf.context.WebContext(application.buildExchange(request, response));
        context.setVariable("elliottMotiveColor", "#3B82F6");
        context.setVariable("elliottCorrectiveColor", "#A855F7");
        context.setVariable("elliottSubwaveColor", "#F59E0B");

        String rendered = engine.process(new TemplateSpec(
                "signal-detail", Set.of("signalDetailBehavior"), TemplateMode.HTML, null), context);

        assertThat(rendered).contains("symbol: container.dataset.symbol || ''");
    }
}
