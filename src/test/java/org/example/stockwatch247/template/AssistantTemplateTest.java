package org.example.stockwatch247.template;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The assistant panel is part of the shared navigation, so every signed-in page has it when it is switched on. */
class AssistantTemplateTest {
    @Test
    void thePanelRendersWithThePagesSuggestedQuestions() {
        var engine = TestTemplateEngines.create();
        var request = new MockHttpServletRequest();
        var application = JakartaServletWebApplication.buildApplication(request.getServletContext());
        var context = new WebContext(application.buildExchange(request, new MockHttpServletResponse()), Locale.ENGLISH);
        context.setVariable("assistantAccount", 42L);
        context.setVariable("assistantSuggestions", List.of("How do I follow a stock?", "Why <no> signals?"));

        String rendered = engine.process(new TemplateSpec("fragments/assistant", Set.of("assistant"), TemplateMode.HTML, null), context);

        assertThat(rendered)
                .contains("data-account=\"42\"")
                .contains("/js/assistant.js").contains("/css/assistant.css").contains("/js/custom-alert-text.js")
                .contains("aria-controls=\"swAssistantPanel\"").contains("role=\"dialog\"").contains("aria-modal=\"false\"")
                .contains(">How do I follow a stock?<").contains("Why &lt;no&gt; signals?")
                .contains("Ask StockWatch").contains("Gemini");
    }

    @Test
    void theNavigationIncludesThePanelOnlyWhenItIsSwitchedOn() throws IOException {
        String navbar = Files.readString(Path.of("src/main/resources/templates/fragments/navbar.html"), StandardCharsets.UTF_8);
        assertThat(navbar).contains("th:if=\"${showAppNavigation == true and assistantEnabled == true}\"")
                .contains("~{fragments/assistant :: assistant}");
    }
}
